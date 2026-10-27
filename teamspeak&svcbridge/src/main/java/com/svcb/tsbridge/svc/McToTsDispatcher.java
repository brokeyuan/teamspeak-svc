package com.svcb.tsbridge.svc;

import com.svcb.tsbridge.TsBridgeMod;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;

import java.util.UUID;

/**
 * SVC 麦克风包 → TS 转发派发器。
 *
 * <p>v2 改为群组模式：所有在线 MC 玩家的音频经 {@link com.svcb.tsbridge.audio.McAudioMixer}
 * 混音后发送到 TS，不再依赖身份配对（配对仅用于 TS→MC 上行音频的身份映射）。</p>
 *
 * <p>mixer 不可用时回退到 v1 的 forwarder 模式（仅转发已配对玩家）。</p>
 *
 * <p>本方法在 SVC 主线程被调用，必须快速返回不阻塞。</p>
 */
public final class McToTsDispatcher {

    private McToTsDispatcher() {
    }

    public static void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection senderConn = event.getSenderConnection();
        if (senderConn == null) {
            return;
        }
        ServerPlayer senderPlayer = senderConn.getPlayer();
        if (senderPlayer == null) {
            return;
        }
        UUID sender = senderPlayer.getUuid();

        MicrophonePacket packet = event.getPacket();
        if (packet == null) {
            return;
        }
        byte[] opusData = packet.getOpusEncodedData();
        if (opusData == null || opusData.length == 0) {
            return;
        }

        // 直接使用本次麦克风事件的群组快照，不用可能尚未撤销授权的成员缓存。
        // SVC 为每个麦克风事件从当前 PlayerState 构造 senderConnection。
        var bridgeId = TsBridgeMod.bridgeGroupId;
        try {
            var group = senderConn.getGroup();
            if (bridgeId == null || group == null || !bridgeId.equals(group.getId())) {
                return;
            }
        } catch (Exception e) {
            // 无法确认群组归属时禁止外发，不能把反射/API 失败解释为允许所有玩家。
            TsBridgeMod.LOGGER.debug("[tsbridge] Cannot verify microphone group: {}", e.getMessage());
            return;
        }

        // 真 1:1 模式：玩家的 Opus 帧直接经其专属 TS 客户端透传（零编解码）。
        // 玩家镜像客户端未连接（TS 不可达等）时静默丢弃。
        if (TsBridgeMod.perPlayerMode) {
            com.svcb.tsbridge.transport.PlayerVoiceClients.sendToPlayer(sender, opusData);
            return;
        }

        // 单 bot 混音模式：mixer 优先
        if (TsBridgeMod.mixer != null && TsBridgeMod.mixer.isRunning()) {
            TsBridgeMod.mixer.enqueue(sender, opusData);
            return;
        }

        // v1 fallback: forwarder 模式。身份/配对不是通信门槛；当前桥接群组快照已授权该帧。
        if (TsBridgeMod.forwarder != null) {
            TsBridgeMod.forwarder.enqueue(sender, opusData, System.currentTimeMillis());
        }
    }
}
