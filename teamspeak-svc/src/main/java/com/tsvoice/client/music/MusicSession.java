package com.tsvoice.client.music;

import com.tsvoice.protocol.music.MusicEngine;
import com.tsvoice.protocol.music.PcmSource;

/**
 * 独立音乐会话（E7/T38 修订）：音乐 Bot 与玩家语音完全隔离的最小宿主。
 *
 * <p>T38 决策（按 V3 备忘与 81c687c 复核纠正）：</p>
 * <ul>
 *   <li><b>不与玩家会话并行发送</b>：本会话持有自己的 TS identity/client/
 *       transport/connection 生命周期（生产由 {@link Dependencies} 注入创建
 *       与停止）；绝不调用玩家全局 endSession、不覆盖玩家 currentClient、
 *       不向玩家连接的 writer 提交帧。</li>
 *   <li><b>编码器独立</b>：{@link OpusEncoderAdapter} 由依赖环境提供（官方
 *       SVC API 的 {@code createEncoder()} 是其中一种实现，许可/装载证据在
 *       提供方记录）。共享引擎不强制 SVC 在位 —— 编码器不可用时
 *       {@link #startPlayback} 抛 {@link EncodingUnavailableException}，
 *       玩家语音不受影响。</li>
 *   <li><b>发送许可绑定会话</b>：编码帧经 {@link FrameSender} 送出；暂停/
 *       取消/替换播放（MusicEngine generation）后旧帧不再获得发送许可
 *       （engine 侧代际 + 宿主侧停发双保险）。</li>
 *   <li><b>引擎契约</b>：T37 修订后的 MusicEngine —— 无损背压、终态 close、
 *       暂停不忙转、代际隔离、慢 sink 不阻塞控制面。</li>
 * </ul>
 *
 * <p>Music-1 的界面/多人控制/第二中继会话在后续阶段；本类只承担
 * "可独立编码、可取消的独立音乐会话"最小真实接线。</p>
 */
public final class MusicSession implements AutoCloseable {

    /** TS 音乐 codec（帧格式与 SVC Opus Music 一致；声道/帧长由编码器决定）。 */
    public static final int CODEC_OPUS_MUSIC = 5;

    /** Opus 编码适配（E7/T38：独立于 SVC 内部类；提供方给出实现与许可证据）。 */
    public interface OpusEncoderAdapter extends AutoCloseable {
        /** 48kHz 单声道 short[] PCM → Opus 字节帧。 */
        byte[] encode(short[] pcm);

        @Override
        void close();

        boolean isClosed();
    }

    /** 编码帧发送桥（宿主接线：独立会话的 sendVoice(CODEC_OPUS_MUSIC)）。 */
    public interface FrameSender {
        /** @return 是否被宿主接受（许可缺失/未连接返回 false）。 */
        boolean send(byte[] opusFrame);
    }

    /** 生产环境依赖（身份创建/玩家会话停止句柄；测试可传 null 走隔离面）。 */
    public interface Dependencies {
        /** 创建独立 TS 身份（绝不复用玩家 identity）。 */
        Object createIdentity() throws Exception;

        /** 玩家会话停止句柄（仅供日志核对：本类在生产代码路径中绝不调用）。 */
        Runnable playerSessionEndHandle();
    }

    /** 单元测试注入面：已连接的独立连接（不触网）。 */
    public static final class ConnectionBundle {
        /** 标记独立连接可用（生产实现替换为真实 client/transport）。 */
        public boolean connected = true;
    }

    public enum State { IDLE, CONNECTED, PLAYING, PAUSED, ENDED, FAILED, CLOSED }

    private final String host;
    private final int port;
    private final String botNickname;
    private final Dependencies dependencies;

    private final Object stateLock = new Object();
    private State state = State.IDLE;
    private String failureReason = "";
    private ConnectionBundle connection;
    /** 拥有的编码器（会话生命周期内唯一；close 释放）。 */
    private OpusEncoderAdapter ownedEncoder;
    /** 拥有的引擎（播放期间持有）。 */
    private MusicEngine ownedEngine;

    public MusicSession(String host, int port, String botNickname, Dependencies dependencies) {
        this.host = host;
        this.port = port;
        this.botNickname = botNickname;
        this.dependencies = dependencies;
    }

    // ---- 连接生命周期（最小面；生产 connect() 在 Music-1 接线） ----

    /** 测试注入：直接绑定已建立的独立连接（生产 connect() 走 identity+transport）。 */
    public void connectForTest(ConnectionBundle bundle) {
        synchronized (stateLock) {
            ensureNotClosed();
            this.connection = bundle;
            if (state == State.IDLE) {
                state = State.CONNECTED;
            }
        }
    }

    public boolean isConnected() {
        synchronized (stateLock) {
            return connection != null && state != State.CLOSED && state != State.IDLE;
        }
    }

    // ---- 播放 ----

    /**
     * 开始播放：音源 → 引擎（无损背压）→ 编码 → 发送。编码器不可用即明确
     * 失败（{@link EncodingUnavailableException}），不影响任何已连接状态。
     */
    public void startPlayback(PcmSource source, java.util.function.LongSupplier clock)
            throws EncodingUnavailableException {
        startPlayback(source, acquireEncoder(), clock);
    }

