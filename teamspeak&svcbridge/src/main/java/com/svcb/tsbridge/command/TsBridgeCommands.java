package com.svcb.tsbridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.audio.McAudioMixer;
import com.svcb.tsbridge.config.BridgeConfig;
import com.svcb.tsbridge.config.PairingStore;
import com.svcb.tsbridge.ghost.GhostPlayerInjector;
import com.svcb.tsbridge.transport.McToTsForwarder;
import com.svcb.tsbridge.transport.TsAudioTransport;
import com.svcb.tsbridge.ts.TsQueryClient;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * /tsbridge 命令树注册。
 *
 * <p>命令：</p>
 * <ul>
 *     <li>{@code /tsbridge pair} - 生成配对码并显示给玩家（任何玩家可执行）</li>
 *     <li>{@code /tsbridge unpair} - 解除自身配对（任何玩家可执行）</li>
 *     <li>{@code /tsbridge status} - 显示桥接器状态（任何玩家可执行）</li>
 *     <li>{@code /tsbridge reload} - 热重载配置（需要 tsbridge.admin 权限）</li>
 *     <li>{@code /tsbridge link <tsChannel>} - 更新桥接频道名（需要 tsbridge.admin 权限）</li>
 * </ul>
 *
 * <p>权限通过 fabric-permissions-api-v0 校验：admin 命令在 {@code .requires} 节点中
 * 调用 {@link Permissions#check(CommandSourceStack, String)}。</p>
 */
public final class TsBridgeCommands {

    private static final String ADMIN_PERMISSION = "tsbridge.admin";

    private TsBridgeCommands() {
    }

    /**
     * 注册 /tsbridge 命令树到 dispatcher。
     * 在 {@code CommandRegistrationCallback.EVENT} 中调用。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                LiteralArgumentBuilder.<CommandSourceStack>literal("tsbridge")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("pair")
                                .executes(TsBridgeCommands::doPair))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("unpair")
                                .executes(TsBridgeCommands::doUnpair))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                                .executes(TsBridgeCommands::doStatus))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                                .requires(src -> Permissions.check(src, ADMIN_PERMISSION))
                                .executes(TsBridgeCommands::doReload))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("link")
                                .requires(src -> Permissions.check(src, ADMIN_PERMISSION))
                                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("tsChannel", StringArgumentType.string())
                                        .executes(TsBridgeCommands::doLink)))
        );
    }

    /**
     * /tsbridge pair：MC 玩家生成一个配对码并显示给玩家。
     *
     * <p>生成的配对码由 {@link com.svcb.tsbridge.pairing.PairingManager#createPairing}
     * 写入 pending 表；TS 用户在桥接频道中发送 {@code !pair <code>}
     * 即可完成 MC↔TS 绑定。</p>
     */
    private static int doPair(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID uuid = player.getUUID();
        String name = player.getName().getString();

        if (TsBridgeMod.pairingManager == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bridge is not initialized yet, please try again later."));
            return 0;
        }

        String code;
        try {
            code = TsBridgeMod.pairingManager.createPairing(uuid, name);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to create pairing for {}: {}",
                    uuid, e.getMessage(), e);
            ctx.getSource().sendFailure(Component.literal(
                    "Failed to create pairing code: " + e.getMessage()));
            return 0;
        }

        int expiryMinutes = 5;
        if (TsBridgeMod.config != null) {
            expiryMinutes = Math.max(1, TsBridgeMod.config.pairCodeExpiryMinutes);
        }

        final String message = "Your pairing code is " + code
                + ". Tell the TS user to type `!pair " + code
                + "` in the bridge channel. Code expires in " + expiryMinutes + " minutes.";
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /**
     * /tsbridge unpair：解除自身配对。同时移除已绑定记录与 pending 配对。
     */
    private static int doUnpair(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID uuid = player.getUUID();

        if (TsBridgeMod.pairings == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bridge is not initialized yet, please try again later."));
            return 0;
        }

        boolean removed = TsBridgeMod.pairings.unbind(uuid);
        if (TsBridgeMod.pairingManager != null) {
            TsBridgeMod.pairingManager.cancelPairing(uuid);
        }

        if (removed) {
            ctx.getSource().sendSuccess(() -> Component.literal("Unpaired successfully."), false);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("You are not paired."));
            return 0;
        }
    }

    /**
     * /tsbridge status：显示桥接器各组件状态。任何玩家可执行。
     *
     * <p>对每个组件做 null 检查避免 NPE。ServerQuery 状态直接读取
     * {@link TsQueryClient#isConnected()}（修复 v2 用 transport 状态推断导致
     * 显示错误的问题）。</p>
     */
    private static int doStatus(CommandContext<CommandSourceStack> ctx) {
        TsQueryClient queryClient = TsBridgeMod.tsQueryClient;
        boolean queryOk = queryClient != null && queryClient.isConnected();

        TsAudioTransport transport = TsBridgeMod.tsAudioTransport;
        boolean transportStarted = transport != null && transport.isStarted();
        String transportType = transport != null ? transport.getClass().getSimpleName() : "none";

        PairingStore pairings = TsBridgeMod.pairings;
        int pairedCount = pairings != null ? pairings.size() : 0;

        // v2: mixer 状态
        McAudioMixer mixer = TsBridgeMod.mixer;
        boolean mixerRunning = mixer != null && mixer.isRunning();
        int activeSenders = mixer != null ? mixer.getActiveSenderCount() : 0;
        long mixDropped = mixer != null ? mixer.getDroppedCount() : 0L;

        McToTsForwarder forwarder = TsBridgeMod.forwarder;
        int queueSize = forwarder != null ? forwarder.getQueueSize() : 0;
        long forwarderDropped = forwarder != null ? forwarder.getDroppedCount() : 0L;

        // v2: Ghost Players
        int ghostCount = GhostPlayerInjector.count();
        Map<UUID, String> ghosts = GhostPlayerInjector.getAll();
        StringBuilder ghostList = new StringBuilder();
        for (String name : ghosts.values()) {
            if (ghostList.length() > 0) ghostList.append(", ");
            ghostList.append(name);
        }

        BridgeConfig config = TsBridgeMod.config;
        String tsHostPort = config != null
                ? config.tsHost + ":" + config.tsQueryPort
                : "(not loaded)";
        String bridgeChannel = config != null ? config.bridgeChannelName : "(not loaded)";

        // v2.1: 永久桥接群组状态
        String groupState = TsBridgeMod.bridgeGroup != null
                ? "'" + TsBridgeMod.bridgeGroup.getName() + "' (id=" + TsBridgeMod.bridgeGroupId + ")"
                : "inactive";

        final String message = "=== TSBridge v2 Status ===\n"
                + "ServerQuery: " + (queryOk ? "connected" : "disconnected") + "\n"
                + "Audio transport: " + transportType + " (" + (transportStarted ? "started" : "stopped") + ")\n"
                + "Mode: " + (TsBridgeMod.perPlayerMode ? "1:1 per-player TS clients" : "single-bot mix") + "\n"
                + "Per-player clients: "
                + (TsBridgeMod.perPlayerMode ? com.svcb.tsbridge.transport.PlayerVoiceClients.connectedCount()
                        + " online" + (com.svcb.tsbridge.transport.PlayerVoiceClients.describe().isEmpty()
                        ? "" : " " + com.svcb.tsbridge.transport.PlayerVoiceClients.describe()) : "n/a") + "\n"
                + "Bridge group: " + groupState + "\n"
                + "Mixer: " + (mixerRunning ? "running (" + activeSenders + " senders)" : "stopped") + "\n"
                + "Mixer dropped: " + mixDropped + " frames\n"
                + "Forwarder queue: " + queueSize + " (dropped " + forwarderDropped + ")\n"
                + "Paired users: " + pairedCount + "\n"
                + "Ghost Players: " + ghostCount + (ghostList.length() > 0 ? " [" + ghostList + "]" : "") + "\n"
                + "Bridge channel: " + bridgeChannel + "\n"
                + "TS host:port: " + tsHostPort;
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /**
     * /tsbridge reload：热重载配置（需要 tsbridge.admin 权限）。
     *
     * <p>仅热更配对/静音策略；连接、频道、模式或隧道配置改变时整次拒绝，
     * 返回明确的重启要求，避免运行组件与公布配置不一致。</p>
     */
    private static int doReload(CommandContext<CommandSourceStack> ctx) {
        BridgeConfig config = TsBridgeMod.config;
        if (config == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Config not loaded yet, nothing to reload."));
            return 0;
        }
        try {
            config.reload();
            ctx.getSource().sendSuccess(() -> Component.literal("Config reloaded."), false);
            TsBridgeMod.LOGGER.info("[tsbridge] Config reloaded by admin");
            return 1;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Reload failed: {}", e.getMessage(), e);
            ctx.getSource().sendFailure(Component.literal(
                    "Failed to reload config: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * /tsbridge link &lt;tsChannel&gt;：更新桥接频道名（需要 tsbridge.admin 权限）。
     *
     * <p>修改 {@link BridgeConfig#bridgeChannelName} 并保存后，立即重新
     * ensure 桥接频道（修复 v2 只改配置、运行时永远不生效的问题）。</p>
     */
    private static int doLink(CommandContext<CommandSourceStack> ctx) {
        String channel = StringArgumentType.getString(ctx, "tsChannel");
        if (channel == null || channel.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Channel name must not be empty."));
            return 0;
        }
        BridgeConfig config = TsBridgeMod.config;
        if (config == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Config not loaded yet, cannot update bridge channel."));
            return 0;
        }
        config.bridgeChannelName = channel;
        try {
            config.save();
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to save config after link: {}",
                    e.getMessage(), e);
            ctx.getSource().sendFailure(Component.literal(
                    "Failed to save config: " + e.getMessage()));
            return 0;
        }

        // 立即生效：重新查找/创建桥接频道
        boolean ensured = false;
        if (TsBridgeMod.tsQueryClient != null && TsBridgeMod.tsQueryClient.isConnected()
                && TsBridgeMod.bridgeChannelManager != null) {
            ensured = TsBridgeMod.bridgeChannelManager.ensureBridgeChannel(config);
            if (ensured && TsBridgeMod.presenceUpdater != null) {
                TsBridgeMod.presenceUpdater.updateNow();
            }
        }

        final String msg = ensured
                ? "Bridge channel switched to '" + channel + "' (id="
                        + (TsBridgeMod.bridgeChannelManager != null
                                ? TsBridgeMod.bridgeChannelManager.getBridgeChannelId() : -1) + ")."
                : "Bridge channel name saved as '" + channel
                        + "', but could not ensure it right now (ServerQuery not connected). It will be "
                        + "ensured after the query reconnects.";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        TsBridgeMod.LOGGER.info("[tsbridge] Bridge channel name updated to '{}' by admin (ensured={})",
                channel, ensured);
        return 1;
    }
}
