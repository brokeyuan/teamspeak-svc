package com.tsvoice.client.music;

import com.tsvoice.client.ts3voice.TsMcChannelTransport;

/**
 * 音乐 Bot 与玩家中继代码的桥（G5）：把 Bot 的 OPEN/attach 委托给玩家 manager
 * 的 purpose 参数化实现，避免复制整个玩家 manager；同时让 music 包不被 harness
 * 直接编译依赖 TsVoiceManager（bot production 编译以 stub 桥接）。
 */
public final class MusicRelayBridge {

    private MusicRelayBridge() {
    }

    /** Bot 的 OPEN(purpose=MUSIC_BOT) → OPENED → attach（阻塞至服务端应答）。 */
    public static void openMcRelaySessionForBot(TsMcChannelTransport transport, String targetId)
            throws Exception {
        com.tsvoice.client.TsVoiceManager.openMcRelaySessionForBot(transport, targetId);
    }
}
