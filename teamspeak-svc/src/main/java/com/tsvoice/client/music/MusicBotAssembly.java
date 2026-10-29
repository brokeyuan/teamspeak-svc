package com.tsvoice.client.music;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;

/**
 * Bot 生产装配（G3/P1-01）：把独立会话、真实 client、可撤销许可、发送桥、
 * 频道确认监听一次性接好 —— 生产 start 与回归测试共用同一装配路径。
 *
 * <p>装配内容：</p>
 * <ul>
 *   <li>writer 代际来源 = {@code permit::currentEpoch}（真实 writer 最终写出点
 *       逐帧复核；撤销即丢弃，无永久常量）；</li>
 *   <li>频道确认：自频道变更事件 → permit.confirm（绑定房间不符 → 撤销 +
 *       停止播出）；</li>
 *   <li>发送桥：编码帧按许可当前代际 sendVoice(CODEC_OPUS_MUSIC)；不可发送
 *       （Long.MIN_VALUE）直接丢弃；</li>
 *   <li>MusicSession 发送门：startPlayback 要求许可 READY；编码后复核共用
 *       同一许可。</li>
 * </ul>
 */
public final class MusicBotAssembly {

    private MusicBotAssembly() {
    }

    /**
     * 装配一个生产 Bot 会话。
     *
     * @param session        已创建的音乐会话
     * @param client         Bot 自己的 TS 客户端（独立身份）
     * @param boundServerUid 授权房间 serverUID（null = 不绑定）
     * @return 本会话的发送许可
     */
    public static MusicSendPermit assemble(MusicSession session, Ts3VoiceClient client,
                                           String boundServerUid) {
        MusicSendPermit permit = new MusicSendPermit(boundServerUid);
        MusicSession.ProductionConnection production = new MusicSession.ProductionConnection(
                null, client, null, null, permit);
        session.attachProductionConnection(production);
        production.closer = () -> {
            permit.revoke("bot closed");
            client.disconnect();
        };

        // 真实 writer 的最终写出许可来源：撤销推进代际 → 已出队未写出的帧丢弃。
        client.setVoiceRouteEpochSupplier(permit::currentEpoch);

        // G3：任何许可撤销（移出房间/断线等）→ 立即停止本会话播出。
        permit.setRevocationListener(session::cancel);

        // 自频道确认/移动：进入或移动到实际频道时确认；授权房间不符 → 撤销并停播。
        client.setChannelMembershipListener(clid -> {
            if (clid != client.getMyClid()) {
                return;
            }
            // 房间不符时 confirm 内部撤销并触发 revocationListener（停播）。
            permit.confirm(client.getVirtualServerUid(), client.getMyChannelId());
        });

        // 发送门：startPlayback 与编码后复核使用同一许可。
        session.setSendGate(permit::isSendable, permit::currentEpoch);

        // 发送桥：编码帧 → sendVoice(当前许可代际)；不可发送/发送异常丢弃该帧。
        session.startPlaybackForTestHookViaProducer(() -> opus -> {
            long epoch = permit.currentEpoch();
            if (epoch == Long.MIN_VALUE) {
                return false; // 许可不可发送：帧丢弃，播放继续（引擎语义）
            }
            try {
                client.sendVoice(opus, MusicSession.CODEC_OPUS_MUSIC, epoch);
                return true;
            } catch (com.tsvoice.client.ts3voice.Ts3VoiceException e) {
                com.tsvoice.client.TeamspeakSvcClient.LOGGER.debug(
                        "[teamspeak-svc] music bot voice send failed: {}", e.getMessage());
                return false;
            }
        });
        return permit;
    }
}
