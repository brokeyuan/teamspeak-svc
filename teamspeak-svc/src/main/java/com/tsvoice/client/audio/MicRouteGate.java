package com.tsvoice.client.audio;

/**
 * 唯一的麦克风路由归属。TS 路由被选中期间，SVC 的 ClientSoundEvent 必须在
 * 任何编码/发送工作之前被取消，使任何失败模式都不能把同一段语音泄漏进 SVC 网络。
 *
 * <p>P0 最小形态：只跟踪"选中后端"。等待/重连等未就绪状态由调用方在取消之后
 * 丢弃帧处理；P1 协调器将以不可变 route 快照与会话 token 取代此简化判定。</p>
 */
public final class MicRouteGate {

    public enum Owner { SVC_NATIVE, TS }

    private static volatile Owner owner = Owner.SVC_NATIVE;

    private MicRouteGate() {
    }

    public static void setOwner(Owner newOwner) {
        owner = newOwner == null ? Owner.SVC_NATIVE : newOwner;
    }

    public static Owner owner() {
        return owner;
    }

    public static boolean tsSelected() {
        return owner == Owner.TS;
    }
}
