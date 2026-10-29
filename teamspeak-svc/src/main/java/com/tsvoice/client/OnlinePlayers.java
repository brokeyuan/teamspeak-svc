package com.tsvoice.client;

import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本服在线玩家快照（主线程每 tick 刷新）：用于把 TS 昵称 {@code [MC] 名字}
 * 解析为真实 MC UUID，以及判断某 UUID 是否为本服玩家（跨维度也覆盖）。
 *
 * <p>TS 音频为等响度，不再需要位置/速度追踪。</p>
 */
public final class OnlinePlayers {

    private static final Map<String, UUID> uuidByName = new ConcurrentHashMap<>();
    private static final Set<UUID> onlineIds = ConcurrentHashMap.newKeySet();

    private OnlinePlayers() {
    }

    /** 客户端主线程每 tick 调用。 */
    public static void tick() {
        uuidByName.clear();
        onlineIds.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        for (var info : mc.getConnection().getOnlinePlayers()) {
            var profile = info.getProfile();
            if (profile != null && profile.id() != null && profile.name() != null) {
                uuidByName.put(profile.name().toLowerCase(java.util.Locale.ROOT), profile.id());
                onlineIds.add(profile.id());
            }
        }
    }

    /** 按 MC 玩家名（大小写不敏感）查在线玩家 UUID。 */
    public static UUID getUuidByName(String name) {
        return name == null ? null : uuidByName.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 该 UUID 是否为本服在线玩家（含其他维度）。 */
    public static boolean isOnline(UUID playerId) {
        return playerId != null && onlineIds.contains(playerId);
    }
}
