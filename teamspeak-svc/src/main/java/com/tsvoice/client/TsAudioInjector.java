package com.tsvoice.client;

import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatClientApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TS 下行注入（T07/R10 重构）：ghosts/sequences 的键统一为
 * {@link VoiceState.ParticipantKey}(serverUID, TS UID)；所有 TS 播放通道 UUID
 * 一律派生自参与者身份 —— 即使该 TS 用户携带可信 MC UUID 也<b>绝不</b>复用原生
 * SVC 玩家的解码/音量通道（MC UUID 只作名字/皮肤展示关联）。
 *
 * <p>身份升降级（未知 ↔ 可信 MC）只改显示名与皮肤，不换通道 —— 个人音量在
 * 重连与升降级间保持稳定。每段流持有 {@link VoiceState.StreamKey}（streamEpoch），
 * 释放经 {@link TsPlaybackRegistry} 的 exact-match 守卫与有界后台退役执行。</p>
 */
public final class TsAudioInjector {

    private static final Map<VoiceState.ParticipantKey, GhostAudio> ghosts = new ConcurrentHashMap<>();
    private static final Map<VoiceState.ParticipantKey, long[]> sequences = new ConcurrentHashMap<>();
    /** 每个参与者的流代际：ghost 重建（离开/回归）递增，驱动 StreamKey。 */
    private static final Map<VoiceState.ParticipantKey, AtomicLong> streamEpochs =
            new ConcurrentHashMap<>();
    /** 音量一次性迁移标记（旧可信 MC 键 → 参与者键；每参与者最多一次）。 */
    private static final java.util.Set<UUID> VOLUME_MIGRATED = ConcurrentHashMap.newKeySet();

    private record GhostAudio(UUID channelUuid, String displayName, UUID mcUuid, boolean ownsState,
                              Object ownedChannel, VoiceState.StreamKey streamKey) {
        GhostAudio withName(String newName) {
            return new GhostAudio(channelUuid, newName, mcUuid, ownsState, ownedChannel, streamKey);
        }

        GhostAudio withOwnedChannel(Object channel) {
            return new GhostAudio(channelUuid, displayName, mcUuid, ownsState, channel, streamKey);
        }

        GhostAudio withIdentity(String newName, UUID newMcUuid) {
            return new GhostAudio(channelUuid, newName, newMcUuid, ownsState, ownedChannel, streamKey);
        }
    }

    private static final AtomicLong injectedFrames = new AtomicLong(0);
    private static volatile boolean codecWarned = false;
    private static volatile boolean firstTsFrameLogged = false;
    private static volatile boolean volumeMigrationUnavailableLogged = false;

    private TsAudioInjector() {
    }

