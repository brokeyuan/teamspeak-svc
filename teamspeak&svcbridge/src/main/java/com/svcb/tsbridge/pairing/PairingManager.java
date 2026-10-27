package com.svcb.tsbridge.pairing;

import com.github.theholywaffle.teamspeak3.api.event.ChannelCreateEvent;
import com.github.theholywaffle.teamspeak3.api.event.ChannelDeletedEvent;
import com.github.theholywaffle.teamspeak3.api.event.ChannelDescriptionEditedEvent;
import com.github.theholywaffle.teamspeak3.api.event.ChannelEditedEvent;
import com.github.theholywaffle.teamspeak3.api.event.ChannelMovedEvent;
import com.github.theholywaffle.teamspeak3.api.event.ChannelPasswordChangedEvent;
import com.github.theholywaffle.teamspeak3.api.event.ClientJoinEvent;
import com.github.theholywaffle.teamspeak3.api.event.ClientLeaveEvent;
import com.github.theholywaffle.teamspeak3.api.event.ClientMovedEvent;
import com.github.theholywaffle.teamspeak3.api.event.PrivilegeKeyUsedEvent;
import com.github.theholywaffle.teamspeak3.api.event.ServerEditedEvent;
import com.github.theholywaffle.teamspeak3.api.event.TextMessageEvent;
import com.github.theholywaffle.teamspeak3.api.event.TS3Listener;
import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.config.BridgeConfig;
import com.svcb.tsbridge.config.PairingStore;
import com.svcb.tsbridge.svc.OnlinePlayerRegistry;
import com.svcb.tsbridge.ts.BridgeChannelManager;
import com.svcb.tsbridge.ts.TsQueryClient;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MC↔TS 身份配对流程管理器。
 *
 * <p>核心流程：</p>
 * <ol>
 *     <li>MC 玩家执行 {@code /tsbridge pair}（Task 8 实现）时调用 {@link #createPairing}，
 *         生成一个随机短配对码并写入 {@link #pendingPairings}（带过期时间）。</li>
 *     <li>TS 用户在桥接频道中发送 {@code !pair <code>} 文本消息，
 *         由 {@link TsPairingListener#onTextMessage} 在 ServerQuery 事件线程上接收。</li>
 *     <li>监听器在 pending 表中匹配配对码（同时校验过期），命中即调用
 *         {@link PairingStore#bind} 持久化绑定，并向双方发送成功提示。</li>
 * </ol>
 *
 * <p>线程安全：{@link #pendingPairings} 使用 {@link ConcurrentHashMap}；
 * 配对码通过原子 {@code remove} 完成一次性消费，避免并发重复消费。</p>
 */
public class PairingManager {

    /** 配对码字符集：去除歧义字符 0/O/1/I/L。 */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    /** pending 配对码 → 配对上下文。 */
    static final Map<String, PendingPairing> pendingPairings = new ConcurrentHashMap<>();

    private final BridgeConfig config;
    private final PairingStore pairings;
    private final TsQueryClient queryClient;
    private final BridgeChannelManager channelManager;

    private final Random random = new java.security.SecureRandom();

    public PairingManager(BridgeConfig config, PairingStore pairings,
                          TsQueryClient queryClient, BridgeChannelManager channelManager) {
        this.config = config;
        this.pairings = pairings;
        this.queryClient = queryClient;
        this.channelManager = channelManager;
    }

    /**
     * 为指定 MC 玩家生成一个随机短配对码，写入 pending 表。
     *
     * <p>配对码长度由 {@link BridgeConfig#pairCodeLength} 控制（默认 6），
     * 过期时间为 {@link BridgeConfig#pairCodeExpiryMinutes} 分钟后。
     * 生成后顺便清理已过期的 pending 项，避免 map 无限增长。</p>
     *
     * @return 生成的配对码
     */
    public String createPairing(UUID mcUuid, String mcName) {
        int length = Math.max(1, config.pairCodeLength);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        String code = sb.toString();

        long expiry = System.currentTimeMillis()
                + Math.max(1, config.pairCodeExpiryMinutes) * 60_000L;
        pendingPairings.put(code, new PendingPairing(mcUuid, mcName, expiry));

        cleanupExpired();

        TsBridgeMod.LOGGER.info("[tsbridge] Created pairing code for MC player {} (name='{}', expires in {}min)",
                mcUuid, mcName, config.pairCodeExpiryMinutes);
        return code;
    }

    /**
     * 取消指定 MC 玩家的 pending 配对（例如玩家主动撤销 /tsbridge pair）。
     *
     * @return 是否实际移除了 pending 项
     */
    public boolean cancelPairing(UUID mcUuid) {
        if (mcUuid == null) {
            return false;
        }
        boolean removed = false;
        for (Map.Entry<String, PendingPairing> e : pendingPairings.entrySet()) {
            if (mcUuid.equals(e.getValue().mcUuid)) {
                pendingPairings.remove(e.getKey());
                removed = true;
                TsBridgeMod.LOGGER.info("[tsbridge] Cancelled pending pairing for MC player {}", mcUuid);
                break;
            }
        }
        return removed;
    }

    /**
     * 清理所有已过期的 pending 项。可由调用方在任意时机调用。
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        pendingPairings.entrySet().removeIf(e -> e.getValue().expiryEpochMs <= now);
    }

    /**
     * 返回当前活跃的配对码列表（"MC名: CODE" 格式），供频道描述展示。
     */
    public List<String> activeCodes() {
        cleanupExpired();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, PendingPairing> e : pendingPairings.entrySet()) {
            result.add(e.getValue().mcName + ": " + e.getKey());
        }
        return result;
    }

    /**
     * 自动配对模式（config.autoPairing 开启时由 Ghost 生命周期调用）：
     * TS 用户进入桥接频道时，将其绑定到最早生成的 pending 配对码。
     *
     * @param tsUniqueId TS 用户唯一 ID
     * @param tsName     TS 用户昵称
     * @return 是否完成了绑定
     */
    public boolean tryAutoPair(String tsUniqueId, String tsName) {
        if (!config.autoPairing || tsUniqueId == null || tsUniqueId.isEmpty()) {
            return false;
        }
        // 已绑定的 TS 用户不重复配对
        if (pairings.getMcUuid(tsUniqueId) != null) {
            return false;
        }
        cleanupExpired();
        // 取最早过期（即最早创建）的 pending 配对码
        String bestCode = null;
        PendingPairing best = null;
        for (Map.Entry<String, PendingPairing> e : pendingPairings.entrySet()) {
            if (best == null || e.getValue().expiryEpochMs < best.expiryEpochMs) {
                best = e.getValue();
                bestCode = e.getKey();
            }
        }
        if (best == null) {
            return false;
        }
        best = pendingPairings.remove(bestCode);
        if (best == null) {
            return false; // 并发下已被消费
        }
        try {
            pairings.bind(best.mcUuid, tsUniqueId);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Auto-pairing failed to persist MC={} -> TS={}: {}",
                    best.mcUuid, tsUniqueId, e.getMessage(), e);
            pendingPairings.put(bestCode, best);
            return false;
        }
        channelManager.sendTextToBridgeChannel(
                "Auto-paired with MC player " + best.mcName);
        notifyMcPlayer(best.mcUuid, "Auto-paired with TS user " + tsName);
        TsBridgeMod.LOGGER.info("[tsbridge] Auto-pairing established: MC {} ({}) <-> TS '{}' ({})",
                best.mcUuid, best.mcName, tsName, tsUniqueId);
        return true;
    }

    /**
     * 向 ServerQuery 注册配对文本事件监听器。
     *
     * <p>调用 {@link TsQueryClient#registerEventListener(TS3Listener)} 注册
     * {@link TsPairingListener}。失败（未连接或 API 异常）仅记录 WARN，
     * 不影响其他组件。应由 Task 9 在 SERVER_STARTED 中调用。</p>
     */
    public void registerTsEventListener() {
        try {
            boolean ok = queryClient.registerEventListener(new TsPairingListener());
            if (ok) {
                TsBridgeMod.LOGGER.info("[tsbridge] TS pairing event listener registered");
            } else {
                TsBridgeMod.LOGGER.warn("[tsbridge] Failed to register TS pairing event listener (registerEventListener returned false)");
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to register TS pairing event listener: {}", e.getMessage(), e);
        }
    }

    /**
     * 通过 Minecraft native API 向指定 MC 玩家发送系统消息。
     *
     * <p>流程：{@link OnlinePlayerRegistry#get} → SVC {@link VoicechatConnection#getPlayer()}
     * → SVC {@link ServerPlayer#getPlayer()}（返回底层平台 {@code Object}）
     * → 强转为 {@link net.minecraft.server.level.ServerPlayer}
     * → 调用 {@code sendSystemMessage}。</p>
     *
     * <p>玩家不在线或底层对象类型不符时静默跳过（debug 日志）。</p>
     */
    public void notifyMcPlayer(UUID mcUuid, String message) {
        if (mcUuid == null || message == null) {
            return;
        }
        VoicechatConnection conn = OnlinePlayerRegistry.get(mcUuid);
        if (conn == null) {
            TsBridgeMod.LOGGER.debug("[tsbridge] notifyMcPlayer skipped: MC player {} not connected to SVC", mcUuid);
            return;
        }
        ServerPlayer svcPlayer = conn.getPlayer();
        if (svcPlayer == null) {
            TsBridgeMod.LOGGER.debug("[tsbridge] notifyMcPlayer skipped: connection has no SVC player for {}", mcUuid);
            return;
        }
        Object raw = svcPlayer.getPlayer();
        if (!(raw instanceof net.minecraft.server.level.ServerPlayer mcPlayer)) {
            TsBridgeMod.LOGGER.debug("[tsbridge] notifyMcPlayer skipped: underlying player not ServerPlayer for {}", mcUuid);
            return;
        }
        try {
            mcPlayer.sendSystemMessage(Component.literal("[TSBridge] " + message));
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to send system message to MC player {}: {}", mcUuid, e.getMessage());
        }
    }

    /** pending 配对上下文。 */
    static final class PendingPairing {
        final UUID mcUuid;
        final String mcName;
        final long expiryEpochMs;

        PendingPairing(UUID mcUuid, String mcName, long expiryEpochMs) {
            this.mcUuid = mcUuid;
            this.mcName = mcName;
            this.expiryEpochMs = expiryEpochMs;
        }
    }

    /**
     * ServerQuery 文本消息事件监听器，处理 TS 用户的 {@code !pair <code>} 命令。
     *
     * <p>仅在 {@link #onTextMessage} 中做事，其他事件方法空实现
     * （TS3Listener 的其他事件与本模块无关）。</p>
     */
    private final class TsPairingListener implements TS3Listener {

        private static final String PAIR_CMD_PREFIX = "!pair ";

        @Override
        public void onTextMessage(TextMessageEvent event) {
            String message;
            try {
                message = event.getMessage();
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] onTextMessage: failed to read message: {}", e.getMessage());
                return;
            }
            if (message == null || message.isEmpty()) {
                return;
            }
            if (!message.startsWith(PAIR_CMD_PREFIX)) {
                return;
            }
            String code = message.substring(PAIR_CMD_PREFIX.length()).trim();
            if (code.isEmpty()) {
                channelManager.sendTextToBridgeChannel("Pairing code invalid or expired, please retry");
                return;
            }

            // 原子消费：移除并返回，确保同一配对码不会被并发重复消费
            PendingPairing pending = pendingPairings.remove(code);
            long now = System.currentTimeMillis();
            if (pending == null || pending.expiryEpochMs <= now) {
                channelManager.sendTextToBridgeChannel("Pairing code invalid or expired, please retry");
                TsBridgeMod.LOGGER.debug("[tsbridge] Pairing code rejected (code='{}', found={}, expired={})",
                        code, pending != null, pending != null && pending.expiryEpochMs <= now);
                return;
            }

            String tsUniqueId;
            String tsName;
            try {
                tsUniqueId = event.getInvokerUniqueId();
                tsName = event.getInvokerName();
            } catch (Exception e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] onTextMessage: failed to read invoker info: {}", e.getMessage());
                // 把 pending 放回，让玩家重试
                pendingPairings.put(code, pending);
                return;
            }
            if (tsUniqueId == null || tsUniqueId.isEmpty()) {
                channelManager.sendTextToBridgeChannel("Pairing failed: cannot resolve TS identity, please retry");
                pendingPairings.put(code, pending);
                return;
            }

            // 持久化绑定
            try {
                pairings.bind(pending.mcUuid, tsUniqueId);
            } catch (Exception e) {
                TsBridgeMod.LOGGER.error("[tsbridge] Failed to persist pairing MC={} -> TS={}: {}",
                        pending.mcUuid, tsUniqueId, e.getMessage(), e);
                channelManager.sendTextToBridgeChannel("Pairing failed on server side, please retry");
                return;
            }

            // 双向通知
            channelManager.sendTextToBridgeChannel(
                    "Paired successfully with MC player " + pending.mcName);
            notifyMcPlayer(pending.mcUuid, "Paired successfully with TS user " + tsName);

            TsBridgeMod.LOGGER.info("[tsbridge] Pairing established: MC {} ({}) <-> TS '{}' ({})",
                    pending.mcUuid, pending.mcName, tsName, tsUniqueId);
        }

        @Override
        public void onClientJoin(ClientJoinEvent e) {
            // no-op
        }

        @Override
        public void onClientLeave(ClientLeaveEvent e) {
            // no-op
        }

        @Override
        public void onClientMoved(ClientMovedEvent e) {
            // no-op
        }

        @Override
        public void onServerEdit(ServerEditedEvent e) {
            // no-op
        }

        @Override
        public void onPrivilegeKeyUsed(PrivilegeKeyUsedEvent e) {
            // no-op
        }

        @Override
        public void onChannelPasswordChanged(ChannelPasswordChangedEvent e) {
            // no-op
        }

        @Override
        public void onChannelCreate(ChannelCreateEvent e) {
            // no-op
        }

        @Override
        public void onChannelDeleted(ChannelDeletedEvent e) {
            // no-op
        }

        @Override
        public void onChannelDescriptionChanged(ChannelDescriptionEditedEvent e) {
            // no-op
        }

        @Override
        public void onChannelEdit(ChannelEditedEvent e) {
            // no-op
        }

        @Override
        public void onChannelMoved(ChannelMovedEvent e) {
            // no-op
        }
    }
}
