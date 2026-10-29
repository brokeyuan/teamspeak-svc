package com.tsvoice.client.music;

import com.tsvoice.client.TeamspeakSvcClient;
import com.tsvoice.client.ts3voice.Ts3Connection;
import com.tsvoice.client.ts3voice.Ts3Crypt;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.protocol.music.MusicEngine;
import com.tsvoice.protocol.music.PcmSource;
import com.tsvoice.protocol.music.WavPcmSource;
import net.minecraft.client.Minecraft;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 独立音乐 Bot 管理器（F5/Music-1 最小闭环）：
 *
 * <ul>
 *   <li><b>独立身份</b>：专用 {@code identity-music.txt}（绝不复用玩家
 *       identity.txt 或书签身份）；昵称前缀 {@code [Music Bot]}。</li>
 *   <li><b>独立连接</b>：自己的 Ts3VoiceClient/Ts3Connection/TsTransport ——
 *       UDP 直连目标书签；绝不触碰玩家会话（endSession/currentClient 不调用）。</li>
 *   <li><b>编码器工厂</b>：SVC 客户端 API {@code createEncoder()}（48kHz 单声道
 *       20ms；许可/装载证据由 SVC mod 提供）。API 缺失时明确失败，玩家语音不受
 *       影响。</li>
 *   <li><b>授权曲目</b>：仅本机明确选取的 WAV/PCM 文件（受控目录白名单外拒收；
 *       远程点歌本轮不开放 —— 见功能表"尚未支持"）。</li>
 *   <li><b>生命周期</b>：玩家断开 TS / 切 SVC / OFF / 退出游戏默认停本机 Bot；
 *       Bot 被移出授权房间即停；不向他人/服务端 Bot 发停止命令。</li>
 * </ul>
 */
public final class MusicBotManager {

    private MusicBotManager() {
    }

    /** 当前 Bot 会话（单实例；重复 start 拒绝并反馈）。 */
    private static final AtomicReference<MusicSession> ACTIVE = new AtomicReference<>();
    /** G4：start 异步进行中标志（防重入；stop 可取消当前尝试）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean START_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean STOP_REQUESTED =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 活跃 Bot 的授权曲目（绝对路径字符串，按加入顺序播放）。 */
    private static final List<String> QUEUE = new ArrayList<>();
    /** 当前曲目在队列中的下标（-1 = 未开始）。 */
    private static volatile int currentIndex = -1;
    /** 个人音乐音量（0..100；只影响本机 Bot 发送的公平提示，不改变远端收听）。 */
    private static volatile int localVolumePercent = 100;

    public static class BotStateException extends IllegalStateException {
        public BotStateException(String message) {
            super(message);
        }
    }

    // ---- 状态查询（UI/命令反馈） ----

    public static boolean isActive() {
        MusicSession s = ACTIVE.get();
        return s != null && s.state() != MusicSession.State.CLOSED
                && s.state() != MusicSession.State.IDLE;
    }

    public static String statusText() {
        MusicSession s = ACTIVE.get();
        if (s == null) {
            if (START_IN_FLIGHT.get()) {
                return "Music bot: connecting..." + (LAST_START_ERROR == null ? "" : " (last error: " + LAST_START_ERROR + ")");
            }
            if (LAST_START_ERROR != null) {
                return "Music bot: stopped (last start failed: " + LAST_START_ERROR + ")";
            }
            return "Music bot: stopped";
        }
        StringBuilder sb = new StringBuilder("Music bot: ").append(s.state());
        if (s.failureReason() != null && !s.failureReason().isEmpty()) {
            sb.append(" (").append(s.failureReason()).append(')');
        }
        synchronized (QUEUE) {
            if (currentIndex >= 0 && currentIndex < QUEUE.size()) {
                sb.append(" | playing ").append(currentIndex + 1).append('/').append(QUEUE.size())
                        .append(": ").append(baseName(QUEUE.get(currentIndex)));
            }
            sb.append(" | queue size ").append(QUEUE.size());
        }
        sb.append(" | volume ").append(localVolumePercent).append('%');
        return sb.toString();
    }

