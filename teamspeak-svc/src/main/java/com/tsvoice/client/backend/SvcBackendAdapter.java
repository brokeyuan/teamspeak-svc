package com.tsvoice.client.backend;

/**
 * SVC 原生连接的观察者：只记录 SVC 会话是否可用。SVC 连接变化不再直接销毁
 * TS 会话（P1 解耦，F01 的前置）—— 后端切换与资源归属由协调器决策。
 *
 * <p>{@code voiceMuted} 是 OFF（关闭全部语音）时本扩展自身的门控覆盖：只表示
 * 本扩展不再注入/消费语音帧，绝不改动用户在 SVC 里的静音设置；恢复语音时
 * 仅清除本扩展的覆盖。</p>
 */
public final class SvcBackendAdapter {

    private static volatile boolean connected;
    private static volatile boolean voiceMuted;

    private SvcBackendAdapter() {
    }

    public static void onSvcConnected() {
        connected = true;
    }

    public static void onSvcDisconnected() {
        connected = false;
    }

    public static boolean isConnected() {
        return connected;
    }

    /** OFF 覆盖（仅本扩展的门控；SVC 用户设置不受影响）。 */
    public static void setVoiceMuted(boolean muted) {
        voiceMuted = muted;
    }

    public static boolean isVoiceMuted() {
        return voiceMuted;
    }
}
