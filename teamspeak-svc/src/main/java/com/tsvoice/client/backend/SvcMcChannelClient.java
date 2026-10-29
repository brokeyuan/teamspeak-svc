package com.tsvoice.client.backend;

import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;

/**
 * SVC_MC_CHANNEL 客户端接线（S2/P5）：socket 注册、MC 通道收发桥、传输宣告。
 *
 * <p>传输决策：UDP 语义默认不变 —— 只有收到本扩展统一服务端的
 * {@code svc_config_v1}（transport=MC_CHANNEL）宣告才把发送/接收桥接到 MC
 * 通道；宣告缺席（原生 SVC 服务器、未装服务端组件、或 svc-extra 提供配置）
 * 时完全不动 SVC 原生 UDP。svc-extra 在场时本扩展<b>不注册 socket、不重复
 * 处理 DSP</b>（双装所有者策略：Extra 赢得 SVC 域，本扩展的 TS 路径不受影响）。</p>
 */
public final class SvcMcChannelClient {

    private static volatile SvcMcChannelSocket socket;
    private static volatile boolean serverAnnouncedMcChannel;
    private static volatile boolean svcExtraPresent;

    private SvcMcChannelClient() {
    }

    /** svc-extra 是否已安装（元数据判断；在场则本扩展让出 SVC 域）。 */
    public static void setSvcExtraPresent(boolean present) {
        svcExtraPresent = present;
    }

    /** 本机是否支持 MC 通道 socket（非 Extra 域 + 客户端环境）。 */
    public static boolean supported() {
        return !svcExtraPresent;
    }

    /** initialization event：注册自有 socket 实例（幂等；同实例复用）。 */
    public static void registerSocket(ClientVoicechatInitializationEvent event) {
        if (svcExtraPresent) {
            return; // Extra 域让渡：不注册、不改写
        }
        if (socket == null || socket.isClosed()) {
            socket = new SvcMcChannelSocket();
        }
        event.setSocketImplementation(socket);
        com.tsvoice.client.TeamspeakSvcClient.LOGGER.info(
                "[teamspeak-svc] SVC MC-channel socket registered (transport follows server announcement)");
    }

    /** 当前 socket（诊断/测试）。 */
    public static SvcMcChannelSocket currentSocket() {
        return socket;
    }

    /** 服务端宣告：config payload 接收后调用（主线程）。 */
    public static void onServerAnnouncement(String transport) {
        serverAnnouncedMcChannel = "MC_CHANNEL".equals(transport);
        com.tsvoice.client.TeamspeakSvcClient.LOGGER.info(
                "[teamspeak-svc] server SVC transport announcement: {} (mc-channel {})", transport,
                serverAnnouncedMcChannel ? "enabled" : "disabled");
        if (socket != null) {
            if (serverAnnouncedMcChannel) {
                socket.open();
                socket.setMcSender(data ->
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                                new com.tsvoice.common.net.SvcMcChannelPayloads.SvcVoiceC2sPayload(data)));
            } else {
                socket.close(); // 回落原生 UDP：socket 关闭即不再接管
            }
        }
    }

    /** Fabric 接收（主线程）：S2C SVC 语音帧 → socket 队列。 */
    public static void onDownstreamVoice(byte[] data) {
        SvcMcChannelSocket current = socket;
        if (current != null && serverAnnouncedMcChannel) {
            current.onMcDatagram(data);
        }
    }

    /** MC 通道是否当前生效（诊断/状态模型）。 */
    public static boolean mcChannelActive() {
        return serverAnnouncedMcChannel && socket != null && !socket.isClosed();
    }

    /** 测试重置。 */
    public static void resetForTest() {
        if (socket != null) {
            socket.close();
        }
        socket = null;
        serverAnnouncedMcChannel = false;
    }
}
