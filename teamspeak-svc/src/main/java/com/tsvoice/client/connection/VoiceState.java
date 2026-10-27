package com.tsvoice.client.connection;

/**
 * 会话与路由的集中类型定义（P1）。快照跨线程只读传递；token 是会话唯一身份，
 * 旧会话回调不得影响新会话。
 */
public final class VoiceState {

    private VoiceState() {
    }

    /** 会话身份：会话启动/重建时由协调器签发；回调绑定其创建时拿到的 token。 */
    public record SessionToken(long sessionEpoch) {
    }

    /** 后端种类（传输种类在 P5/P6 引入）。 */
    public enum BackendKind { SVC, TS3 }

    /** 不可变路由快照：协调器发布，音频/回调线程只读。 */
    public record RouteSnapshot(SessionToken session, BackendKind backend, boolean transmitReady) {
    }

    /** 失败类别（P4 回退策略使用；P1 先集中定义）。 */
    public enum FailureKind {
        NETWORK_TIMEOUT, DNS_FAILED, AUTH_DENIED, CHANNEL_PASSWORD, PERMISSION_DENIED,
        BANNED, SERVER_FULL, UNSUPPORTED_PROTOCOL, FRONTEND_UNAVAILABLE, CANCELLED, INTERNAL
    }
}
