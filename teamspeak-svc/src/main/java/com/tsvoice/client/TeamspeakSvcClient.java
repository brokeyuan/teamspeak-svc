package com.tsvoice.client;

import com.tsvoice.client.net.BeaconPayload;
import com.tsvoice.client.net.GroupInfoPayload;
import com.tsvoice.common.net.TsRelayPayloads;
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
public class TeamspeakSvcClient {

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

    /** 客户端初始化（M7 起由 bootstrap/ClientEntrypoint 在依赖检查通过后调用）。 */
    public static void onInitializeClient() {
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

        // 前端自检（P2）：关键 SVC hook 缺失 → 禁用 TS 并明确告警；可选失败只禁功能。
        // 不用 defaultRequire=0 掩盖关键路径失效；未接管时原版 SVC 照常可用。
        com.tsvoice.client.frontend.FrontendCapabilities capabilities =
                com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance().inspect();
        if (capabilities.tsUsable()) {
            LOGGER.info("[teamspeak-svc] Frontend self-check passed (mic={}, playback={}, clientStopping={})",
                    capabilities.micThreadUsable(), capabilities.playbackInjectionUsable(),
                    capabilities.clientStoppingHookPresent());
        } else {
            LOGGER.error("[teamspeak-svc] TS DISABLED — frontend self-check failed: {} (vanilla SVC stays available)",
                    capabilities.missingCriticalHooks());
        }
        TsVoiceManager.setFrontendUnavailable(!capabilities.tsUsable());

        // 退出前资源编排：停 TS、关 mic 租约与音频管线（CLIENT_STOPPING）。
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> {
                    try {
                        TsVoiceManager.manualDisconnect();
                    } catch (Throwable t) {
                        LOGGER.debug("[teamspeak-svc] client-stopping TS teardown: {}", t.getMessage());
                    }
                    try {
                        com.tsvoice.client.audio.AudioPipeline.shutdown();
                    } catch (Throwable t) {
                        LOGGER.debug("[teamspeak-svc] client-stopping audio teardown: {}", t.getMessage());
                    }
                });

        // payload 类型注册（S2C 接收）
        ClientIdentityService.register();
        PayloadTypeRegistry.clientboundPlay().register(BeaconPayload.TYPE, BeaconPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GroupInfoPayload.TYPE, GroupInfoPayload.CODEC);
        // T12/N6/M7：S2C 公告接收（payload 注册归主入口；这里只挂客户端接收器）
        ClientPlayNetworking.registerGlobalReceiver(com.tsvoice.common.net.CapabilitiesPayloads.CapabilitiesPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        TsVoiceManager.onCapabilitiesAnnouncementFrame(payload.frame())));

        // S1.4/P6：TS 中继下行（主线程投递给 manager 的等待队列/传输）
        ClientPlayNetworking.registerGlobalReceiver(TsRelayPayloads.RelayOpenedPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        TsVoiceManager.onRelayOpenedFrame(payload.frame())));
        ClientPlayNetworking.registerGlobalReceiver(TsRelayPayloads.RelayDataS2cPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        TsVoiceManager.onRelayDownstreamFrame(payload.frame())));
        ClientPlayNetworking.registerGlobalReceiver(TsRelayPayloads.RelayClosedPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        TsVoiceManager.onRelayDownstreamFrame(payload.frame())));
        ClientPlayNetworking.registerGlobalReceiver(TsRelayPayloads.RelayErrorPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        TsVoiceManager.onRelayDownstreamFrame(payload.frame())));

        // 信标：新 endpoint 需要玩家显式授权；只有已记住的 endpoint 自动采用
        // T06/R09：issuer 信任变化立即刷新 Ghost 展示（降级不改稳定 UUID，不停音频）。
        ClientIdentityService.setIdentityChangeListener(() ->
                Minecraft.getInstance().execute(() -> {
                    try {
                        TsAudioInjector.syncAllGhostVisibility(TsVoiceManager.currentClient());
                    } catch (Exception e) {
                        LOGGER.debug("[teamspeak-svc] identity change refresh failed: {}", e.getMessage());
                    }
                }));
        TsVoiceManager.setUntrustedBeaconListener(payload ->
                                    Minecraft.getInstance().setScreenAndShow(new BeaconTrustScreen(payload)));
        // S0.4/R23：公告候选首次授权入口 —— 未预信任的公告候选弹授权界面
        //（/tsvoice trust-announce 也可随时手动进入）。setScreenAndShow 顶替当前
        // 屏是授权流程的预期行为：不弹窗则候选保持未授权（fail-closed），不会
        // 自动连接。
        TsVoiceManager.setPendingConsentListener(() ->
                Minecraft.getInstance().execute(() -> {
                    if (TsVoiceManager.hasPendingAnnouncementConsent()) {
                        Minecraft.getInstance().setScreenAndShow(new CapabilityConsentScreen());
                    }
                }));
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
            // T12/N6：进入世界即开始发现窗口；hello 只在当前连接支持该通道时发送
            //（服务端装了可选公告 mod 才有通道）。帧在发送前做上限校验。
            try {
                byte[] hello = TsVoiceManager.beginCapabilityDiscovery();
                if (hello != null && hello.length <= com.tsvoice.protocol.capability.CapabilityWire.MAX_FRAME_BYTES
                        && ClientPlayNetworking.canSend(
                        com.tsvoice.common.net.CapabilitiesPayloads.CapabilitiesHelloPayload.TYPE)) {
                    ClientPlayNetworking.send(new com.tsvoice.common.net.CapabilitiesPayloads
                            .CapabilitiesHelloPayload(hello));
                }
            } catch (Throwable t) {
                LOGGER.debug("[teamspeak-svc] capabilities hello failed: {}", t.toString());
            }
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
                        // S0.4/R23：公告首次授权入口（无旧 beacon 的纯公告服务器）
                        .then(literal("trust-announce").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> {
                                if (TsVoiceManager.pendingConsentCandidates().isEmpty()) {
                                    ctx.getSource().sendFeedback(Component.translatable("tsvoice.consent.none"));
                                } else {
                                    Minecraft.getInstance().setScreenAndShow(new CapabilityConsentScreen());
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
