package com.tsvoice.client.ts3voice;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TS 频道/用户快照（P3）：主键为 (serverUID, cid)，名称只用于显示与旧配置迁移。
 * 未在列表中的字段保持 UNKNOWN，不把"未列出"判断为不存在。快照由
 * channellist/initserver/clientlist 行驱动，与既有 GroupChannelMapper 的
 * 名称→cid 映射并存（该映射作为兼容层继续服务旧路径）。
 */
public final class TsChannelSnapshot {

    public static final int UNKNOWN = -1;

    /** 频道记录（不可变）；parentCid=0 表示根。 */
    public record Channel(int cid, int parentCid, String name, boolean passwordKnown,
                          int order, int totalClients, int maxClients) {
        public boolean isUnknownParent() {
            return parentCid == UNKNOWN;
        }
    }

    private final Map<Integer, Channel> channelsByCid = new ConcurrentHashMap<>();
    private volatile String serverUid;

    public TsChannelSnapshot(String serverUid) {
        this.serverUid = serverUid;
    }

    public String serverUid() {
        return serverUid;
    }

    /** 握手确认 serverUID 后刷新命名空间（只允许从 null/blank 升级）。 */
    public synchronized void refreshServerUid(String newUid) {
        if (serverUid == null || serverUid.isBlank()) {
            serverUid = newUid;
        }
    }

    /** 原子替换快照内容（channellist 全量行后调用）。 */
    public synchronized void replaceAll(List<Channel> channels) {
        channelsByCid.clear();
        for (Channel channel : channels) {
            channelsByCid.put(channel.cid(), channel);
        }
    }

    /** 单条 upsert（created/edited/moved 通知）。 */
    public synchronized void upsert(Channel channel) {
        channelsByCid.put(channel.cid(), channel);
    }

    public synchronized void remove(int cid) {
        channelsByCid.remove(cid);
    }

    public synchronized Channel byCid(int cid) {
        return channelsByCid.get(cid);
    }

    /** 按名查询全部匹配（同名频道可能跨父频道存在 —— 显示层必须列出歧义）。 */
    public synchronized List<Channel> byName(String name) {
        if (name == null) {
            return List.of();
        }
        return channelsByCid.values().stream()
                .filter(c -> name.equalsIgnoreCase(c.name()))
                .sorted((a, b) -> Integer.compare(a.cid(), b.cid()))
                .toList();
    }

    /** 深度排序视图：根频道 + 按父层级嵌（显示层直接渲染）。 */
    public synchronized List<Channel> ordered() {
        return channelsByCid.values().stream()
                .sorted((a, b) -> {
                    if (a.parentCid() != b.parentCid()) {
                        return Integer.compare(a.parentCid(), b.parentCid());
                    }
                    if (a.order() != b.order()) {
                        return Integer.compare(a.order(), b.order());
                    }
                    return Integer.compare(a.cid(), b.cid());
                })
                .toList();
    }

    public synchronized int size() {
        return channelsByCid.size();
    }
}
