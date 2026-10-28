package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.VoiceState;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;

/**
 * 麦克风改道（T01）：每帧只读取一次协调器快照并据此决定归属。
 *
 * <ul>
 *   <li>TS 被选中（含连接中/重连中/等待频道）：先取消 SVC 发送再编码转发 TS；
 *       编码/发送异常、会话未就绪都只丢帧，绝不放行 SVC。</li>
 *   <li>PAUSED/OFF 或 AUTO 未解析：取消并丢弃 —— 断开 TS 本身不得启动 SVC
 *       麦克风上传，也不得被 AUTO 立即撤销。</li>
 *   <li>SVC 被选中：仅当跨后端发言边界已满足才放行原版路径。持续讲话期间
 *       （PTT 按住/语音激活连续发声）不允许把受众悄悄切到 SVC；静默超过边界
 *       窗口后才恢复原版发送（PTT 需松开重按，语音激活利用静默边界）。</li>
 * </ul>
 */
public final class MicRedirector {

    /** 跨后端发言边界窗口：被 TS/暂停消费过语音后的静默时长（毫秒）。 */
    static volatile long speakingBoundaryMillis = 250;

    private static de.maxhenkel.voicechat.api.opus.OpusEncoder encoder;
    private static volatile boolean firstFrameLogged = false;
    /** 最近一次被 TS/PAUSED/OFF 消费的帧时刻：SVC 放行窗口的依据。 */
    private static volatile long lastOwnedFrameNanos;

    private MicRedirector() {
    }

    public static void onMicSound(ClientSoundEvent event) {
        VoiceState.CoordinatorSnapshot snapshot = BackendCoordinator.snapshot();
        VoiceState.BackendKind selected = snapshot.selection().selectedBackend().orElse(null);
        if (selected != VoiceState.BackendKind.SVC) {
            // TS 选中、PAUSED/OFF 或 AUTO 未解析：先关闭 SVC 路径，后续失败不得改变归属。
            event.cancel();
            lastOwnedFrameNanos = System.nanoTime();
            if (selected == VoiceState.BackendKind.TS3) {
                sendToTs(event);
            }
            return;
        }
        // SVC 被选中：越过发言边界才放行，防止持续讲话中途切换受众。
        if (System.nanoTime() - lastOwnedFrameNanos < speakingBoundaryMillis * 1_000_000L) {
            event.cancel();
            lastOwnedFrameNanos = System.nanoTime();
        }
        // 放行原版 SVC（不取消事件，编码/上传由 SVC 原逻辑完成）。
    }

    private static void sendToTs(ClientSoundEvent event) {
        short[] pcm = event.getRawAudio();
        if (pcm == null || pcm.length == 0) {
            return;
        }
        if (!TsVoiceManager.isSessionConnected()) {
            return; // 会话未就绪（等待/重连/频道未确认）：帧已消费并丢弃
        }
        try {
            byte[] opus = encoder().encode(pcm);
            if (opus == null || opus.length == 0) {
                return;
            }
            if (TsVoiceManager.sendMic(opus) && !firstFrameLogged) {
                firstFrameLogged = true;
                TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] MC->TS audio path ACTIVE (first mic frame sent to TS)");
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
