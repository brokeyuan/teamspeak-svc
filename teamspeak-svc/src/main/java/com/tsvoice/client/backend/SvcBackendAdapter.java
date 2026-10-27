package com.tsvoice.client.backend;

/**
 * SVC 原生连接的观察者：只记录 SVC 会话是否可用。SVC 连接变化不再直接销毁
 * TS 会话（P1 解耦，F01 的前置）—— 后端切换与资源归属由协调器决策。
 */
public final class SvcBackendAdapter {

    private static volatile boolean connected;

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
}
