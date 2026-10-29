package com.tsvoice.client.backend;

import com.tsvoice.common.net.SvcMcChannelPayloads;

/**
 * SVC_MC_CHANNEL 客户端接线（E2/T36 修订）：socket 注册、MC 通道收发桥、
 * 传输宣告与连接生命周期。
 *
 * <p>T36 修订点：</p>
 * <ul>
 *   <li><b>顺序无关</b>：initialization event（socket 注册）与 config 宣告
 *       （MC_CHANNEL/UDP）任意先后都能接通 —— 宣告先到时记下决定，socket
 *       注册时应用；socket 先到时宣告即时生效。</li>
 *   <li><b>原生 UDP 保护</b>：宣告 UDP/缺席时 socket 保持原生 UDP 委托
 *       （{@link SvcMcChannelSocket#open()} 创建真实 DatagramSocket）；
 *       宣告 MC_CHANNEL 才单向切换。不使用"关闭自定义对象"冒充恢复 UDP。</li>
 *   <li><b>连接生命周期</b>：MC 断连/换服（{@link #onMcConnectionClosed()}）
 *       关闭 socket、清发送桥并复位宣告状态 —— 不跨连接复用旧决定/旧队列。</li>
 *   <li><b>下行接线</b>：S2C receiver 必须调用 {@link #onDownstreamVoice}；
 *       未激活（宣告缺席/未注册）时丢弃，不排队。</li>
 * </ul>
 */
public final class SvcMcChannelClient {

    private static volatile SvcMcChannelSocket socket;
    /** 服务端宣告结果（null=未宣告；值=TRANSPORT_*）。 */
    private static volatile String announcedTransport;
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
    public static void registerSocket(de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent event) {
        if (svcExtraPresent) {
            return; // Extra 域让渡：不注册、不改写
        }
        if (socket == null || socket.isClosed()) {
            socket = new SvcMcChannelSocket();
        }
        event.setSocketImplementation(socket);
        applyAnnouncementTo(socket);
        com.tsvoice.client.TeamspeakSvcClient.LOGGER.info(
                "[teamspeak-svc] SVC MC-channel socket registered (transport follows server announcement)");
    }

    /** 当前 socket（诊断/测试）。 */
    public static SvcMcChannelSocket currentSocket() {
        return socket;
    }

    /** 服务端宣告：config payload 接收后调用（主线程）。 */
    public static void onServerAnnouncement(String transport) {
        announcedTransport = transport;
        com.tsvoice.client.TeamspeakSvcClient.LOGGER.info(
                "[teamspeak-svc] server SVC transport announcement: {}", transport);
        SvcMcChannelSocket current = socket;
        if (current != null) {
            applyAnnouncementTo(current);
        }
    }

    /** 宣告决定应用到 socket（MC_CHANNEL 单向切换；UDP/其他保持原生委托）。 */
    private static void applyAnnouncementTo(SvcMcChannelSocket target) {
        String transport = announcedTransport;
        if (transport != null
                && SvcMcChannelPayloads.SvcTransportConfigPayload.TRANSPORT_MC_CHANNEL.equals(transport)) {
            target.activateMcChannel();
            target.setMcSender(data ->
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            new SvcMcChannelPayloads.SvcVoiceC2sPayload(data)));
        }
        // 非 MC_CHANNEL：什么都不做 —— socket 保持原生 UDP 委托，绝不关闭冒充恢复。
    }

    /** Fabric 接收（主线程）：S2C SVC 语音帧 → socket 队列（未激活丢弃）。 */
    public static void onDownstreamVoice(byte[] data) {
        SvcMcChannelSocket current = socket;
        if (current != null && !current.isClosed() && current.isMcChannelMode()) {
            current.onMcDatagram(data);
        }
    }

    /** MC 通道是否当前生效（诊断/状态模型）。 */
    public static boolean mcChannelActive() {
        return socket != null && !socket.isClosed() && socket.isMcChannelMode();
    }

    /**
     * MC 断连/换服：关闭 socket、清发送桥、复位宣告。下一次 SVC 连接
     * 由 initialization event 重新注册（新会话新决定，不继承旧状态）。
     */
    public static void onMcConnectionClosed() {
        SvcMcChannelSocket current = socket;
        socket = null;
        if (current != null) {
            current.setMcSender(null);
            current.close();
        }
        announcedTransport = null;
    }

    /** 测试重置。 */
    public static void resetForTest() {
        onMcConnectionClosed();
        svcExtraPresent = false;
    }
}
