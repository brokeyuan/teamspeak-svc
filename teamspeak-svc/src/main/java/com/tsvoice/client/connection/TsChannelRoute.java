package com.tsvoice.client.connection;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;

import java.util.List;
import java.util.Objects;

/**
 * 共用确认路由服务（T02/R02）：语音与频道文字读取同一有效路由；向协调器提交
 * 频道观察。legacy mapper 只在这里提交群组模式目标；书签默认频道/锁定频道/
 * 服务器强制移动都经同一确认流程。只发送 join 请求不产生发送许可 —— 许可只能
 * 由协调器 confirmChannel 在策略允许且观察到实际频道后发布。
 *
 * <p>本类是 manager 与 mapper 之间的会话级门面：持当前会话 token，全部协调器
 * 调用都经它校验；控制线程串行化沿用协调器 monitor，本类锁只保护自己的
 * 少量会话级字段，不得在锁内做网络调用。</p>
 */
public final class TsChannelRoute {

    private static final Object LOCK = new Object();

    private static VoiceState.SessionToken token = new VoiceState.SessionToken(0);
    /** 书签/锁定模式的初始目标频道名（仅名称；cid 由观察期解析）。 */
    private static String initialChannelName;
    private static boolean initialResolved;
    private static Integer pendingDesiredCid;
    private static long pendingDeadlineMs;
    private static String pauseReason = "";
    private static long lastChannelListRequestMs;

    /** 在途移动请求的最长等待：超时后重新以实际服务器状态为准（避免永久卡在旧目标）。 */
    private static final long PENDING_TIMEOUT_MS = 10_000L;

    private TsChannelRoute() {
    }

    /** 会话开始：登记策略与可选初始目标频道（书签默认频道；null=由 mapper 驱动）。 */
    public static void beginSession(VoiceState.SessionToken sessionToken,
                                    VoiceState.ChannelPolicy policy, String initialChannel) {
        synchronized (LOCK) {
            token = sessionToken == null ? new VoiceState.SessionToken(0) : sessionToken;
            initialChannelName = initialChannel;
            initialResolved = initialChannel == null;
            pendingDesiredCid = null;
            pendingDeadlineMs = 0;
            pauseReason = "";
            lastChannelListRequestMs = 0;
        }
        BackendCoordinator.setChannelTarget(sessionToken, policy, null);
    }

    /** 会话结束：失效本地状态（协调器路由由 endSession/invalidate 统一重置）。 */
    public static void endSession(VoiceState.SessionToken sessionToken) {
        synchronized (LOCK) {
            if (token.equals(sessionToken)) {
                token = new VoiceState.SessionToken(0);
                initialChannelName = null;
                initialResolved = true;
                pendingDesiredCid = null;
                pauseReason = "";
            }
        }
    }

    /** TS 连接丢失（重连前）：清待完成请求；确认路由由未就绪发布清除。 */
    public static void onConnectionLost(VoiceState.SessionToken sessionToken) {
        synchronized (LOCK) {
            if (!token.equals(sessionToken)) {
                return;
            }
            pendingDesiredCid = null;
            pauseReason = "TS connection lost";
        }
    }

    /**
     * 观察我方实际频道（self move 通知或周期对账）：提交协调器按策略批准。
     * 存在较新的本地移动请求时，旧频道的观察不提交（旧应答不能完成新请求，
     * 3.4）；请求超时后以服务器实际状态为准重新对账。
     */
    public static void observeSelf(Ts3VoiceClient client) {
        if (client == null) {
            return;
        }
        VoiceState.SessionToken current;
        synchronized (LOCK) {
            current = token;
        }
        int cid = client.getMyChannelId();
        if (cid < 0) {
            return;
        }
        boolean confirmed;
        synchronized (LOCK) {
            if (!BackendCoordinator.isCurrent(current)) {
                return;
            }
            if (pendingDesiredCid != null) {
                if (pendingDesiredCid == cid) {
                    pendingDesiredCid = null; // 目标已由服务器确认
                } else if (System.currentTimeMillis() < pendingDeadlineMs) {
                    return; // 旧观察不覆盖在途请求
                } else {
                    pendingDesiredCid = null; // 请求超时：回到以实际状态为准
                }
            }
        }
        confirmed = BackendCoordinator.confirmChannel(current,
                client.getVirtualServerUid(), cid);
        synchronized (LOCK) {
            if (confirmed) {
                pauseReason = "";
            }
        }
    }

    /**
     * 发起频道移动（mapper 或 UI）：先撤销发送许可并递增 routeEpoch，再提交
     * clientmove。同一目标的重复请求不重发（避免周期对账刷请求）。
     */
    public static boolean requestMove(Ts3VoiceClient client, int targetCid, String channelPassword) {
        if (client == null || targetCid < 0) {
            return false;
        }
        VoiceState.SessionToken current;
        synchronized (LOCK) {
            current = token;
            if (!BackendCoordinator.isCurrent(current)) {
                return false;
            }
            if (pendingDesiredCid != null && pendingDesiredCid == targetCid) {
                return true; // 已在途，等确认
            }
        }
        BackendCoordinator.invalidateRoute(current);
        synchronized (LOCK) {
            pendingDesiredCid = targetCid;
            pendingDeadlineMs = System.currentTimeMillis() + PENDING_TIMEOUT_MS;
            pauseReason = "moving to channel " + targetCid;
        }
        try {
            client.joinChannel(targetCid, channelPassword);
            return true;
        } catch (Exception e) {
            synchronized (LOCK) {
                pendingDesiredCid = null;
                pauseReason = "channel move failed: " + e.getMessage();
            }
            return false;
        }
    }

