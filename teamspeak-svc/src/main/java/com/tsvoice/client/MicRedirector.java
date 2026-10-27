package com.tsvoice.client;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;

/**
 * 麦克风改道：SVC 语音激活/PTT 产生语音时（ClientSoundEvent 仅在实际发送路径触发），
 * 将 PCM 经 SVC 客户端编码器编码为 Opus 后直发玩家自己的 TS 客户端，并取消
 * SVC 发往 MC 服务器的原始发送（服务端仅收到空停止包，无语音内容）。
 *
 * <p>TS 会话未连接时不接管 —— SVC 原生路径照常工作（优雅降级）。</p>
 */
public final class MicRedirector {

    private static de.maxhenkel.voicechat.api.opus.OpusEncoder encoder;
    private static volatile boolean firstFrameLogged = false;

    private MicRedirector() {
    }

    public static void onMicSound(ClientSoundEvent event) {
        if (!TsVoiceManager.isSessionConnected()) {
            return; // 不接管：SVC 原生路径
        }
        short[] pcm = event.getRawAudio();
        if (pcm == null || pcm.length == 0) {
            return;
        }
        try {
            byte[] opus = encoder().encode(pcm);
            if (opus != null && opus.length > 0 && TsVoiceManager.sendMic(opus)) {
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] MC->TS audio path ACTIVE (first mic frame sent to TS)");
                }
                event.cancel(); // 抑制发往 MC 服务器（改道 TS）
            }
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] mic redirect failed: {}", e.getMessage());
        }
    }

    private static de.maxhenkel.voicechat.api.opus.OpusEncoder encoder() {
        if (encoder == null || encoder.isClosed()) {
            VoicechatClientApi api = TeamspeakSvcClient.clientApi();
            if (api == null) {
                throw new IllegalStateException("SVC client api not ready");
            }
            encoder = api.createEncoder();
        }
        return encoder;
    }
}
