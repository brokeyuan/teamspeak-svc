package com.svcb.tsbridge.net;

import com.svcb.tsbridge.TsBridgeMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务器信标与群组信息下发（teamspeak-svc 客户端 mod 的服务端配合组件）。
 *
 * <ul>
 *     <li><b>信标</b>：玩家进服即发送 {@link BeaconPayload}（publicTsHost 非空时），
 *         客户端免确认直接自动连接 TS；</li>
 *     <li><b>群组信息</b>：周期（2 秒）反射读取 SVC 服务端群组（名称/密码/类型）
 *         与成员归属，把 {@link GroupInfoPayload} <b>仅发给群组成员</b>——
 *         客户端用它把 SVC 群组（含密码、开放/普通/独立类型）复刻到 TS 子频道。
 *         密码明文绝不下发给非成员（与 SVC 自身安全模型同级）。</li>
 * </ul>
 *
 * <p>类型编码：typeOrd 0=OPEN, 1=NORMAL, 2=ISOLATED（与客户端约定一致）。
 * 反射失败仅禁用群组下发（信标不受影响）。</p>
 */
public final class BeaconSender {

    private static volatile ScheduledExecutorService executor;
    private static volatile boolean started = false;

    private static Method getGroupManager;
    private static Field groupsField;
    private static Method groupGetId;
    private static Method groupGetName;
    private static Method groupGetPassword;
    private static Method groupGetType;
    private static Object apiTypeOpen;
    private static Object apiTypeIsolated;
    private static volatile boolean reflectionReady = false;
    private static volatile boolean reflectionFailed = false;

    private BeaconSender() {
    }

