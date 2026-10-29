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
 * SVC_MC_CHANNEL 服务端适配（E2/T36 修订）：把 MC 通道上的 SVC NetworkMessage
 * 原始字节按玩家喂给 SVC 的 {@code Server.addRawPacket}，并把 SVC 出站的
 * {@code sendPacketRaw} 按玩家经 MC 通道送回 —— 每玩家独立会话地址，UDP 玩家
 * 与 MC 通道玩家在 SVC 内部天然混听（同一个 SVC Server 实例）。
 *
 * <p>T36 修订点：</p>
 * <ul>
 *   <li><b>登记键 = server instance + 真实连接</b>：不是线程 ID
 *       （{@code Thread.currentThread().getId()} 会把同一 server 线程上的
 *       不同玩家/不同连接混同）。server instance 变化（换服/重启）清空全部
 *       登记 —— 不跨世界复用状态。</li>
 *   <li><b>地址永不复用</b>：单调计数器分配，回收的地址不再进入分配空间
 *       （旧连接的迟到包不能污染新连接）。</li>
 *   <li><b>合成地址确定且无 DNS</b>：{@code createUnresolved("mc", port)}
 *       —— SVC 只做 {@code SocketAddress.equals} 比对（字节码核实），从不
 *       解析；主机名形式 {@code new InetSocketAddress("mc", p)} 会尝试解析
 *       "mc"，createUnresolved 明确不解析。</li>
 *   <li><b>Extra 让渡</b>：svc-extra 在场时本适配停用（不登记/不路由），
 *       TS 路径不受影响。</li>
 * </ul>
 */
