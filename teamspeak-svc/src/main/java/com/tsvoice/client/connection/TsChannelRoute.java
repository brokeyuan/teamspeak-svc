package com.tsvoice.client.connection;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 共用确认路由服务（T02/R02）：语音与频道文字读取同一有效路由；向协调器提交
 * 频道观察。legacy mapper 只在这里提交群组模式目标；书签默认频道/锁定频道/
 * 服务器强制移动都经同一确认流程。只发送 join 请求不产生发送许可 —— 许可只能
 * 由协调器 confirmChannel 在策略允许且观察到实际频道后发布。
 *
 * <p>N1/D05 移动请求生命周期：{@link MovePhase} 描述 RESOLVING（书签初始目标
 * 未解析）/ MOVING（clientmove 在途，许可已撤销）/ READY（服务器确认的实际
 * 频道已批准）/ FAILED（拒绝、超时或目标歧义 —— 持续阻断，等待用户明确操作）/
 * BLOCKED_BY_POLICY（观察到的频道不满足当前策略）。命令成功与 self 频道确认
 * 分开：clientmove 的成功应答只结束"命令在途"，发送许可仍只由 observeSelf 的
 * 实际频道确认发布。真实 clientmove 经 {@link Ts3VoiceClient#moveChannelAsync}
 * 进入命令 tracker，结果按 return_code 关联创建时的请求；被新请求取代的迟到
 * 回复按代际丢弃，不猜测成功。</p>
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
    /** 在途移动请求（N1/D05）；null=无在途命令。 */
    private static PendingMove pending;
    /** 移动请求代际：每次新请求递增；迟到回复按代际丢弃。 */
    private static long moveGeneration;
    /** D05：失败后持续阻断发送许可，直到用户明确"在当前频道继续"或新请求成功确认。 */
    private static boolean failedAwaitingUser;
    private static String failureReason = "";
    /** 失败发生时占据的频道：该频道的观察不得自动恢复许可（原频道不得静默重批）。 */
    private static int failedFromChannel = -1;
    private static String pauseReason = "";
    private static long lastChannelListRequestMs;

    /** 在途移动命令的最长等待（tracker 超时；超时按 FAILED 处理，不静默回到原频道）。 */
    private static final long MOVE_COMMAND_TIMEOUT_MS = 8_000L;
    /** observeSelf 兜底等待（必须长于命令超时；仅在 tracker 结果丢失时自愈）。 */
    static volatile long pendingTimeoutMs = 10_000L;

    /** 在途移动请求：代际 + 目标 + 兜底期限；回复绑定该实例，被取代即失效。 */
    private record PendingMove(long generation, int targetCid, long deadlineMs,
                               Ts3VoiceClient client) {
    }

    private TsChannelRoute() {
    }

    /** 移动请求生命周期（UI/诊断可观察；READY 只由服务器 self 确认达成）。 */
    public enum MovePhase {
        /** 书签初始目标频道尚未解析（缺失/等待频道表）。 */
        RESOLVING,
        /** clientmove 已提交且未到期；发送许可已撤销，等待命令结果与 self 确认。 */
        MOVING,
        /** 当前频道已由服务器确认且策略允许；正常发送。 */
        READY,
        /** 移动被拒绝/超时/目标歧义：持续阻断，等待用户明确操作。 */
        FAILED,
        /** 观察到的频道不满足当前策略（锁定/群组映射目标不符）。 */
        BLOCKED_BY_POLICY
    }

    /** 会话开始：登记策略与可选初始目标频道（书签默认频道；null=由 mapper 驱动）。 */
    public static void beginSession(VoiceState.SessionToken sessionToken,
                                    VoiceState.ChannelPolicy policy, String initialChannel) {
        synchronized (LOCK) {
            token = sessionToken == null ? new VoiceState.SessionToken(0) : sessionToken;
            initialChannelName = initialChannel;
            initialResolved = initialChannel == null;
            pending = null;
            moveGeneration++;
            failedAwaitingUser = false;
            failureReason = "";
            failedFromChannel = -1;
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
                pending = null;
                failedAwaitingUser = false;
                failureReason = "";
                failedFromChannel = -1;
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
            pending = null;
            pauseReason = "TS connection lost";
        }
    }

    /**
     * 观察我方实际频道（self move 通知或周期对账）：提交协调器按策略批准。
     * 在途请求未到期时，其他频道的观察不提交（旧应答不能完成新请求，3.4）；
     * 初始目标未解析或上一移动失败待用户处理时，不自动批准当前频道（N1/D05）。
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
        synchronized (LOCK) {
            if (!BackendCoordinator.isCurrent(current)) {
                return;
            }
            if (pending != null) {
                if (pending.targetCid() == cid) {
                    pending = null; // 目标已由服务器确认（命令结果与 self 确认分开）
                } else if (System.currentTimeMillis() >= pending.deadlineMs()) {
                    // 兜底：tracker 结果丢失。超时按失败处理，不静默批准当前频道。
                    pending = null;
                    markFailedLocked("channel move timed out waiting for the server", cid);
                    return;
                } else {
                    return; // 旧观察不覆盖在途请求
                }
            }
            if (!initialResolved) {
                return; // 初始目标未解析：不批准任何频道（书签模式 fail-closed）
            }
            if (failedAwaitingUser && (cid == failedFromChannel || failedFromChannel < 0)) {
                return; // D05：失败后不得自动批准原频道（来源未知时全部阻断），等待用户明确操作
            }
        }
        Integer previousChannel = BackendCoordinator.route().channelId();
        boolean confirmed = BackendCoordinator.confirmChannel(current,
                client.getVirtualServerUid(), cid);
        synchronized (LOCK) {
            if (confirmed) {
                // 服务器主动移动的本地隔离边界（R08/T03）：确认频道变化即失效旧
                // 代际（confirmChannel 递增 routeEpoch）并清排队旧帧。
                if (!Objects.equals(previousChannel, cid)) {
                    client.clearQueuedVoice();
                }
                failedAwaitingUser = false;
                failureReason = "";
                pauseReason = "";
            } else {
                // D04：确认被拒（策略不允许）时协调器已撤销许可并递增代际；
                // 这里把已排队旧帧一并清除，队列与写出点双重失效。
                client.clearQueuedVoice();
            }
        }
    }

    /**
     * 发起频道移动（mapper 或 UI）：先撤销发送许可并递增 routeEpoch，再提交
     * clientmove（经命令 tracker 关联结果）。同一目标的重复请求不重发（避免
     * 周期对账刷请求）。命令成功不授予发送许可 —— 只等待 self 频道确认。
     */
    public static boolean requestMove(Ts3VoiceClient client, int targetCid, String channelPassword) {
        if (client == null || targetCid < 0) {
            return false;
        }
        VoiceState.SessionToken current;
        PendingMove move;
        synchronized (LOCK) {
            current = token;
            if (!BackendCoordinator.isCurrent(current)) {
                return false;
            }
            if (pending != null && pending.targetCid() == targetCid) {
                return true; // 已在途，等确认
            }
            move = new PendingMove(++moveGeneration, targetCid,
                    System.currentTimeMillis() + pendingTimeoutMs, client);
            pending = move;
            failedAwaitingUser = false; // 新的显式移动请求取代失败等待
            failureReason = "";
            pauseReason = "moving to channel " + targetCid;
        }
        BackendCoordinator.invalidateRoute(current);
        // 用户/mapper 的显式选择即该策略下的新合法目标（LOCKED 仍须先解锁，
        // setChannelTarget 对 LOCKED 策略的 null-policy 调用不生效于此处语义之外）。
        BackendCoordinator.setChannelTarget(current, null, targetCid);
        // 先撤销许可并清旧代际排队帧，再按 writer 顺序提交移动控制命令（R08/T03）。
        client.clearQueuedVoice();
        CompletableFuture<Ts3VoiceClient.MoveResult> future;
        try {
            future = client.moveChannelAsync(targetCid, channelPassword, MOVE_COMMAND_TIMEOUT_MS);
        } catch (Exception e) {
            synchronized (LOCK) {
                if (pending == move) {
                    pending = null;
                    markFailedLocked("channel move failed: " + e.getMessage(),
                            client.getMyChannelId());
                }
            }
            return false;
        }
        // 命令结果回调（协议读取线程/tracker 看护线程）：只做状态交换，不做网络调用。
        future.whenComplete((result, error) -> onMoveCommandDone(move, result, error));
        return true;
    }

    /**
     * 命令结果处理（N1/D05）：绑定创建时的请求实例 —— 被新请求取代的迟到回复
     * 在此丢弃；失败（含超时/取消之外的网络异常）进入 FAILED 持续阻断；成功
     * 只结束命令在途，等待 observeSelf 的实际频道确认。
     */
    private static void onMoveCommandDone(PendingMove move,
                                          Ts3VoiceClient.MoveResult result, Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException ce
                && ce.getCause() != null) {
            error = ce.getCause(); // 还原命令 Future 的原始异常类型
        }
        boolean failure;
        String reason;
        if (error instanceof java.util.concurrent.CancellationException) {
            return; // 会话关闭的统一取消：由 endSession 重置，不算移动失败
        }
        if (error != null) {
            failure = true;
            reason = error instanceof java.util.concurrent.TimeoutException
                    ? "channel move timed out after " + MOVE_COMMAND_TIMEOUT_MS + "ms"
                    : "channel move failed: " + error.getMessage();
        } else if (result == null) {
            failure = true;
            reason = "channel move failed: empty command result";
        } else {
            failure = !result.succeeded();
            reason = failure ? "channel move rejected (id=" + result.errorCode()
                    + (result.errorMessage() == null ? "" : ": " + result.errorMessage()) + ")" : "";
        }
        synchronized (LOCK) {
            if (pending != move) {
                return; // 迟到/被取代的旧请求回复：明确忽略，不猜测成功或失败
            }
            pending = null;
            if (failure) {
                markFailedLocked(reason, move.client().getMyChannelId());
            }
            // 成功：保持 MOVING 语义直到 self 频道确认；observeSelf 完成批准。
        }
    }

    /** 锁内标记失败等待：记录原因与失败时占据的频道，持续阻断自动重批。 */
    private static void markFailedLocked(String reason, int fromChannel) {
        failedAwaitingUser = true;
        failureReason = reason == null ? "channel move failed" : reason;
        failedFromChannel = fromChannel;
        if (pauseReason.isEmpty() || !pauseReason.startsWith("ambiguous")) {
            pauseReason = failureReason;
        }
    }

    /** 服务器拒绝移动请求（错误应答或显式通知）：保持阻断并记录原因，不猜测回退频道。 */
    public static void onMoveRejected(VoiceState.SessionToken sessionToken, String reason) {
        synchronized (LOCK) {
            if (!token.equals(sessionToken)) {
                return;
            }
            pending = null;
            Integer confirmed = BackendCoordinator.route().channelId();
            markFailedLocked(reason == null ? "channel move rejected" : reason,
                    confirmed == null ? -1 : confirmed);
        }
    }

    /**
     * 用户明确选择"在当前频道继续"（N5 界面/命令入口）：解除失败等待并以实际
     * 频道重新对账。LOCKED_TARGET 的锁定目标改靶为当前频道；LEGACY 更新合法目标；
     * FOLLOW 无需改靶（观察即批准）。书签初始频道名一并失效（以实际频道为准）。
     */
    public static boolean acceptCurrentChannel(Ts3VoiceClient client) {
        if (client == null) {
            return false;
        }
        VoiceState.SessionToken current;
        int cid;
        synchronized (LOCK) {
            current = token;
            if (!BackendCoordinator.isCurrent(current)) {
                return false;
            }
            failedAwaitingUser = false;
            failureReason = "";
            initialResolved = true;
            initialChannelName = null;
            pending = null;
        }
        cid = client.getMyChannelId();
        if (cid < 0) {
            return false;
        }
        switch (BackendCoordinator.channelPolicy()) {
            case LOCKED_TARGET ->
                    BackendCoordinator.setChannelTarget(current, VoiceState.ChannelPolicy.LOCKED_TARGET, cid);
            case LEGACY_GROUP_MAPPING -> BackendCoordinator.setChannelTarget(current, null, cid);
            case TS_NATIVE_FOLLOW -> {
            }
        }
        observeSelf(client);
        return true;
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
            if (pending != null && pending.targetCid() != targetCid) {
                pending = null;
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

    /** 书签初始频道解析：按名唯一匹配才发起移动；歧义/缺失阻断并记录原因（D05）。 */
    private static void resolveInitialTarget(Ts3VoiceClient client, String name) {
        List<com.tsvoice.client.ts3voice.TsChannelSnapshot.Channel> matches;
        try {
            matches = client.channelSnapshot().byName(name);
        } catch (Exception e) {
            return;
        }
        if (matches.isEmpty()) {
            requestChannelListBounded(client);
            synchronized (LOCK) {
                if (!failedAwaitingUser) {
                    pauseReason = "initial channel not in server channel list yet: " + name;
                }
            }
            return;
        }
        if (matches.size() > 1) {
            synchronized (LOCK) {
                initialResolved = true; // 不靠迭代次序猜频道；等用户明确选择
                markFailedLocked("ambiguous channel name: " + name, client.getMyChannelId());
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

    /** 最近一次移动失败的具体原因（UI 显示；空串=无失败等待）。 */
    public static String failureReason() {
        synchronized (LOCK) {
            return failedAwaitingUser ? failureReason : "";
        }
    }

    /** 当前移动生命周期阶段（UI/诊断观察，N4/N5 状态模型数据源之一）。 */
    public static MovePhase movePhase() {
        synchronized (LOCK) {
            if (failedAwaitingUser) {
                return MovePhase.FAILED;
            }
            if (pending != null) {
                return MovePhase.MOVING;
            }
            if (!initialResolved) {
                return MovePhase.RESOLVING;
            }
        }
        VoiceState.RouteSnapshot route = BackendCoordinator.snapshot().route();
        if (route.transmitReady()
                && route.backend().orElse(null) == VoiceState.BackendKind.TS3
                && route.channelId() != null) {
            return MovePhase.READY;
        }
        return MovePhase.BLOCKED_BY_POLICY;
    }

    /** 当前会话 token（UI 层锁定频道等操作使用）。 */
    public static VoiceState.SessionToken currentToken() {
        synchronized (LOCK) {
            return token;
        }
    }
}