    /** 在 mod 初始化时调用（注册 payload 类型与进服事件；幂等）。 */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(BeaconPayload.TYPE, BeaconPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GroupInfoPayload.TYPE, GroupInfoPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                sendBeacon(handler.getPlayer());
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Failed to send beacon: {}", e.getMessage());
            }
        });
    }

    /** 服务器启动后调用：启动群组信息周期下发（幂等）。 */
    public static synchronized void startGroupSync() {
        if (started) {
            return;
        }
        started = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TsBridge-GroupBeacon");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            var server = TsBridgeMod.mcServer;
            if (server != null) {
                // SVC state reflection and immutable snapshot publication run on the main thread.
                server.execute(BeaconSender::distributeGroupInfo);
            }
        }, 2, 2, TimeUnit.SECONDS);
        TsBridgeMod.LOGGER.info("[tsbridge] Group info beacon started (interval=2s)");
    }

    public static void shutdown() {
        ScheduledExecutorService e = executor;
        executor = null;
        started = false;
        if (e != null) {
            e.shutdownNow();
        }
    }

    /** 玩家进服：发送信标（publicTsHost 为空 = 未启用，静默跳过）。 */
    public static void sendBeacon(ServerPlayer player) {
        var config = TsBridgeMod.config;
        if (config == null || config.publicTsHost == null || config.publicTsHost.isBlank()) {
            return;
        }
        String channel = "group-sync".equalsIgnoreCase(config.mode)
                ? config.groupSyncParentChannel : config.bridgeChannelName;
        // backendMode：direct=客户端 mod 直连 TS（group-sync 形态）；
        // server-bridge=服务端桥负责语音（bridge 形态），客户端 mod 收到即休眠
        String backendMode = "group-sync".equalsIgnoreCase(config.mode) ? "direct" : "server-bridge";
        ServerPlayNetworking.send(player, new BeaconPayload(
                config.publicTsHost, config.tsVoicePort, channel == null ? "" : channel, backendMode,
                config.tcpTunnelPort));
        TsBridgeMod.LOGGER.debug("[tsbridge] Beacon sent to {}", player.getName().getString());
    }

    /** 周期：把每个（有成员的）群组的信息（含密码）发给其成员。 */
    private static void distributeGroupInfo() {
        try {
            if (TsBridgeMod.mcServer == null) {
                return;
            }
            if (!ensureReflection()) {
                com.svcb.tsbridge.svc.BridgeMembershipService.markUnknown();
                return;
            }
            Object server = reflectionServer();
            if (server == null) {
                com.svcb.tsbridge.svc.BridgeMembershipService.markUnknown();
                return;
            }
            Object groupManager = getGroupManager.invoke(server);
            if (groupManager == null) {
                com.svcb.tsbridge.svc.BridgeMembershipService.markUnknown();
                return;
            }
            @SuppressWarnings("unchecked")
            Map<UUID, Object> groups = (Map<UUID, Object>) groupsField.get(groupManager);
            if (groups == null || groups.isEmpty()) {
                com.svcb.tsbridge.svc.BridgeMembershipService.publish(Map.of());
                return;
            }

            // 成员归属：玩家 UUID → 群组 UUID（PlayerStateManager states 反射）
            Map<UUID, UUID> memberToGroup = readMemberGroups(server);
            com.svcb.tsbridge.svc.BridgeMembershipService.publish(memberToGroup);
            if (memberToGroup.isEmpty()) {
                return;
            }
            Map<UUID, List<UUID>> groupMembers = new HashMap<>();
            for (Map.Entry<UUID, UUID> e : memberToGroup.entrySet()) {
                groupMembers.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }

            for (Map.Entry<UUID, Object> g : groups.entrySet()) {
                List<UUID> members = groupMembers.get(g.getKey());
                if (members == null || members.isEmpty()) {
                    continue;
                }
                Object group = g.getValue();
                UUID id = (UUID) groupGetId.invoke(group);
                String name = (String) groupGetName.invoke(group);
                String password = (String) groupGetPassword.invoke(group);
                Object type = groupGetType.invoke(group);
                int typeOrd = type == apiTypeOpen ? 0 : type == apiTypeIsolated ? 2 : 1;
                GroupInfoPayload payload = new GroupInfoPayload(id, name, password, typeOrd);
                for (UUID member : members) {
                    ServerPlayer player = TsBridgeMod.mcServer.getPlayerList().getPlayer(member);
                    if (player != null) {
                        ServerPlayNetworking.send(player, payload);
                    }
                }
            }
        } catch (Throwable t) {
            com.svcb.tsbridge.svc.BridgeMembershipService.markUnknown();
            TsBridgeMod.LOGGER.debug("[tsbridge] Group info beacon failed: {}", t.toString());
        }
    }

    private static Object reflectionServer() {
        try {
            Class<?> voicechatClass = Class.forName("de.maxhenkel.voicechat.Voicechat");
            Object serverVoiceEvents = voicechatClass.getField("SERVER").get(null);
            return serverVoiceEvents.getClass().getMethod("getServer").invoke(serverVoiceEvents);
        } catch (Exception e) {
            return null;
        }
    }

    /** 玩家 → 所在群组（仅未断开且有群组的）。 */
    private static Map<UUID, UUID> readMemberGroups(Object server) throws Exception {
        Map<UUID, UUID> result = new HashMap<>();
        Object stateManager = server.getClass().getMethod("getPlayerStateManager").invoke(server);
        if (stateManager == null) {
            return result;
        }
        Method getStates = stateManager.getClass().getMethod("getStates");
        Method stateGetUuid = null;
        Method stateGetGroup = null;
        Method stateIsDisconnected = null;
        for (Object state : (Iterable<?>) getStates.invoke(stateManager)) {
            if (stateGetUuid == null) {
                stateGetUuid = state.getClass().getMethod("getUuid");
                stateGetGroup = state.getClass().getMethod("getGroup");
                stateIsDisconnected = state.getClass().getMethod("isDisconnected");
            }
            Object uuid = stateGetUuid.invoke(state);
            Object group = stateGetGroup.invoke(state);
            Object disconnected = stateIsDisconnected.invoke(state);
            if (uuid instanceof UUID u && group instanceof UUID gid
                    && !(disconnected instanceof Boolean b && b)) {
                result.put(u, gid);
            }
        }
        return result;
    }

    private static synchronized boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        try {
            Class<?> groupClass = Class.forName("de.maxhenkel.voicechat.voice.server.Group");
            groupGetId = groupClass.getMethod("getId");
            groupGetName = groupClass.getMethod("getName");
            groupGetPassword = groupClass.getMethod("getPassword");
            groupGetType = groupClass.getMethod("getType");
            Class<?> apiType = Class.forName("de.maxhenkel.voicechat.api.Group$Type");
            apiTypeOpen = apiType.getField("OPEN").get(null);
            apiTypeIsolated = apiType.getField("ISOLATED").get(null);

            Class<?> serverClass = Class.forName("de.maxhenkel.voicechat.voice.server.Server");
            getGroupManager = serverClass.getMethod("getGroupManager");
            Class<?> gmClass = Class.forName("de.maxhenkel.voicechat.voice.server.ServerGroupManager");
            groupsField = gmClass.getDeclaredField("groups");
            groupsField.setAccessible(true);

            reflectionReady = true;
            TsBridgeMod.LOGGER.info("[tsbridge] Group info reflection initialized");
            return true;
        } catch (Throwable t) {
            reflectionFailed = true;
            TsBridgeMod.LOGGER.warn("[tsbridge] Group info beacon disabled (reflection failed): {}", t.toString());
            return false;
        }
    }
}
