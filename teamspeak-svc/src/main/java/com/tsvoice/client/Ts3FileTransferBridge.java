package com.tsvoice.client;

/**
 * Ts3VoiceClient（ts3voice 包）与 Ts3FileTransfer（client 包）的桥：
 * 避免协议层直接依赖文件传输实现。
 */
public final class Ts3FileTransferBridge {
    private Ts3FileTransferBridge() {
    }

    public static void onFtReply(String row) {
        Ts3FileTransfer.onFtReply(row);
    }
}
