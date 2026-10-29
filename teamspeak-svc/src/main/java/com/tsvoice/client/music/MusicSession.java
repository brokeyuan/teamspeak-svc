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
        /**
         * 48kHz 单声道 short[] PCM → Opus 字节帧。
         *
         * @throws Exception 反射/实现底层异常（引擎按 FAILED 处理）
         */
        byte[] encode(short[] pcm) throws Exception;

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

    /** 单元测试注入面（仅供回归 harness 使用；生产 connect() 走真实连接）。 */
    public static final class ConnectionBundle {
        public boolean connected = true;
    }

    /** 生产连接持有物：独立 identity/client/connection/transport（F5）。 */
    public static final class ProductionConnection {
        public final Object identity;
        public final Object client;
        public final Object connection;
        public final Object transport;
        /** G3：Bot 发送许可（身份/连接/确认频道共同绑定）。 */
        public final MusicSendPermit permit;
        /** Bot 自己连接的关闭桥（AutoCloseable；session close 时调用）——
         *  绝不指向玩家会话的资源。 */
        public volatile AutoCloseable closer;
        /** 绑定授权房间（serverUID/cid）；Bot 被移出即停。 */
        public volatile String boundServerUid;
        public volatile int boundChannelId = -1;

        public ProductionConnection(Object identity, Object client, Object connection, Object transport,
                                    MusicSendPermit permit) {
            this.identity = identity;
            this.client = client;
            this.connection = connection;
            this.transport = transport;
            this.permit = permit;
        }
    }

    /** 编码器工厂（F5）：生产从 SVC 客户端 API 创建；共享引擎不强制其存在。 */
    public interface EncoderFactory {
        /** @return 实际可用的编码器；不可用返回 null（调用方明确失败）。 */
        OpusEncoderAdapter acquire() throws Exception;
    }

    public enum State { IDLE, CONNECTING, CONNECTED, PLAYING, PAUSED, ENDED, FAILED, CLOSED }

    private final String host;
    private final int port;
    private final String botNickname;
    private final Dependencies dependencies;

    private final Object stateLock = new Object();
    private State state = State.IDLE;
    private String failureReason = "";
    private ConnectionBundle testConnection;
    private volatile ProductionConnection production;
    private volatile EncoderFactory encoderFactory;
    /** G3 发送门（生产装配注入）：可发送判定 + 当前代际（编码后复核用）。 */
    private volatile java.util.function.BooleanSupplier sendGate;
    private volatile java.util.function.LongSupplier gateEpochSupplier;
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

    // ---- 连接生命周期 ----

    /**
     * 生产连接（F5）：使用独立 identity 建立真实 TS client/connection；绝不
     * 复用玩家 identity/client，也绝不调用玩家 endSession/currentClient。
     * connect 由 BotManager 驱动（identity/client 构造见该类）。
     */
    public void attachProductionConnection(ProductionConnection connection) {
        synchronized (stateLock) {
            ensureNotClosed();
            this.production = connection;
            this.testConnection = null;
            // G3/P2-04：attach ≠ 握手成功 —— 不在此置 CONNECTED；
            // markConnecting/markConnected 由 BotManager 按真实连接进度调用。
        }
    }

    /** 注册生产编码器工厂（startPlayback 无显式编码器时使用）。 */
    public void setEncoderFactory(EncoderFactory factory) {
        synchronized (stateLock) {
            this.encoderFactory = factory;
        }
    }

    /** 连接进行中（握手未完成；attachProductionConnection 后、connect 成功前）。 */
    public void markConnecting() {
        synchronized (stateLock) {
            ensureNotClosed();
            if (state == State.IDLE) {
                state = State.CONNECTING;
            }
        }
    }

    /** 连接已建立（频道未确认 —— 可发送性由 sendGate/许可决定）。 */
    public void markConnected() {
        synchronized (stateLock) {
            ensureNotClosed();
            if (state == State.IDLE || state == State.CONNECTING) {
                state = State.CONNECTED;
            }
        }
    }

    /** G3：注入生产发送门（可发送判定 + 代际来源；编码后复核共用）。 */
    public void setSendGate(java.util.function.BooleanSupplier sendable,
                            java.util.function.LongSupplier epochSupplier) {
        synchronized (stateLock) {
            this.sendGate = sendable;
            this.gateEpochSupplier = epochSupplier;
        }
    }

    /** 测试注入（回归 harness 专用）：绑定无网络连接。 */
    public void connectForTest(ConnectionBundle bundle) {
        synchronized (stateLock) {
            ensureNotClosed();
            this.testConnection = bundle;
            this.production = null;
            if (state == State.IDLE) {
                state = State.CONNECTED;
            }
        }
    }

    public boolean isConnected() {
        synchronized (stateLock) {
            if (state == State.CLOSED || state == State.IDLE) {
                return false;
            }
            return (production != null && production.client != null) || testConnection != null;
        }
    }

    /** G3 诊断/回归：生产装配句柄（未装配为 null）。 */
    public ProductionConnection productionForTest() {
        synchronized (stateLock) {
            return production;
        }
    }

    /** G3 诊断/回归：当前发送许可（未装配为 null）。 */
    public MusicSendPermit permitForTest() {
        ProductionConnection pc = productionForTest();
        return pc == null ? null : pc.permit;
    }

    /** G6 音量键来源：Bot 绑定房间的 serverUID + Bot TS UID（未确认时 null）。 */
    public record BotIdentitySnapshot(String serverUid, String tsUid) {
    }

    public BotIdentitySnapshot botIdentitySnapshot() {
        ProductionConnection pc = productionForTest();
        if (pc == null) {
            return null;
        }
        return new BotIdentitySnapshot(pc.boundServerUid, botTsUid);
    }

    /** Bot 自己的 TS UID（identity 派生；attach 后由 BotManager 设置）。 */
    private volatile String botTsUid;

    public void setBotTsUid(String uid) {
        this.botTsUid = uid;
    }

    /** 房间绑定（授权 serverUID/cid）；Bot 被移出该房间即停（F5 边界）。 */
    public void bindRoom(String serverUid, int channelId) {
        ProductionConnection pc = production;
        if (pc != null) {
            pc.boundServerUid = serverUid;
            pc.boundChannelId = channelId;
        }
    }

    /** Bot 是否仍在其授权房间内（被移出返回 false；无绑定视为在位）。 */
    public boolean inAuthorizedRoom(String serverUid, int channelId) {
        ProductionConnection pc = production;
        if (pc == null || pc.boundServerUid == null) {
            return true; // 未绑定房间约束
        }
        return pc.boundServerUid.equals(serverUid) && pc.boundChannelId == channelId;
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

    /** 编码器由调用方持有注入时的路径（测试/宿主预建；公开供生产回归）。 */
    public void startPlayback(PcmSource source, OpusEncoderAdapter encoder,
                              java.util.function.LongSupplier clock) throws EncodingUnavailableException {
        synchronized (stateLock) {
            ensureNotClosed();
            if (production == null && testConnection == null) {
                throw new IllegalStateException("not connected");
            }
            // G3：生产会话必须已确认房间（许可 READY）才能开始播放；
            // CONNECTING/CONNECTED(未确认) 明确失败，不提前显示可播。
            if (production != null && sendGate != null && !sendGate.getAsBoolean()) {
                throw new IllegalStateException(
                        "bot not ready: channel not confirmed or permission revoked");
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
            playbackGeneration++; // F4：新播放代 —— 旧代编码中的帧作废
            state = State.PLAYING;
            failureReason = "";
        }
        engine.play(source);
    }

    /** 引擎 sink：PCM → 编码 → 发送（阻塞在 engine worker；不占控制面）。 */
    private boolean encodeAndSend(short[] pcm) {
        OpusEncoderAdapter encoder;
        long myGeneration;
        FrameSender sender;
        long capturedEpoch;
        synchronized (stateLock) {
            encoder = ownedEncoder;
            myGeneration = playbackGeneration;
            sender = frameSenderRef;
            capturedEpoch = gateEpochSupplier == null ? 0 : gateEpochSupplier.getAsLong();
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
        } catch (Exception e) {
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
        // F4：编码后、最终网络入队前的许可复核 —— 编码期间发生的 cancel/
        // pause/replace/断连/关闭使本帧作废：不调用 sender（绝不给旧帧换绑
        // 新 sender），播放代际由 startPlayback/cancel 递增。
        // G3：生产路径同时复核发送门与代际（许可撤销/房间移动 → 帧作废）。
        synchronized (stateLock) {
            if (playbackGeneration != myGeneration
                    || (state != State.PLAYING && state != State.PAUSED)) {
                framesDroppedAfterEncode++;
                return false;
            }
        }
        java.util.function.BooleanSupplier gate = sendGate;
        java.util.function.LongSupplier gateEpoch = gateEpochSupplier;
        if (gate != null) {
            if (!gate.getAsBoolean() || gateEpoch.getAsLong() != capturedEpoch) {
                synchronized (stateLock) {
                    framesDroppedAfterEncode++;
                }
                return false;
            }
        }
        if (sender == null) {
            // 生产装配：BotManager 的 producer hook 在首次播放时绑定
            ProducerSenderHook hook = producerSenderHook;
            if (hook != null) {
                sender = hook.create();
                frameSenderRef = sender;
            }
        }
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

    /** playback generation：startPlayback/cancel 递增（F4 编码后复核依据）。 */
    private long playbackGeneration;
    /** 编码完成后因代际/状态失配而丢弃的帧数（诊断）。 */
    private long framesDroppedAfterEncode;

    /** 编码后因许可失配丢弃的帧数（诊断/测试）。 */
    public long framesDroppedAfterEncode() {
        synchronized (stateLock) {
            return framesDroppedAfterEncode;
        }
    }

    private volatile FrameSender frameSenderRef;

    /** 生产 sender 装配钩子（BotManager 传入工厂；startPlayback 装配发送桥）。 */
    public interface ProducerSenderHook {
        FrameSender create();
    }

    private volatile ProducerSenderHook producerSenderHook;

    public void startPlaybackForTestHookViaProducer(ProducerSenderHook hook) {
        synchronized (stateLock) {
            this.producerSenderHook = hook;
        }
    }

    /** 测试/宿主重载：显式发送桥 + 时钟（公开供回归/宿主接线）。 */
    public void startPlaybackForTest(PcmSource source, OpusEncoderAdapter encoder,
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

    /** 取消播放：引擎 cancel（代际递增 → 在途/编码中帧废弃）；回到 CONNECTED。 */
    public void cancel() {
        synchronized (stateLock) {
            if (ownedEngine != null) {
                ownedEngine.cancel();
            }
            playbackGeneration++; // F4：编码中的帧在复核点废弃，不再调用 sender
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
        EncoderFactory factory = encoderFactory;
        if (factory != null) {
            try {
                OpusEncoderAdapter created = factory.acquire();
                if (created != null) {
                    return created;
                }
            } catch (Exception e) {
                throw new EncodingUnavailableException(
                        "opus encoder factory failed: " + e.getMessage());
            }
        }
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
        ProductionConnection pc;
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
            pc = production;
            production = null;
            testConnection = null;
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
        // F5：独立 Bot 连接的断开（绝不触碰玩家会话 —— pc.client 是音乐自己的
        // client；通过 Disconnectable 适配断开，MusicSession 不静态依赖
        // ts3voice 类型，共享核心可在无客户端环境编译/测试）。
        if (pc != null && pc.closer != null) {
            try {
                pc.closer.close();
            } catch (RuntimeException ignored) {
            } catch (Exception ignored) {
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
