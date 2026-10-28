package com.tsvoice.client;

import com.tsvoice.client.connection.VoiceState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TS 播放流注册表（T07/R10）：StreamKey → 本扩展自己拥有的 SVC 通道对象。
 * 删除只针对完全匹配的流/对象 —— 旧流的迟到清理绝不删除同参与者新流的资源
 * （streamEpoch 守卫）；关闭经 {@link ClientStateReflector#retireAudioChannel}
 * 的有界后台路径执行，不在调用线程（含 MC 主线程）持锁等待 closeAndKill。
 */
public final class TsPlaybackRegistry {

    private record Entry(VoiceState.StreamKey stream, UUID channelUuid, Object ownedChannel) {
    }

    private static final Map<UUID, Entry> ACTIVE = new ConcurrentHashMap<>();

    private TsPlaybackRegistry() {
    }

    /** 新流接管该通道的登记（同通道重复登记以最新流为准）。 */
    public static void register(VoiceState.StreamKey stream, UUID channelUuid, Object ownedChannel) {
        if (stream == null || channelUuid == null) {
            return;
        }
        ACTIVE.put(channelUuid, new Entry(stream, channelUuid, ownedChannel));
    }

    /**
     * 旧流退役：只有当该通道的当前登记仍是这一条流时才执行关闭。
     * 新流已接管（streamEpoch 更新）时为无操作 —— 不按全局 UUID 盲删。
     */
    public static void retire(VoiceState.StreamKey stream, UUID channelUuid) {
        if (stream == null || channelUuid == null) {
            return;
        }
        Entry entry = ACTIVE.get(channelUuid);
        if (entry == null || !entry.stream().equals(stream)) {
            return; // 旧清理迟到：新流已在位
        }
        ACTIVE.remove(channelUuid, entry);
        if (entry.ownedChannel() != null) {
            // N2/D01：本扩展自有的独立播放通道不在 SVC 的 audioChannels 表中，
            // 由适配器直接退役；SVC 表内的对象仍走反射路径，二者按对象身份
            // 各自关闭，互不越权。
            com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance()
                    .retirePlaybackChannel(channelUuid);
            ClientStateReflector.retireAudioChannel(channelUuid, entry.ownedChannel());
        }
        ClientStateReflector.removeGhost(channelUuid);
        GhostSkins.unregister(channelUuid);
    }

    /** 该通道当前登记是否仍是这一条流。 */
    public static boolean isCurrent(VoiceState.StreamKey stream, UUID channelUuid) {
        if (stream == null || channelUuid == null) {
            return false;
        }
        Entry entry = ACTIVE.get(channelUuid);
        return entry != null && entry.stream().equals(stream);
    }

    /** 会话结束：清空登记（通道关闭由 removeAll 的逐流 retire 驱动）。 */
    public static void clear() {
        ACTIVE.clear();
    }
}
