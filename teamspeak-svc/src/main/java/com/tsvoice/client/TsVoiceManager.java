package com.tsvoice.client;

import com.tsvoice.client.net.BeaconPayload;
import com.tsvoice.client.ts3voice.Ts3Connection;
import com.tsvoice.client.ts3voice.Ts3Crypt;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.client.ts3voice.Ts3VoiceException;
import com.tsvoice.client.ts3voice.TsMcChannelTransport;
import com.tsvoice.common.net.TsRelayPayloads;
import com.tsvoice.protocol.relay.TsRelayWire;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

    public enum State {
        DORMANT, WAITING_SVC, AWAITING_TRUST, CONNECTING, CONNECTED, RECONNECTING,
        /** 用户主动断开 TS：不自动切换 SVC/不自动重连，等待用户明确选择（D1）。 */
        PAUSED,
        /** 用户关闭全部语音收发（保留配置）。 */
        VOICE_OFF, ERROR
    }

    /**
     * 当前生效的会话配置（S1.1/P6：transport 为明确类型 —— 旧 boolean tcp 只
     * 迁移为 TS_TUNNEL_V1；password=服务器/频道密码）。
     */
    public record SessionConfig(String host, int port, String channel, String source,
                                com.tsvoice.client.connection.VoiceTransport transport,
                                String password, String channelPassword) {

        /** 兼容视图：M7 语义的 tcp 标志（true=旧隧道；MC 通道/UDP 均为 false）。 */
        public boolean tcp() {
            return transport == com.tsvoice.client.connection.VoiceTransport.TS_TUNNEL_V1;
        }

        /** 兼容构造器（旧调用方/harness）：boolean tcp → 明确传输类型。 */
        public SessionConfig(String host, int port, String channel, String source, boolean tcp,
                             String password, String channelPassword) {
            this(host, port, channel, source,
                    com.tsvoice.client.connection.VoiceTransport.fromLegacyTcp(tcp),
                    password, channelPassword);
        }
    }

    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long ALIVE_POLL_MS = 500L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;

    /** 连接超时（毫秒）—— 音乐 Bot 等独立会话复用同一网络预算。 */
    public static long connectTimeoutMs() {
        return CONNECT_TIMEOUT_MS;
    }
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
    /** 前端 mic 租约（T08b）：会话启动获取、停止释放；SVC 断开由适配器关闭后按需重取。 */
    private static volatile com.tsvoice.client.frontend.FrontendLease frontendLease;
    /** 书签独立昵称（T09/N4）：null=默认 "[MC] 玩家名"；不写入身份或凭据。 */
    private static volatile String sessionNicknameOverride;
    /**
     * 能力发现状态机（T12/N6）：hello/公告/TTL/授权的生命周期；候选只作为 AUTO
     * 语义下的服务器推荐，手动书签/PAUSED/OFF 一律不受影响。
     */
    private static final com.tsvoice.client.connection.CapabilityDiscovery capabilityDiscovery =
            new com.tsvoice.client.connection.CapabilityDiscovery(System::nanoTime);
    /** 最近一次连接失败的统一类别（T12/N6：认证/密码/权限不当作 UDP 不通）。 */
    private static volatile com.tsvoice.client.connection.VoiceState.FailureKind lastFailureKind;
    /**
     * 最近一次被接受的公告帧原文（T12/N6 重复响应防护）：字节完全相同的重放
     * 不再进入状态机（幂等拒绝）；同 nonce 的更新公告（不同字节）仍按 T11 语义
     * 替换候选集。换服时随 context 失效。
     */
    private static volatile byte[] lastAcceptedAnnouncementFrame;

    /** 会话是否持有前端 mic 租约（诊断/回归观测用）。 */
    public static boolean frontendLeaseHeld() {
        com.tsvoice.client.frontend.FrontendLease lease = frontendLease;
        return lease != null && !lease.isClosed();
    }

    /**
     * 持有的租约是否带有存活采集源（N2/D02 诊断：区分"持有租约"与"mic 存活" ——
     * D02 探针中两者曾长期背离）。
     */
    public static boolean frontendMicAlive() {
        com.tsvoice.client.frontend.FrontendLease lease = frontendLease;
        return lease != null && lease.micAlive();
    }
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

    /** MC 连接变化（进入/离开服务器）。PAUSED/OFF 与显式选择不被换服自动覆盖（E14）。 */
    public static void onServerChanged(String address) {
        currentAddress = address == null ? "" : address;
        // MC 上下文代际递增（T04）：排队的旧 MC 关联任务（聊天显示等）执行时失效。
        com.tsvoice.client.connection.BackendCoordinator.onMcContextChanged(currentAddress);
        beacon = null;
        pendingBeacon = null;
        GroupChannelMapper.reset();
        // T12/N6：换服失效全部公告/授权与在途请求；手动 TS/PAUSED/OFF 意图不动。
        capabilityDiscovery.onContextChanged(
                com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch());
        lastAcceptedAnnouncementFrame = null;
        evaluateActivation();
    }

    // ---- 能力发现（T12/N6）：生产链 hello → 公告 → 授权 → 候选选择 ----

    /**
     * 开始发现窗口并返回 hello 帧（nonce 由状态机生成，响应必须回带）。
     * Fabric 层仅在当前连接支持该通道时实际发送（ClientPlayNetworking.canSend）。
     */
    public static byte[] beginCapabilityDiscovery() {
        long nonce = capabilityDiscovery.begin();
        return com.tsvoice.protocol.capability.CapabilityWire.encode(
                new com.tsvoice.protocol.capability.CapabilityWire.Capabilities(nonce,
                        com.tsvoice.protocol.capability.CapabilityWire.CURRENT_MAJOR,
                        com.tsvoice.protocol.capability.CapabilityWire.CURRENT_MINOR,
                        0, "", java.util.List.of()));
    }

    /** 当前发现请求 nonce（回归测试构造合法公告用；生产路径不使用）。 */
    public static long discoveryRequestNonceForTest() {
        return capabilityDiscovery.currentRequestNonce();
    }

    /**
     * 公告帧消费（Fabric receiver 在主线程委托）：有界校验 → 解码 → 绑定当前
     * context 与请求 nonce 的状态机登记。返回是否产生了候选。
     */
    public static boolean onCapabilitiesAnnouncementFrame(byte[] frame) {
        if (frame == null || frame.length > com.tsvoice.protocol.capability.CapabilityWire.MAX_FRAME_BYTES) {
            return false;
        }
        com.tsvoice.protocol.capability.CapabilityWire.Capabilities capabilities;
        try {
            capabilities = com.tsvoice.protocol.capability.CapabilityWire.decode(frame);
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] malformed capabilities frame: {}", e.getMessage());
            return false;
        }
        // S0.2/R27：decode 对短帧/坏 magic/非法计数等畸形输入返回 null 而不是抛出
        // 异常 —— 直接解引用会在 Fabric 接收任务里抛 NPE。null 一律按畸形帧丢弃，
        // 不改变既有授权/选择状态。
        if (capabilities == null) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] malformed capabilities frame: decode returned null");
            return false;
        }
        if (java.util.Arrays.equals(frame, lastAcceptedAnnouncementFrame)) {
            return false; // 字节级重复响应：状态已登记，幂等拒绝
        }
        boolean accepted = capabilityDiscovery.onAnnouncement(
                com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch(),
                capabilities.requestNonce(), capabilities);
        if (accepted) {
            lastAcceptedAnnouncementFrame = frame.clone();
        }
        if (accepted) {
            if (!capabilityDiscovery.hasAuthorizedTsRecommendation()) {
                // 预信任 endpoint（信标记住的同源地址）自动授权；其余进入待授权，
                // 由用户经 CapabilityConsentScreen 显式决定（S0.4/R23）。
                authorizeAnnouncedCandidates();
            }
            notifyPendingConsent();
        }
        return accepted;
    }

    /** 未授权候选监听器（Fabric 层注册）：进入授权界面或状态提示。 */
    private static volatile Runnable pendingConsentListener;

    public static void setPendingConsentListener(Runnable listener) {
        pendingConsentListener = listener;
    }

    /** 有新的待授权公告候选且玩家尚未拒绝过：通知监听器（客户端线程调用）。 */
    private static void notifyPendingConsent() {
        if (capabilityDiscovery.receivedCandidates().isEmpty()
                || !capabilityDiscovery.rejectedCandidates().isEmpty()) {
            return; // 无候选，或本窗口已被玩家拒绝过：不再打扰
        }
        var listener = pendingConsentListener;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * 公告候选授权（N6 → S0.4/R23 修订）：公告来自玩家已加入的 MC 服务器本身 ——
     * 与信标同源的信任模型：只有 endpoint 已被信标流程显式信任的候选自动授权；
     * 其余候选保持未授权并进入待授权状态（显式授权入口 = CapabilityConsentScreen），
     * 绝不把未授权候选送入选择。
     */
    public static void authorizeAnnouncedCandidates() {
        var entry = ServerConfigStore.get(currentAddress);
        var received = capabilityDiscovery.receivedCandidates();
        var trusted = received.stream().filter(c -> {
            var candidate = c.candidate();
            boolean hostTrusted = entry.trustedBeaconHost != null
                    && entry.trustedBeaconHost.equals(candidate.host());
            boolean portTrusted = entry.trustedBeaconPort == null
                    || entry.trustedBeaconPort.equals(candidate.port());
            return hostTrusted && portTrusted;
        }).toList();
        if (trusted.isEmpty()) {
            TeamspeakSvcClient.LOGGER.info(
                    "[teamspeak-svc] capabilities announcement received: {} candidate(s) — none pre-trusted, awaiting explicit authorization",
                    received.size());
            return;
        }
        boolean ok = capabilityDiscovery.authorize(
                com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch(), trusted);
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] capabilities announcement: {} trusted candidate(s) authorized={}",
                trusted.size(), ok);
    }

    // ---- S0.4/R23：公告首次授权入口（显式动作，绑定 context/接收快照/能力种类）----

    /** 是否有等待玩家显式决定的公告候选（未授权、未拒绝、未过期）。 */
    public static boolean hasPendingAnnouncementConsent() {
        return !capabilityDiscovery.receivedCandidates().isEmpty()
                && capabilityDiscovery.hasAuthorizedTsRecommendation() == false
                && capabilityDiscovery.rejectedCandidates().isEmpty();
    }

    /** 待授权候选快照（授权界面数据源；未授权、含过期点）。 */
    public static java.util.List<com.tsvoice.client.connection.CapabilityDiscovery.AnnouncedCandidate>
    pendingConsentCandidates() {
        var rejected = capabilityDiscovery.rejectedCandidates();
        return capabilityDiscovery.receivedCandidates().stream()
                .filter(c -> !rejected.contains(c))
                .toList();
    }

    /**
     * 玩家显式接受当前待授权公告候选（授权界面动作）。
     *
     * @param remember 同时把 TS endpoint 记入本服信任（与信标记住同一语义）；
     *                 仅记住 TS_ENDPOINT —— 不把同地址所有未来能力自动批准
     *                 （新能力种类仍需重新授权）。
     */
    public static boolean acceptAnnouncedCandidates(boolean remember) {
        var pending = pendingConsentCandidates();
        if (pending.isEmpty()) {
            return false;
        }
        boolean ok = capabilityDiscovery.authorize(
                com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch(), pending);
        if (ok && remember) {
            // 记住 TS endpoint（与 BeaconTrustScreen 的 remember 同一信任存储）；
            // 每个候选单独记录，不扩大到同地址的其他能力种类。
            for (var c : pending) {
                if (c.candidate().kind()
                        == com.tsvoice.protocol.capability.CapabilityWire.CandidateKind.TS_ENDPOINT) {
                    ServerConfigStore.Entry entry = ServerConfigStore.get(currentAddress);
                    entry.trustedBeaconHost = c.candidate().host();
                    entry.trustedBeaconPort = c.candidate().port();
                    ServerConfigStore.save();
                    break;
                }
            }
        }
        if (ok) {
            TeamspeakSvcClient.LOGGER.info(
                    "[teamspeak-svc] announcement candidates explicitly accepted ({} candidate(s), remember={})",
                    pending.size(), remember);
            evaluateActivation();
        }
        return ok;
    }

    /**
     * 玩家显式拒绝当前待授权公告候选：本 context 内不再弹窗/自动授权；
     * 已授权集合不受影响（拒绝只作用于未授权候选）。
     */
    public static boolean rejectAnnouncedCandidates() {
        var pending = pendingConsentCandidates();
        if (pending.isEmpty()) {
            return false;
        }
        boolean ok = capabilityDiscovery.reject(
                com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch(), pending);
        if (ok) {
            TeamspeakSvcClient.LOGGER.info(
                    "[teamspeak-svc] announcement candidates rejected by user ({} candidate(s))",
                    pending.size());
        }
        return ok;
    }

    /** 当前已授权且未过期的公告 TS 候选（选择策略输入；诊断用）。 */
    public static java.util.List<com.tsvoice.client.connection.CapabilityDiscovery.AnnouncedCandidate>
    authorizedAnnouncedCandidates() {
        return capabilityDiscovery.authorizedCandidates();
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
            var selection = com.tsvoice.client.connection.BackendCoordinator.snapshot().selection();
            if (selection.intent() != com.tsvoice.client.connection.SelectionPolicy.IntentKind.PAUSED
                    && selection.intent() != com.tsvoice.client.connection.SelectionPolicy.IntentKind.OFF) {
                state = State.AWAITING_TRUST;
            }
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
        // N2/D02 前端交接（建立侧）：若会话正持有自有隔离租约而原生 mic 已上线，
        // 关闭自有租约并立即重取（将借用原生 mic），避免双采集。
        com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance().onSvcConnected();
        reacquireFrontendLeaseIfNeeded();
        evaluateActivation();
    }

    /**
     * SVC 客户端断开。P1 起只更新适配器与状态显示，不再销毁 TS 会话 ——
     * TS 路由被选中期间麦克风保持消费（不泄漏回 SVC），TS 重连循环继续。
     * N2/D02 前端交接（断开侧）：借用原生 mic 随 SVC 断开死亡 —— 前端断连钩子
     * 关闭受影响租约，会话循环按修复后的顺序（先释放再创建）重取隔离 mic。
     */
    public static void onSvcDisconnected() {
        svcConnected = false;
        com.tsvoice.client.backend.SvcBackendAdapter.onSvcDisconnected();
        com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance().onSvcDisconnected();
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
            // S0.5/R24：凭据按书签自身 ref 解析（旧书签无 ref 回落 endpoint）——
            // 同 host:port 的两个书签通过引用保存独立凭据；引用缺失明确无凭据，
            // 不静默借用其他书签/endpoint 的密码。
            var credential = com.tsvoice.client.config.EndpointCredentialStore.getByRef(
                    bookmark.credentialRef(), bookmark.host(), port);
            // S1.1：明确传输类型（旧 "tcp" 只映射 TS_TUNNEL_V1，不自动变 MC 通道；
            // "mc_channel" 显式选择中继）
            var transport = com.tsvoice.client.connection.VoiceTransport.fromConfig(
                    bookmark.transportPolicy());
            return new SessionConfig(bookmark.host(), bookmark.effectivePort(),
                    bookmark.channel() != null ? bookmark.channel() : "Minecraft", "manual",
                    transport,
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
        // T12/N6：已授权公告推荐 —— AUTO 语义下的服务器推荐 TS 目标，优先于全局
        // 自动配置、低于本服/信标/书签。无公告不影响既有配置（只淘汰公告候选）；
        // 公告 endpoint 绝不继承其他 endpoint 的凭据。
        if (enabled && capabilityDiscovery.hasAuthorizedTsRecommendation()) {
            var candidate = capabilityDiscovery.authorizedCandidates().stream()
                    .filter(c -> c.candidate().kind()
                            == com.tsvoice.protocol.capability.CapabilityWire.CandidateKind.TS_ENDPOINT
                            || c.candidate().kind()
                            == com.tsvoice.protocol.capability.CapabilityWire.CandidateKind.TUNNEL)
                    .findFirst();
            if (candidate.isPresent()) {
                var target = candidate.get().candidate();
                boolean tunnel = target.kind()
                        == com.tsvoice.protocol.capability.CapabilityWire.CandidateKind.TUNNEL;
                return new SessionConfig(target.host(), target.port(),
                        "Minecraft", "announcement", tunnel, null, null);
            }
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
     * 连接失败 → 统一 FailureKind（T12/N6）：认证/密码/权限/封禁/版本不再被
     * 当作"UDP 不通"，也不得经跨网络切换绕过 —— 这些类别终止重试等待用户处理。
     *
     * <p>S0.7/R26 收紧：优先按异常类型与结构化 TS 错误 id 分类（协议拒绝由
     * {@link TsProtocolException} 携带 id/msg token）；普通 IOException 及其
     * "connection refused/reset/timed out" 等传输症状保持可重试的 NETWORK_TIMEOUT；
     * 取消与内部错误各归各类 —— 未识别异常不再默认 NETWORK_TIMEOUT。</p>
     */
    static com.tsvoice.client.connection.VoiceState.FailureKind classifyConnectFailure(Throwable t) {
        if (t == null) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.INTERNAL;
        }
        if (t instanceof java.util.concurrent.CancellationException
                || (t instanceof InterruptedException)) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.CANCELLED;
        }
        if (t instanceof com.tsvoice.client.ts3voice.TsProtocolException protocol) {
            // 结构化 TS 协议拒绝：按服务器返回的 msg token 分类（数值 id 的
            // 解释须依真实协议资料核实，此处仅使用 msg token；未识别 token
            // 属服务器明确的协议拒绝 —— 终止，不当网络超时重试）。
            String msg = protocol.errorMessage() == null ? "" : protocol.errorMessage().toLowerCase();
            String extra = protocol.extraMessage() == null ? "" : protocol.extraMessage().toLowerCase();
            if (msg.contains("invalid_server_password") || msg.contains("serverpassword")) {
                return com.tsvoice.client.connection.VoiceState.FailureKind.AUTH_DENIED;
            }
            if (msg.contains("invalid_channel_password") || msg.contains("channel_password")) {
                return com.tsvoice.client.connection.VoiceState.FailureKind.CHANNEL_PASSWORD;
            }
            if (msg.contains("permission") || msg.contains("insufficient")) {
                return com.tsvoice.client.connection.VoiceState.FailureKind.PERMISSION_DENIED;
            }
            if (msg.contains("ban")) {
                return com.tsvoice.client.connection.VoiceState.FailureKind.BANNED;
            }
            if (msg.contains("server_full") || msg.contains("maxclients")) {
                return com.tsvoice.client.connection.VoiceState.FailureKind.SERVER_FULL;
            }
            if (msg.contains("version") || msg.contains("protocol")) {
                return com.tsvoice.client.connection.VoiceState.FailureKind.UNSUPPORTED_PROTOCOL;
            }
            // 服务器明确拒绝但 token 未收录：不重试，等待用户/日志研判。
            return com.tsvoice.client.connection.VoiceState.FailureKind.PROTOCOL_REJECTED;
        }
        if (t instanceof java.net.UnknownHostException) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.DNS_FAILED;
        }
        if (t instanceof java.io.IOException
                || t instanceof java.net.SocketException
                || t instanceof java.net.SocketTimeoutException
                || t instanceof java.net.ConnectException) {
            // 传输层症状（含 UDP 不可达/连接拒绝/超时）：可重试。
            return com.tsvoice.client.connection.VoiceState.FailureKind.NETWORK_TIMEOUT;
        }
        String message = String.valueOf(t.getMessage() == null ? t : t.getMessage()).toLowerCase();
        if (message.contains("unknown host") || message.contains("unknownhostexception")) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.DNS_FAILED;
        }
        if (message.contains("invalid server password") || message.contains("bad login")
                || message.contains("authentication")) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.AUTH_DENIED;
        }
        if (message.contains("invalid channel password") || message.contains("channel password")) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.CHANNEL_PASSWORD;
        }
        if (message.contains("permission") || message.contains("insufficient")) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.PERMISSION_DENIED;
        }
        if (message.contains("banned")) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.BANNED;
        }
        if (message.contains("unsupported") || message.contains("protocol mismatch")
                || message.contains("old client")) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.UNSUPPORTED_PROTOCOL;
        }
        if (message.contains("connection closed") || message.contains("removed from server")
                || message.contains("server error id=")) {
            // 服务器端断开/拒绝（非结构化历史路径）：保留数值/原因供展示；
            // 终止性由具体原因判断，未知不当网络超时重试。
            return com.tsvoice.client.connection.VoiceState.FailureKind.INTERNAL;
        }
        if (t instanceof RuntimeException || t instanceof Error
                || t instanceof IllegalStateException
                || t instanceof NullPointerException
                || t instanceof ClassCastException
                || t instanceof IllegalArgumentException) {
            return com.tsvoice.client.connection.VoiceState.FailureKind.INTERNAL;
        }
        // 未识别异常：显式 INTERNAL —— 不自动推断 UDP 不通（R26）。
        return com.tsvoice.client.connection.VoiceState.FailureKind.INTERNAL;
    }

    /** 失败类别是否终止自动重试（等待用户修改入口：凭据/地址/授权）。 */
    static boolean isTerminalFailure(com.tsvoice.client.connection.VoiceState.FailureKind kind) {
        return kind == com.tsvoice.client.connection.VoiceState.FailureKind.AUTH_DENIED
                || kind == com.tsvoice.client.connection.VoiceState.FailureKind.CHANNEL_PASSWORD
                || kind == com.tsvoice.client.connection.VoiceState.FailureKind.PERMISSION_DENIED
                || kind == com.tsvoice.client.connection.VoiceState.FailureKind.BANNED
                || kind == com.tsvoice.client.connection.VoiceState.FailureKind.UNSUPPORTED_PROTOCOL
                || kind == com.tsvoice.client.connection.VoiceState.FailureKind.FRONTEND_UNAVAILABLE
                // S0.7/R26：内部错误不是网络症状，不再冒充 NETWORK_TIMEOUT 触发
                // 跨网络切换推断；可重试（缺陷会记录在 ERROR 展示与日志），但不
                // 等同"UDP 不通"。PROTOCOL_REJECTED（服务器明确拒绝）终止等待用户。
                || kind == com.tsvoice.client.connection.VoiceState.FailureKind.PROTOCOL_REJECTED;
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
     * 重新评估激活：配置变化触发会话重建，无配置则休眠。
     *
     * <p>选择归属的唯一提交点是 {@link com.tsvoice.client.connection.BackendCoordinator}：
     * AUTO 在此提交解析出的后端；PAUSED/OFF 与显式 PINNED 不被公告、重试、设备事件
     * 或 MC 换服自动覆盖（D1/E14）。</p>
     */
    static synchronized void evaluateActivation() {
        var selection = com.tsvoice.client.connection.BackendCoordinator.snapshot().selection();
        var intent = selection.intent();
        if (intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PAUSED
                || intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.OFF) {
            // 用户主动断开/全关：任何自动来源都不得恢复语音或改选后端。
            stopSession();
            state = intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.OFF
                    ? State.VOICE_OFF : State.PAUSED;
            return;
        }
        if (intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PINNED_SVC) {
            stopSession();
            state = State.DORMANT;
            return;
        }
        SessionConfig resolved = resolveSessionConfig();
        activeConfig = resolved;
        if (intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PINNED_TS) {
            if (resolved == null) {
                // 显式 TS 目标已不存在（如书签被删除）：清除意图，回落常规解析。
                com.tsvoice.client.connection.BackendCoordinator.selectAuto(
                        com.tsvoice.client.connection.VoiceState.BackendKind.SVC, "vanilla");
                stopSession();
                state = pendingBeacon != null ? State.AWAITING_TRUST : State.DORMANT;
                return;
            }
        } else {
            // AUTO：把解析出的后端归属提交为事实（发送归属跟随配置存在与否）。
            com.tsvoice.client.connection.BackendCoordinator.selectAuto(
                    resolved != null ? com.tsvoice.client.connection.VoiceState.BackendKind.TS3
                            : com.tsvoice.client.connection.VoiceState.BackendKind.SVC,
                    resolved != null
                            ? resolved.source() + "@" + resolved.host() + ":" + resolved.port()
                            : "vanilla");
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
        // T08b：TS 不再等待 SVC 服务器连接 —— 前端自检（frontendUnavailable）决定
        // 可用性；无 SVC 服务器时用 null-connection 隔离 mic 采集。
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

    // ---- 用户意图入口（全部经协调器提交，R01 修复面）----

    /**
     * 用户手动连接 TS：先提交 TS 选择与未就绪路由，再启动后台连接；
     * 连接失败不改变已选后端（保持 TS，进入重连循环）。
     */
    public static void manualConnect() {
        com.tsvoice.client.backend.SvcBackendAdapter.setVoiceMuted(false); // 仅清除 OFF 覆盖
        SessionConfig resolved = resolveSessionConfig();
        if (resolved == null) {
            lastError = "no configuration (server beacon or manual config required)";
            state = State.ERROR;
            return;
        }
        com.tsvoice.client.connection.BackendCoordinator.select(
                com.tsvoice.client.connection.SelectionPolicy.IntentKind.PINNED_TS,
                resolved.source() + "@" + resolved.host());
        evaluateActivation();
    }

    /**
     * 按书签建立显式连接意图（P3）：立即解析书签配置并提交 TS 选择；不受本服公告
     * （含 server-bridge 休眠规则）与 autoConnect 影响。书签/凭据缺失时报可操作错误。
     */
    public static void manualConnect(String bookmarkId) {
        com.tsvoice.client.backend.SvcBackendAdapter.setVoiceMuted(false);
        com.tsvoice.client.config.TsBookmarkStore.Bookmark bookmark =
                com.tsvoice.client.config.TsBookmarkStore.get(bookmarkId);
        if (bookmark == null) {
            lastError = "bookmark not found: " + bookmarkId;
            state = State.ERROR;
            return;
        }
        explicitBookmarkId = bookmarkId;
        SessionConfig resolved = resolveSessionConfig();
        if (resolved == null) {
            lastError = "bookmark cannot be resolved: " + bookmarkId;
            state = State.ERROR;
            return;
        }
        com.tsvoice.client.connection.BackendCoordinator.select(
                com.tsvoice.client.connection.SelectionPolicy.IntentKind.PINNED_TS,
                "bookmark:" + bookmarkId);
        evaluateActivation();
    }

    /**
     * 用户断开 TeamSpeak（D1）：进入 PAUSED —— 关闭 TS 会话并阻断麦克风，
     * 不自动切换到 SVC、不被公告/换服自动撤销。SVC 已被明确选中时为无操作。
     */
    public static void disconnectTeamSpeak() {
        boolean paused = com.tsvoice.client.connection.BackendCoordinator.disconnectTeamSpeak();
        explicitBookmarkId = null;
        stopSession();
        // F5：断开 TS 停本机音乐 Bot（产品默认；不触碰他人/服务端 Bot）
        com.tsvoice.client.music.MusicBotLifecycle.onPlayerVoiceLifecycleChange("TS disconnected");
        if (paused) {
            state = State.PAUSED;
        } else if (activeConfig == null) {
            state = State.DORMANT;
        }
    }

    /** 兼容入口：旧调用方（GUI/命令/CLIENT_STOPPING）语义即"断开 TeamSpeak"。 */
    public static void manualDisconnect() {
        disconnectTeamSpeak();
    }

    /**
     * 书签删除（T09/N4）：删除"正在使用"的书签不得静默换目标 —— 进入 PAUSED
     * 并清除意图，等待用户明确选择下一个目标；删除其他书签不影响当前会话。
     */
    public static void onBookmarkDeleted(String bookmarkId) {
        if (bookmarkId != null && bookmarkId.equals(explicitBookmarkId)) {
            TeamspeakSvcClient.LOGGER.info(
                    "[teamspeak-svc] Active bookmark '{}' deleted — pausing instead of silently retargeting",
                    bookmarkId);
            disconnectTeamSpeak();
            explicitBookmarkId = null;
        }
    }

    /** 用户切回原生 SVC（独立操作）：TS 会话关闭，发言边界过后恢复原版发送。 */
    public static void switchToSvc() {
        explicitBookmarkId = null;
        com.tsvoice.client.connection.BackendCoordinator.switchToSvc();
        com.tsvoice.client.backend.SvcBackendAdapter.setVoiceMuted(false);
        stopSession();
        // F5：切 SVC 停本机音乐 Bot（产品默认）
        com.tsvoice.client.music.MusicBotLifecycle.onPlayerVoiceLifecycleChange("switched to SVC");
        state = State.DORMANT;
    }

    /** 用户关闭全部语音（收发同时停止，保留配置；用户原有静音设置不受影响）。 */
    public static void disableAllVoice() {
        explicitBookmarkId = null;
        com.tsvoice.client.connection.BackendCoordinator.disableAllVoice();
        // N3/D03：意图立即生效（原生接收 mixin 直读协调器），既有原生 SVC 播放
        // 通道的排队帧与输出资源一并释放；恢复时 SVC 按需重建通道，用户静音/音量不动。
        com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance()
                .flushNativePlaybackChannels();
        com.tsvoice.client.backend.SvcBackendAdapter.setVoiceMuted(true);
        stopSession();
        // F5：OFF 停本机音乐 Bot（已确认产品默认；不触碰他人/服务端 Bot）
        com.tsvoice.client.music.MusicBotLifecycle.onPlayerVoiceLifecycleChange("voice off");
        state = State.VOICE_OFF;
    }

    private static synchronized void startSession() {
        if (sessionRunning || frontendUnavailable) {
            return;
        }
        // T08b：唯一采集资源 —— 先取前端租约再启动会话线程；租约失败 = FRONTEND_UNAVAILABLE。
        if (frontendLease == null || frontendLease.isClosed()) {
            frontendLease = com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance()
                    .acquire(sessionToken == null ? null : sessionToken);
            if (frontendLease == null) {
                lastError = "FRONTEND_UNAVAILABLE (mic lease unavailable)";
                state = State.ERROR;
                return;
            }
        }
        sessionRunning = true;
        state = State.CONNECTING;
        long gen = ++sessionGeneration;
        sessionToken = com.tsvoice.client.connection.BackendCoordinator.beginSession();
        // 连接前先发布未就绪路由（R01）：后台连接期间发送许可保持关闭。
        com.tsvoice.client.connection.BackendCoordinator.publishRoute(sessionToken,
                com.tsvoice.client.connection.VoiceState.BackendKind.TS3, false);
        // 频道策略（T02/T09）：显式书签默认 TS 原生跟随（初始频道=书签频道）；
        // 书签声明 locked 时以书签频道为锁定目标；其余配置沿用 legacy 群组映射。
        com.tsvoice.client.config.TsBookmarkStore.Bookmark activeBookmark =
                explicitBookmarkId != null
                        ? com.tsvoice.client.config.TsBookmarkStore.get(explicitBookmarkId) : null;
        com.tsvoice.client.connection.VoiceState.ChannelPolicy channelPolicy =
                explicitBookmarkId != null
                        ? (activeBookmark != null && activeBookmark.lockedChannel()
                        ? com.tsvoice.client.connection.VoiceState.ChannelPolicy.LOCKED_TARGET
                        : com.tsvoice.client.connection.VoiceState.ChannelPolicy.TS_NATIVE_FOLLOW)
                        : com.tsvoice.client.connection.VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING;
        sessionNicknameOverride = activeBookmark != null ? activeBookmark.effectiveNickname() : null;
        String initialChannel = explicitBookmarkId != null && activeConfig != null
                ? activeConfig.channel() : null;
        com.tsvoice.client.connection.TsChannelRoute.beginSession(sessionToken, channelPolicy, initialChannel);
        Thread t = new Thread(() -> sessionLoop(gen), "teamspeak-svc-voice");
        t.setDaemon(true);
        t.start();
        sessionThread = t;
    }

    /** 会话循环的租约重取（管理器锁内串行；会话已停则为无操作）。 */
    private static synchronized void reacquireFrontendLeaseIfNeeded() {
        if (!sessionRunning) {
            return;
        }
        com.tsvoice.client.frontend.FrontendLease lease = frontendLease;
        if (lease != null && !lease.isClosed() && lease.micAlive()) {
            return;
        }
        com.tsvoice.client.frontend.FrontendLease fresh =
                com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance().acquire(sessionToken);
        if (fresh != null) {
            frontendLease = fresh;
        }
    }

    /**
     * 借用/自有交接（N2/D02）：会话持有自有隔离 mic 且 SVC 原生 mic 已可用时，
     * 关闭自有租约并立即重取为借用原生 mic —— 单一采集所有者不变，来源随 SVC
     * 生命周期切换。幂等：交接完成后租约为借用，检查直接跳过。
     */
    private static synchronized void handoffIsolatedLeaseToNativeMic() {
        if (!sessionRunning) {
            return;
        }
        com.tsvoice.client.frontend.FrontendLease lease = frontendLease;
        if (lease == null || lease.isClosed() || lease.isBorrowedFromNativeSvc()) {
            return;
        }
        if (!com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance().nativeMicAvailable()) {
            return;
        }
        lease.close();
        reacquireFrontendLeaseIfNeeded();
    }

    private static synchronized void stopSession() {
        sessionGeneration++;
        sessionRunning = false;
        lastAppliedConfig = null;
        // S1.4/P6：MC 中继会话随 TS 会话关闭（CLOSE → 服务端回收 socket/worker）
        try {
            closeMcRelaySession();
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] relay session close failed: {}", e.getMessage());
        }
        com.tsvoice.client.frontend.FrontendLease lease = frontendLease;
        frontendLease = null;
        if (lease != null) {
            try {
                lease.close();
            } catch (Exception ignored) {
            }
        }
        com.tsvoice.client.connection.TsChannelRoute.endSession(sessionToken);
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
        // S0.1/R21：本代会话的 token 在线程启动时捕获一次。startSession 在同一管理器
        // 锁内先 beginSession 再启动线程，因此这里读到的一定是本代会话的 token；
        // stopSession 之后 token 被替换/清零，旧线程捕获值不再匹配当前路由。
        final com.tsvoice.client.connection.VoiceState.SessionToken loopToken = sessionToken;
        try {
            long backoffMs = INITIAL_BACKOFF_MS;
        int backoffAttempt = 0;
            while (active(gen)) {
                try {
                    Ts3VoiceClient c = connect(gen, loopToken);
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
                    // 握手成功 ≠ 发送许可：路由就绪只能由频道确认（confirmChannel）发布（3.3）。
                    com.tsvoice.client.connection.BackendCoordinator.publishRoute(sessionToken,
                            com.tsvoice.client.connection.VoiceState.BackendKind.TS3, false);
                } catch (Exception e) {
                    if (gen == sessionGeneration) {
                        var failureKind = classifyConnectFailure(e);
                        lastFailureKind = failureKind;
                        lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                        if (isTerminalFailure(failureKind)) {
                            // 认证/密码/权限/封禁/版本：无界重试没有意义 —— 停在 ERROR
                            // 等待用户修改凭据/地址/授权，不静默跨传输或跨后端绕过。
                            state = State.ERROR;
                            com.tsvoice.client.connection.TsChannelRoute.onConnectionLost(sessionToken);
                            com.tsvoice.client.connection.BackendCoordinator.publishRoute(sessionToken,
                                    com.tsvoice.client.connection.VoiceState.BackendKind.TS3, false);
                            TeamspeakSvcClient.LOGGER.error(
                                    "[teamspeak-svc] TS connect failed terminally ({}): {} — waiting for user action",
                                    failureKind, lastError);
                            return;
                        }
                        state = State.RECONNECTING;
                        com.tsvoice.client.connection.TsChannelRoute.onConnectionLost(sessionToken);
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
                    // T08b：SVC 断开/重建后按所有权交接 —— 租约失效则重新获取
                    //（与管理器锁串行，避免与会话启动的获取竞争）。
                    reacquireFrontendLeaseIfNeeded();
                    if (now - lastGroupCheck >= 2_000L) {
                        lastGroupCheck = now;
                        try {
                            // N2/D02：原生 mic 的启动可能晚于 SVC 连接事件 —— 会话
                            // 周期复核借用/自有交接，保证同一时刻只有一个采集源。
                            handoffIsolatedLeaseToNativeMic();
                            // 频道确认对账（含书签初始目标解析）先于 legacy 映射；
                            // 两者都只提交目标/观察，许可仍由协调器发布。
                            com.tsvoice.client.connection.TsChannelRoute.tick(c);
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
                    com.tsvoice.client.connection.TsChannelRoute.onConnectionLost(sessionToken);
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
                // T12/N6：计划退避基值 2→4→…→60s + ±20% jitter（统一走选择策略）
                backoffMs = com.tsvoice.client.connection.SelectionPolicy.backoffMillis(
                        backoffAttempt++, java.util.concurrent.ThreadLocalRandom.current());
            }
        } finally {
            synchronized (TsVoiceManager.class) {
                if (gen == sessionGeneration) {
                    sessionRunning = false;
                }
            }
        }
    }

    private static Ts3VoiceClient connect(long gen,
            com.tsvoice.client.connection.VoiceState.SessionToken sessionBoundToken) throws Exception {
        SessionConfig cfg = activeConfig;
        if (cfg == null) {
            throw new IllegalStateException("no session config");
        }
        // S0.5/R24：身份解析顺序 —— 书签 identityRef（受控 identities/ 目录）>
        // 全局 identity.txt（兼容默认）。引用存在但记录缺失时明确报错，不静默
        // 换用全局身份；私钥绝不写入书签或日志。
        Path identityPath = FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc").resolve("identity.txt");
        Ts3Identity identity = null;
        com.tsvoice.client.config.TsBookmarkStore.Bookmark activeBookmark =
                explicitBookmarkId != null
                        ? com.tsvoice.client.config.TsBookmarkStore.get(explicitBookmarkId) : null;
        if (activeBookmark != null && activeBookmark.identityRef() != null
                && !activeBookmark.identityRef().isBlank()) {
            identity = com.tsvoice.client.config.EndpointCredentialStore.loadIdentityRef(
                    activeBookmark.identityRef());
            if (identity == null) {
                throw new IllegalStateException(
                        "identity reference missing: " + activeBookmark.identityRef()
                                + " (create it in config/teamspeak-svc/identities/)");
            }
        }
        if (identity == null) {
            identity = Ts3Identity.load(identityPath);
        }
        if (identity == null) {
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Generating TS identity (level 24)...");
            identity = Ts3Identity.generate(24, 8_000);
            identity.save(identityPath);
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS identity ready (uid={})", identity.getClientUid());
        }

        String nickname = sessionNicknameOverride != null
                ? sessionNicknameOverride
                : "[MC] " + Minecraft.getInstance().getUser().getName();
        // S1.4/P6：MC 中继路径 —— 传输注入构造；客户端不做任何 TS 主机名解析
        //（endpoint 解析与允许判断都在服务端按 allowlist 执行）。
        Ts3VoiceClient c;
        final TsMcChannelTransport mcTransport;
        if (cfg.transport() == com.tsvoice.client.connection.VoiceTransport.TS_MC_CHANNEL) {
            mcTransport = new TsMcChannelTransport();
            Ts3Connection relayConnection = new Ts3Connection(new Ts3Crypt(identity), mcTransport);
            c = new Ts3VoiceClient(identity, nickname, "", relayConnection);
        } else {
            mcTransport = null;
            c = new Ts3VoiceClient(identity, nickname, "", cfg.tcp());
        }
        c.setServerPassword(cfg.password());
        // writer 最终写出点的路由代际来源（R08/T03）：路由未就绪/已失效时全部语音丢弃。
        // S0.1/R21：许可额外绑定本代会话 token（sessionLoop 捕获后传入，非实时读取）——
        // 跨会话重复的全局 epoch 数值不再构成许可；旧会话帧在 writer 线程读到的
        // token 已被替换，必被丢弃。
        c.setVoiceRouteEpochSupplier(() -> {
            var route = com.tsvoice.client.connection.BackendCoordinator.snapshot().route();
            return com.tsvoice.client.connection.BackendCoordinator.writePermittedFor(sessionBoundToken)
                    ? route.routeEpoch() : Long.MIN_VALUE;
        });
        // 回调绑定来源 client 与会话代际（投递/处理双检）：被替换会话的迟到回调
        // 在此整体丢弃，不得查询全局"当前 client"解释自己的 clid/文本。聊天显示
        // 属 MC 关联输出，额外绑定 MC 上下文代际（T04/R07）。
        c.setTextListener((invokerName, message) -> {
            if (active(gen) && client == c) {
                onTsText(new com.tsvoice.client.connection.VoiceState.CallbackContext(
                        sessionToken, com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch(), c),
                        invokerName, message);
            }
        });
        c.setVoiceListener(frame -> {
            if (active(gen) && client == c) {
                handleVoiceFrame(c, frame);
            }
        });
        try {
            if (mcTransport != null) {
                // MC 中继：OPEN →（服务端 allowlist/限额）→ OPENED → attach，
                // 再经注入传输进入正常 TS 握手。任何一步失败即连接失败。
                openMcRelaySession(mcTransport, cfg);
                c.connect(CONNECT_TIMEOUT_MS);
            } else {
                c.connect(InetAddress.getByName(cfg.host()), cfg.port(), CONNECT_TIMEOUT_MS);
            }
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
        // TS 用户在频道间移动 → Ghost 可见性实时同步；我方移动 → 提交共用路由确认。
        c.setChannelMembershipListener(clid -> {
            if (!active(gen) || client != c) {
                return;
            }
            if (clid == c.getMyClid()) {
                com.tsvoice.client.connection.TsChannelRoute.observeSelf(c);
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

    // ---- S1.4/P6：MC 中继客户端侧接线 ----

    /** 当前会话的 MC 中继会话 id（OPENED 下发；诊断/上行投递路由用）。 */
    private static volatile byte[] mcRelaySessionId;
    /** 当前会话的 MC 中继传输（下行投递目标；非中继会话为 null）。 */
    private static volatile TsMcChannelTransport mcRelayTransport;
    /** 在途 OPEN 的请求 id（AtomicInteger 环形分配，避免 id 冲突）。 */
    private static final java.util.concurrent.atomic.AtomicInteger RELAY_REQUEST_IDS =
            new java.util.concurrent.atomic.AtomicInteger(1);

    /** G5：当前 MC 连接是否支持 TS 中继通道（Bot MC 模式前置检查）。 */
    public static boolean isMcRelayChannelSupported() {
        return ClientPlayNetworking.canSend(TsRelayPayloads.RelayOpenPayload.TYPE);
    }

    /** G1 测试入口：驱动生产 openMcRelaySession（含 canSend 分支）。 */
    public static void openMcRelaySessionForTest(TsMcChannelTransport transport, SessionConfig cfg) throws Exception {
        openMcRelaySession(transport, cfg);
    }

    /** G1 测试入口：驱动生产 closeMcRelaySession（含 canSend 分支与本地清理）。 */
    public static void closeMcRelaySessionForTest() {
        closeMcRelaySession();
    }

    /**
     * OPEN → OPENED → attach：经 Fabric 通道完成 MC 中继会话建立（阻塞至
     * 服务端应答或超时）。本方法在会话线程执行；payload 发送是线程安全的，
     * 下行应答由 Fabric receiver 在主线程投递给 {@link #onRelayOpenedFrame}
     * 等待队列。客户端不解析 TS 主机名 —— targetId 由书签/配置提供。
     */
    private static void openMcRelaySession(TsMcChannelTransport transport, SessionConfig cfg) throws Exception {
        openMcRelaySession(transport, cfg, TsRelayWire.Purpose.PLAYER_VOICE);
    }

    /**
     * G5：purpose 参数化的 MC 中继会话建立（PLAYER_VOICE=玩家；MUSIC_BOT=独立
     * Bot 第二会话）。等待项按 requestId+purpose 精确关联；Bot 会话不占用玩家
     * 的 mcRelaySessionId/mcRelayTransport 全局。
     */
    public static void openMcRelaySession(TsMcChannelTransport transport, SessionConfig cfg,
                                          TsRelayWire.Purpose purpose) throws Exception {
        openMcRelaySession(transport, cfg, purpose,
                cfg == null ? null
                        : com.tsvoice.client.config.EndpointCredentialStore
                        .normalizeEndpoint(cfg.host(), cfg.port()));
    }

    /** G5：Bot 入口 —— targetId 显式（服务端 allowlist 键），purpose=MUSIC_BOT。 */
    public static void openMcRelaySessionForBot(TsMcChannelTransport transport, String targetId)
            throws Exception {
        openMcRelaySession(transport, null, TsRelayWire.Purpose.MUSIC_BOT, targetId);
    }

    private static void openMcRelaySession(TsMcChannelTransport transport, SessionConfig cfg,
                                           TsRelayWire.Purpose purpose, String targetId) throws Exception {
        if (!ClientPlayNetworking.canSend(TsRelayPayloads.RelayOpenPayload.TYPE)) {
            throw new IllegalStateException("this server does not support the TS relay channel");
        }
        // targetId 约定：书签/手动配置的 host:port 规范化串（服务端 allowlist 键）。
        // 服务器不允许的目标会以 ERR_UNKNOWN_TARGET/ERR_TARGET_NOT_ALLOWED 拒绝。
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalStateException("relay targetId required");
        }
        int requestId = RELAY_REQUEST_IDS.getAndIncrement();
        byte[] open = TsRelayWire.encodeOpen(new TsRelayWire.OpenFrame(
                requestId, targetId, purpose));
        java.util.concurrent.CompletableFuture<TsRelayWire.OpenedFrame> waiter =
                new java.util.concurrent.CompletableFuture<>();
        // T34：等待项按 requestId 登记，并绑定创建时的 MC 上下文与传输 ——
        // 晚到/换服后的旧应答不能批准新尝试（onRelayOpenedFrame 逐一核对）。
        long contextEpoch = com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch();
        PENDING_OPENS.put(requestId, new PendingOpen(waiter, contextEpoch, transport, targetId, purpose));
        try {
            ClientPlayNetworking.send(new TsRelayPayloads.RelayOpenPayload(open));
            TsRelayWire.OpenedFrame opened = waiter.get(CONNECT_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            // F2：sender 闭包绑定不可变的本会话 id（不从可变全局取"当前会话"
            // 给旧帧换身份）；路由代际由 transport.send(data, epoch) 携带（写线程
            // 在最终写出点复核过的值原样入帧）。
            final byte[] boundSessionId = opened.relaySessionId();
            mcRelaySessionId = opened.relaySessionId();
            mcRelayTransport = transport;
            transport.setRelaySender((com.tsvoice.client.ts3voice.TsMcChannelTransport.RelayFrameSender)
                    (datagram, routeEpoch, trafficClass) -> {
                // G2：transport 按入口给定 CONTROL/MEDIA 类别，帧内如实携带。
                byte[] frame = TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_C2S,
                        new TsRelayWire.DataFrame(boundSessionId, routeEpoch, trafficClass, datagram));
                ClientPlayNetworking.send(new TsRelayPayloads.RelayDataC2sPayload(frame));
            });
            transport.attachRelaySession(opened.relaySessionId(), opened.maxDatagramBytes());
            TeamspeakSvcClient.LOGGER.info(
                    "[teamspeak-svc] MC relay session attached (target={}, limits={}/{}/{}B)",
                    targetId, opened.maxDatagramBytes(), opened.maxDatagramsPerSecond(),
                    opened.maxBytesPerSecond());
        } finally {
            PENDING_OPENS.remove(requestId);
        }
    }

    /**
     * 在途 OPEN 等待登记（T34）：requestId → 登记项。绑定创建时的 MC 上下文
     * 代际、传输实例与目标 —— OPENED/ERROR 按 requestId 精确关联，不做任意
     * waiter 遍历完成；换服/旧尝试的迟到应答不会被新尝试消费。
     */
    private record PendingOpen(java.util.concurrent.CompletableFuture<TsRelayWire.OpenedFrame> waiter,
                               long contextEpoch, TsMcChannelTransport transport, String targetId,
                               TsRelayWire.Purpose purpose) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, PendingOpen> PENDING_OPENS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Fabric receiver（主线程）：OPENED 帧 → 按 requestId 完成在途等待。 */
    public static void onRelayOpenedFrame(byte[] frame) {
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null || decoded.type() != TsRelayWire.MessageType.OPENED) {
            return;
        }
        TsRelayWire.OpenedFrame opened = decoded.opened();
        PendingOpen pending = PENDING_OPENS.get(opened.requestId());
        if (pending == null) {
            return; // 未知/已超时取消的 requestId：丢弃（不批准任何尝试）
        }
        long currentContext = com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch();
        if (pending.contextEpoch() != currentContext
                || pending.transport() != mcRelayTransport && mcRelayTransport != null) {
            // 换服后迟到的旧 OPENED / 已被其他尝试抢先 attach：拒绝并清登记
            PENDING_OPENS.remove(opened.requestId());
            return;
        }
        if (pending.waiter().complete(opened)) {
            PENDING_OPENS.remove(opened.requestId());
        }
    }

    /**
     * G5：Bot 专用 OPENED 分发 —— 按 requestId 精确完成 Bot 的在途 OPEN，
     * 不触碰玩家的 mcRelaySessionId/mcRelayTransport（purpose 隔离）。
     */
    public static boolean completeBotRelayOpened(byte[] frame) {
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null || decoded.type() != TsRelayWire.MessageType.OPENED) {
            return false;
        }
        TsRelayWire.OpenedFrame opened = decoded.opened();
        PendingOpen pending = PENDING_OPENS.get(opened.requestId());
        if (pending == null || pending.purpose() != TsRelayWire.Purpose.MUSIC_BOT) {
            return false; // 不是 Bot 的等待项
        }
        return pending.waiter().complete(opened);
    }

    /** G5：Bot 专用 ERROR 分发（失败 Bot 的在途 OPEN）。 */
    public static boolean completeBotRelayError(byte[] frame) {
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null || decoded.type() != TsRelayWire.MessageType.ERROR) {
            return false;
        }
        TsRelayWire.ErrorFrame error = decoded.error();
        if (error.requestId() == 0) {
            return false;
        }
        PendingOpen pending = PENDING_OPENS.remove(error.requestId());
        if (pending == null || pending.purpose() != TsRelayWire.Purpose.MUSIC_BOT) {
            return false;
        }
        pending.waiter().completeExceptionally(new Ts3VoiceException(
                "relay open rejected: code=" + error.stableErrorCode() + " " + error.message()));
        return true;
    }

    /** Fabric receiver（主线程）：S2C 数据报/CLOSED → 传输投递。 */
    public static void onRelayDownstreamFrame(byte[] frame) {
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null) {
            return;
        }
        TsMcChannelTransport transport = mcRelayTransport;
        switch (decoded.type()) {
            case DATA_S2C -> {
                if (transport != null) {
                    transport.onRelayDatagram(decoded.data().datagram());
                }
            }
            case CLOSED -> {
                if (transport != null) {
                    transport.closeWithException(new java.io.IOException(
                            "relay closed: " + decoded.close().reason()));
                    mcRelayTransport = null;
                    mcRelaySessionId = null;
                }
            }
            case ERROR -> {
                // OPEN 阶段错误：按 requestId 失败对应等待项（T34：不波及其他
                // 在途尝试；requestId=0 的会话级错误按当前上下文清理全部）
                TsRelayWire.ErrorFrame error = decoded.error();
                if (error.requestId() != 0) {
                    PendingOpen pending = PENDING_OPENS.remove(error.requestId());
                    if (pending != null) {
                        pending.waiter().completeExceptionally(new Ts3VoiceException(
                                "relay open rejected: code=" + error.stableErrorCode()
                                        + " " + error.message()));
                    }
                } else if (error.stableErrorCode() != 0) {
                    for (var e : PENDING_OPENS.values()) {
                        e.waiter().completeExceptionally(new Ts3VoiceException(
                                "relay open rejected: code=" + error.stableErrorCode()
                                        + " " + error.message()));
                    }
                    PENDING_OPENS.clear();
                }
            }
            default -> {
            }
        }
    }

    /**
     * F2：路由代际推进点（频道确认/许可变更）向服务端发送 ROUTE_BARRIER ——
     * barrier 之后旧代际帧在服务端被拒（与客户端 writer 丢弃双保险）。
     * 非中继会话/无会话为 no-op；发送失败仅记录（barrier 丢失不会造成错误
     * 放行 —— 服务端只按收到的 barrier 生效）。
     *
     * @return 是否实际发送（诊断/测试）。
     */
    public static boolean sendRelayRouteBarrier() {
        byte[] session = mcRelaySessionId;
        TsMcChannelTransport transport = mcRelayTransport;
        if (session == null || transport == null || transport.isClosed()) {
            return false;
        }
        long epoch = com.tsvoice.client.connection.BackendCoordinator.snapshot().route().routeEpoch();
        try {
            byte[] frame = TsRelayWire.encodeRouteBarrier(
                    new TsRelayWire.RouteBarrierFrame(session, epoch));
            ClientPlayNetworking.send(new TsRelayPayloads.RelayRouteBarrierC2sPayload(frame));
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] relay route barrier sent (epoch={})", epoch);
            return true;
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] relay barrier send failed: {}", e.getMessage());
            return false;
        }
    }

    /** 当前 MC 中继会话 id（诊断；非中继会话为 null）。 */
    public static byte[] currentMcRelaySessionId() {
        byte[] id = mcRelaySessionId;
        return id == null ? null : id.clone();
    }

    /** 会话清理：断开 MC 中继会话（CLOSE）并复位桥接状态。 */
    private static void closeMcRelaySession() {
        byte[] session = mcRelaySessionId;
        TsMcChannelTransport transport = mcRelayTransport;
        mcRelaySessionId = null;
        mcRelayTransport = null;
        if (transport != null) {
            transport.close();
        }
        if (session != null && ClientPlayNetworking.canSend(TsRelayPayloads.RelayClosePayload.TYPE)) {
            try {
                byte[] frame = TsRelayWire.encodeClose(TsRelayWire.MessageType.CLOSE,
                        new TsRelayWire.CloseFrame(session, "client session end"));
                ClientPlayNetworking.send(new TsRelayPayloads.RelayClosePayload(frame));
            } catch (Exception e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] relay close send failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 麦克风帧发送（SVC mic 线程调用，帧已由 SVC 编码，直通 TS）。
     *
     * @return 是否已接管（接管后调用方应取消 SVC 原始发送）
     */
    public static boolean sendMic(byte[] opusData) {
        // 发送前复核选择快照：MicRedirector 与本方法之间选择可能已变化。
        var snap = com.tsvoice.client.connection.BackendCoordinator.snapshot();
        if (snap.selection().selectedBackend().orElse(null)
                != com.tsvoice.client.connection.VoiceState.BackendKind.TS3) {
            return false;
        }
        Ts3VoiceClient c = client;
        if (c == null || !c.isConnected()) {
            return false;
        }
        if (!com.tsvoice.client.connection.TsChannelRoute.isReadyForVoice(c)) {
            // 仍接管麦克风，防止回落 SVC/旧 TS 频道泄露频道切换中的语音。
            return true;
        }
        // 出站帧绑定同一快照的路由代际（R08/T03）：writer 在最终写出点复核。
        var route = snap.route();
        long routeEpoch = route.transmitReady()
                && route.backend().orElse(null) == com.tsvoice.client.connection.VoiceState.BackendKind.TS3
                ? route.routeEpoch() : Long.MIN_VALUE;
        try {
            c.sendVoice(opusData, Ts3VoiceClient.CODEC_OPUS_VOICE, routeEpoch);
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
        if (!com.tsvoice.client.connection.TsChannelRoute.isReadyForVoice(c)) {
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
    private static void onTsText(com.tsvoice.client.connection.VoiceState.CallbackContext ctx,
                                 String invokerName, String message) {
        if (!chatBridgeOn() || invokerName == null || message == null || message.isBlank()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            // 二次消费检查（R07/T04）：排队与执行之间会话或 MC 上下文可能已更换。
            if (!isCurrentCallback(ctx)) {
                return;
            }
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[TS] " + invokerName + ": " + message));
            }
        });
    }

    /** 回调二次消费检查：会话 token 仍有效、MC 上下文未变、来源 client 仍是当前 client。 */
    static boolean isCurrentCallback(com.tsvoice.client.connection.VoiceState.CallbackContext ctx) {
        return ctx != null
                && com.tsvoice.client.connection.BackendCoordinator.isCurrent(ctx.session())
                && com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch() == ctx.contextEpoch()
                && client == ctx.source();
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
    /** 测试入口（E5）：从"当前会话的 client"注入一帧，驱动生产 handleVoiceFrame。 */
    public static void onVoiceFrameForTest(Ts3VoiceClient.TsVoiceIn frame) {
        Ts3VoiceClient c = client;
        if (c != null) {
            c.setVoiceListenerForTestDispatch(frame);
        }
    }

    private static void handleVoiceFrame(Ts3VoiceClient source, Ts3VoiceClient.TsVoiceIn frame) {
        // 全关/主动断开时不向任何播放管线投递（OFF 覆盖由 SvcBackendAdapter 记录）。
        var intent = com.tsvoice.client.connection.BackendCoordinator.snapshot()
                .selection().intent();
        if (intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.OFF
                || intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PAUSED
                || com.tsvoice.client.backend.SvcBackendAdapter.isVoiceMuted()) {
            return;
        }
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
