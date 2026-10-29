package com.tsvoice.client;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * SVC 客户端插件入口（fabric.mod.json entrypoint "voicechat"，SVC 客户端侧加载）。
 *
 * <p>事件：</p>
 * <ul>
 *     <li>{@link ClientVoicechatInitializationEvent} — SVC 客户端 API 就绪；</li>
 *     <li>{@link ClientVoicechatConnectionEvent} — SVC 语音连接建立/断开 → TS 会话生命周期；</li>
 *     <li>{@link ClientSoundEvent} — 麦克风改道（见 {@link MicRedirector}）。</li>
 * </ul>
 */
public class VoicechatPluginImpl implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "teamspeak-svc";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        // SVC 加载插件时传入的 api 可能尚未就绪（客户端实例未建）——
        // 真正可靠的设置点在各客户端事件回调的 getVoicechat()，见 registerEvents
        if (voicechatApi instanceof VoicechatClientApi clientApi) {
            TeamspeakSvcClient.setClientApi(clientApi);
        }
        VoicechatPlugin.super.initialize(voicechatApi);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatInitializationEvent.class, event -> {
            TeamspeakSvcClient.setClientApi(event.getVoicechat());
            // S2/P5：SVC_MC_CHANNEL 客户端 socket —— 服务器宣告 MC_CHANNEL 且
            // 本机支持时，经官方 initialization event 设置自有 socket（不与
            // svc-extra 争用注入点；对方未装/装了都在 payload ID 层隔离）。
            // UDP 语义默认不变：只有收到服务端 config 才切换。
            try {
                if (com.tsvoice.client.backend.SvcMcChannelClient.supported()) {
                    com.tsvoice.client.backend.SvcMcChannelClient.registerSocket(event);
                }
            } catch (Throwable t) {
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] MC-channel socket registration unavailable: {}",
                        t.toString());
            }
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] SVC client initialized (client api ready)");
        });
        registration.registerEvent(ClientVoicechatConnectionEvent.class, event -> {
            TeamspeakSvcClient.setClientApi(event.getVoicechat());
            if (event.isConnected()) {
                TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] SVC voice connection established — starting TS session");
                TsVoiceManager.onSvcConnected();
            } else {
                TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] SVC voice connection closed — stopping TS session");
                TsVoiceManager.onSvcDisconnected();
            }
        });
        registration.registerEvent(de.maxhenkel.voicechat.api.events.ClientSoundEvent.class, MicRedirector::onMicSound);
    }
}
