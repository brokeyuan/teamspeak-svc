package com.tsvoice.client.connection;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单一控制平面：会话 token 与不可变路由快照的唯一所有者。
 *
 * <p>写路径集中在 MC 客户端线程（TsVoiceManager 的同步方法内）并以 monitor
 * 串行化；快照 volatile 发布，音频/网络回调线程只读。voice/text/membership/
 * identity/avatar/command 回调在投递与处理两端都必须用
 * {@link #isCurrent(SessionToken)}（配合来源 client 比对）校验 —— 旧会话回调
 * 只能看到 false 并整体丢弃，不得查询"当前 client"解释自己的数据。</p>
 */
public final class BackendCoordinator {

    private static final AtomicLong SESSION_EPOCH = new AtomicLong(0);
    private static volatile VoiceState.SessionToken current = new VoiceState.SessionToken(0);
    private static volatile VoiceState.RouteSnapshot route =
            new VoiceState.RouteSnapshot(current, VoiceState.BackendKind.SVC, false);

    private BackendCoordinator() {
    }

    /** 新会话开始：token 递增，此前签发的全部 token 失效。 */
    public static synchronized VoiceState.SessionToken beginSession() {
        current = new VoiceState.SessionToken(SESSION_EPOCH.incrementAndGet());
        route = new VoiceState.RouteSnapshot(current, route.backend(), false);
        return current;
    }

    /** 会话结束（仅当前 token 持有者可调用）：发布未就绪路由并失效 token。 */
    public static synchronized void endSession(VoiceState.SessionToken token) {
        if (isCurrent(token)) {
            route = new VoiceState.RouteSnapshot(current, route.backend(), false);
            current = new VoiceState.SessionToken(0);
        }
    }

    /** 发布当前会话的路由快照；旧 token 的发布被拒绝并返回 false。 */
    public static synchronized boolean publishRoute(VoiceState.SessionToken token,
                                                    VoiceState.BackendKind backend, boolean transmitReady) {
        if (!isCurrent(token)) {
            return false;
        }
        route = new VoiceState.RouteSnapshot(token, backend, transmitReady);
        return true;
    }

    /** 回调校验：token 是否仍代表当前会话。 */
    public static boolean isCurrent(VoiceState.SessionToken token) {
        return token != null && token.equals(current);
    }

    /** 当前路由快照（不可变）。 */
    public static VoiceState.RouteSnapshot route() {
        return route;
    }
}
