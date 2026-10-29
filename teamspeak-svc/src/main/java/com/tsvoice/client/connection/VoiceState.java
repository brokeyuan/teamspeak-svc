package com.tsvoice.client.connection;

import com.tsvoice.client.connection.SelectionPolicy.IntentKind;

import java.util.Optional;

/**
 * 会话与路由的集中类型定义。快照跨线程只读传递；token 是会话唯一身份，
 * 旧会话回调不得影响新会话。
 *
 * <p>T01 起选择归属的唯一事实来源是 {@link BackendCoordinator#snapshot()}：
 * {@link SelectionSnapshot} 描述发送归属（用户意图 + 解析出的后端），
 * {@link RouteSnapshot} 描述该后端当前的会话、目标频道与发送许可。
 * PAUSED/OFF 用空 {@code selectedBackend} 表示"没有活动后端"，不伪造第三种协议后端。</p>
 */
public final class VoiceState {

    private VoiceState() {
    }

    /** 会话身份：会话启动/重建时由协调器签发；回调绑定其创建时拿到的 token。 */
    public record SessionToken(long sessionEpoch) {
    }

    /** 后端种类（传输种类在 P5/P6 引入）。 */
    public enum BackendKind { SVC, TS3 }

    /** 不可变选择快照：麦克风帧发送归属的唯一依据（协调器发布，音频线程只读）。 */
    public record SelectionSnapshot(long selectionEpoch, IntentKind intent,
                                    Optional<BackendKind> selectedBackend, String targetRef) {
    }

    /** 不可变路由快照：当前后端的会话绑定、目标频道（authority+cid）与发送许可。 */
    public record RouteSnapshot(SessionToken session, long selectionEpoch, long routeEpoch,
                                Optional<BackendKind> backend, String authority, Integer channelId,
                                boolean transmitReady) {
    }

    /** 协调器完整快照：麦克风热路径每帧读取一次；其余组件只读观察。 */
    public record CoordinatorSnapshot(SelectionSnapshot selection, RouteSnapshot route) {
    }

    /**
     * 频道策略（3.4）：TS_NATIVE_FOLLOW 默认书签模式（接受服务器确认的 self 频道）；
     * LOCKED_TARGET 用户明确锁定目标频道；LEGACY_GROUP_MAPPING 沿用 SVC 群组受众约束。
     * 连接书签的默认频道不隐式开启 LOCKED_TARGET。
     */
    public enum ChannelPolicy { TS_NATIVE_FOLLOW, LOCKED_TARGET, LEGACY_GROUP_MAPPING }

    /** MC 连接上下文（T04/T11）：contextEpoch 在换服/离开世界时递增。 */
    public record McContext(long contextEpoch, String serverAddress) {
    }

    /** TS 参与者稳定身份（T07/R10）：serverUID+TS UID，与 clid/会话/身份升降级无关。 */
    public record ParticipantKey(String serverUid, String tsUid) {
    }

    /** 播放流代际（T07/R10）：同一参与者的每段流有唯一 epoch，旧清理不得删除新流。 */
    public record StreamKey(ParticipantKey participant, long streamEpoch) {
    }

    /**
     * 回调绑定（T04/R07）：异步任务捕获其来源会话、MC 上下文与来源 client，
     * 排队后执行前复核 —— 旧会话/旧上下文任务不得解释或写入新状态。
     */
    public record CallbackContext(SessionToken session, long contextEpoch,
                                  com.tsvoice.client.ts3voice.Ts3VoiceClient source) {
    }

    /**
     * 失败类别（唯一枚举；SelectionPolicy.FailureKind 仅保留常量别名避免同义漂移）。
     * S0.7/R26 新增 {@link #PROTOCOL_REJECTED}：服务器以结构化 error 行明确拒绝、
     * 但 token 未收录进具体凭据/权限类别 —— 终止重试，不当网络超时。
     */
    public enum FailureKind {
        NETWORK_TIMEOUT, DNS_FAILED, AUTH_DENIED, CHANNEL_PASSWORD, PERMISSION_DENIED,
        BANNED, SERVER_FULL, UNSUPPORTED_PROTOCOL, FRONTEND_UNAVAILABLE, CANCELLED, INTERNAL,
        PROTOCOL_REJECTED
    }
}
