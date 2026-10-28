package com.tsvoice.client;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 Ghost 玩家状态维护：把 TS 用户以 {@code [TS] 昵称} 注册进 SVC 客户端的
 * {@code ClientPlayerStateManager.states}，使其出现在 SVC 玩家界面；
 * 说话指示经公共 TalkCache（{@code ClientVoicechat.getTalkCache().updateTalking}）。
 *
 * <p>线程约束：SVC 的 {@code states} 是普通 HashMap（由 SVC 网络线程写、主线程读），
 * 因此本类的所有反射<b>写操作</b>都提交到客户端主线程；跨线程只读
 * {@link #knownStateIds} 并发快照（每 tick 更新）。</p>
 *
 * <p>反射目标（MC 26.x 未混淆，SVC 2.6.25 实测）：
 * {@code de.maxhenkel.voicechat.voice.client.ClientPlayerStateManager.states}
 * （Map&lt;UUID, PlayerState&gt;）、{@code de.maxhenkel.voicechat.voice.common.PlayerState(UUID,String,boolean,boolean)}。
 * 反射失败仅禁用界面可见性，不影响语音。</p>
 */
public final class ClientStateReflector {

    private static volatile boolean reflectionReady = false;
    private static boolean reflectionFailed = false;
    private static Constructor<?> playerStateCtor;
    private static Field statesField;
    private static Method setGroupMethod;

    /** 当前活跃的 Ghost（ghostUuid → 显示名）。 */
    private static final Map<UUID, String> activeGhosts = new ConcurrentHashMap<>();
    /** 只拥有自己实际放入 states 的对象；原生状态替换它后即失去所有权。 */
    private static final Map<UUID, Object> ownedStates = new ConcurrentHashMap<>();
    /** TS 播放通道所有权：旧流清理只允许关闭自己注册的通道对象（防杀新流）。 */
    private static final Map<UUID, Object> ownedChannels = new ConcurrentHashMap<>();
    /** SVC states 键快照（主线程每 tick 原子替换；供 TS 网络线程安全判定同服玩家）。 */
    private static volatile Set<UUID> knownStateIds = Set.of();

    private ClientStateReflector() {
    }

    public static Map<UUID, String> activeGhosts() {
        return activeGhosts;
    }

    private static void onClientThread(Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            action.run();
        } else {
            mc.execute(action);
        }
    }

    /** 客户端主线程每 tick 调用：刷新 states 键快照。 */
    public static void tickSnapshot() {
        if (!ensureReflection()) {
            return;
        }
        try {
            Object manager = statesManager();
            if (manager == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<UUID, Object> states = (Map<UUID, Object>) statesField.get(manager);
            if (states == null) {
                return;
            }
            Set<UUID> next = new java.util.HashSet<>(states.keySet());
            ownedStates.forEach((id, owned) -> {
                if (states.get(id) == owned) {
                    next.remove(id);
                }
            });
            knownStateIds = Set.copyOf(next);
        } catch (Throwable e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] state snapshot failed: {}", e.getMessage());
        }
    }

    private static synchronized boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        try {
            Class<?> stateClass = Class.forName("de.maxhenkel.voicechat.voice.common.PlayerState");
            playerStateCtor = stateClass.getConstructor(UUID.class, String.class, boolean.class, boolean.class);
            setGroupMethod = stateClass.getMethod("setGroup", UUID.class);
            Class<?> managerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientPlayerStateManager");
            statesField = managerClass.getDeclaredField("states");
            statesField.setAccessible(true);
            reflectionReady = true;
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Ghost reflection initialized");
            return true;
        } catch (Throwable e) {
            reflectionFailed = true;
            TeamspeakSvcClient.LOGGER.error("[teamspeak-svc] Ghost UI disabled (reflection failed): {}", e.toString());
            return false;
        }
    }

    private static Object statesManager() {
        try {
            Class<?> clientManagerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
            return clientManagerClass.getMethod("getPlayerStateManager").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 注入/刷新本地 Ghost 状态（groupOverride=null 时跟随本地玩家当前群组）。 */
    public static void injectGhost(UUID ghostUuid, String displayName, VoicechatClientApi api,
                                   UUID groupOverride) {
        activeGhosts.put(ghostUuid, displayName);
        if (!ensureReflection()) {
            return;
        }
        onClientThread(() -> injectGhostOnClientThread(ghostUuid, displayName, api, groupOverride));
    }

    private static void injectGhostOnClientThread(UUID ghostUuid, String displayName, VoicechatClientApi api,
                                                  UUID groupOverride) {
        try {
            if (!displayName.equals(activeGhosts.get(ghostUuid))) {
                return; // 已被移除或更新的排队操作不能重新复活旧状态。
            }
            Object manager = statesManager();
            if (manager == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<UUID, Object> states = (Map<UUID, Object>) statesField.get(manager);
            if (states == null) {
                return;
            }
            Object existing = states.get(ghostUuid);
            if (existing != null && existing != ownedStates.get(ghostUuid)) {
                ownedStates.remove(ghostUuid);
                activeGhosts.remove(ghostUuid);
                return; // SVC 原生玩家状态始终优先。
            }
            Object state = playerStateCtor.newInstance(ghostUuid, displayName, false, false);
            // Ghost 群组归属：优先显式指定（跨群组显示），否则跟随本地玩家当前群组
            UUID groupId = groupOverride;
            if (groupId == null) {
                try {
                    de.maxhenkel.voicechat.api.Group group = api.getGroup();
                    if (group != null) {
                        groupId = group.getId();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (groupId != null && setGroupMethod != null) {
                setGroupMethod.invoke(state, groupId);
            }
            states.put(ghostUuid, state);
            ownedStates.put(ghostUuid, state);
        } catch (Throwable e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] injectGhost failed: {}", e.getMessage());
        }
    }

    /**
     * Closes the SVC {@code AudioChannel} owned by a TS Ghost stream. The caller
     * must pass the channel object it registered ({@link #registerOwnedChannel});
     * the close only takes effect while that exact object is still the current
     * channel, so a stale cleanup can never kill a newer stream's channel. This
     * is intentionally separate from PlayerState removal: SVC's own close helper
     * only removes its map entry, leaving decoder/jitter/speaker threads alive.
     */
    public static void closeAudioChannel(UUID channelId, Object expectedOwned) {
        if (channelId == null || expectedOwned == null) {
            return;
        }
        onClientThread(() -> {
            try {
                Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                        .getMethod("getClient").invoke(null);
                if (client == null) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<UUID, Object> channels = (Map<UUID, Object>) client.getClass()
                        .getMethod("getAudioChannels").invoke(client);
                if (channels == null) {
                    return;
                }
                synchronized (channels) {
                    if (channels.remove(channelId, expectedOwned)) {
                        expectedOwned.getClass().getMethod("closeAndKill").invoke(expectedOwned);
                        ownedChannels.remove(channelId, expectedOwned);
                    }
                }
            } catch (Throwable e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] close SVC audio channel failed: {}", e.toString());
            }
        });
    }

    /**
     * T07/R10：有界后台退役 —— 在独立守护线程等待客户端线程完成通道移除与关闭，
     * 绝不让调用线程（MC 主线程/UI）持 channels 锁等待 closeAndKill。超时记录失败
     * 并保留通道交给 SVC 生命周期；registry 的 exact-match 守卫禁止把旧活动解码器
     * 重交给新流。
     */
    public static void retireAudioChannel(UUID channelId, Object expectedOwned) {
        if (channelId == null || expectedOwned == null) {
            return;
        }
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        onClientThread(() -> {
            try {
                Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                        .getMethod("getClient").invoke(null);
                if (client == null) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<UUID, Object> channels = (Map<UUID, Object>) client.getClass()
                        .getMethod("getAudioChannels").invoke(client);
                if (channels == null) {
                    return;
                }
                synchronized (channels) {
                    if (channels.remove(channelId, expectedOwned)) {
                        expectedOwned.getClass().getMethod("closeAndKill").invoke(expectedOwned);
                        ownedChannels.remove(channelId, expectedOwned);
                    }
                }
            } catch (Throwable e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] retire SVC audio channel failed: {}", e.toString());
            } finally {
                done.complete(null);
            }
        });
        RETIRE_WATCHDOG.schedule(() -> {
            if (!done.isDone()) {
                done.complete(null);
                TeamspeakSvcClient.LOGGER.warn(
                        "[teamspeak-svc] audio channel retire exceeded 5s (channel left to SVC lifecycle): {}", channelId);
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
        RETIRE_EXECUTOR.execute(() -> {
            try {
                done.get(6, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] retire wait failed: {}", e.getMessage());
            }
        });
    }

    private static final java.util.concurrent.ScheduledExecutorService RETIRE_WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "teamspeak-svc-retire-watchdog");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.concurrent.ExecutorService RETIRE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "teamspeak-svc-retire");
                t.setDaemon(true);
                return t;
            });

    /** Registers the channel object a TS stream owns (snapshot after first injection). */
    public static void registerOwnedChannel(UUID channelId, Object channel) {
        if (channelId != null && channel != null) {
            ownedChannels.put(channelId, channel);
        }
    }

    /** Read-only snapshot of the SVC audio channel currently registered for a UUID. */
    public static Object ownedAudioChannel(UUID channelId) {
        try {
            Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                    .getMethod("getClient").invoke(null);
            if (client == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<UUID, Object> channels = (Map<UUID, Object>) client.getClass()
                    .getMethod("getAudioChannels").invoke(client);
            synchronized (channels) {
                return channels.get(channelId);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** 移除本地 Ghost 状态。 */
    public static void removeGhost(UUID ghostUuid) {
        activeGhosts.remove(ghostUuid);
        if (!ensureReflection()) {
            return;
        }
        onClientThread(() -> {
            try {
                Object manager = statesManager();
                if (manager == null) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<UUID, Object> states = (Map<UUID, Object>) statesField.get(manager);
                Object owned = ownedStates.remove(ghostUuid);
                if (states != null && owned != null && states.get(ghostUuid) == owned) {
                    states.remove(ghostUuid, owned);
                }
            } catch (Throwable e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] removeGhost failed: {}", e.getMessage());
            }
        });
    }

    /** 群组变化后刷新所有 Ghost 的群组归属。 */
    public static void refreshGroups(VoicechatClientApi api, de.maxhenkel.voicechat.api.Group group) {
        if (!ensureReflection() || activeGhosts.isEmpty()) {
            return;
        }
        onClientThread(() -> {
            try {
                Object manager = statesManager();
                if (manager == null) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<UUID, Object> states = (Map<UUID, Object>) statesField.get(manager);
                if (states == null) {
                    return;
                }
                for (UUID ghostUuid : activeGhosts.keySet()) {
                    Object state = states.get(ghostUuid);
                    if (state != null && state == ownedStates.get(ghostUuid) && setGroupMethod != null) {
                        setGroupMethod.invoke(state, group != null ? group.getId() : null);
                    } else if (state != null) {
                        ownedStates.remove(ghostUuid);
                        activeGhosts.remove(ghostUuid);
                    }
                }
            } catch (Throwable e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] refreshGroups failed: {}", e.getMessage());
            }
        });
    }

    /** 说话指示（公共 TalkCache；失败静默）。 */
    public static void updateTalking(UUID ghostUuid) {
        onClientThread(() -> {
            try {
                Class<?> clientManagerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
                Object client = clientManagerClass.getMethod("getClient").invoke(null);
                if (client == null) {
                    return;
                }
                Object talkCache = client.getClass().getMethod("getTalkCache").invoke(client);
                if (talkCache != null) {
                    talkCache.getClass().getMethod("updateTalking", UUID.class, boolean.class)
                            .invoke(talkCache, ghostUuid, false);
                }
            } catch (Throwable ignored) {
                // 说话指示为尽力而为
            }
        });
    }

    /**
     * 客户端已知 SVC 群组表（小写群组名 → 群组 UUID）。
     * 数据来自 SVC 客户端 ClientGroupManager（群组列表界面的同一数据源）。
     */
    public static java.util.Map<String, UUID> clientGroupsByName() {
        java.util.Map<String, UUID> out = new java.util.HashMap<>();
        try {
            Class<?> clientManagerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
            Object groupManager = clientManagerClass.getMethod("getGroupManager").invoke(null);
            if (groupManager == null) {
                return out;
            }
            Object groups = groupManager.getClass().getMethod("getGroups").invoke(groupManager);
            Method getId = null;
            Method getName = null;
            for (Object g : (Iterable<?>) groups) {
                if (getId == null) {
                    getId = g.getClass().getMethod("getId");
                    getName = g.getClass().getMethod("getName");
                }
                Object id = getId.invoke(g);
                Object name = getName.invoke(g);
                if (id instanceof UUID u && name instanceof String n && !n.isBlank()) {
                    out.put(n.toLowerCase(java.util.Locale.ROOT), u);
                }
            }
        } catch (Throwable e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] clientGroupsByName failed: {}", e.toString());
        }
        return out;
    }

    /** 本服 PlayerStateManager 是否已有该玩家（同服 mod 玩家判定；读并发快照）。 */
    public static boolean hasLocalState(UUID uuid) {
        return knownStateIds.contains(uuid) || OnlinePlayers.isOnline(uuid);
    }

    /**
     * 补 USERNAME_CACHE（uuid→name）——SVC 的 单玩家音量/静音/名字显示
     * 以 USERNAME_CACHE.has(uuid) 为前提（AudioChannel.writeToSpeaker），
     * 反射注入的状态不会自动填它，必须显式补。
     */
    public static void updateUsernameCache(UUID uuid, String name) {
        if (name == null) {
            return;
        }
        onClientThread(() -> {
            try {
                Class<?> clientClass = Class.forName("de.maxhenkel.voicechat.VoicechatClient");
                Object cache = clientClass.getField("USERNAME_CACHE").get(null);
                if (cache != null) {
                    cache.getClass().getMethod("updateUsernameAndSave", UUID.class, String.class)
                            .invoke(cache, uuid, name);
                }
            } catch (Throwable e) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] username cache update failed: {}", e.toString());
            }
        });
    }

}
