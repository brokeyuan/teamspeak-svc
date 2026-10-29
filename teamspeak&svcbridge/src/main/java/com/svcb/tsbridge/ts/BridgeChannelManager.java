package com.svcb.tsbridge.ts;

import com.github.theholywaffle.teamspeak3.TS3Api;
import com.github.theholywaffle.teamspeak3.api.wrapper.Channel;
import com.github.theholywaffle.teamspeak3.api.wrapper.Client;
import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.config.BridgeConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桥接频道管理器。
 *
 * <p>负责在 ServerQuery 连接成功后检查 / 创建配置的桥接频道，
 * 并提供查询频道内客户端、移动客户端、按 uniqueId 查找客户端、
 * 向桥接频道发送文本消息等便捷方法。</p>
 *
 * <p>所有方法均为 synchronized，避免并发调用导致 TS3 命令交错。
 * 未连接或频道无效时各方法返回安全默认值（false / 空 list / null）。</p>
 */
public class BridgeChannelManager {

    private final TsQueryClient client;
    private int bridgeChannelId = -1;

    public BridgeChannelManager(TsQueryClient client) {
        this.client = client;
    }

    /**
     * 确保配置的桥接频道存在。
     *
     * <p>先通过 {@link TS3Api#getChannelByNameExact(String, boolean)} 精确匹配频道名，
     * 找到则记录 channelId；找不到则通过 {@link TS3Api#createChannel(String, java.util.Map)}
     * 创建。失败（如未连接、API 异常）记录 ERROR 并返回 false。</p>
     *
     * @param config 当前配置，读取 {@link BridgeConfig#bridgeChannelName}
     * @return true 表示频道已就绪（已存在或新建成功）
     */
    public synchronized boolean ensureBridgeChannel(BridgeConfig config) {
        TS3Api api = client.getApi();
        if (api == null || !client.isConnected()) {
            TsBridgeMod.LOGGER.error("[tsbridge] Cannot ensure bridge channel: ServerQuery not connected");
            return false;
        }
        if (config.bridgeChannelName == null || config.bridgeChannelName.isEmpty()) {
            TsBridgeMod.LOGGER.error("[tsbridge] Cannot ensure bridge channel: bridgeChannelName is empty");
            return false;
        }

        try {
            // 先尝试精确查找（大小写敏感）
            Channel existing = api.getChannelByNameExact(config.bridgeChannelName, false);
            if (existing != null) {
                this.bridgeChannelId = existing.getId();
                TsBridgeMod.LOGGER.info("[tsbridge] Bridge channel '{}' already exists (channelId={})",
                        config.bridgeChannelName, this.bridgeChannelId);
                return true;
            }

            // 不存在则创建（永久频道：默认的临时频道会随创建者 Query 连接断开而被
            // TS3 服务器删除，导致语音客户端加入时报 768 invalid channelID）
            java.util.Map<com.github.theholywaffle.teamspeak3.api.ChannelProperty, String> props =
                    new java.util.HashMap<>();
            props.put(com.github.theholywaffle.teamspeak3.api.ChannelProperty.CHANNEL_FLAG_PERMANENT, "1");
            props.put(com.github.theholywaffle.teamspeak3.api.ChannelProperty.CHANNEL_FLAG_SEMI_PERMANENT, "0");
            int newId = api.createChannel(config.bridgeChannelName, props);
            this.bridgeChannelId = newId;
            TsBridgeMod.LOGGER.info("[tsbridge] Permanent bridge channel '{}' created (channelId={})",
                    config.bridgeChannelName, this.bridgeChannelId);
            return true;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to ensure bridge channel '{}': {}",
                    config.bridgeChannelName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 返回桥接频道 ID，可能为 -1（尚未初始化或初始化失败）。
     */
    public synchronized int getBridgeChannelId() {
        return bridgeChannelId;
    }

    /**
     * 返回当前位于桥接频道内的所有客户端。
     * 未连接或 channelId 无效时返回空 list。
     */
    public synchronized List<Client> getClientsInBridgeChannel() {
        if (bridgeChannelId < 0) {
            return Collections.emptyList();
        }
        TS3Api api = client.getApi();
        if (api == null || !client.isConnected()) {
            return Collections.emptyList();
        }
        try {
            List<Client> all = api.getClients();
            if (all == null || all.isEmpty()) {
                return Collections.emptyList();
            }
            List<Client> inChannel = new ArrayList<>();
            for (Client c : all) {
                if (c.getChannelId() == bridgeChannelId) {
                    inChannel.add(c);
                }
            }
            return inChannel;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to list clients in bridge channel: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将指定客户端移动到桥接频道。
     *
     * @param clientId TS 客户端 ID
     * @return true 表示移动成功
     */
    public synchronized boolean moveClientToBridge(int clientId) {
        if (bridgeChannelId < 0) {
            return false;
        }
        TS3Api api = client.getApi();
        if (api == null || !client.isConnected()) {
            return false;
        }
        try {
            api.moveClient(clientId, bridgeChannelId);
            return true;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to move client {} to bridge channel: {}",
                    clientId, e.getMessage());
            return false;
        }
    }

    /**
     * 按 TS uniqueIdentifier 查找当前在线的客户端。
     *
     * @param uniqueId TS 客户端唯一 ID
     * @return 匹配的 Client，未找到或未连接时返回 null
     */
    public synchronized Client findClientByUniqueId(String uniqueId) {
        if (uniqueId == null || uniqueId.isEmpty()) {
            return null;
        }
        TS3Api api = client.getApi();
        if (api == null || !client.isConnected()) {
            return null;
        }
        try {
            List<Client> all = api.getClients();
            if (all == null) {
                return null;
            }
            for (Client c : all) {
                if (uniqueId.equals(c.getUniqueIdentifier())) {
                    return c;
                }
            }
            return null;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to find client by uniqueId '{}': {}", uniqueId, e.getMessage());
            return null;
        }
    }

    /**
     * 向桥接频道发送文本消息。
     *
     * <p>使用 {@link TS3Api#sendChannelMessage(int, String)} 直接指定频道 ID 发送，
     * 无需移动 ServerQuery 客户端到目标频道。</p>
     *
     * @param message 文本内容
     * @return true 表示发送成功
     */
    public synchronized boolean sendTextToBridgeChannel(String message) {
        if (bridgeChannelId < 0 || message == null || message.isEmpty()) {
            return false;
        }
        TS3Api api = client.getApi();
        if (api == null || !client.isConnected()) {
            return false;
        }
        try {
            api.sendChannelMessage(bridgeChannelId, message);
            return true;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to send text to bridge channel: {}", e.getMessage());
            return false;
        }
    }
}