    /** 编码器由调用方持有注入时的路径（测试/宿主预建）。 */
    void startPlayback(PcmSource source, OpusEncoderAdapter encoder,
                       java.util.function.LongSupplier clock) throws EncodingUnavailableException {
        synchronized (stateLock) {
            ensureNotClosed();
            if (connection == null) {
                throw new IllegalStateException("not connected");
            }
        }
        if (encoder == null) {
            synchronized (stateLock) {
                state = State.FAILED;
                failureReason = "opus encoder unavailable: music requires an Opus encoder "
                        + "(SVC API implementation or bundled library with license/packaging evidence)";
            }
            throw new EncodingUnavailableException(
                    "opus encoder unavailable: music requires an Opus encoder "
                            + "(SVC API implementation or bundled library with license/packaging evidence)");
        }
        MusicEngine engine = new MusicEngine(this::encodeAndSend, clock, MusicEngine.DEFAULT_QUEUE_CAPACITY);
        synchronized (stateLock) {
            ensureNotClosed();
            if (ownedEngine != null) {
                ownedEngine.close(); // 旧引擎（含其源）退役
            }
            ownedEncoder = encoder;
            ownedEngine = engine;
            state = State.PLAYING;
            failureReason = "";
        }
        engine.play(source);
    }

    /** 引擎 sink：PCM → 编码 → 发送（阻塞在 engine worker；不占控制面）。 */
    private boolean encodeAndSend(short[] pcm) {
        OpusEncoderAdapter encoder;
        synchronized (stateLock) {
            encoder = ownedEncoder;
            if (state != State.PLAYING && state != State.PAUSED) {
                return false; // 已取消/关闭：丢弃取出帧
            }
        }
        if (encoder == null || encoder.isClosed()) {
            return false;
        }
        byte[] opus;
        try {
            opus = encoder.encode(pcm);
        } catch (RuntimeException e) {
            synchronized (stateLock) {
                if (state == State.PLAYING || state == State.PAUSED) {
                    state = State.FAILED;
                    failureReason = "encode failed: " + e.getMessage();
                }
            }
            return false;
        }
        if (opus == null || opus.length == 0) {
            synchronized (stateLock) {
                if (state == State.PLAYING || state == State.PAUSED) {
                    state = State.FAILED;
                    failureReason = "encode produced an empty frame";
                }
            }
            return false;
        }
        // 发送桥由 startPlayback 的 sender 参数承载（见重载）—— 测试与生产
        // 各自接线；此处经由 frameSenderRef。
        FrameSender sender = frameSenderRef;
        if (sender == null) {
            return false;
        }
        boolean accepted = sender.send(opus);
        if (!accepted) {
            // 许可缺失：帧丢弃但播放继续（与 engine framesDroppedByPermit 对应）
            return false;
        }
        return true;
    }

    private volatile FrameSender frameSenderRef;

    /** 测试/宿主重载：显式发送桥 + 时钟。 */
    void startPlaybackForTest(PcmSource source, OpusEncoderAdapter encoder,
                              FrameSender sender, java.util.function.LongSupplier clock)
            throws EncodingUnavailableException {
        this.frameSenderRef = sender;
        startPlayback(source, encoder, clock);
    }

    public void pause() {
        synchronized (stateLock) {
            if (state == State.PLAYING && ownedEngine != null) {
                ownedEngine.pause();
                state = State.PAUSED;
            }
        }
    }

    public void resumeForTest(java.util.function.LongSupplier clock) {
        resume();
    }

    public void resume() {
        synchronized (stateLock) {
            if (state == State.PAUSED && ownedEngine != null) {
                ownedEngine.resume();
                state = State.PLAYING;
            }
        }
    }

    /** 取消播放：引擎 cancel（代际递增 → 在途帧废弃）；回到 CONNECTED。 */
    public void cancel() {
        synchronized (stateLock) {
            if (ownedEngine != null) {
                ownedEngine.cancel();
            }
            if (state == State.PLAYING || state == State.PAUSED
                    || state == State.ENDED || state == State.FAILED) {
                state = State.CONNECTED;
            }
        }
    }

    public State state() {
        synchronized (stateLock) {
            if (ownedEngine != null) {
                MusicEngine.State es = ownedEngine.state();
                // 引擎自然结束/失败上浮到会话状态
                if (es == MusicEngine.State.ENDED && state == State.PLAYING) {
                    state = State.ENDED;
                } else if (es == MusicEngine.State.FAILED && (state == State.PLAYING || state == State.PAUSED)) {
                    state = State.FAILED;
                    failureReason = ownedEngine.failureReason();
                }
            }
            return state;
        }
    }

    public String failureReason() {
        synchronized (stateLock) {
            return failureReason;
        }
    }

    // ---- 生命周期 ----

    private OpusEncoderAdapter acquireEncoder() throws EncodingUnavailableException {
        // 生产实现（Music-1 接线）：依赖注入的编码器工厂。当前阶段由调用方
        // 显式提供（测试/宿主预建）；无编码器 = 明确失败。
        throw new EncodingUnavailableException(
                "opus encoder unavailable: music requires an Opus encoder "
                        + "(SVC API implementation or bundled library with license/packaging evidence)");
    }

    private void ensureNotClosed() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("music session closed");
        }
    }

    public void closeForTest() {
        close();
    }

    @Override
    public void close() {
        OpusEncoderAdapter encoder;
        MusicEngine engine;
        synchronized (stateLock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            engine = ownedEngine;
            ownedEngine = null;
            encoder = ownedEncoder;
            ownedEncoder = null;
            connection = null;
            frameSenderRef = null;
        }
        if (engine != null) {
            engine.close();
        }
        if (encoder != null) {
            try {
                encoder.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** 编码器不可用（无 SVC API 且无带许可证据的打包实现）。 */
    public static final class EncodingUnavailableException extends Exception {
        public EncodingUnavailableException(String message) {
            super(message);
        }
    }
}