    private static String baseName(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    // ---- 授权曲目管理 ----

    /** 添加授权本地曲目（仅 .wav；存在性检查；不触及网络）。 */
    public static void add(String rawPath) {
        String normalized = rawPath.trim();
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".wav")) {
            throw new BotStateException("only .wav files are accepted (authorized local files only)");
        }
        Path p = Path.of(normalized);
        if (!java.nio.file.Files.isRegularFile(p)) {
            throw new BotStateException("file not found: " + normalized);
        }
        synchronized (QUEUE) {
            if (QUEUE.size() >= 50) {
                throw new BotStateException("queue full (50 tracks max)");
            }
            QUEUE.add(normalized);
        }
    }

    public static void clearQueue() {
        synchronized (QUEUE) {
            QUEUE.clear();
            currentIndex = -1;
        }
    }

    public static List<String> queueSnapshot() {
        synchronized (QUEUE) {
            return List.copyOf(QUEUE);
        }
    }

    public static void setVolume(int percent) {
        if (percent < 0 || percent > 100) {
            throw new BotStateException("volume must be 0..100");
        }
        localVolumePercent = percent;
    }

    public static int volume() {
        return localVolumePercent;
    }

    // ---- 生命周期 ----

    /**
     * 启动 Bot：建立独立身份/连接并加入目标频道，然后开始播放队列首曲目。
     *
     * @param host       TS 服务器地址（UDP 直连）
     * @param port       TS 语音端口（通常 9987）
     * @param channel    目标频道名（空 = 默认频道）
     * @param serverUid  授权房间 serverUID（可空 = 不绑定房间约束）
     */
    public static synchronized void start(String host, int port, String channel, String serverUid)
            throws Exception {
        start(host, port, channel, serverUid, false, null);
    }

    /**
     * G5：传输模式显式 —— udp（直连 TS）或 mc（经当前 MC 服务器的受控
     * MUSIC_BOT 第二会话；targetId = 服务端 allowlist 键）。禁止 UI 选择了
     * MC 却悄悄走 UDP。
     *
     * @param mcTargetId MC 模式下的中继 targetId（服务端 allowlist 键；UDP 模式忽略）
     */
    public static synchronized void start(String host, int port, String channel, String serverUid,
                                          boolean viaMcChannel, String mcTargetId)
            throws Exception {
        if (isActive()) {
            throw new BotStateException("music bot already running (use /tsvoice music stop)");
        }
        if (!viaMcChannel && (host == null || host.isBlank())) {
            throw new BotStateException("TS host required (or use the MC-channel mode)");
        }
        if (viaMcChannel && (mcTargetId == null || mcTargetId.isBlank())) {
            throw new BotStateException("MC-channel mode requires a relay targetId");
        }
        if (viaMcChannel && !com.tsvoice.client.TsVoiceManager.isMcRelayChannelSupported()) {
            throw new BotStateException("this server does not support the TS relay channel");
        }
        if (START_IN_FLIGHT.get()) {
            throw new BotStateException("music bot start already in progress");
        }
        START_IN_FLIGHT.set(true);
        // G4：identity 生成、DNS、最长 15 秒握手全部移出客户端线程 —— 命令立即
        // 返回"连接中"，状态/错误在客户端线程反馈；stop 可取消当前尝试。
        Thread worker = new Thread(() -> {
            MusicSession session = null;
            try {
                Path identityPath = FabricLoader.getInstance().getConfigDir()
                        .resolve("teamspeak-svc").resolve("identity-music.txt");
                Ts3Identity identity = Ts3Identity.load(identityPath);
                if (identity == null) {
                    TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] generating music bot identity (level 16)...");
                    identity = Ts3Identity.generate(16, 4_000);
                    identity.save(identityPath);
                }
                String nickname = "[Music Bot] " + Minecraft.getInstance().getUser().getName();

                Ts3Connection connection = new Ts3Connection(new Ts3Crypt(identity), false);
                Ts3VoiceClient client = new Ts3VoiceClient(identity, nickname,
                        channel == null ? "" : channel, connection);
                session = new MusicSession(host, port, nickname, null);
                MusicSendPermit permit = MusicBotAssembly.assemble(session, client, serverUid);
                session.setEncoderFactory(() -> {
                    de.maxhenkel.voicechat.api.VoicechatClientApi api = TeamspeakSvcClient.clientApi();
                    if (api == null) {
                        return null;
                    }
                    de.maxhenkel.voicechat.api.opus.OpusEncoder enc = api.createEncoder();
                    return enc == null ? null : new SvcApiEncoderAdapter(enc);
                });
                if (!ACTIVE.compareAndSet(null, session)) {
                    session.close();
                    return; // 已有 Bot：放弃本次启动
                }
                session.markConnecting();
                if (STOP_REQUESTED.get()) {
                    stopInternal(session);
                    return;
                }
                if (viaMcChannel) {
                    // G5：受控第二会话 —— OPEN(purpose=MUSIC_BOT) → OPENED →
                    // attach → TS 握手（经 MC 通道传输；客户端不解析 TS DNS）。
                    com.tsvoice.client.ts3voice.TsMcChannelTransport botTransport =
                            new com.tsvoice.client.ts3voice.TsMcChannelTransport();
                    com.tsvoice.client.music.MusicRelayBridge.openMcRelaySessionForBot(botTransport, mcTargetId);
                    connection.setTransportForTest(botTransport);
                    client.connect(15_000L);
                } else {
                    client.connect(java.net.InetAddress.getByName(host), port, 15_000L);
                }
                if (STOP_REQUESTED.get()) {
                    stopInternal(session);
                    return;
                }
                permit.connectedUnconfirmed();
                session.markConnected();
                TeamspeakSvcClient.LOGGER.info(
                        "[teamspeak-svc] music bot connected (uid={}, host={}:{}, via={}, channel='{}', awaiting channel confirm)",
                        identity.getClientUid(), host, viaMcChannel ? "MC_CHANNEL" : "UDP", channel);
            } catch (Exception e) {
                if (session != null) {
                    stopInternal(session);
                }
                LAST_START_ERROR = e.getMessage() == null ? e.toString() : e.getMessage();
                TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] music bot start failed: {}", LAST_START_ERROR);
            } finally {
                START_IN_FLIGHT.set(false);
                STOP_REQUESTED.set(false);
            }
        }, "tsvoice-music-bot-start");
        worker.setDaemon(true);
        worker.start();
    }

    /** start 过程中的错误（连接线程写入；statusText 反馈）。 */
    private static volatile String LAST_START_ERROR;

    /** 供 start() 装配的内部钩子（把 sender 工厂交给 MusicSession）。 */
    private interface SenderFactory {
        MusicSession.FrameSender create();
    }

    private static final java.util.Map<MusicSession, SenderFactory> PENDING_SENDERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void stopInternal(MusicSession session) {
        ACTIVE.compareAndSet(session, null);
        try {
            session.close();
        } catch (RuntimeException ignored) {
        }
        PENDING_SENDERS.remove(session);
    }

    // ---- 播放控制（命令面） ----

    /** 开始/继续播放队列当前曲目。 */
    public static synchronized void play() throws Exception {
        MusicSession s = ACTIVE.get();
        if (s == null || !isActive()) {
            throw new BotStateException("music bot is not running (use /tsvoice music start)");
        }
        List<String> queue;
        synchronized (QUEUE) {
            queue = List.copyOf(QUEUE);
        }
        if (queue.isEmpty()) {
            throw new BotStateException("queue is empty (use /tsvoice music add <wav path>)");
        }
        if (currentIndex < 0 || currentIndex >= queue.size()) {
            currentIndex = 0;
        }
        // G3：新一轮播放取得新许可代际（旧帧全部失效）
        if (s.permitForTest() != null && !s.permitForTest().rearmForPlayback()) {
            throw new BotStateException("music bot cannot play (permission revoked: "
                    + s.permitForTest().snapshot().revokeReason() + ")");
        }
        playTrack(s, queue.get(currentIndex));
    }

    private static void playTrack(MusicSession s, String path) throws Exception {
        PcmSource source = WavPcmSource.open(java.nio.file.Path.of(path));
        s.startPlayback(source, System::nanoTime);
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] music bot playing: {}", baseName(path));
    }

    public static synchronized void pause() {
        MusicSession s = ACTIVE.get();
        if (s == null) {
            throw new BotStateException("music bot is not running");
        }
        s.pause();
        // G3：暂停撤销旧播放许可（代际推进；恢复时再武装取得新代际）
        if (s.permitForTest() != null) {
            s.permitForTest().revoke("paused");
        }
    }

    public static synchronized void resume() {
        MusicSession s = ACTIVE.get();
        if (s == null) {
            throw new BotStateException("music bot is not running");
        }
        if (s.permitForTest() != null && !s.permitForTest().rearmForPlayback()) {
            throw new BotStateException("music bot cannot resume (permission revoked: "
                    + s.permitForTest().snapshot().revokeReason() + ")");
        }
        s.resume();
    }

    public static synchronized void next() throws Exception {
        MusicSession s = ACTIVE.get();
        if (s == null || !isActive()) {
            throw new BotStateException("music bot is not running");
        }
        List<String> queue;
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) {
                throw new BotStateException("queue is empty");
            }
            currentIndex = (currentIndex + 1) % QUEUE.size();
            queue = List.copyOf(QUEUE);
        }
        s.cancel();
        // G3：跳过撤销旧许可；下一轮播放再武装取得新代际（旧帧全部失效）
        if (s.permitForTest() != null) {
            s.permitForTest().revoke("skipped");
        }
        playTrack(s, queue.get(currentIndex));
    }

    public static synchronized void stopPlayback() {
        MusicSession s = ACTIVE.get();
        if (s != null) {
            s.cancel();
            if (s.permitForTest() != null) {
                s.permitForTest().revoke("stopped playback");
            }
        }
        currentIndex = -1;
    }

    /** 完全停止 Bot（断开独立连接、释放编码器/引擎）。 */
    public static synchronized void stopBot() {
        STOP_REQUESTED.set(true);
        MusicSession s = ACTIVE.getAndSet(null);
        if (s != null) {
            s.cancel();
            if (s.permitForTest() != null) {
                s.permitForTest().revoke("bot closed");
            }
            s.close();
        }
        currentIndex = -1;
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] music bot stopped");
    }

    /**
     * 玩家语音生命周期钩子：断开 TS / 切 SVC / OFF / 退出游戏时停本机 Bot
     * （已确认产品默认）。绝不触碰其他玩家/服务端 Bot。
     */
    public static void onPlayerVoiceLifecycleChange(String reason) {
        if (isActive()) {
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] stopping local music bot ({})", reason);
            stopBot();
        }
    }

    /** SVC API 编码适配器（F5 生产编码后端之一）。 */
    private static final class SvcApiEncoderAdapter implements MusicSession.OpusEncoderAdapter {
        private final de.maxhenkel.voicechat.api.opus.OpusEncoder delegate;

        SvcApiEncoderAdapter(de.maxhenkel.voicechat.api.opus.OpusEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] encode(short[] pcm) {
            return delegate.encode(pcm);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }
    }
}
