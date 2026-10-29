package com.tsvoice;

import com.tsvoice.common.net.CapabilitiesPayloads;
import com.tsvoice.common.net.SvcMcChannelPayloads;
import com.tsvoice.common.net.TsRelayPayloads;
import com.tsvoice.bootstrap.RuntimeDependencies;
import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.ServerCommands;
import com.tsvoice.server.ServerRuntime;
import com.tsvoice.server.svc.SvcMcChannelAdapter;
import com.tsvoice.server.relay.OpenEstablisher;
import com.tsvoice.server.relay.TsRelaySession;
import com.tsvoice.server.svc.SvcServerBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主入口（M7-T14.1/2/5）：环境中性的公共初始化 —— payload 类型一次注册、
 * 每 MinecraftServer 的运行时生命周期、服务端公告 hello 接收、管理命令。
 * 不引用 SVC、client、OpenAL、WebRTC 类型（客户端初始化走 bootstrap/ClientEntrypoint）。
 *
 * <p>codec/receiver 全 JVM 只注册一次；receiver 按当前 player 所属 server 取运行时，
 * 不捕获首个世界的旧 server。同一 JAR 同时装在客户端与服务端时（单人集成），
 * 集成服务器默认关闭公告（ServerRuntime.applyIntegratedServerGate）。</p>
 */
