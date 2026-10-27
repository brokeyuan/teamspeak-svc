package com.svcb.tsbridge.svc;

import de.maxhenkel.voicechat.api.VoicechatConnection;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线 SVC 玩家连接表。
 *
 * <p>由 {@link com.svcb.tsbridge.TsVoicechatPlugin} 在
 * {@code PlayerConnectedEvent} / {@code PlayerDisconnectedEvent} 中维护，
 * 供 {@link McAudioInjector} 及后续 Task 5 的转发器查询目标玩家连接。</p>
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap}，所有方法可无锁并发访问。</p>
 */
public final class OnlinePlayerRegistry {

    private OnlinePlayerRegistry() {
    }

    private static final Map<UUID, VoicechatConnection> online = new ConcurrentHashMap<>();

    /**
     * 玩家连接 SVC 时调用。null 连接或无 player 的连接会被忽略。
     */
    public static void onPlayerConnected(VoicechatConnection conn) {
        if (conn == null || conn.getPlayer() == null) {
            return;
        }
        online.put(conn.getPlayer().getUuid(), conn);
    }

    /**
     * 玩家断开 SVC 时调用。null UUID 会被忽略。
     */
    public static void onPlayerDisconnected(UUID uuid) {
        if (uuid == null) {
            return;
        }
        online.remove(uuid);
    }

    /**
     * 返回指定 UUID 的连接，未在线返回 null。
     */
    public static VoicechatConnection get(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return online.get(uuid);
    }

    /**
     * 返回当前所有在线连接的视图。调用方不应修改返回的集合。
     */
    public static Collection<VoicechatConnection> all() {
        return online.values();
    }

    /**
     * 返回当前在线连接数。
     */
    public static int size() {
        return online.size();
    }
}
