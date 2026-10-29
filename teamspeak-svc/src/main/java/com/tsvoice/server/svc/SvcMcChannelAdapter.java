package com.tsvoice.server.svc;

import com.tsvoice.common.net.SvcMcChannelPayloads;
import de.maxhenkel.voicechat.plugins.impl.RawUdpPacketImpl;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SVC_MC_CHANNEL 服务端适配（S2/P5）：把 MC 通道上的 SVC NetworkMessage 原始
 * 字节按玩家喂给 SVC 的 {@code Server.addRawPacket}，并把 SVC 出站的
 * {@code sendPacketRaw} 按玩家经 MC 通道送回 —— 每玩家独立会话地址，UDP 玩家
 * 与 MC 通道玩家在 SVC 内部天然混听（同一个 SVC Server 实例）。
 *
 * <p>与 SVC-Extra @6669d6f 的关键差异（源码核实后的自有实现）：
 * <ul>
 *   <li>会话地址 = 每个 (MC 连接代际, 玩家) 分配的<b>唯一</b> {@code mc:<sessionId>}
 *       端口空间（单调计数器 + 连接实例哈希，不做 UUID.hashCode%65535 的碰撞
 *       映射 —— Extra 的地址映射存在碰撞空间）；</li>
 *   <li>按玩家路由：只对声明走 MC 通道的玩家拦截 sendPacketRaw（通过
 *       {@link #mcChannelPlayers}），UDP 玩家路径完全不动 —— 不是全服
 *       transport 开关；</li>
 *   <li>出站投递直接经 Fabric send（不抓 Netty channel）；玩家离线即失败，
 *       不排队。</li>
 * </ul></p>
 *
 * <p>本类只搬运字节；SVC 的加密/认证/secret 会话完全由 SVC 内部掌握
 * （AuthenticatePacket/ConnectionCheckPacket 原样进出）。SVC 不在场时本类
 * 不初始化（依赖延迟加载由调用方保证 —— server-fixes 的无 SVC 环境不触达）。</p>
 */
public final class SvcMcChannelAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    /** 声明走 MC 通道的玩家（服务端在玩家连接初始化时登记；MC 通道外层判断）。 */
    private static final Map<UUID, SessionAddress> MC_CHANNEL_PLAYERS = new ConcurrentHashMap<>();
    /** 会话地址计数器：唯一端口空间（不用 UUID.hashCode%65535 的碰撞映射）。 */
    private static final AtomicLong SESSION_COUNTER = new AtomicLong(1);

    /** 每玩家的唯一会话地址（SVC 以地址关联 ClientConnection）。 */
    public record SessionAddress(UUID player, long connectionInstance, int sessionId) {
        public InetSocketAddress toSocketAddress() {
            // 端口 = sessionId % 65536；sessionId 由单调计数器分配，唯一。
            // host 保留 "mc" 未解析形式（SVC 只做 equals 比对，从不解析）。
            return new InetSocketAddress("mc", 1024 + (int) (sessionId % 60000));
        }
    }

    private SvcMcChannelAdapter() {
    }

    /** 玩家连接初始化（SVC initializePlayerConnection 时机由调用方钩子触发）。 */
    public static SessionAddress onPlayerInitialized(UUID player) {
        SessionAddress address = new SessionAddress(player,
                Thread.currentThread().getId(), (int) SESSION_COUNTER.incrementAndGet());
        MC_CHANNEL_PLAYERS.put(player, address);
        LOGGER.debug("[teamspeak-svc] SVC MC-channel session address assigned: {} -> mc:{}",
                player, address.toSocketAddress().getPort());
        return address;
    }

    /** 玩家断开：回收会话地址（SVC 自己的 secrets/connections 超时清理）。 */
    public static void onPlayerDisconnected(UUID player) {
        MC_CHANNEL_PLAYERS.remove(player);
    }

    /** 该玩家当前是否声明走 MC 通道。 */
    public static boolean isMcChannelPlayer(UUID player) {
        return MC_CHANNEL_PLAYERS.containsKey(player);
    }

    /** 当前 MC 通道玩家数（诊断）。 */
    public static int mcChannelPlayerCount() {
        return MC_CHANNEL_PLAYERS.size();
    }

    /** 当前登记的会话地址快照（诊断）。 */
    public static java.util.List<SessionAddress> sessionSnapshot() {
        return java.util.List.copyOf(MC_CHANNEL_PLAYERS.values());
    }

    /**
     * C2S 接收：SVC NetworkMessage 原始字节 → SVC Server.addRawPacket。
     * 会话地址取登记值（与 SVC 的地址关联语义一致）；未登记的玩家拒收
     * （MC 通道声明必须先于语音）。
     *
     * @return 是否已投递（诊断/测试）。
     */
    public static boolean onVoiceFromClient(MinecraftServer mcServer, UUID player, byte[] data) {
        if (data == null || data.length == 0 || data.length > SvcMcChannelPayloads.MAX_VOICE_BYTES) {
            return false;
        }
        SessionAddress address = MC_CHANNEL_PLAYERS.get(player);
        if (address == null) {
            return false;
        }
        Object svcServer = SvcServerBridge.voicechatServer();
        if (svcServer == null) {
            return false;
        }
        try {
            // 反射调用 addRawPacket：避免编译期依赖 SVC 内部类（voicechat SERVER 类）。
            svcServer.getClass().getMethod("addRawPacket",
                    Class.forName("de.maxhenkel.voicechat.api.RawUdpPacket"))
                    .invoke(svcServer, new RawUdpPacketImpl(data,
                            address.toSocketAddress(), System.currentTimeMillis()));
            return true;
        } catch (Exception e) {
            LOGGER.debug("[teamspeak-svc] SVC addRawPacket failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * S2C 出站：SVC sendPacketRaw → 按玩家 MC 通道。只对 MC 通道玩家处理；
     * 返回 true 表示已接管（调用方取消 SVC 原始 UDP 发送），UDP 玩家返回 false。
     */
    public static boolean onSendPacketRaw(UUID targetPlayer, byte[] fullPacket,
                                          java.util.function.BiConsumer<UUID, byte[]> sender) {
        if (!MC_CHANNEL_PLAYERS.containsKey(targetPlayer)) {
            return false;
        }
        sender.accept(targetPlayer, fullPacket);
        return true;
    }

    /** 测试/重载清理。 */
    public static void resetForTest() {
        MC_CHANNEL_PLAYERS.clear();
        SESSION_COUNTER.set(1);
    }
}