public final class TeamspeakSvcMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    /** 按 MinecraftServer 实例的运行时表（生命周期注册一次，映射随 SERVER_STOPPED 清理）。 */
    private static final Map<MinecraftServer, ServerRuntime> RUNTIMES = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        // 公共 payload 注册（唯一所有者：客户端/服务端不得重复注册同 ID 类型）
        CapabilitiesPayloads.register();
        // S1.2/P6：TS 中继 wire 注册（方向化；不支持中继的服务器只是不收/不回）
        TsRelayPayloads.register();
        // S2/P5：SVC_MC_CHANNEL 注册（自有 ID，与 svc-extra 双装隔离）
        SvcMcChannelPayloads.register();

        // ---- S2/P5：SVC MC 通道接收（C2S：SVC NetworkMessage 原始字节）----
        // SVC 不在场时 addRawPacket 失败为 no-op —— 基本功能不因无 SVC 失败。
        ServerPlayNetworking.registerGlobalReceiver(
                SvcMcChannelPayloads.SvcVoiceC2sPayload.TYPE, (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    if (server == null) {
                        return;
                    }
                    SvcMcChannelAdapter.onVoiceFromClient(server, player.getUUID(), payload.data());
                });

        // 服务端公告 hello 接收器（全局注册一次；按 player→server 路由）
        ServerPlayNetworking.registerGlobalReceiver(
                CapabilitiesPayloads.CapabilitiesHelloPayload.TYPE, (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    ServerRuntime runtime = server == null ? null : RUNTIMES.get(server);
                    if (runtime == null || !runtime.announcing()) {
                        return;
                    }
                    byte[] response = runtime.onHello(connectionId(player), payload.frame());
                    if (response != null) {
                        try {
                            ServerPlayNetworking.send(player,
                                    new CapabilitiesPayloads.CapabilitiesPayload(response));
                        } catch (RuntimeException e) {
                            LOGGER.error("[teamspeak-svc] failed to send capabilities response", e);
                        }
                    }
                });

        // ---- S1.3/P6：TS 中继 payload 接收（默认关闭的服务器只是不回 ERROR 之外的帧）----
        ServerPlayNetworking.registerGlobalReceiver(
                TsRelayPayloads.RelayOpenPayload.TYPE, (payload, context) -> {
                    ServerPlayer player = context.player();
                    ServerRuntime runtime = RUNTIMES.get(context.server());
                    if (runtime == null) {
                        return;
                    }
                    String connectionId = connectionId(player);
                    var result = runtime.relayRuntime().onOpen(connectionId,
                            String.valueOf(player.getUUID()), payload.frame());
                    if (!result.ok() && result.openedFrame() == null && result.errorFrame() == null) {
                        // G4：OPEN 已受理，异步建立中 —— 应答帧由完成回调发送。
                        return;
                    }
                    try {
                        if (result.ok()) {
                            ServerPlayNetworking.send(player,
                                    new TsRelayPayloads.RelayOpenedPayload(result.openedFrame()));
                        } else {
                            ServerPlayNetworking.send(player,
                                    new TsRelayPayloads.RelayErrorPayload(result.errorFrame()));
                        }
                    } catch (RuntimeException e) {
                        LOGGER.error("[teamspeak-svc] failed to answer relay OPEN", e);
                    }
                });
        ServerPlayNetworking.registerGlobalReceiver(
                TsRelayPayloads.RelayDataC2sPayload.TYPE, (payload, context) -> {
                    ServerPlayer player = context.player();
                    ServerRuntime runtime = RUNTIMES.get(context.server());
                    if (runtime == null) {
                        return;
                    }
                    runtime.relayRuntime().onData(connectionId(player), payload.frame());
                });
        ServerPlayNetworking.registerGlobalReceiver(
                TsRelayPayloads.RelayRouteBarrierC2sPayload.TYPE, (payload, context) -> {
                    ServerRuntime runtime = RUNTIMES.get(context.server());
                    if (runtime == null) {
                        return;
                    }
                    runtime.relayRuntime().onRouteBarrier(connectionId(context.player()), payload.frame());
                });
        ServerPlayNetworking.registerGlobalReceiver(
                TsRelayPayloads.RelayClosePayload.TYPE, (payload, context) -> {
                    ServerPlayer player = context.player();
                    ServerRuntime runtime = RUNTIMES.get(context.server());
                    if (runtime == null) {
                        return;
                    }
                    byte[] closed = runtime.relayRuntime().onClose(connectionId(player), payload.frame());
                    if (closed != null) {
                        try {
                            ServerPlayNetworking.send(player, new TsRelayPayloads.RelayClosedPayload(closed));
                        } catch (RuntimeException e) {
                            LOGGER.error("[teamspeak-svc] failed to send relay CLOSED", e);
                        }
                    }
                });

        // 每 server 生命周期：dedicated 与集成服务器统一走这里（无 server 入口例外）。
        // S0.3/R22：环境在启动时固定并参与所有状态计算（reload/恢复不再绕过集成门）。
        // S1.3：TS 中继上行 sink —— DATA_S2C/CLOSED 从 session worker 投递给玩家
        //（有界队列在 TsRelayRuntime.sink 侧；发送失败由 runtime 回收会话）。
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // E2/T36：svc-extra 让渡注入（元数据查询；Extra 在场时适配停用）
            SvcMcChannelAdapter.setSvcExtraOwner(RuntimeDependencies.svcExtraPresent());
            Path configDir = FabricLoader.getInstance().getConfigDir();
            ServerRuntime runtime = ServerRuntime.start(configDir, server.isDedicatedServer(),
                    (relaySessionId, playerUuid, datagram) -> {
                        ServerPlayer target = findPlayer(server, playerUuid);
                        if (target == null) {
                            return false;
                        }
                        // T32/E3 唯一外层编码点：runtime 交来的字节可能是原始 TS
                        // UDP 数据报（绝大多数）或 TSRL 控制帧（CLOSED/ERROR）。
                        // 按 classifyUpstreamDatagram 分型后分别包装/透传 ——
                        // 绝不把 raw TS 数据报当 TSRL 解码（81c687c 反例已除）。
                        TsRelayWire.MessageType kind =
                                TsRelayWire.classifyUpstreamDatagram(datagram);
                        try {
                            switch (kind) {
                                case DATA_S2C -> ServerPlayNetworking.send(target,
                                        new TsRelayPayloads.RelayDataS2cPayload(
                                                TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_S2C,
                                                        new TsRelayWire.DataFrame(relaySessionId, 0, 0, datagram))));
                                case CLOSED -> ServerPlayNetworking.send(target,
                                        new TsRelayPayloads.RelayClosedPayload(datagram));
                                case ERROR -> ServerPlayNetworking.send(target,
                                        new TsRelayPayloads.RelayErrorPayload(datagram));
                                default -> {
                                    return false; // 无法归属的字节：拒绝投递
                                }
                            }
                            return true;
                        } catch (RuntimeException e) {
                            LOGGER.debug("[teamspeak-svc] relay sink send failed: {}", e.getMessage());
                            return false;
                        }
                    });
            // G4：OPEN 异步建立器 —— DNS/socket 在有界 worker（4 worker/64 待办）
            // 进行，不阻塞 MC 主线程；迟到结果丢弃并回收；断连撤销。
            runtime.relayRuntime().installEstablisher(new OpenEstablisher(
                    new OpenEstablisher.Attempt() {
                        @Override
                        public void discard(TsRelaySession session) {
                            session.close();
                        }

                        @Override
                        public void complete(String connectionId, String playerUuid,
                                             TsRelayWire.OpenFrame openFrame, TsRelaySession session,
                                             String failure) {
                            var result = runtime.relayRuntime().completeAsyncOpen(
                                    connectionId, playerUuid, openFrame, session, failure);
                            ServerPlayer target = findPlayer(server, playerUuid);
                            if (target == null) {
                                if (session != null) {
                                    session.close(); // 玩家已离线：回收会话
                                }
                                return;
                            }
                            try {
                                if (result.ok()) {
                                    ServerPlayNetworking.send(target,
                                            new TsRelayPayloads.RelayOpenedPayload(result.openedFrame()));
                                } else {
                                    ServerPlayNetworking.send(target,
                                            new TsRelayPayloads.RelayErrorPayload(result.errorFrame()));
                                }
                            } catch (RuntimeException e) {
                                LOGGER.error("[teamspeak-svc] failed to answer async relay OPEN", e);
                                if (session != null) {
                                    session.close();
                                }
                            }
                        }
                    }));
            RUNTIMES.put(server, runtime);
            // S2/P5：SVC MC 通道桥登记当前 server（SVC 不在场时桥为 no-op）
            SvcServerBridge.setMinecraftServer(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ServerRuntime runtime = RUNTIMES.remove(server);
            if (runtime != null) {
                runtime.close();
            }
        });

        // 连接退出：清理该连接的限流记录与中继会话（连接身份，而非仅 player UUID）
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerRuntime runtime = RUNTIMES.get(server);
            if (runtime != null && handler.player != null) {
                String connectionId = connectionId(handler.player);
                runtime.forgetConnection(connectionId);
                runtime.relayRuntime().onClientDisconnected(connectionId);
                // S2/P5：SVC MC 通道会话地址回收（按真实连接）
                SvcMcChannelAdapter.onPlayerDisconnected(handler.player.getUUID(), connectionId);
            }
        });

        // S2/P5：玩家连接建立 —— 登记会话地址并宣告本服 SVC 传输
        //（svc-extra 在场时本扩展让出 SVC 域：不宣告、不登记）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (handler.player == null) {
                return;
            }
            if (RuntimeDependencies.svcExtraPresent()) {
                return; // 双装所有者策略：Extra 拥有 SVC 域，本扩展不宣告
            }
            ServerRuntime runtime = RUNTIMES.get(server);
            if (runtime == null) {
                return;
            }
            boolean enabled = runtime.relayConfig().svcMcChannelEnabled();
            SvcMcChannelPayloads.SvcTransportConfigPayload announcement =
                    new SvcMcChannelPayloads.SvcTransportConfigPayload(enabled
                            ? SvcMcChannelPayloads.SvcTransportConfigPayload.TRANSPORT_MC_CHANNEL
                            : SvcMcChannelPayloads.SvcTransportConfigPayload.TRANSPORT_UDP);
            try {
                ServerPlayNetworking.send(handler.player, announcement);
                if (enabled) {
                    // E2/T36：按 server instance + 真实连接登记（不是线程 ID）
                    SvcMcChannelAdapter.onPlayerInitializedForConnection(
                            handler.player.getUUID(), connectionId(handler.player), server);
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[teamspeak-svc] SVC transport announcement failed: {}", e.getMessage());
            }
        });

        ServerCommands.register();
        LOGGER.info("[teamspeak-svc] common init complete (server runtime per-world, announcements per config)");
    }

    /** 连接身份：UUID + 连接实例（同玩家重连区分限流）。 */
    private static String connectionId(ServerPlayer player) {
        UUID id = player.getUUID();
        return id + "@" + System.identityHashCode(player);
    }

    /** 按 player UUID 查找在线玩家（relay 上行 sink 用；离线返回 null）。 */
    private static ServerPlayer findPlayer(MinecraftServer server, String playerUuid) {
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(playerUuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 运行时查询（管理命令/测试）。 */
    public static ServerRuntime runtimeOf(MinecraftServer server) {
        return RUNTIMES.get(server);
    }
}
