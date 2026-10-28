package com.tsvoice.server;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.network.chat.Component;

/**
 * 服务端管理诊断（M7-T14.5）：/tsvoice-server status|reload。
 * 仅控制台或 OP（权限等级 2）可执行；只报告真实存在的功能与状态，
 * 不输出占位能力，不输出任何秘密。
 */
public final class ServerCommands {

    private ServerCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("tsvoice-server")
                        // 26.3 权限模型：控制台(ALL) 或 OP 等级≥2（MODERATORS）
                        .requires(source -> {
                            PermissionSet set = source.permissions();
                            if (set == LevelBasedPermissionSet.ALL) {
                                return true;
                            }
                            return set.hasPermission(new Permission.HasCommandLevel(PermissionLevel.MODERATORS));
                        })
                        .then(Commands.literal("status").executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            var runtime = com.tsvoice.TeamspeakSvcMod.runtimeOf(source.getServer());
                            source.sendSuccess(() -> Component.literal(runtime == null
                                    ? "teamspeak-svc server runtime: not started"
                                    : describe(runtime)), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("reload").executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            var runtime = com.tsvoice.TeamspeakSvcMod.runtimeOf(source.getServer());
                            if (runtime == null) {
                                source.sendFailure(Component.literal(
                                        "teamspeak-svc server runtime: not started"));
                            } else {
                                var result = runtime.reload();
                                source.sendSuccess(() -> Component.literal(
                                        "reload applied=" + result.applied() + " — " + result.reason()), false);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    private static String describe(com.tsvoice.server.ServerRuntime runtime) {
        var status = runtime.status();
        return "teamspeak-svc server runtime: state=" + status.state()
                + " targets=" + status.configuredTargets()
                + " config=" + status.configFile()
                + (status.reason() == null || status.reason().isEmpty()
                ? "" : " — " + status.reason());
    }
}
