package com.svcb.tsbridge.ghost;

import com.github.theholywaffle.teamspeak3.TS3Api;
import com.github.theholywaffle.teamspeak3.api.event.BaseEvent;
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
import com.github.theholywaffle.teamspeak3.api.wrapper.Client;
import com.svcb.tsbridge.TsBridgeMod;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TS 侧 Ghost Player 生命周期监听器。
 *
 * <p>修复 v2 的生命周期缺陷：</p>
 * <ul>
 *     <li>加入事件只在其目标频道为桥接频道时注入（v2 对任何进服的 TS 用户都注入）；</li>
 *     <li>ClientMoved/ClientLeave 通知载荷中<strong>没有</strong> client_unique_identifier
 *         （只有 clid），因此维护 clid → (uniqueId, nickname) 缓存，
 *         在 join/moved 时填充，在 leave 时消费；</li>
 *     <li>{@link #reconcile()} 定期全量对账：以 TS 桥接频道内的真实客户端为准，
 *         补齐缺失的 Ghost、移除多余的 Ghost，事件丢失也能自愈。</li>
 * </ul>
 */
public final class TsGhostPresenceListener implements TS3Listener {

    /** clid → 缓存条目。 */
    private static final Map<Integer, ClientInfo> clientCache = new ConcurrentHashMap<>();

    private record ClientInfo(String uniqueId, String nickname) {
    }

    // ---- 事件处理 ----

    @Override
    public void onClientJoin(ClientJoinEvent e) {
        int clid = intProp(e, "clid", -1);
        String uniqueId = prop(e, "client_unique_identifier");
        String nickname = prop(e, "client_nickname");
        int channelId = intProp(e, "ctid", intProp(e, "client_channel_id", -1));

        // join 载荷通常带完整属性，但缓存起来供 leave 事件使用
        if (clid > 0 && uniqueId != null) {
            clientCache.put(clid, new ClientInfo(uniqueId, nickname));
        }

        if (!isInBridgeChannel(channelId, clid)) {
            return;
        }
        if (uniqueId != null && nickname != null) {
            injectGhost(uniqueId, nickname);
            TsBridgeMod.LOGGER.debug("[tsbridge] TS client joined bridge channel: {} ({})", nickname, uniqueId);
        }
    }

    @Override
    public void onClientMoved(ClientMovedEvent e) {
        int clid = intProp(e, "clid", -1);
        int targetChan = intProp(e, "ctid", -1);
        int fromChan = intProp(e, "cfid", -1);
        int bridgeChanId = bridgeChannelId();

        // moved 载荷没有 uniqueId —— 从缓存或 ServerQuery 解析
        ClientInfo info = resolveClient(clid);

        if (targetChan == bridgeChanId) {
            if (info != null) {
                injectGhost(info.uniqueId(), info.nickname());
            }
        } else if (fromChan == bridgeChanId) {
            if (info != null) {
                GhostPlayerInjector.remove(GhostPlayerInjector.ghostUuidFor(info.uniqueId()));
                TsBridgeMod.LOGGER.debug("[tsbridge] TS client moved out of bridge channel: {} ({})",
                        info.nickname(), info.uniqueId());
            }
        }
    }

    @Override
    public void onClientLeave(ClientLeaveEvent e) {
        int clid = intProp(e, "clid", -1);
        ClientInfo info = clid > 0 ? clientCache.remove(clid) : null;
        if (info != null) {
            GhostPlayerInjector.remove(GhostPlayerInjector.ghostUuidFor(info.uniqueId()));
            TsBridgeMod.LOGGER.debug("[tsbridge] TS client left: {} ({})", info.nickname(), info.uniqueId());
        }
    }

    // ---- 对账 ----

    /**
     * 全量对账：以桥接频道内的真实客户端为准，补齐/移除 Ghost。
     * 由维护任务定期调用，也在 ServerQuery 重连恢复后调用。
     */
    public static void reconcile() {
        int bridgeChanId = bridgeChannelId();
        TS3Api api = currentApi();
        if (bridgeChanId < 0 || api == null) {
            return;
        }
        Map<String, String> expected = new HashMap<>(); // uniqueId -> nickname
        try {
            for (Client c : api.getClients()) {
                if (c.getChannelId() == bridgeChanId && c.getUniqueIdentifier() != null
                        // 真 1:1 模式：MC 玩家镜像客户端不是"TS 用户"，排除出 Ghost 对账
                        && !(TsBridgeMod.perPlayerMode
                                && com.svcb.tsbridge.transport.PlayerVoiceClients.isMcOwnedUid(c.getUniqueIdentifier()))) {
                    expected.put(c.getUniqueIdentifier(), c.getNickname());
                    if (c.getId() > 0) {
                        clientCache.put(c.getId(), new ClientInfo(c.getUniqueIdentifier(), c.getNickname()));
                    }
                }
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Ghost reconcile: failed to list TS clients: {}", e.getMessage());
            return;
        }

        // 补齐缺失
        for (Map.Entry<String, String> e : expected.entrySet()) {
            boolean exists = GhostPlayerInjector.getAllTsUniqueIds().contains(e.getKey());
            if (!exists) {
                injectGhost(e.getKey(), e.getValue());
            }
        }
        // 移除多余
        for (String tsUniqueId : GhostPlayerInjector.getAllTsUniqueIds()) {
            if (!expected.containsKey(tsUniqueId)) {
                GhostPlayerInjector.remove(GhostPlayerInjector.ghostUuidFor(tsUniqueId));
            }
        }
    }

    private static void injectGhost(String uniqueId, String nickname) {
        if (uniqueId == null) {
            return;
        }
        // 真 1:1 模式：MC 玩家的镜像客户端不注入 Ghost，也不参与自动配对
        //（否则每个玩家会多出一个 [TS] 自己的幽灵，且镜像客户端会误消费配对码）
        if (TsBridgeMod.perPlayerMode
                && com.svcb.tsbridge.transport.PlayerVoiceClients.isMcOwnedUid(uniqueId)) {
            return;
        }
        String name = nickname != null ? nickname : uniqueId;
        GhostPlayerInjector.inject(GhostPlayerInjector.ghostUuidFor(uniqueId), "[TS] " + name, uniqueId);

        // v2: 自动配对（TS 用户进入桥接频道即绑定最早的 pending 配对码）
        if (TsBridgeMod.config != null && TsBridgeMod.config.autoPairing
                && TsBridgeMod.pairingManager != null) {
            try {
                TsBridgeMod.pairingManager.tryAutoPair(uniqueId, name);
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Auto-pairing failed for {}: {}", uniqueId, e.getMessage());
            }
        }
    }

    // ---- 工具 ----

    private static int bridgeChannelId() {
        return TsBridgeMod.bridgeChannelManager != null
                ? TsBridgeMod.bridgeChannelManager.getBridgeChannelId() : -1;
    }

    private static TS3Api currentApi() {
        return TsBridgeMod.tsQueryClient != null && TsBridgeMod.tsQueryClient.isConnected()
                ? TsBridgeMod.tsQueryClient.getApi() : null;
    }

    /**
     * 判断客户端是否在桥接频道：优先用事件里的频道 ID，
     * 缺失时通过 ServerQuery 查询（client_channel_id）。
     */
    private static boolean isInBridgeChannel(int channelId, int clid) {
        int bridgeChanId = bridgeChannelId();
        if (bridgeChanId < 0) {
            return false;
        }
        if (channelId > 0) {
            return channelId == bridgeChanId;
        }
        TS3Api api = currentApi();
        if (api != null && clid > 0) {
            try {
                var info = api.getClientInfo(clid);
                Object chan = info != null ? info.get("client_channel_id") : null;
                return chan != null && Integer.parseInt(chan.toString()) == bridgeChanId;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 解析 clid → (uniqueId, nickname)：先查缓存，未命中走 ServerQuery。
     */
    private static ClientInfo resolveClient(int clid) {
        if (clid > 0) {
            ClientInfo cached = clientCache.get(clid);
            if (cached != null) {
                return cached;
            }
            TS3Api api = currentApi();
            if (api != null) {
                try {
                    var info = api.getClientInfo(clid);
                    if (info != null) {
                        String uniqueId = info.get("client_unique_identifier");
                        String nickname = info.get("client_nickname");
                        if (uniqueId != null) {
                            ClientInfo resolved = new ClientInfo(uniqueId, nickname);
                            clientCache.put(clid, resolved);
                            return resolved;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static String prop(BaseEvent e, String key) {
        try {
            return e.getMap().get(key);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int intProp(BaseEvent e, String key, int defaultVal) {
        try {
            String v = e.getMap().get(key);
            return v != null ? Integer.parseInt(v) : defaultVal;
        } catch (Exception ex) {
            return defaultVal;
        }
    }

    // 其余事件与本监听器无关
    @Override public void onTextMessage(TextMessageEvent e) {}
    @Override public void onServerEdit(ServerEditedEvent e) {}
    @Override public void onChannelEdit(ChannelEditedEvent e) {}
    @Override public void onChannelCreate(ChannelCreateEvent e) {}
    @Override public void onChannelDeleted(ChannelDeletedEvent e) {}
    @Override public void onChannelMoved(ChannelMovedEvent e) {}
    @Override public void onChannelDescriptionChanged(ChannelDescriptionEditedEvent e) {}
    @Override public void onChannelPasswordChanged(ChannelPasswordChangedEvent e) {}
    @Override public void onPrivilegeKeyUsed(PrivilegeKeyUsedEvent e) {}
}