    /**
     * 由 TS uid 派生稳定参与者 UUID：按 serverUID 划分命名空间，与 clid/会话代际/
     * 身份升降级无关 —— 重连/换 clid/可信 MC 关联变化后个人音量与说话高亮保持稳定，
     * 不同 TS 服务器的相同 uid 互不串音量。
     */
    public static UUID participantUuidFor(String authority, String tsUniqueId) {
        String scope = authority == null || authority.isBlank() ? "unknown" : authority;
        return UUID.nameUUIDFromBytes(("tsvoice-participant-v2\0" + scope + "\0" + tsUniqueId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 兼容入口（无权威上下文；新调用方应使用带 serverUid 的重载）。 */
    public static void onTsFrame(String tsUniqueId, String nickname, java.util.UUID mcUuid,
                                 int sequence16, int codec, byte[] opusData) {
        onTsFrame(null, tsUniqueId, nickname, mcUuid, sequence16, codec, opusData);
    }

    /** TS 语音帧入口（TS 接收线程）。authority 为来源虚拟服务器 UID。 */
    public static void onTsFrame(String authority, String tsUniqueId, String nickname, java.util.UUID mcUuid,
                                 int sequence16, int codec, byte[] opusData) {
        if (tsUniqueId == null || opusData == null) {
            if (opusData != null && !firstTsFrameLogged) {
                firstTsFrameLogged = true;
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] TS voice frame arrived with UNKNOWN sender (client list not synced yet) — dropped");
            }
            return;
        }
        if (opusData.length > 0 && !firstTsFrameLogged) {
            firstTsFrameLogged = true;
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS->MC audio path ACTIVE (first frame from TS user {})", tsUniqueId);
        }
        VoicechatClientApi api = TeamspeakSvcClient.clientApi();
        if (api == null) {
            return;
        }
        VoiceState.ParticipantKey key = new VoiceState.ParticipantKey(authority, tsUniqueId);
        GhostAudio ghost = ghosts.computeIfAbsent(key, k -> createGhost(api, k, nickname, mcUuid));
        if (ghost == null) {
            return;
        }
        // MC UUID 可能晚于第一帧语音到达：身份升级只改显示名/皮肤 —— 通道与音量键不变。
        if (ghost.mcUuid() == null && mcUuid != null) {
            ghost = upgradeIdentity(api, key, ghost, mcUuid, nickname);
            if (ghost == null) {
                return;
            }
        }
        // 昵称变化时刷新显示名（ghost 状态始终由本扩展拥有）
        String displayName = ghost.mcUuid() == null ? "[TS] " + stripMcPrefix(nickname)
                : (nickname != null && !nickname.isBlank() ? nickname : ghost.displayName());
        if (nickname != null && !nickname.isBlank() && !displayName.equals(ghost.displayName())) {
            GhostAudio renamed = ghost.withName(displayName);
            ghosts.put(key, renamed);
            ClientStateReflector.injectGhost(renamed.channelUuid(), renamed.displayName(), api, null);
            ghost = renamed;
        }
        if (codec != Ts3VoiceClient.CODEC_OPUS_VOICE && codec != Ts3VoiceClient.CODEC_OPUS_MUSIC
                && !codecWarned) {
            codecWarned = true;
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Unexpected TS codec {} (expected 4=voice / 5=music)", codec);
        }
        Sequence sequence = unwrapSequence(key, sequence16);
        if (sequence.resetMarker() >= 0) {
            // 发送者重连导致序号回退：先注入空包重置 SVC 解码器/缓冲（marker > 旧序号）
            ghost = injectSoundPacket(key, ghost, sequence.resetMarker(), EMPTY);
        }
        // TS 音频统一等响度（与 TS 客户端一致；不做距离衰减，避免远距离/跨维度静音）
        injectSoundPacket(key, ghost, sequence.value(), opusData);
    }

    /** 身份升级（可信 MC 关联到达）：原通道原 key 原地更新显示与皮肤，音量不重置。 */
    private static GhostAudio upgradeIdentity(VoicechatClientApi api, VoiceState.ParticipantKey key,
                                              GhostAudio old, java.util.UUID mcUuid, String nickname) {
        String displayName = nickname != null && !nickname.isBlank() ? nickname : old.displayName();
        GhostAudio upgraded = old.withIdentity(displayName, mcUuid);
        try {
            GhostSkins.register(old.channelUuid(), mcUuid, nickname);
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] ghost skin registration failed: {}", e.getMessage());
        }
        ghosts.put(key, upgraded);
        ClientStateReflector.injectGhost(old.channelUuid(), displayName, api, null);
        ClientStateReflector.updateUsernameCache(old.channelUuid(), displayName);
        migrateVolumeOnce(mcUuid, old.channelUuid());
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS identity upgraded to MC UUID {} (channel/volume unchanged)",
                mcUuid);
        return upgraded;
    }

    /**
     * 旧可信 MC 音量的一次性迁移（T07）：把原生 SVC 音量缓存中该 MC UUID 的个人
     * 音量复制到参与者键，最多一次并记录来源。SVC 音量配置 ABI 未在真实环境核对前
     * 为尽力而为；失败只记录，不影响音频。
     */
    private static void migrateVolumeOnce(UUID mcUuid, UUID participant) {
        if (mcUuid == null || !VOLUME_MIGRATED.add(participant)) {
            return;
        }
        try {
            Object config = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                    .getMethod("getConfig").invoke(null);
            Object volumes = config.getClass().getMethod("getVolumes").invoke(config);
            @SuppressWarnings("unchecked")
            Map<UUID, Double> volumeMap = (Map<UUID, Double>) volumes;
            Double previous = volumeMap != null ? volumeMap.get(mcUuid) : null;
            if (previous != null && volumeMap != null) {
                volumeMap.put(participant, previous);
                TeamspeakSvcClient.LOGGER.info(
                        "[teamspeak-svc] personal volume migrated {} -> {} (source: trusted MC mapping, one-time)",
                        mcUuid, participant);
            }
        } catch (Throwable t) {
            if (!volumeMigrationUnavailableLogged) {
                volumeMigrationUnavailableLogged = true;
                TeamspeakSvcClient.LOGGER.info(
                        "[teamspeak-svc] per-player volume migration unavailable (SVC config ABI not verified): {}",
                        t.toString());
            }
        }
    }