    /** 服务器拒绝移动请求（错误应答）：保持暂停并记录原因，不猜测回退频道。 */
    public static void onMoveRejected(VoiceState.SessionToken sessionToken, String reason) {
        synchronized (LOCK) {
            if (!token.equals(sessionToken)) {
                return;
            }
            pendingDesiredCid = null;
            pauseReason = reason == null ? "channel move rejected" : reason;
        }
    }

    /** legacy mapper 重置（换服/配置变化）：清除群组模式目标，待重新解析。 */
    public static void onMapperReset() {
        VoiceState.SessionToken current;
        synchronized (LOCK) {
            current = token;
        }
        BackendCoordinator.setChannelTarget(current, VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING, null);
    }

    /** legacy mapper 目标提交（LEGACY_GROUP_MAPPING 模式；目标即合法确认频道）。 */
    public static void setLegacyTarget(Ts3VoiceClient client, int targetCid) {
        VoiceState.SessionToken current;
        synchronized (LOCK) {
            current = token;
            // mapper 的最新解析取代旧的在途请求（旧目标不再有效）。
            if (pendingDesiredCid != null && pendingDesiredCid != targetCid) {
                pendingDesiredCid = null;
                pendingDeadlineMs = 0;
            }
        }
        BackendCoordinator.setChannelTarget(current, VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING, targetCid);
    }

    /**
     * 会话周期对账（manager 每 2s 调用）：观察当前频道 + 解析书签初始目标频道。
     */
    public static void tick(Ts3VoiceClient client) {
        if (client == null) {
            return;
        }
        String initial;
        boolean resolved;
        synchronized (LOCK) {
            if (!BackendCoordinator.isCurrent(token)) {
                return;
            }
            initial = initialChannelName;
            resolved = initialResolved;
        }
        observeSelf(client);
        if (!resolved && initial != null && !initial.isBlank()) {
            resolveInitialTarget(client, initial);
        }
    }

    /** 书签初始频道解析：按名唯一匹配才发起移动；歧义/缺失暂停并记录原因。 */
    private static void resolveInitialTarget(Ts3VoiceClient client, String name) {
        List<com.tsvoice.client.ts3voice.TsChannelSnapshot.Channel> matches;
        try {
            matches = client.channelSnapshot().byName(name);
        } catch (Exception e) {
            return;
        }
        if (matches.isEmpty()) {
            requestChannelListBounded(client);
            pause("initial channel not in server channel list yet: " + name);
            return;
        }
        if (matches.size() > 1) {
            synchronized (LOCK) {
                initialResolved = true; // 不靠迭代次序猜频道；等用户明确选择
                pauseReason = "ambiguous channel name: " + name;
            }
            return;
        }
        int cid = matches.get(0).cid();
        synchronized (LOCK) {
            initialResolved = true;
        }
        Integer currentRoute = BackendCoordinator.snapshot().route().channelId();
        if (!Objects.equals(currentRoute, cid)) {
            String password = com.tsvoice.client.TsVoiceManager.currentSessionConfig() != null
                    ? com.tsvoice.client.TsVoiceManager.currentSessionConfig().channelPassword()
                    : null;
            requestMove(client, cid, password);
        }
    }

    private static void requestChannelListBounded(Ts3VoiceClient client) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - lastChannelListRequestMs < 10_000L) {
                return;
            }
            lastChannelListRequestMs = now;
        }
        try {
            client.requestChannelList();
        } catch (Exception ignored) {
        }
    }

    private static void pause(String reason) {
        synchronized (LOCK) {
            if (pauseReason.isEmpty() || !pauseReason.startsWith("ambiguous")) {
                pauseReason = reason;
            }
        }
    }

    /**
     * 当前是否可发送（语音与频道文字读取同一判定）：路由就绪、后端为 TS、
     * 确认频道与该 client 的实际频道一致。
     */
    public static boolean isReadyForVoice(Ts3VoiceClient client) {
        if (client == null || !client.isConnected()) {
            return false;
        }
        VoiceState.RouteSnapshot route = BackendCoordinator.snapshot().route();
        return route.transmitReady()
                && route.backend().orElse(null) == VoiceState.BackendKind.TS3
                && route.channelId() != null
                && route.channelId() == client.getMyChannelId();
    }

    /** 当前频道策略（UI/诊断观察）。 */
    public static VoiceState.ChannelPolicy currentPolicy() {
        return BackendCoordinator.channelPolicy();
    }

    /** 当前暂停/等待原因（UI/诊断观察；空串=无）。 */
    public static String statusReason() {
        synchronized (LOCK) {
            return pauseReason;
        }
    }

    /** 当前会话 token（UI 层锁定频道等操作使用）。 */
    public static VoiceState.SessionToken currentToken() {
        synchronized (LOCK) {
            return token;
        }
    }
}
