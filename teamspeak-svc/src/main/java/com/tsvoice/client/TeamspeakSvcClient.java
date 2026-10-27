package com.tsvoice.client;

import com.tsvoice.client.net.BeaconPayload;
import com.tsvoice.client.net.GroupInfoPayload;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * 客户端入口：配置加载、信标/群组信息接收、/tsvoice 命令、配置 GUI。
 */
public class TeamspeakSvcClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    private static ModConfig config;
    private static volatile de.maxhenkel.voicechat.api.VoicechatClientApi clientApi;

    public static ModConfig config() {
        return config;
    }

    /** SVC 客户端 API（事件回调设置；initialize 过早拿不到时反射兜底）。 */
    public static de.maxhenkel.voicechat.api.VoicechatClientApi clientApi() {
        de.maxhenkel.voicechat.api.VoicechatClientApi api = clientApi;
        if (api != null) {
            return api;
        }
        try {
            api = (de.maxhenkel.voicechat.api.VoicechatClientApi) Class.forName(
                    "de.maxhenkel.voicechat.plugins.impl.VoicechatClientApiImpl")
                    .getMethod("instance").invoke(null);
            if (api != null) {
                clientApi = api;
            }
        } catch (Throwable ignored) {
        }
        return clientApi;
    }

    public static void setClientApi(de.maxhenkel.voicechat.api.VoicechatClientApi api) {
        clientApi = api;
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void onInitializeClient() {
        config = ModConfig.load();
        ServerConfigStore.load();
        com.tsvoice.client.audio.TsAudio.refresh();
        String version = FabricLoader.getInstance().getModContainer("teamspeak-svc")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info("[teamspeak-svc] Client mod initialized (v{}, tsHost={}:{})",
                version, config.tsHost, config.tsVoicePort);

        // 客户端主线程 tick：速度快照、闪避、混响、遮挡缓存
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    OnlinePlayers.tick();
                    com.tsvoice.client.audio.DuckingManager.tick();
                    com.tsvoice.client.audio.RayTracedReverb.tick();
                    ClientStateReflector.tickSnapshot();
                });

        // payload 类型注册（S2C 接收）
        ClientIdentityService.register();
        PayloadTypeRegistry.clientboundPlay().register(BeaconPayload.TYPE, BeaconPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GroupInfoPayload.TYPE, GroupInfoPayload.CODEC);

        // 信标：新 endpoint 需要玩家显式授权；只有已记住的 endpoint 自动采用
        TsVoiceManager.setUntrustedBeaconListener(payload ->
                                    Minecraft.getInstance().setScreenAndShow(new BeaconTrustScreen(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BeaconPayload.TYPE, (payload, context) ->
                context.client().execute(() -> TsVoiceManager.onBeacon(payload)));

        // 群组信息（tsbridge → 成员定向；含密码，仅内存使用不落日志）
        ClientPlayNetworking.registerGlobalReceiver(GroupInfoPayload.TYPE, (payload, context) ->
                context.client().execute(() -> GroupChannelMapper.onGroupInfo(payload)));

        // 服务器连接上下文（地址跟踪 + 信标重置）
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String address = client.getCurrentServer() != null ? client.getCurrentServer().ip : "";
            ClientIdentityService.clearVerified();
            TsVoiceManager.onServerChanged(address);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientIdentityService.clearVerified();
            TsVoiceManager.onServerChanged("");
        });

        // /tsvoice 命令
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("tsvoice")
                        .then(literal("status").executes(ctx -> {
                            Minecraft.getInstance().execute(() ->
                                    ctx.getSource().sendFeedback(Component.literal(statusText())));
                            return 1;
                        }))
                        .then(literal("gui").executes(ctx -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreenAndShow(new ConfigScreen()));
                            return 1;
                        }))
                        .then(literal("connect").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                TsVoiceManager.manualConnect();
                                ctx.getSource().sendFeedback(Component.literal(statusText()));
                            });
                            return 1;
                        }))
                        .then(literal("disconnect").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                TsVoiceManager.manualDisconnect();
                                ctx.getSource().sendFeedback(Component.translatable("tsvoice.ts_disconnected"));
                            });
                            return 1;
                        }))
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                .argument("say", StringArgumentType.greedyString())
                                .executes(ctx -> sendTsText(ctx,
                                        StringArgumentType.getString(ctx, "say"))))
                        .then(literal("say").then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                .argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> sendTsText(ctx,
                                        StringArgumentType.getString(ctx, "message")))))
                        .then(literal("trust").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                var pending = TsVoiceManager.pendingBeacon();
                                if (pending == null) {
                                    ctx.getSource().sendFeedback(Component.translatable("tsvoice.trust.none"));
                                } else {
                                    Minecraft.getInstance().setScreenAndShow(new BeaconTrustScreen(pending));
                                }
                            });
                            return 1;
                        }))
                        .then(literal("rays").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                com.tsvoice.client.audio.TsAudio.showRayTrace = !com.tsvoice.client.audio.TsAudio.showRayTrace;
                                ctx.getSource().sendFeedback(Component.translatable(
                                        com.tsvoice.client.audio.TsAudio.showRayTrace ? "tsvoice.rays_on" : "tsvoice.rays_off"));
                            });
                            return 1;
                        }))
                        .then(literal("reload").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                config = ModConfig.load();
                                ServerConfigStore.load();
                                com.tsvoice.client.audio.TsAudio.refresh();
                                com.tsvoice.client.audio.AudioPipeline.reconfigure();
                                TsVoiceManager.onManualConfigChanged();
                                ctx.getSource().sendFeedback(Component.translatable("tsvoice.config_reloaded"));
                            });
                            return 1;
                        }))
        ));
    }

    /** `/tsvoice <消息>` 与 `/tsvoice say <消息>` 共用的频道文字发送。 */
    private static int sendTsText(CommandContext<FabricClientCommandSource> ctx, String msg) {
        Minecraft.getInstance().execute(() -> {
            if (!TsVoiceManager.isSessionConnected()) {
                ctx.getSource().sendError(Component.translatable("tsvoice.ts_not_connected"));
                return;
            }
            if (!TsVoiceManager.sendChannelText(msg)) {
                ctx.getSource().sendError(Component.translatable("tsvoice.route_not_ready"));
            }
        });
        return 1;
    }

    private static String statusText() {
        String denoise = config() != null ? config().denoiseMode : "OFF";
        if ("OFF".equalsIgnoreCase(denoise)) {
            denoise = "OFF (SVC native RNNoise)";
        }
        boolean nvidiaAvailable = com.tsvoice.client.audio.AudioPipeline.isNvidiaAvailable();
        StringBuilder sb = new StringBuilder(Component.translatable("tsvoice.status").getString()
                .formatted(TsVoiceManager.getState(), TsVoiceManager.configSource(), denoise,
                        nvidiaAvailable ? " [RTX]" : "",
                        ClientStateReflector.activeGhosts().size(), TsVoiceManager.getReceivedVoiceFrames()));
        if (TsVoiceManager.getState() == TsVoiceManager.State.ERROR
                || TsVoiceManager.getState() == TsVoiceManager.State.RECONNECTING) {
            sb.append(" (").append(TsVoiceManager.getLastError()).append(")");
        }
        sb.append(", injected=").append(TsAudioInjector.getInjectedFrames())
          .append(", sent=").append(TsVoiceManager.getSentVoiceFrames());
        return sb.toString();
    }

}