    private static String stripMcPrefix(String nickname) {
        if (nickname != null && nickname.startsWith("[MC] ")) {
            return nickname.substring("[MC] ".length());
        }
        return nickname;
    }

    private static final byte[] EMPTY = new byte[0];

    private record Sequence(long value, long resetMarker) {
    }

    /**
     * 16 位 TS 语音序号 → 单调 long；处理 16 位回绕。
     * 若检测到发送者重连（序号大幅回退），重置基准并在 resetMarker 返回一个
     * 大于旧序号的标记（供调用方注入空包重置 SVC 通道）。
     */
    private static Sequence unwrapSequence(VoiceState.ParticipantKey key, int raw16) {
        long[] st = sequences.computeIfAbsent(key, k -> new long[]{-1L, -1L});
        long raw = raw16 & 0xFFFFL;
        if (st[1] < 0) {
            st[0] = raw;
            st[1] = raw;
            return new Sequence(raw, -1L);
        }
        long delta = raw - st[0];
        if (delta < -32768) {
            // 发送者重连：重置基准，注入大于旧序号的空包让 SVC 重置解码器
            long marker = st[1] + 0x10000L;
            st[0] = raw;
            st[1] = marker;
            return new Sequence(marker, marker);
        }
        long candidate = st[1] + delta;
        if (candidate > st[1]) {
            st[0] = raw;
            st[1] = candidate;
        }
        return new Sequence(candidate, -1L);
    }

    /** 每段播放流持有唯一 StreamKey：ghost 重建（离开/回归）递增 streamEpoch。 */
    private static VoiceState.StreamKey streamKeyFor(VoiceState.ParticipantKey key) {
        long epoch = streamEpochs.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        return new VoiceState.StreamKey(key, epoch);
    }

