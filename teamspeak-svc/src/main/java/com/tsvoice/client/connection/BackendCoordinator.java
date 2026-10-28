package com.tsvoice.client.connection;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单一控制平面（T01/T02）：用户意图提交、会话 token、频道确认与不可变选择/路由
 * 快照的唯一所有者。
 *
 * <p>写路径集中在 MC 客户端线程（TsVoiceManager 的同步方法内）并以 monitor
 * 串行化；快照 volatile 发布，音频/网络回调线程只读。所有改变用户意图的入口
 * （GUI、命令、自动激活、书签）都必须经过本类的提交方法，不允许组件各自批准
 * 发送归属。voice/text/membership/identity/avatar/command 回调在投递与处理两端
 * 都必须用 {@link #isCurrent(VoiceState.SessionToken)}（配合来源 client 比对）校验 —— 旧会话
 * 回调只能看到 false 并整体丢弃，不得查询"当前 client"解释自己的数据。</p>
 *
 * <p>不得持本类锁等待网络、命令 Future 或线程退出：提交方法只做原子状态交换。</p>
 */
public final class BackendCoordinator {

    private static final AtomicLong SELECTION_EPOCH = new AtomicLong(0);
    private static final AtomicLong SESSION_EPOCH = new AtomicLong(0);

    private static volatile VoiceState.SessionToken current = new VoiceState.SessionToken(0);
    /** 初始选择：AUTO + 原生 SVC —— 未安装/未配置 TS 时零干扰。 */
    private static volatile VoiceState.SelectionSnapshot selection =
            new VoiceState.SelectionSnapshot(0, SelectionPolicy.IntentKind.AUTO,
                    Optional.of(VoiceState.BackendKind.SVC), "vanilla");
    private static volatile VoiceState.RouteSnapshot route =
            new VoiceState.RouteSnapshot(current, 0, 0, Optional.empty(), null, null, false);

    // ---- 频道确认状态（T02）：策略与合法目标经 setChannelTarget 提交，self 观察经 confirmChannel 批准 ----
    private static volatile VoiceState.ChannelPolicy channelPolicy = VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING;
    /** LOCKED_TARGET 的唯一合法频道；LEGACY_GROUP_MAPPING 的 mapper 目标频道。 */
    private static volatile Integer legalTargetCid;
    private static volatile Integer confirmedChannelId;

    private BackendCoordinator() {
    }

    // ---- 用户意图提交（唯一写入口）----

    /**
     * 用户显式意图：PINNED_TS / PINNED_SVC / PAUSED / OFF。AUTO 的后端归属由
     * 激活解析经 {@link #selectAuto} 紧跟提交（调用方必须成对使用）。
     */
    public static synchronized void select(SelectionPolicy.IntentKind intent, String targetRef) {
        Optional<VoiceState.BackendKind> backend = switch (intent) {
            case PINNED_TS -> Optional.of(VoiceState.BackendKind.TS3);
            case PINNED_SVC -> Optional.of(VoiceState.BackendKind.SVC);
            // AUTO 的后端归属由解析结果提交；PAUSED/OFF 没有活动后端。
            case AUTO, PAUSED, OFF -> Optional.empty();
        };
        selection = new VoiceState.SelectionSnapshot(SELECTION_EPOCH.incrementAndGet(), intent,
                backend, targetRef);
    }

    /** 激活解析结果提交（AUTO）：后端由 resolveSessionConfig 等纯解析决定。 */
    public static synchronized void selectAuto(VoiceState.BackendKind backend, String targetRef) {
        selection = new VoiceState.SelectionSnapshot(SELECTION_EPOCH.incrementAndGet(),
                SelectionPolicy.IntentKind.AUTO, Optional.ofNullable(backend), targetRef);
    }

    /**
     * 用户断开 TeamSpeak：TS 归属下进入 PAUSED —— 不自动切换 SVC、不自动重连；
     * 恢复只能由用户再次明确选择。SVC 已被明确选中（本就没有活动 TS）时为无操作，
     * 不得把已明确的 SVC 选择改写为 PAUSED。
     *
     * @return 是否进入了 PAUSED（false = 当前没有 TS 归属，选择保持不变）
     */
    public static synchronized boolean disconnectTeamSpeak() {
        VoiceState.SelectionSnapshot sel = selection;
        boolean tsOwned = sel.selectedBackend().orElse(VoiceState.BackendKind.SVC)
                == VoiceState.BackendKind.TS3;
        if (!tsOwned) {
            return false;
        }
        select(SelectionPolicy.IntentKind.PAUSED, sel.targetRef());
        return true;
    }

    /** 用户切回原生 SVC（独立操作，不是断开的隐含结果）。 */
    public static synchronized void switchToSvc() {
        select(SelectionPolicy.IntentKind.PINNED_SVC, "vanilla-svc");
    }

    /** 用户关闭全部语音（收发同时停止，保留配置）。 */
    public static synchronized void disableAllVoice() {
        select(SelectionPolicy.IntentKind.OFF, "all-voice");
    }

    // ---- 会话与路由 ----

    /** 新会话开始：token 递增，此前签发的全部 token 失效；路由与频道确认重置。 */
    public static synchronized VoiceState.SessionToken beginSession() {
        current = new VoiceState.SessionToken(SESSION_EPOCH.incrementAndGet());
        route = new VoiceState.RouteSnapshot(current, selection.selectionEpoch(), 0,
                Optional.empty(), null, null, false);
        channelPolicy = VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING;
        legalTargetCid = null;
        confirmedChannelId = null;
        return current;
    }

    /** 会话结束（仅当前 token 持有者可调用）：发布未就绪路由并失效 token。 */
    public static synchronized void endSession(VoiceState.SessionToken token) {
        if (isCurrent(token)) {
            route = new VoiceState.RouteSnapshot(current, selection.selectionEpoch(),
                    route.routeEpoch(), Optional.empty(), null, null, false);
            current = new VoiceState.SessionToken(0);
        }
    }

    /**
     * 提交当前会话的频道策略与合法目标。LOCKED_TARGET 传锁定频道；
     * LEGACY_GROUP_MAPPING 由 mapper 每次解析后提交目标（null=尚无合法目标）；
     * TS_NATIVE_FOLLOW 忽略目标参数（任何服务器确认的 self 频道都可批准）。
     * policy=null 时仅更新 LEGACY 模式的目标（如 requestMove 的显式选择）；
     * 锁定目标只能通过显式的 setChannelTarget(LOCKED_TARGET, cid) 解锁/改靶。
     */
    public static synchronized void setChannelTarget(VoiceState.SessionToken token,
                                                     VoiceState.ChannelPolicy policy,
                                                     Integer legalTarget) {
        if (!isCurrent(token)) {
            return;
        }
        if (policy != null) {
            channelPolicy = policy;
        }
        if (policy == VoiceState.ChannelPolicy.LOCKED_TARGET) {
            legalTargetCid = legalTarget;
        } else if (policy == VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING
                || (policy == null && channelPolicy == VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING)) {
            legalTargetCid = legalTarget;
        }
    }

    /**
     * 频道确认：按当前 ChannelPolicy 检查观察到的 self 频道是否可批准，
     * 不把每条通知直接变成发送许可。批准后发布 ready 路由（authority=serverUid，
     * channelId=确认频道）；确认频道变化时递增 routeEpoch（T03 队列隔离依据）。
     */
    public static synchronized boolean confirmChannel(VoiceState.SessionToken token,
                                                      String serverUid, int cid) {
        if (!isCurrent(token) || cid < 0) {
            return false;
        }
        boolean allowed = switch (channelPolicy) {
            case TS_NATIVE_FOLLOW -> true;
            case LOCKED_TARGET, LEGACY_GROUP_MAPPING ->
                    legalTargetCid != null && legalTargetCid == cid;
        };
        if (!allowed) {
            return false;
        }
        long nextEpoch = route.routeEpoch()
                + (confirmedChannelId != null && confirmedChannelId != cid ? 1 : 0);
        route = new VoiceState.RouteSnapshot(token, selection.selectionEpoch(), nextEpoch,
                Optional.of(VoiceState.BackendKind.TS3), serverUid, cid, true);
        confirmedChannelId = cid;
        return true;
    }

    /** 发布当前会话的路由状态；未就绪发布同时清除频道目标信息（旧 token 被拒绝）。 */
    public static synchronized boolean publishRoute(VoiceState.SessionToken token,
                                                    VoiceState.BackendKind backend,
                                                    boolean transmitReady) {
        if (!isCurrent(token)) {
            return false;
        }
        route = new VoiceState.RouteSnapshot(token, selection.selectionEpoch(), route.routeEpoch(),
                Optional.ofNullable(backend),
                transmitReady ? route.authority() : null,
                transmitReady ? route.channelId() : null,
                transmitReady);
        if (!transmitReady) {
            confirmedChannelId = null;
        }
        return true;
    }

    /**
     * 撤销当前会话的发送许可并递增 routeEpoch（频道移动/确认丢失时使用，
     * T03 队列线性化依赖该代际）。旧 token 的调用被拒绝并返回 false。
     */
    public static synchronized boolean invalidateRoute(VoiceState.SessionToken token) {
        if (!isCurrent(token)) {
            return false;
        }
        route = new VoiceState.RouteSnapshot(token, selection.selectionEpoch(),
                route.routeEpoch() + 1, route.backend(), null, null, false);
        confirmedChannelId = null;
        return true;
    }

    /** 回调校验：token 是否仍代表当前会话。 */
    public static boolean isCurrent(VoiceState.SessionToken token) {
        return token != null && token.equals(current);
    }

    /** 当前频道策略（只读观察）。 */
    public static VoiceState.ChannelPolicy channelPolicy() {
        return channelPolicy;
    }

    /** 完整快照：麦克风热路径每帧只读取一次；其余组件只读观察。 */
    public static VoiceState.CoordinatorSnapshot snapshot() {
        return new VoiceState.CoordinatorSnapshot(selection, route);
    }

    /** 兼容读取：与 {@link #snapshot()} 同源派生，不发布第二份可能冲突的状态。 */
    public static VoiceState.RouteSnapshot route() {
        return route;
    }
}
