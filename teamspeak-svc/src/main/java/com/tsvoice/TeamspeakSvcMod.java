package com.tsvoice;

import com.tsvoice.common.net.CapabilitiesPayloads;
import com.tsvoice.common.net.TsRelayPayloads;
import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.ServerCommands;
import com.tsvoice.server.ServerRuntime;
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
            Path configDir = FabricLoader.getInstance().getConfigDir();
            ServerRuntime runtime = ServerRuntime.start(configDir, server.isDedicatedServer(),
                    (relaySessionId, playerUuid, datagram) -> {
                        ServerPlayer target = findPlayer(server, playerUuid);
                        if (target == null) {
                            return false;
                        }
                        TsRelayWire.Decoded decoded = TsRelayWire.decode(datagram);
                        if (decoded == null) {
                            return false;
                        }
                        try {
                            switch (decoded.type()) {
                                case DATA_S2C -> ServerPlayNetworking.send(target,
                                        new TsRelayPayloads.RelayDataS2cPayload(datagram));
                                case CLOSED -> ServerPlayNetworking.send(target,
                                        new TsRelayPayloads.RelayClosedPayload(datagram));
                                default -> {
                                    return false; // sink 只承载 DATA_S2C/CLOSED
                                }
                            }
                            return true;
                        } catch (RuntimeException e) {
                            LOGGER.debug("[teamspeak-svc] relay sink send failed: {}", e.getMessage());
                            return false;
                        }
                    });
            RUNTIMES.put(server, runtime);
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
