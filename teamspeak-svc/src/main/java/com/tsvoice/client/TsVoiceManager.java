package com.tsvoice.client;

import com.tsvoice.client.net.BeaconPayload;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.client.ts3voice.Ts3VoiceException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端 TS 语音连接管理器（v0.2 激活策略版）。
 *
 * <p><b>激活判定</b>（{@link #evaluateActivation}）：仅当解析出会话配置时启动 TS 会话，
 * 否则完全休眠（不建连接、不拦麦克风、不渲染 HUD —— 非 TS 服务器零干扰）：</p>
 * <ol>
 *     <li>本服信标已收到且未对该服停用 → 使用信标值（source=server）；</li>
 *     <li>本服手动配置（servers.json 条目）→ 使用手动值（source=manual）；</li>
 *     <li>全局 teamspeak-svc.json（v0.1 兼容）→ source=manual；</li>
 *     <li>都没有 → 休眠。</li>
 * </ol>
 */
public final class TsVoiceManager {

    public enum State { DORMANT, WAITING_SVC, AWAITING_TRUST, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    /** 当前生效的会话配置（tcp=true 时 port 为隧道端口；password=服务器/频道密码）。 */
    public record SessionConfig(String host, int port, String channel, String source, boolean tcp,
                                String password, String channelPassword) {
    }

    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long ALIVE_POLL_MS = 500L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    /** Serializes blocking network close off the manager and GUI threads. */
    private static final java.util.concurrent.ExecutorService CLOSE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "teamspeak-svc-close");
                t.setDaemon(true);
                return t;
            });

    private static volatile State state = State.DORMANT;
    private static volatile Ts3VoiceClient client;
    private static volatile Thread sessionThread;
    private static volatile boolean svcConnected = false;
    private static volatile boolean sessionRunning = false;
    /** 会话代际：stopSession 递增使旧线程的清理不再影响新会话。 */
    private static volatile long sessionGeneration = 0;
    /** 当前会话的协调器 token：回调绑定它，旧会话回调据此整体丢弃。 */
    private static volatile com.tsvoice.client.connection.VoiceState.SessionToken sessionToken =
            new com.tsvoice.client.connection.VoiceState.SessionToken(0);
    /** 用户手动断开：在换服或再次手动连接前抑制自动激活。 */
    private static volatile boolean userDisabled = false;
    /** 前端自检失败：关键 SVC hook 缺失，TS 整体禁用（原版 SVC 不受影响）。 */
    private static volatile boolean frontendUnavailable = false;
    private static volatile String lastError = "";
    private static final AtomicLong voiceCounter = new AtomicLong(0);
    /** 成功发往 TS 的麦克风帧数（诊断用）。 */
    private static final AtomicLong sentVoiceFrames = new AtomicLong(0);
    /** 已告警过的未知 clid（客户端表尚未同步）。 */
    private static final java.util.Set<Integer> unknownClids = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 激活上下文
    private static volatile String currentAddress = "";        // 当前 MC 服务器地址
    private static volatile BeaconPayload beacon;              // 已授权的本服信标
    private static volatile BeaconPayload pendingBeacon;       // 等待用户授权的新 endpoint
    private static volatile java.util.function.Consumer<BeaconPayload> untrustedBeaconListener;
    private static volatile SessionConfig activeConfig;        // 当前生效配置
    private static volatile SessionConfig lastAppliedConfig;  // 以完整配置比较，避免密码哈希碰撞
    /** 显式书签意图（manualConnect(bookmarkId) 设置）：优先于本服公告（含 server-bridge）。 */
    private static volatile String explicitBookmarkId;

    private TsVoiceManager() {
    }

    // ---- 上下文更新入口 ----

    /** MC 连接变化（进入/离开服务器）。 */
    public static void onServerChanged(String address) {
        currentAddress = address == null ? "" : address;
        beacon = null;
        pendingBeacon = null;
        userDisabled = false; // 换服/重进世界恢复自动策略
        GroupChannelMapper.reset();
        evaluateActivation();
    }

    /** 收到本服信标；新 endpoint 必须由玩家显式授权后才可用于连接。 */
    public static void onBeacon(BeaconPayload payload) {
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Server beacon received ({}:{}, channel='{}', mode={})",
                payload.host(), payload.port(), payload.channel(), payload.backendMode());
        ServerConfigStore.Entry entry = ServerConfigStore.get(currentAddress);
        if (entry.useServerBeacon != null && !entry.useServerBeacon) {
            return;
        }
        if (isTrustedBeacon(entry, payload)) {
            pendingBeacon = null;
            beacon = payload;
        } else {
            beacon = null;
            pendingBeacon = payload;
            var listener = untrustedBeaconListener;
            if (listener != null) {
                listener.accept(payload);
            }
        }
        evaluateActivation();
        if (pendingBeacon != null) {
            state = State.AWAITING_TRUST;
        }
    }

    /** 用户在授权界面选择“仅本次 / 记住 / 拒绝 / 禁用信标”后调用。 */
    public static void authorizePendingBeacon(boolean remember, boolean accept) {
        BeaconPayload payload = pendingBeacon;
        pendingBeacon = null;
        if (payload != null && accept) {
            beacon = payload;
            if (remember) {
                ServerConfigStore.Entry entry = ServerConfigStore.get(currentAddress);
                entry.trustedBeaconHost = payload.host();
                entry.trustedBeaconPort = payload.port();
                ServerConfigStore.save();
            }
        } else {
            beacon = null;
        }
        evaluateActivation();
    }

    public static BeaconPayload pendingBeacon() {
        return pendingBeacon;
    }

    public static void setUntrustedBeaconListener(java.util.function.Consumer<BeaconPayload> listener) {
        untrustedBeaconListener = listener;
    }

    private static boolean isTrustedBeacon(ServerConfigStore.Entry entry, BeaconPayload payload) {
        return entry.trustedBeaconHost != null
                && entry.trustedBeaconPort != null
                && entry.trustedBeaconHost.equals(payload.host())
                && entry.trustedBeaconPort == payload.port();
    }

    /** 手动配置保存后调用（GUI）。 */
    public static void onManualConfigChanged() {
        evaluateActivation();
    }

    /** SVC 客户端连接建立（进入世界且 SVC 激活）。 */
    public static void onSvcConnected() {
        svcConnected = true;
        com.tsvoice.client.backend.SvcBackendAdapter.onSvcConnected();
        evaluateActivation();
    }

    /**
     * SVC 客户端断开。P1 起只更新适配器与状态显示，不再销毁 TS 会话 ——
     * TS 路由被选中期间麦克风保持消费（不泄漏回 SVC），TS 重连循环继续。
     */
    public static void onSvcDisconnected() {
        svcConnected = false;
        com.tsvoice.client.backend.SvcBackendAdapter.onSvcDisconnected();
        if (activeConfig == null && !sessionRunning) {
            state = State.DORMANT;
        }
    }

    // ---- 激活策略 ----

    /** 解析当前会话配置；null = 休眠。 */
    static SessionConfig resolveSessionConfig() {
        ServerConfigStore.Entry entry = ServerConfigStore.get(currentAddress);
        boolean enabled = entry.enabled == null || entry.enabled;

        // 显式书签意图（P3）：手动书签优先于本服公告 —— server-bridge 信标只能
        // 休眠自动路径，不能覆盖显式连接。换服不清除意图（D05）。
        com.tsvoice.client.config.TsBookmarkStore.Bookmark bookmark =
                com.tsvoice.client.config.TsBookmarkStore.get(explicitBookmarkId);
        if (bookmark != null) {
            Integer port = bookmark.port();
            var credential = com.tsvoice.client.config.EndpointCredentialStore.get(
                    bookmark.host(), port);
            boolean tcp = "tcp".equalsIgnoreCase(bookmark.transportPolicy());
            return new SessionConfig(bookmark.host(), bookmark.effectivePort(),
                    bookmark.channel() != null ? bookmark.channel() : "Minecraft", "manual", tcp,
                    credential != null ? credential.serverPassword() : null,
                    credential != null ? credential.channelPassword() : null);
        }
        if (explicitBookmarkId != null) {
            // 书签已被删除：清除意图，回落常规解析
            explicitBookmarkId = null;
        }

        // 服务器声明走服务端桥（tsbridge bridge 模式）时：语音由 SVC 原生 + 服务端桥负责，
        // 本 mod 直连会造成同名双客户端/回声 —— 客户端休眠
        if (beacon != null && "server-bridge".equalsIgnoreCase(beacon.backendMode())) {
            return null;
        }
        boolean wantTcp = wantTcp(entry);
        ModConfig global = TeamspeakSvcClient.config();
        String password = null;
        String channelPassword = null;
        if (beacon != null && enabled
                && (entry.useServerBeacon == null || entry.useServerBeacon)) {
            // A beacon selects a server-controlled endpoint. Never forward credentials
            // saved for a manual or different TS endpoint to that endpoint.
            password = null;
            channelPassword = null;
            // 本服传输覆盖 > 信标隧道可用性
            boolean tcp;
            if ("tcp".equalsIgnoreCase(entry.transport)) {
                tcp = beacon.tunnelPort() > 0;
            } else if ("udp".equalsIgnoreCase(entry.transport)) {
                tcp = false;
            } else {
                tcp = wantTcp && beacon.tunnelPort() > 0;
            }
            if (wantTcp && beacon.tunnelPort() <= 0 && !"udp".equalsIgnoreCase(entry.transport)) {
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] transport=tcp but server has no tunnel — falling back to UDP");
            }
            return new SessionConfig(beacon.host(),
                    tcp ? beacon.tunnelPort() : beacon.port(),
                    beacon.channel(), "server", tcp, password, channelPassword);
        }
        if (enabled && entry.host != null && !entry.host.isBlank()) {
            // F08：全局密码只属于全局 endpoint —— 其他手动 host 密码为空时保持为空
            // （连接时由 TS 服务器提示），绝不跨 endpoint 继承。
            boolean sameAsGlobal = global != null && global.isConfigured()
                    && com.tsvoice.client.config.EndpointCredentialStore.normalizeEndpoint(
                            entry.host, entry.port).equals(
                            com.tsvoice.client.config.EndpointCredentialStore.normalizeEndpoint(
                                    global.tsHost, global.tsVoicePort));
            password = configuredPassword(entry.serverPassword,
                    sameAsGlobal ? (global != null ? global.serverPassword : null) : null);
            channelPassword = configuredPassword(entry.channelPassword,
                    sameAsGlobal ? (global != null ? global.channelPassword : null) : null);
            // 手动连其他 TS 服务器：host/port 直接填（TCP 时=隧道地址与端口）
            boolean tcp = "tcp".equalsIgnoreCase(entry.transport) || (entry.transport == null && wantTcp);
            return new SessionConfig(entry.host,
                    entry.port != null ? entry.port : 9987,
                    entry.channel != null ? entry.channel : "Minecraft",
                    "manual", tcp, password, channelPassword);
        }
        if (enabled && global != null && global.isConfigured() && global.autoConnect) {
            password = configuredPassword(entry.serverPassword, global.serverPassword);
            channelPassword = configuredPassword(entry.channelPassword, global.channelPassword);
            return new SessionConfig(global.tsHost, global.tsVoicePort, global.channelName, "manual", wantTcp,
                    password, channelPassword);
        }
        return null;
    }

    /**
     * 前端自检结果（P2）：关键 SVC hook 缺失时 TS 整体禁用并停止现有会话；
     * 原版 SVC 在未接管时仍可用。恢复只能经重新自检（游戏重启），不做静默半可用。
     */
    public static void setFrontendUnavailable(boolean unavailable) {
        frontendUnavailable = unavailable;
        if (unavailable) {
            stopSession();
            if (activeConfig != null) {
                state = State.ERROR;
                lastError = "TS frontend unavailable (SVC hook self-check failed)";
            }
        }
        refreshRouteOwnership();
    }

    public static boolean isFrontendUnavailable() {
        return frontendUnavailable;
    }

    /** null 表示继承；空值明确清除。非空密码保留空格，不进行 trim。 */
    private static String configuredPassword(String override, String fallback) {
        String value = override != null ? override : fallback;
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 麦克风路由归属：存在生效 TS 配置且未被用户禁用即选中 TS。
     * 等待 SVC/重连期间帧被消费丢弃（P1 由协调器的 route 快照取代此简化判定）。
     */
    private static void refreshRouteOwnership() {
        com.tsvoice.client.audio.MicRouteGate.setOwner(
                activeConfig != null && !userDisabled && !frontendUnavailable
                        ? com.tsvoice.client.audio.MicRouteGate.Owner.TS
                        : com.tsvoice.client.audio.MicRouteGate.Owner.SVC_NATIVE);
    }

    /** 重新评估激活：配置变化触发会话重建，无配置则休眠。 */
    static synchronized void evaluateActivation() {
        SessionConfig resolved = resolveSessionConfig();
        activeConfig = resolved;
        refreshRouteOwnership();
        if (userDisabled) {
            stopSession();
            state = State.DORMANT;
            return;
        }
        if (frontendUnavailable) {
            stopSession();
            state = State.ERROR;
            lastError = "TS frontend unavailable (SVC hook self-check failed)";
            return;
        }
        if (resolved == null) {
            stopSession();
            state = pendingBeacon != null ? State.AWAITING_TRUST : State.DORMANT;
            return;
        }
        if (!svcConnected) {
            state = State.WAITING_SVC;
            return;
        }
        boolean reconnect = sessionRunning && !resolved.equals(lastAppliedConfig);
        if (resolved.equals(lastAppliedConfig) && sessionRunning) {
            return; // 配置未变
        }
        lastAppliedConfig = resolved;
        GroupChannelMapper.reset();
        if (reconnect) {
            // 运行中配置变化（host/port/channel/密码/传输）→ 重建会话
            stopSession();
            lastAppliedConfig = resolved;
        }
        startSession();
    }

    // ---- 会话生命周期 ----

    public static void manualConnect() {
        userDisabled = false;
        if (activeConfig == null) {
            lastError = "no configuration (server beacon or manual config required)";
            state = State.ERROR;
            return;
        }
        if (!svcConnected) {
            // SVC 尚未连接：等待其连接事件后再启动会话（避免空转线程占用 sessionRunning）
            state = State.WAITING_SVC;
            return;
        }
        startSession();
    }

    /**
     * 按书签建立显式连接意图（P3）：立即解析书签配置并启动会话；不受本服公告
     * （含 server-bridge 休眠规则）与 autoConnect 影响。书签/凭据缺失时报可操作错误。
     */
    public static void manualConnect(String bookmarkId) {
        userDisabled = false;
        com.tsvoice.client.config.TsBookmarkStore.Bookmark bookmark =
                com.tsvoice.client.config.TsBookmarkStore.get(bookmarkId);
        if (bookmark == null) {
            lastError = "bookmark not found: " + bookmarkId;
            state = State.ERROR;
            return;
        }
        explicitBookmarkId = bookmarkId;
        evaluateActivation();
        if (activeConfig == null) {
            lastError = "bookmark cannot be resolved: " + bookmarkId;
            state = State.ERROR;
            return;
        }
        if (!svcConnected) {
            state = State.WAITING_SVC;
            return;
        }
        startSession();
    }

    public static void manualDisconnect() {
        userDisabled = true;
        explicitBookmarkId = null;
        refreshRouteOwnership();
        stopSession();
        state = State.DORMANT;
    }

    private static synchronized void startSession() {
        if (sessionRunning || !svcConnected) {
            return;
        }
        sessionRunning = true;
        state = State.CONNECTING;
        long gen = ++sessionGeneration;
        sessionToken = com.tsvoice.client.connection.BackendCoordinator.beginSession();
        Thread t = new Thread(() -> sessionLoop(gen), "teamspeak-svc-voice");
        t.setDaemon(true);
        t.start();
        sessionThread = t;
    }

    private static synchronized void stopSession() {
        sessionGeneration++;
        sessionRunning = false;
        lastAppliedConfig = null;
        com.tsvoice.client.connection.BackendCoordinator.endSession(sessionToken);
        sessionToken = new com.tsvoice.client.connection.VoiceState.SessionToken(0);
        Thread t = sessionThread;
        if (t != null) {
            t.interrupt();
        }
        cleanupClient();
        // 手动断开/配置停用时释放 Ghost 与 AEC 参考，避免界面残留与解码器泄漏
        TsAudioInjector.removeAll();
        com.tsvoice.client.audio.AecReferenceMixer.clear();
    }

    /**
     * Network close is submitted off the manager lock. TCP disconnect can block;
     * holding the monitor here would stall GUI, SVC lifecycle and reconnect paths.
     */
    private static void cleanupClient() {
        Ts3VoiceClient old = client;
        client = null;
        if (old == null) {
            return;
        }
        ClientIdentityService.tsDisconnected(old);
        CLOSE_EXECUTOR.execute(() -> {
            try {
                old.disconnect();
            } catch (Exception ignored) {
            }
        });
    }

    /** 当前线程是否仍代表有效会话（未被 stopSession 换代）。 */
    private static boolean active(long gen) {
        return sessionRunning && gen == sessionGeneration;
    }

    private static void disconnectQuiet(Ts3VoiceClient c) {
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
            ClientIdentityService.tsDisconnected(c);
        }
    }

    private static void sessionLoop(long gen) {
        try {
            long backoffMs = INITIAL_BACKOFF_MS;
            while (active(gen)) {
                try {
                    Ts3VoiceClient c = connect(gen);
                    boolean committed;
                    synchronized (TsVoiceManager.class) {
                        committed = active(gen);
                        if (committed) {
                            client = c;
                            state = State.CONNECTED;
                            lastError = "";
                            backoffMs = INITIAL_BACKOFF_MS;
                        }
                    }
                    if (!committed) {
                        disconnectQuiet(c);
                        return;
                    }
                    com.tsvoice.client.connection.BackendCoordinator.publishRoute(sessionToken,
                            com.tsvoice.client.connection.VoiceState.BackendKind.TS3, true);
                } catch (Exception e) {
                    if (gen == sessionGeneration) {
                        state = State.RECONNECTING;
                        lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                        com.tsvoice.client.connection.BackendCoordinator.publishRoute(sessionToken,
                                com.tsvoice.client.connection.VoiceState.BackendKind.TS3, false);
                    }
                    TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] TS connect failed: {}. Retrying in {}ms",
                            e.getMessage(), backoffMs);
                }
                if (!active(gen)) {
                    return;
                }

                long lastGroupCheck = 0;
                while (active(gen)) {
                    Ts3VoiceClient c = client;
                    if (c == null || !c.isConnected()) {
                        break;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastGroupCheck >= 2_000L) {
                        lastGroupCheck = now;
                        try {
                            GroupChannelMapper.tick(c);
                            TsAudioInjector.reconcile(c);
                        } catch (Exception e) {
                            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] group mapping failed: {}", e.getMessage());
                        }
                    }
                    try {
                        Thread.sleep(ALIVE_POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!active(gen)) {
                    return;
                }

                Ts3VoiceClient lost;
                synchronized (TsVoiceManager.class) {
                    if (!active(gen)) {
                        return;
                    }
                    state = State.RECONNECTING;
                    com.tsvoice.client.connection.BackendCoordinator.publishRoute(sessionToken,
                            com.tsvoice.client.connection.VoiceState.BackendKind.TS3, false);
                    lost = client;
                    client = null;
                }
                disconnectQuiet(lost);
                TsAudioInjector.removeAll();
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] TS connection lost — reconnecting in {}ms", backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        } finally {
            synchronized (TsVoiceManager.class) {
                if (gen == sessionGeneration) {
                    sessionRunning = false;
                }
            }
        }
    }

    private static Ts3VoiceClient connect(long gen) throws Exception {
        SessionConfig cfg = activeConfig;
        if (cfg == null) {
            throw new IllegalStateException("no session config");
        }
        Path identityPath = FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc").resolve("identity.txt");
        Ts3Identity identity = Ts3Identity.load(identityPath);
        if (identity == null) {
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Generating TS identity (level 24)...");
            identity = Ts3Identity.generate(24, 8_000);
            identity.save(identityPath);
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS identity ready (uid={})", identity.getClientUid());
        }

        String nickname = "[MC] " + Minecraft.getInstance().getUser().getName();
        Ts3VoiceClient c = new Ts3VoiceClient(identity, nickname, "", cfg.tcp());
        c.setServerPassword(cfg.password());
        // 回调绑定来源 client 与会话代际（投递/处理双检）：被替换会话的迟到回调
        // 在此整体丢弃，不得查询全局"当前 client"解释自己的 clid/文本。
        c.setTextListener((invokerName, message) -> {
            if (active(gen) && client == c) {
                onTsText(invokerName, message);
            }
        });
        c.setVoiceListener(frame -> {
            if (active(gen) && client == c) {
                handleVoiceFrame(c, frame);
            }
        });
        try {
            c.connect(InetAddress.getByName(cfg.host()), cfg.port(), CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
            throw e;
        }
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS voice connected (clid={}, player={}, via {}, channel='{}')",
                c.getMyClid(), nickname, cfg.source(), cfg.channel());
        try {
            c.sendCommand("clientlist -uid");
            // 生产路径必须主动拉取频道表：GroupChannelMapper 依赖 频道名→cid 映射
            c.requestChannelList();
            c.requestServerInfo();
        } catch (Ts3VoiceException e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] clientlist/channellist request failed: {}", e.getMessage());
        }
        // Optional MC↔TS identity proof. No claim means an ordinary TS Ghost.
        try {
            ClientIdentityService.beginProof(c, identity);
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] identity proof request failed: {}", e.getMessage());
        }
        // TS 用户在频道间移动 → Ghost 可见性实时同步（进我频道=出现，离开=消失）
        c.setChannelMembershipListener(clid -> {
            if (!active(gen) || client != c) {
                return;
            }
            try {
                TsAudioInjector.syncAllGhostVisibility(c);
            } catch (Exception e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] ghost visibility sync failed: {}", e.getMessage());
            }
        });
        // 广播自己的 MC UUID（跨服皮肤互认）+ 上传 MC 脸头像（各一次，后台执行）
        try {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                c.publishMcUuid(player.getUUID());
                String avatarNamespace = cfg.host() + ":" + cfg.port();
                AvatarPipeline.uploadAvatarAsync(c, player.getUUID(), avatarNamespace, cfg.host());
            }
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] post-connect extras failed: {}", e.getMessage());
        }
        return c;
    }

    /**
     * 麦克风帧发送（SVC mic 线程调用，帧已由 SVC 编码，直通 TS）。
     *
     * @return 是否已接管（接管后调用方应取消 SVC 原始发送）
     */
    public static boolean sendMic(byte[] opusData) {
        Ts3VoiceClient c = client;
        if (c == null || !c.isConnected()) {
            return false;
        }
        if (!GroupChannelMapper.isReadyForVoice(c)) {
            // 仍接管麦克风，防止回落 SVC/旧 TS 频道泄露群组切换中的语音。
            return true;
        }
        try {
            c.sendVoice(opusData, Ts3VoiceClient.CODEC_OPUS_VOICE);
            sentVoiceFrames.incrementAndGet();
            return true;
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] sendVoice failed: {}", e.getMessage());
            return true; // 会话在，但发送失败：仍接管（避免双重音频）
        }
    }

    /**
     * Sends channel text through the same confirmed routing snapshot used by voice.
     * It never falls back to a stale TS channel while a private group move is pending.
     */
    public static boolean sendChannelText(String message) {
        Ts3VoiceClient c = client;
        if (c == null || !c.isConnected()) {
            return false;
        }
        if (!GroupChannelMapper.isReadyForVoice(c)) {
            return false;
        }
        try {
            c.sendChannelText(message);
            return true;
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] sendChannelText failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean isSessionConnected() {
        Ts3VoiceClient c = client;
        return c != null && c.isConnected();
    }

    public static State getState() {
        return state;
    }

    public static String getLastError() {
        return lastError;
    }

    private static boolean wantTcp(ServerConfigStore.Entry entry) {
        if ("tcp".equalsIgnoreCase(entry.transport)) {
            return true;
        }
        if ("udp".equalsIgnoreCase(entry.transport)) {
            return false;
        }
        ModConfig global = TeamspeakSvcClient.config();
        return global != null && "tcp".equalsIgnoreCase(global.transport);
    }

    /** 显式书签意图是否生效（P3 行为分模式：TS 频道选择优先于周期群组映射）。 */
    public static boolean hasExplicitBookmarkIntent() {
        return explicitBookmarkId != null;
    }

    /** 当前会话信息（主频道名等）；休眠为 null。 */
    public static GroupChannelMapper.SessionInfo currentSession() {
        SessionConfig cfg = activeConfig;
        return cfg != null ? new GroupChannelMapper.SessionInfo(cfg.channel()) : null;
    }

    /** 当前完整会话配置（host/port/channel/source/tcp）；休眠为 null。 */
    public static SessionConfig currentSessionConfig() {
        return activeConfig;
    }

    /** 当前配置来源（server/manual），休眠为 "-"。 */
    public static String configSource() {
        SessionConfig cfg = activeConfig;
        return cfg != null ? cfg.source() : "-";
    }

    public static long getReceivedVoiceFrames() {
        return voiceCounter.get();
    }

    /** 成功发往 TS 的麦克风帧数（诊断用）。 */
    public static long getSentVoiceFrames() {
        return sentVoiceFrames.get();
    }

    /** TS 频道文字消息 → MC 聊天栏（chatBridgeEnabled 开时）。 */
    private static void onTsText(String invokerName, String message) {
        if (!chatBridgeOn() || invokerName == null || message == null || message.isBlank()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[TS] " + invokerName + ": " + message));
            }
        });
    }

    private static boolean chatBridgeOn() {
        ModConfig global = TeamspeakSvcClient.config();
        return global == null || global.chatBridgeEnabled;
    }

    /** 当前信标（GUI 状态区/编辑锁定用）；无则 null。 */
    public static com.tsvoice.client.net.BeaconPayload currentBeacon() {
        return beacon;
    }

    /** 当前 TS 客户端（/tsvoice say 用）；可能为 null。 */
    public static Ts3VoiceClient currentClient() {
        return client;
    }

    /** 当前 TS 客户端（注入器周期同步用）；可能为 null。 */
    public static Ts3VoiceClient currentClientForSync() {
        return client;
    }

    /** TS 语音帧处理：source 是帧的来源会话，解析只依赖 source（F03）。 */
    private static void handleVoiceFrame(Ts3VoiceClient source, Ts3VoiceClient.TsVoiceIn frame) {
        voiceCounter.incrementAndGet();
        String uid = source.getUniqueIdOf(frame.senderClid());
        if (uid == null) {
            if (unknownClids.add(frame.senderClid())) {
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] voice frame from unknown clid {} (TS client list not synced) — dropped",
                        frame.senderClid());
            }
            return; // 未知 clid（映射尚未同步）
        }
        String nickname = source.getNicknameOf(frame.senderClid());
        // TS nicknames/descriptions are unverified presentation data. Only a signed
        // claim can map a TS user to an MC player; everyone else is a normal Ghost.
        java.util.UUID mcUuid = ClientIdentityService.getVerifiedMcUuid(
                source.getVirtualServerUid(), uid);
        TsAudioInjector.onTsFrame(source.getVirtualServerUid(), uid, nickname, mcUuid,
                frame.sequence(), frame.codec(), frame.opus());
    }
}