    private static GhostAudio createGhost(VoicechatClientApi api, VoiceState.ParticipantKey key,
                                          String nickname, java.util.UUID mcUuid) {
        try {
            // 通道一律派生自参与者身份（T07/R10）：TS 音频不占用原生 MC 玩家通道，
            // 同 MC UUID 的多个 TS 身份互不合并。MC UUID 只影响名字与皮肤。
            UUID channelUuid = participantUuidFor(key.serverUid(), key.tsUid());
            String displayName = mcUuid != null
                    ? (nickname != null && !nickname.isBlank() ? nickname : mcUuid.toString())
                    : "[TS] " + (nickname != null && !nickname.isBlank()
                    ? stripMcPrefix(nickname) : key.tsUid());
            GhostSkins.register(channelUuid, mcUuid, nickname);
            ClientStateReflector.injectGhost(channelUuid, displayName, api, null);
            ClientStateReflector.updateUsernameCache(channelUuid, displayName);
            if (mcUuid != null) {
                migrateVolumeOnce(mcUuid, channelUuid);
            }
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS audio channel for '{}' (participant {}, {})",
                    displayName, channelUuid, mcUuid != null ? "MC-linked display" : "TS user");
            return new GhostAudio(channelUuid, displayName, mcUuid, true, null,
                    streamKeyFor(key));
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to init ghost audio: {}", e.getMessage());
            return null;
        }
    }

    // ---- SVC 包注入（T08c：版本敏感入口收敛到前端适配器） ----

    /** 注入一帧：经前端适配器的 SVC 播放管线；首次成功后快照通道对象登记所有权。 */
    private static GhostAudio injectSoundPacket(VoiceState.ParticipantKey key, GhostAudio ghost,
                                                long sequence, byte[] opus) {
        if (!com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance()
                .play(ghost.streamKey(), ghost.channelUuid(), opus, sequence)) {
            // FRONTEND_UNAVAILABLE for playback：帧丢弃，原因由状态层呈现
            return ghost;
        }
        injectedFrames.incrementAndGet();
        if (ghost.ownedChannel() == null) {
            Object channel = ClientStateReflector.ownedAudioChannel(ghost.channelUuid());
            if (channel != null) {
                GhostAudio owned = ghost.withOwnedChannel(channel);
                ghosts.put(key, owned);
                ClientStateReflector.registerOwnedChannel(ghost.channelUuid(), channel);
                TsPlaybackRegistry.register(owned.streamKey(), owned.channelUuid(), channel);
                return owned;
            }
        }
        return ghost;
    }

    /**
     * Ghost 可见性全量重算：任何一方（我或 TS 用户）移动频道后调用。
     * 对每个活跃 Ghost：其 TS 用户当前在我频道 → 注入/保留状态；不在 → 移除状态
     * （音频通道保留，对方回到我频道后首帧/下次同步自动恢复）。
     */
    public static void syncAllGhostVisibility(Ts3VoiceClient client) {
        VoicechatClientApi api = TeamspeakSvcClient.clientApi();
        if (api == null) {
            return;
        }
        // SVC 群组名 → 群组 UUID（用于"TS 用户在我不在的群组子频道"的跨群组显示）
        java.util.Map<String, UUID> groupsByName = ClientStateReflector.clientGroupsByName();
        UUID myGroup = null;
        try {
            Group g = api.getGroup();
            myGroup = g != null ? g.getId() : null;
        } catch (Throwable ignored) {
        }
        int myChannel = client.getMyChannelId();

        for (Map.Entry<VoiceState.ParticipantKey, GhostAudio> e : ghosts.entrySet()) {
            String tsUid = e.getKey().tsUid();
            GhostAudio ghost = e.getValue();
            UUID ghostUuid = ghost.channelUuid();

            int clid = client.getClidOfUid(tsUid);
            if (clid < 0) {
                // 不在服务器：隐藏
                if (ClientStateReflector.activeGhosts().containsKey(ghostUuid)) {
                    ClientStateReflector.removeGhost(ghostUuid);
                }
                continue;
            }
            int hisChannel = client.getChannelOf(clid);
            UUID showGroup;
            boolean sameChannel = myChannel >= 0 && hisChannel == myChannel;
            if (sameChannel) {
                showGroup = myGroup;        // 同频道：跟随我的群组（或无群组）
            } else {
                // 跨频道：其子频道名匹配某个 SVC 群组 → 显示为该群组成员
                String chanName = client.getChannelNameByCid(hisChannel);
                showGroup = chanName != null ? groupsByName.get(chanName.toLowerCase(java.util.Locale.ROOT)) : null;
            }
            if (sameChannel || showGroup != null) {
                boolean wasVisible = ClientStateReflector.activeGhosts().containsKey(ghostUuid);
                ClientStateReflector.injectGhost(ghostUuid, ghost.displayName(), api, showGroup);
                if (!wasVisible) {
                    ClientStateReflector.updateUsernameCache(ghostUuid, ghost.displayName());
                    TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] ghost visible: {}", ghost.displayName());
                }
            } else if (ClientStateReflector.activeGhosts().containsKey(ghostUuid)) {
                ClientStateReflector.removeGhost(ghostUuid);
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] ghost hidden: {}", ghost.displayName());
            }
        }
    }

    /** TS 用户离开频道/会话断开时释放其注入状态（exact-match 退役，不删除新流）。 */
    public static void remove(VoiceState.ParticipantKey key) {
        GhostAudio ghost = ghosts.remove(key);
        sequences.remove(key);
        if (ghost == null) {
            return;
        }
        if (ghost.ownsState()) {
            // 通道对象只在确为本流所有时关闭（registry exact-match 守卫）；
            // ghost 状态与皮肤始终随参与者移除。
            TsPlaybackRegistry.retire(ghost.streamKey(), ghost.channelUuid());
            ClientStateReflector.removeGhost(ghost.channelUuid());
            GhostSkins.unregister(ghost.channelUuid());
        }
        TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] Ghost released ({}/{})",
                key.serverUid(), key.tsUid());
    }

    /** 成功注入 SVC 播放管线的帧数（诊断用）。 */
    public static long getInjectedFrames() {
        return injectedFrames.get();
    }

    /** 会话断开时释放全部 Ghost。 */
    public static void removeAll() {
        for (VoiceState.ParticipantKey key : ghosts.keySet().toArray(new VoiceState.ParticipantKey[0])) {
            remove(key);
        }
        sequences.clear();
        TsPlaybackRegistry.clear();
    }

    /**
     * 与 TS 频道成员对账：移除已不在服务器的 Ghost（幂等，由群组映射周期调用）。
     */
    public static void reconcile(Ts3VoiceClient client) {
        for (VoiceState.ParticipantKey key : ghosts.keySet().toArray(new VoiceState.ParticipantKey[0])) {
            if (!client.hasUid(key.tsUid())) {
                remove(key);
            }
        }
    }
}
