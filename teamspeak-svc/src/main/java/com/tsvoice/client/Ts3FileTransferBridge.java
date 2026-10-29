package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;

/**
 * Ts3VoiceClient（ts3voice 包）与 Ts3FileTransfer（client 包）的桥：
 * 避免协议层直接依赖文件传输实现。应答按来源 client 隔离（T05/R06）。
 */
public final class Ts3FileTransferBridge {
    private Ts3FileTransferBridge() {
    }

    public static void onFtReply(String row, Ts3VoiceClient source) {
        Ts3FileTransfer.onFtReply(row, source);
    }

    /** 会话关闭：取消该 client 的全部在途上传登记并失败其 Future。 */
    public static void cancelClient(Ts3VoiceClient source) {
        Ts3FileTransfer.cancelClient(source);
    }
}