public final class SvcMcChannelAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    /** 声明走 MC 通道的会话（键 = 玩家 UUID；值 = 该玩家当前活跃连接的会话）。 */
    private static final Map<UUID, SessionAddress> MC_CHANNEL_PLAYERS = new ConcurrentHashMap<>();
    /** 玩家当前活跃连接（连接退役后旧 connection 的包不再受理）。 */
    private static final Map<UUID, String> ACTIVE_CONNECTIONS = new ConcurrentHashMap<>();
    /** 当前 server instance（换服/重启即清空登记）。 */
    private static volatile Object currentServerInstance;
    /** svc-extra 让渡开关（true：本适配停用）。 */
    private static volatile boolean svcExtraOwner;

    /**
     * 会话地址：全局单调计数器，回收不复用（不与活跃/未退役连接冲突）。
     * 端口 = 1024 + (sessionId % 60000)：61024..71023 单调空间，唯一由
     * sessionId 保证（≥2^48 会话后才回绕 —— 实际连接数远达不到）。
     */
    private static final AtomicLong SESSION_COUNTER = new AtomicLong(1);

    /** 每玩家的唯一会话地址（SVC 以地址关联 ClientConnection）。 */
    public record SessionAddress(UUID player, String connectionId, long sessionId) {
        public InetSocketAddress toSocketAddress() {
            // 端口 = 1024 + (sessionId % 60000)；sessionId 全局单调唯一。
            // createUnresolved：明确不解析（SVC 只做 equals 比对，从不解析）。
            return InetSocketAddress.createUnresolved("mc",
                    1024 + (int) (sessionId % 60000));
        }
    }

    private SvcMcChannelAdapter() {
    }

    /** svc-extra 让渡开关（RuntimeDependencies.svcExtraPresent() 注入）。 */
    public static void setSvcExtraOwner(boolean owner) {
        svcExtraOwner = owner;
    }

    /**
     * 玩家连接初始化（生产：主入口 JOIN；按 server instance + 真实连接登记）。
     *
     * @param player       玩家 UUID
     * @param connectionId 真实 MC 连接身份（不是线程 ID）
     * @param server       当前 server instance（换服检测）
     * @return 分配的会话地址；svc-extra 让渡时返回 null
     */
    public static SessionAddress onPlayerInitializedForConnection(UUID player, String connectionId,
                                                                  Object server) {
        if (svcExtraOwner) {
            return null;
        }
        if (server != null && server != currentServerInstance) {
            // 换服/重启：上一实例的登记全部失效（不跨世界串用）
            MC_CHANNEL_PLAYERS.clear();
            ACTIVE_CONNECTIONS.clear();
            currentServerInstance = server;
        }
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        String active = ACTIVE_CONNECTIONS.get(player);
        if (connectionId.equals(active)) {
            SessionAddress existing = MC_CHANNEL_PLAYERS.get(player);
            if (existing != null) {
                return existing; // 幂等重入
            }
        }
        long sessionId = SESSION_COUNTER.incrementAndGet();
        SessionAddress address = new SessionAddress(player, connectionId, sessionId);
        MC_CHANNEL_PLAYERS.put(player, address);
        ACTIVE_CONNECTIONS.put(player, connectionId);
        LOGGER.debug("[teamspeak-svc] SVC MC-channel session assigned: {} conn={} -> mc:{}",
                player, connectionId, address.toSocketAddress().getPort());
        return address;
    }

    /** 玩家断开：回收该连接的会话（地址空间不复用）。 */
    public static void onPlayerDisconnected(UUID player, String connectionId) {
        String active = ACTIVE_CONNECTIONS.get(player);
        if (active != null && !active.equals(connectionId)) {
            return; // 旧连接的迟到断开：不回收新连接的会话
        }
        MC_CHANNEL_PLAYERS.remove(player);
        ACTIVE_CONNECTIONS.remove(player, connectionId);
    }

    /** 该玩家当前是否声明走 MC 通道（活跃连接）。 */
    public static boolean isMcChannelPlayer(UUID player) {
        return !svcExtraOwner && MC_CHANNEL_PLAYERS.containsKey(player);
    }

    /** 指定连接是否为该玩家的活跃 MC 通道会话。 */
    public static boolean isMcChannelPlayerOnConnection(UUID player, String connectionId) {
        if (svcExtraOwner) {
            return false;
        }
        return connectionId != null && connectionId.equals(ACTIVE_CONNECTIONS.get(player))
                && MC_CHANNEL_PLAYERS.containsKey(player);
    }

    /** 当前 MC 通道玩家数（诊断）。 */
    public static int mcChannelPlayerCount() {
        return MC_CHANNEL_PLAYERS.size();
    }

    /** 当前登记的会话地址快照（诊断）。 */
    public static java.util.List<SessionAddress> sessionSnapshot() {
        return java.util.List.copyOf(MC_CHANNEL_PLAYERS.values());
    }

    /** server instance 变化（换服/重启）：清空全部登记。 */
    public static void onServerInstanceChanged(Object server) {
        if (server != currentServerInstance) {
            MC_CHANNEL_PLAYERS.clear();
            ACTIVE_CONNECTIONS.clear();
            currentServerInstance = server;
        }
    }

    /**
     * C2S 接收：SVC NetworkMessage 原始字节 → SVC Server.addRawPacket。
     * 只受理登记玩家（活跃连接）的帧；未登记拒收。
     *
     * @return 是否已投递（诊断/测试）。
     */
    public static boolean onVoiceFromClient(MinecraftServer mcServer, UUID player, byte[] data) {
        if (data == null || data.length == 0 || data.length > SvcMcChannelPayloads.MAX_VOICE_BYTES) {
            return false;
        }
        SessionAddress address = MC_CHANNEL_PLAYERS.get(player);
        if (address == null || svcExtraOwner) {
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
        if (!isMcChannelPlayer(targetPlayer)) {
            return false;
        }
        sender.accept(targetPlayer, fullPacket);
        return true;
    }

    /** 测试/重载清理。 */
    public static void resetForTest() {
        MC_CHANNEL_PLAYERS.clear();
        ACTIVE_CONNECTIONS.clear();
        currentServerInstance = null;
        svcExtraOwner = false;
        SESSION_COUNTER.set(1);
    }
}
