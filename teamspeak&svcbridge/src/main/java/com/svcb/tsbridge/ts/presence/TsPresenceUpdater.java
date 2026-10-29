package com.svcb.tsbridge.ts.presence;

import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.config.BridgeConfig;
import com.svcb.tsbridge.ghost.GhostPlayerInjector;
import com.svcb.tsbridge.svc.OnlinePlayerRegistry;
import com.svcb.tsbridge.transport.Ts3NativeTransport;
import com.svcb.tsbridge.ts.BridgeChannelManager;
import com.svcb.tsbridge.ts.TsQueryClient;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * MC→TS 存在感同步器。
 *
 * <p>向 TS 侧展示当前桥接群组内的 MC 玩家：</p>
 * <ul>
 *     <li>Bot 昵称：{@code MCBridge (N online)} —— 同时更新 ServerQuery 客户端
 *         与原生语音 bot（TS 用户在频道里看到的是后者）；</li>
 *     <li>频道描述：桥接群组成员名列表（每行一个，最多 16 人）；</li>
 *     <li>3 秒 debounce：多次变更合并为一次更新。</li>
 * </ul>
 *
 * <p>成员列表以 SVC 桥接群组的真实成员为准（每 tick 重算，而非增量维护），
 * 群组成员变化、服务器重启、事件丢失都能自愈。ServerQuery 不可用时静默跳过。</p>
 */
public class TsPresenceUpdater {

    private final TsQueryClient queryClient;
    private final BridgeChannelManager channelManager;
    private final BridgeConfig config;

    /** 单线程调度器：用于 debounce */
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "TsBridge-PresenceDebounce");
        t.setDaemon(true);
        return t;
    });

    /** 待执行的 debounce 任务 */
    private volatile ScheduledFuture<?> pendingUpdate;

    private static final long DEBOUNCE_MS = 3_000L;
    private static final int MAX_DESC_LINES = 16;

    public TsPresenceUpdater(TsQueryClient queryClient, BridgeChannelManager channelManager, BridgeConfig config) {
        this.queryClient = queryClient;
        this.channelManager = channelManager;
        this.config = config;
    }

    /**
     * MC 玩家连接 SVC 时调用（触发一次防抖更新，成员在更新时重算）。
     */
    public void onPlayerJoined(String playerName) {
        if (playerName == null) return;
        scheduleUpdate();
    }

    /**
     * MC 玩家断开 SVC 时调用（触发一次防抖更新，成员在更新时重算）。
     */
    public void onPlayerLeft(String playerName) {
        if (playerName == null) return;
        scheduleUpdate();
    }

    /**
     * 执行昵称和描述的立即更新（跳过 debounce）。
     * 在 SERVER_STARTED 初始化后首次调用。
     */
    public void updateNow() {
        doUpdate();
    }

    /**
     * 关闭调度器。在 SERVER_STOPPING 中调用。
     */
    public void shutdown() {
        ScheduledFuture<?> pending = pendingUpdate;
        if (pending != null) {
            pending.cancel(false);
        }
        scheduler.shutdownNow();
        TsBridgeMod.LOGGER.debug("[tsbridge] TsPresenceUpdater shut down");
    }

    // ---- 内部逻辑 ----

    private void scheduleUpdate() {
        ScheduledFuture<?> pending = pendingUpdate;
        if (pending != null) {
            pending.cancel(false);
        }
        pendingUpdate = scheduler.schedule(this::doUpdate, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void doUpdate() {
        if (queryClient == null || !queryClient.isConnected()
                || channelManager == null || config == null) {
            return;
        }

        List<String> names = resolveBridgeMemberNames();
        int count = names.size();
        String nickname = "MCBridge (" + count + " online)";
        try {
            updateNickname(nickname);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Failed to update TS bot nickname: {}", e.getMessage());
        }

        try {
            updateDescription(names);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Failed to update TS channel description: {}", e.getMessage());
        }

        TsBridgeMod.LOGGER.debug("[tsbridge] TsPresence updated: nickname='{}', {} MC players in bridge group",
                nickname, count);
    }

    /**
     * 解析桥接群组当前成员的 MC 玩家名。群组成员反射不可用时回退为全部在线 SVC 玩家。
     */
    private List<String> resolveBridgeMemberNames() {
        List<String> names = new ArrayList<>();
        List<UUID> members = GhostPlayerInjector.getBridgeGroupMemberUuids();
        if (members != null) {
            for (UUID member : members) {
                String name = playerNameOf(member);
                if (name != null) {
                    names.add(name);
                }
            }
        } else {
            for (var conn : OnlinePlayerRegistry.all()) {
                if (conn.getPlayer() == null) {
                    continue;
                }
                if (conn.getPlayer().getPlayer() instanceof ServerPlayer sp) {
                    names.add(sp.getName().getString());
                }
            }
        }
        return names;
    }

    private String playerNameOf(UUID uuid) {
        var conn = OnlinePlayerRegistry.get(uuid);
        if (conn == null || conn.getPlayer() == null) {
            return null;
        }
        if (conn.getPlayer().getPlayer() instanceof ServerPlayer sp) {
            return sp.getName().getString();
        }
        return null;
    }

    /**
     * 更新 bot 昵称为 {@code MCBridge (N online)}。
     *
     * <p>只更新原生语音客户端 —— 它是 TS 频道里真正可见的 bot。ServerQuery 客户端
     * 保持 {@code TsBridge-Query}：若两者改成同名，TS 会报 513（昵称已被占用）。</p>
     */
    private void updateNickname(String nickname) {
        if (TsBridgeMod.tsAudioTransport instanceof Ts3NativeTransport nativeTransport) {
            nativeTransport.updateNickname(nickname);
        }
    }

    /**
     * 更新频道描述为桥接群组成员列表。
     * autoPairing 开启时附带当前活跃的配对码（TS 用户无需先在 MC 侧查询）。
     */
    private void updateDescription(List<String> names) {
        if (channelManager.getBridgeChannelId() < 0) return;
        if (queryClient.getApi() == null) return;

        StringBuilder desc = new StringBuilder("[MC Online Players]\n");
        if (names.isEmpty()) {
            desc.append("(no MC players in bridge group)");
        } else {
            int limit = Math.min(names.size(), MAX_DESC_LINES);
            for (int i = 0; i < limit; i++) {
                desc.append("  ").append(names.get(i)).append("\n");
            }
            if (names.size() > MAX_DESC_LINES) {
                desc.append("  ... and ").append(names.size() - MAX_DESC_LINES).append(" more");
            }
        }

        // autoPairing: 展示活跃配对码
        if (config.autoPairing && TsBridgeMod.pairingManager != null) {
            try {
                List<String> codes = TsBridgeMod.pairingManager.activeCodes();
                if (!codes.isEmpty()) {
                    desc.append("\n[Pairing Codes]\n");
                    for (String code : codes) {
                        desc.append("  ").append(code).append("\n");
                    }
                    desc.append("(join this channel to auto-pair)");
                }
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Failed to list pairing codes: {}", e.getMessage());
            }
        }

        try {
            java.util.Map<com.github.theholywaffle.teamspeak3.api.ChannelProperty, String> props = new HashMap<>();
            props.put(com.github.theholywaffle.teamspeak3.api.ChannelProperty.CHANNEL_DESCRIPTION, desc.toString());
            queryClient.getApi().editChannel(channelManager.getBridgeChannelId(), props);
        } catch (Exception ignored) {
        }
    }
}
