package com.tsvoice;

import com.tsvoice.common.net.CapabilitiesPayloads;
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

        // 每 server 生命周期：dedicated 与集成服务器统一走这里（无 server 入口例外）。
        // S0.3/R22：环境在启动时固定并参与所有状态计算（reload/恢复不再绕过集成门）。
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            ServerRuntime runtime = ServerRuntime.start(configDir, server.isDedicatedServer());
            RUNTIMES.put(server, runtime);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ServerRuntime runtime = RUNTIMES.remove(server);
            if (runtime != null) {
                runtime.close();
            }
        });

        // 连接退出：清理该连接的限流记录（连接身份，而非仅 player UUID）
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerRuntime runtime = RUNTIMES.get(server);
            if (runtime != null && handler.player != null) {
                runtime.forgetConnection(connectionId(handler.player));
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

    /** 运行时查询（管理命令/测试）。 */
    public static ServerRuntime runtimeOf(MinecraftServer server) {
        return RUNTIMES.get(server);
    }
}
