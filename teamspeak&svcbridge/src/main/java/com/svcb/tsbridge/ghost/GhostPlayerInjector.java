package com.svcb.tsbridge.ghost;

import com.svcb.tsbridge.TsBridgeMod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ghost Player 注入器。
 *
 * <p>通过反射构造 SVC 的真实网络包，把 TS 用户注册为 MC 客户端可见的虚拟玩家：</p>
 * <ul>
 *     <li>{@code de.maxhenkel.voicechat.voice.common.PlayerState}
 *         — 构造器 {@code (UUID uuid, String name, boolean disabled, boolean disconnected)}，
 *         {@code setGroup(UUID)} 把 Ghost 挂到桥接群组；</li>
 *     <li>{@code de.maxhenkel.voicechat.net.PlayerStatePacket} — 构造器 {@code (PlayerState)}；</li>
 *     <li>{@code de.maxhenkel.voicechat.net.RemovePlayerStatePacket} — 构造器 {@code (UUID)}；</li>
 *     <li>{@code de.maxhenkel.voicechat.net.NetManager#sendToClient(ServerPlayer, Packet)}
 *         — 静态发送方法，直接走 MC 自定义载荷通道。</li>
 * </ul>
 *
 * <p>说明：SVC 客户端的"正在说话"高亮由客户端根据收到的语音包发送者自动判定，
 * 无需（也无法）通过状态包控制 —— TS→MC 音频使用 Ghost 的 UUID 作为发送者
 * （见 {@link com.svcb.tsbridge.svc.McAudioInjector}），说话高亮随之自动生效。</p>
 *
 * <p>反射失败（SVC 内部类变更）时记录一次错误并整体禁用 Ghost 功能，不影响其余组件。</p>
 */
public final class GhostPlayerInjector {

    private GhostPlayerInjector() {
    }

    /** ghostUuid → 条目。 */
    private static final Map<UUID, GhostEntry> ghosts = new ConcurrentHashMap<>();

    /** 反射是否就绪。 */
    private static volatile boolean reflectionReady = false;
    /** 反射初始化失败原因（一次）。 */
    private static final AtomicBoolean reflectionErrorLogged = new AtomicBoolean(false);

    private static Constructor<?> playerStateCtor;
    private static Method playerStateSetGroup;
    private static Constructor<?> playerStatePacketCtor;
    private static Constructor<?> removeStatePacketCtor;
    private static Method netManagerSendToClient;
    private static Method serverGetPlayerStateManager;
    private static Method stateManagerGetStates;
    private static Method stateGetUuid;
    private static Method stateGetGroup;
    private static Method stateIsDisconnected;
    /** SVC 内部 voice server 实例（用于读取群组成员）。 */
    private static Object voicechatServer;

    /**
     * 由 TS uniqueId 派生 Ghost UUID。全 mod 统一使用本方法（修复 v2 中
     * "ts-" / "ts-ghost-" 前缀不一致的问题）。
     */
    public static UUID ghostUuidFor(String tsUniqueId) {
        return UUID.nameUUIDFromBytes(("ts-ghost-" + tsUniqueId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 注入（或刷新）一个 Ghost Player：向所有在线 MC 玩家广播其 PlayerState
     * （挂在桥接群组上）。
     *
     * @return 是否成功广播
     */
    public static synchronized boolean inject(UUID ghostUuid, String displayName, String tsUniqueId) {
        if (ghostUuid == null || displayName == null || tsUniqueId == null) {
            return false;
        }
        ghosts.put(ghostUuid, new GhostEntry(displayName, tsUniqueId));
        boolean ok = broadcastState(ghostUuid, displayName);
        if (ok) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Ghost Player injected: {} ({})", displayName, ghostUuid);
        }
        return ok;
    }

    /**
     * 移除指定 Ghost Player（广播 RemovePlayerStatePacket），
     * 同时释放该 TS 用户的音频注入资源（StaticAudioChannel / AudioPlayer / 解码器）。
     */
    public static synchronized void remove(UUID ghostUuid) {
        if (ghostUuid == null) {
            return;
        }
        GhostEntry entry = ghosts.remove(ghostUuid);
        if (entry != null) {
            broadcastRemoveState(ghostUuid);
            try {
                com.svcb.tsbridge.svc.McAudioInjector.stopTsAudio(ghostUuid);
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Failed to release TS audio for ghost {}: {}",
                        ghostUuid, e.getMessage());
            }
            TsBridgeMod.LOGGER.debug("[tsbridge] Ghost Player removed: {} ({})", entry.displayName, ghostUuid);
        }
    }

    /**
     * 返回当前 Ghost Player 数量。
     */
    public static int count() {
        return ghosts.size();
    }

    /**
     * 返回所有 Ghost（uuid → 显示名），用于 status 命令。
     */
    public static Map<UUID, String> getAll() {
        Map<UUID, String> result = new java.util.LinkedHashMap<>();
        ghosts.forEach((uuid, entry) -> result.put(uuid, entry.displayName));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 返回所有 Ghost 的 tsUniqueId 集合（用于对账）。
     */
    public static synchronized List<String> getAllTsUniqueIds() {
        List<String> ids = new ArrayList<>();
        ghosts.values().forEach(e -> ids.add(e.tsUniqueId));
        return ids;
    }

    /**
     * 新玩家接入 SVC 时，把当前所有 Ghost 状态补发给该玩家
     * （SVC 初始状态同步不包含 mod 注入的 Ghost）。
     */
    public static synchronized void sendAllStatesTo(Object mcServerPlayer) {
        if (mcServerPlayer == null || !ensureReflection()) {
            return;
        }
        for (Map.Entry<UUID, GhostEntry> e : ghosts.entrySet()) {
            try {
                Object packet = buildStatePacket(e.getKey(), e.getValue().displayName);
                if (packet != null) {
                    netManagerSendToClient.invoke(null, mcServerPlayer, packet);
                }
            } catch (Exception ex) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Failed to send ghost state to new player: {}", ex.getMessage());
            }
        }
    }

    /**
     * 关闭所有 Ghost 并清空状态。在 SERVER_STOPPING 中调用。
     */
    public static synchronized void shutdownAll() {
        for (UUID uuid : ghosts.keySet()) {
            broadcastRemoveState(uuid);
        }
        ghosts.clear();
        TsBridgeMod.LOGGER.info("[tsbridge] GhostPlayerInjector shut down (all ghosts removed)");
    }

    /** 成员列表缓存有效期（毫秒）：麦克风热路径每包都要查成员，反射遍历不能每帧都做。 */
    private static final long MEMBER_CACHE_MS = 1_000;
    private static volatile long memberCacheAt = 0;
    private static volatile List<UUID> memberCache = null;

    /**
     * Returns the last authoritative bridge-group snapshot. Reflection itself is
     * performed on the Minecraft main thread by {@code BeaconSender}; unknown state
     * preserves the last verified audience instead of expanding to everyone.
     */
    public static List<UUID> getBridgeGroupMemberUuids() {
        return com.svcb.tsbridge.svc.BridgeMembershipService
                .getBridgeGroupMembers(TsBridgeMod.bridgeGroupId);
    }

    // ---- 反射与广播 ----

    /**
     * 构造 PlayerStatePacket（挂桥接群组）。
     */
    private static Object buildStatePacket(UUID ghostUuid, String displayName) throws Exception {
        Object state = playerStateCtor.newInstance(ghostUuid, displayName, false, false);
        UUID bridgeGroupId = TsBridgeMod.bridgeGroupId;
        if (bridgeGroupId != null) {
            playerStateSetGroup.invoke(state, bridgeGroupId);
        }
        return playerStatePacketCtor.newInstance(state);
    }

    /**
     * 向所有在线 MC 玩家广播 Ghost 状态包（单个玩家失败不影响其余玩家）。
     */
    private static boolean broadcastState(UUID ghostUuid, String displayName) {
        if (!ensureReflection()) {
            return false;
        }
        try {
            Object packet = buildStatePacket(ghostUuid, displayName);
            int sent = 0;
            for (Object player : onlinePlayers()) {
                try {
                    netManagerSendToClient.invoke(null, player, packet);
                    sent++;
                } catch (Exception e) {
                    TsBridgeMod.LOGGER.debug("[tsbridge] Failed to send ghost state to {}: {}",
                            player, e.getMessage());
                }
            }
            return sent > 0 || onlinePlayers().isEmpty();
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to broadcast Ghost Player state for {}: {}",
                    ghostUuid, e.getMessage());
            return false;
        }
    }

    /**
     * 向所有在线 MC 玩家广播 Ghost 移除包（单个玩家失败不影响其余玩家）。
     */
    private static void broadcastRemoveState(UUID ghostUuid) {
        if (!ensureReflection()) {
            return;
        }
        try {
            Object packet = removeStatePacketCtor.newInstance(ghostUuid);
            for (Object player : onlinePlayers()) {
                try {
                    netManagerSendToClient.invoke(null, player, packet);
                } catch (Exception e) {
                    TsBridgeMod.LOGGER.debug("[tsbridge] Failed to send ghost removal to {}: {}",
                            player, e.getMessage());
                }
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Failed to broadcast Ghost Player removal for {}: {}",
                    ghostUuid, e.getMessage());
        }
    }

    /**
     * 获取当前在线 MC 玩家列表（net.minecraft.server.level.ServerPlayer）。
     */
    private static List<Object> onlinePlayers() {
        List<Object> players = new ArrayList<>();
        var server = TsBridgeMod.mcServer;
        if (server != null) {
            players.addAll(server.getPlayerList().getPlayers());
        }
        return players;
    }

    /**
     * 惰性初始化反射缓存（双重检查锁）。失败时记录一次错误并返回 false。
     */
    private static boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        synchronized (GhostPlayerInjector.class) {
            if (reflectionReady) {
                return true;
            }
            try {
                // 1. SVC 内部 voice server 实例：Voicechat.SERVER.getServer()
                Class<?> voicechatClass = Class.forName("de.maxhenkel.voicechat.Voicechat");
                Field serverEventsField = voicechatClass.getField("SERVER");
                Object serverVoiceEvents = serverEventsField.get(null);
                if (serverVoiceEvents == null) {
                    throw new IllegalStateException("Voicechat.SERVER not initialized (voice chat server not started)");
                }
                voicechatServer = serverVoiceEvents.getClass().getMethod("getServer").invoke(serverVoiceEvents);

                // 2. PlayerState (UUID, String, boolean disabled, boolean disconnected)
                Class<?> playerStateClass = Class.forName("de.maxhenkel.voicechat.voice.common.PlayerState");
                playerStateCtor = playerStateClass.getConstructor(
                        UUID.class, String.class, boolean.class, boolean.class);
                playerStateSetGroup = playerStateClass.getMethod("setGroup", UUID.class);
                stateGetUuid = playerStateClass.getMethod("getUuid");
                stateGetGroup = playerStateClass.getMethod("getGroup");
                stateIsDisconnected = playerStateClass.getMethod("isDisconnected");

                // 3. net 包中的状态包
                Class<?> statePacketClass = Class.forName("de.maxhenkel.voicechat.net.PlayerStatePacket");
                playerStatePacketCtor = statePacketClass.getConstructor(playerStateClass);
                Class<?> removePacketClass = Class.forName("de.maxhenkel.voicechat.net.RemovePlayerStatePacket");
                removeStatePacketCtor = removePacketClass.getConstructor(UUID.class);

                // 4. NetManager.sendToClient(ServerPlayer, Packet)
                Class<?> netManagerClass = Class.forName("de.maxhenkel.voicechat.net.NetManager");
                netManagerSendToClient = netManagerClass.getMethod("sendToClient",
                        Class.forName("net.minecraft.server.level.ServerPlayer"),
                        Class.forName("de.maxhenkel.voicechat.net.Packet"));

                // 5. Server.getPlayerStateManager().getStates()（群组成员查询用）
                if (voicechatServer != null) {
                    serverGetPlayerStateManager = voicechatServer.getClass()
                            .getMethod("getPlayerStateManager");
                    Class<?> stateManagerClass = Class.forName(
                            "de.maxhenkel.voicechat.voice.server.PlayerStateManager");
                    stateManagerGetStates = stateManagerClass.getMethod("getStates");
                }

                reflectionReady = true;
                TsBridgeMod.LOGGER.info("[tsbridge] Ghost Player reflection initialized successfully");
                return true;
            } catch (Exception e) {
                if (reflectionErrorLogged.compareAndSet(false, true)) {
                    TsBridgeMod.LOGGER.error("[tsbridge] Ghost Player feature disabled - SVC internal classes "
                            + "not found or changed: {}", e.toString());
                }
                return false;
            }
        }
    }

    private record GhostEntry(String displayName, String tsUniqueId) {
    }
}
