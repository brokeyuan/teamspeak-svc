package com.tsvoice.client.audio;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.VoiceState;

/**
 * 唯一的麦克风路由归属（T01 起为协调器快照的兼容视图）。
 * 状态唯一来源是 {@link BackendCoordinator}；本类不再持有独立写入口。
 *
 * <p>注意：{@link #owner()} 的两值映射不区分 PAUSED/OFF —— 需要"断开/全关"
 * 语义的调用方必须读取协调器完整快照，不得把暂停解释为 SVC_NATIVE。</p>
 */
public final class MicRouteGate {

    public enum Owner { SVC_NATIVE, TS }

    private MicRouteGate() {
    }

    /** TS 路由当前被选中（含连接中/重连中/等待频道等未就绪状态）。 */
    public static boolean tsSelected() {
        return BackendCoordinator.snapshot().selection().selectedBackend()
                .orElse(VoiceState.BackendKind.SVC) == VoiceState.BackendKind.TS3;
    }

    /** 兼容视图：仅表达"TS 选中与否"；PAUSED/OFF 由协调器快照表达。 */
    public static Owner owner() {
        return tsSelected() ? Owner.TS : Owner.SVC_NATIVE;
    }
}
