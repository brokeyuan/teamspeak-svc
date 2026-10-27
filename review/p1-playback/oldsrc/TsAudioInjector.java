package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TS→MC 音频注入：把 TS 的编码 Opus 包按 SVC 的 {@code SoundPacket} 注入
 * {@code ClientVoicechat.processSoundPacket}，让 SVC 自己的 AudioChannel 完成：
 * 抖动缓冲/乱序重排、Opus FEC/PLC 丢包补偿、解码、音量与说话指示。
 *
 * <p>不使用 {@code ClientStaticAudioChannel.play(short[])}：API 通道会构造
 * sequence=-1 的裸 PCM 包，AudioChannel 直接写扬声器，绕过上述所有补偿
 * （这是 TS 声音发闷/断音的根本原因）。</p>
 *
 * <p>TS 语音包内的 16 位序号是发送者维度的，这里展开为单调 long（处理回绕），
 * 作为 SVC 的 sequenceNumber 使用。</p>
 */
public final class TsAudioInjector {

    /** tsUid → 注入上下文。 */
    private static final Map<String, GhostAudio> ghosts = new ConcurrentHashMap<>();
    /** tsUid → [lastRaw16, unwrapped]。仅 TS 接收线程访问。 */
    private static final Map<String, long[]> sequences = new ConcurrentHashMap<>();

    private record GhostAudio(UUID channelUuid, String displayName, UUID mcUuid, boolean ownsState) {
        GhostAudio withDisplayName(String name) {
            return new GhostAudio(channelUuid, name, mcUuid, ownsState);
        }
    }

    // ---- SVC 内部包注入（反射；SVC 2.6.x 签名稳定） ----
    private static volatile boolean reflectionReady = false;
    private static boolean reflectionFailed = false;
    private static Method processSoundPacket;
    private static Constructor<?> groupCtor;
    private static volatile boolean injectErrorLogged = false;
    private static volatile boolean clientNullLogged = false;
    private static volatile boolean codecWarned = false;
    /** 成功注入 SVC 播放管线的帧数（诊断用）。 */
    private static final java.util.concurrent.atomic.AtomicLong injectedFrames =
            new java.util.concurrent.atomic.AtomicLong();

    private TsAudioInjector() {
    }

    /** 由 TS uid 派生 Ghost UUID（与 tsbridge 服务端桥接一致，便于身份互认）。 */
    public static UUID ghostUuidFor(String tsUniqueId) {
        return UUID.nameUUIDFromBytes(("ts-ghost-" + tsUniqueId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static volatile boolean firstTsFrameLogged = false;

    /** TS 语音帧入口（TS 接收线程）。opus 可以为空数组（发送者结束说话）。 */
    public static void onTsFrame(String tsUniqueId, String nickname, java.util.UUID mcUuid,
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
        GhostAudio ghost = ghosts.computeIfAbsent(tsUniqueId, id -> createGhost(api, id, nickname, mcUuid));
        if (ghost == null) {
            return;
        }
        // MC UUID 可能晚于第一帧语音到达：把先建的派生 Ghost 迁移到真实玩家 UUID
        if (ghost.mcUuid() == null && mcUuid != null) {
            ghost = migrateGhost(tsUniqueId, ghost, mcUuid, nickname, api);
            if (ghost == null) {
                return;
            }
        }
        // 昵称变化时刷新显示名（仅注入态：同服玩家名字以原生为准）
        String displayName = ghost.mcUuid() == null ? "[TS] " + stripMcPrefix(nickname) : nickname;
        if (nickname != null && !nickname.isBlank() && !displayName.equals(ghost.displayName())
                && ghost.ownsState()
                && ClientStateReflector.activeGhosts().containsKey(ghost.channelUuid())) {
            GhostAudio renamed = ghost.withDisplayName(displayName);
            ghosts.put(tsUniqueId, renamed);
            ClientStateReflector.injectGhost(renamed.channelUuid(), renamed.displayName(), api, null);
            ghost = renamed;
        }
        if (codec != Ts3VoiceClient.CODEC_OPUS_VOICE && codec != Ts3VoiceClient.CODEC_OPUS_MUSIC
                && !codecWarned) {
            codecWarned = true;
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Unexpected TS codec {} (expected 4=voice / 5=music)", codec);
        }
        Sequence sequence = unwrapSequence(tsUniqueId, sequence16);
        if (sequence.resetMarker() >= 0) {
            // 发送者重连导致序号回退：先注入空包重置 SVC 解码器/缓冲（marker > 旧序号）
            injectSoundPacket(ghost, sequence.resetMarker(), EMPTY);
        }
        // TS 音频统一等响度（与 TS 客户端一致；不做距离衰减，避免远距离/跨维度静音）
        injectSoundPacket(ghost, sequence.value(), opusData);
    }

    /** 第一帧到达时 MC UUID 未知 → 先用派生 Ghost；UUID 同步后迁移到真实玩家。 */
    private static GhostAudio migrateGhost(String tsUniqueId, GhostAudio old, java.util.UUID mcUuid,
                                          String nickname, VoicechatClientApi api) {
        GhostAudio fresh = createGhost(api, tsUniqueId, nickname, mcUuid);
        if (fresh == null) {
            return old;
        }
        if (old.ownsState() && !old.channelUuid().equals(fresh.channelUuid())) {
            ClientStateReflector.closeAudioChannel(old.channelUuid());
            ClientStateReflector.removeGhost(old.channelUuid());
            GhostSkins.unregister(old.channelUuid());
        }
        ghosts.put(tsUniqueId, fresh);
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Ghost migrated to MC UUID {} ({})",
                mcUuid, fresh.displayName());
        return fresh;
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
    private static Sequence unwrapSequence(String tsUniqueId, int raw16) {
        long[] st = sequences.computeIfAbsent(tsUniqueId, k -> new long[]{-1L, -1L});
        long raw = raw16 & 0xFFFFL;
        if (st[1] < 0) {
            st[0] = raw;
            st[1] = raw;
            return new Sequence(raw, -1L);
        }
        long delta = raw - st[0];
        if (delta < -32768) {
            delta += 65536;
        } else if (delta > 32768) {
            delta -= 65536;
        }
        if (delta < -1000) {
            // 发送者重连/流重置：旧 SVC AudioChannel 的 lastSequence 很高
            long marker = st[1] + 1;
            st[0] = raw;
            st[1] = raw;
            return new Sequence(raw, marker);
        }
        long candidate = st[1] + delta;
        if (candidate > st[1]) {
            st[0] = raw;
            st[1] = candidate;
        }
        return new Sequence(candidate, -1L);
    }

    private static GhostAudio createGhost(VoicechatClientApi api, String tsUniqueId, String nickname,
                                          java.util.UUID mcUuid) {
        try {
            // 三分流：同服 mod 玩家（真实 UUID，不注入状态）/
            // 跨服 mod 玩家（真实 UUID，注入为普通玩家）/
            // 纯 TS 用户（派生 UUID，注入 [TS] Ghost）
            UUID channelUuid;
            String displayName;
            boolean injectState;
            boolean sameServer = mcUuid != null
                    && (ClientStateReflector.hasLocalState(mcUuid)
                    || OnlinePlayers.isOnline(mcUuid));
            if (mcUuid != null) {
                channelUuid = mcUuid;
                injectState = !sameServer;
                displayName = nickname != null && !nickname.isBlank() ? nickname : mcUuid.toString();
            } else {
                channelUuid = ghostUuidFor(tsUniqueId);
                injectState = true;
                displayName = "[TS] " + (nickname != null && !nickname.isBlank()
                        ? stripMcPrefix(nickname) : tsUniqueId);
            }
            // 皮肤：跨服玩家注册真实皮肤管线；同服玩家交给 SVC 原生；TS 用户走内置皮兜底
            if (mcUuid != null && !sameServer) {
                GhostSkins.register(channelUuid, mcUuid, nickname);
            }
            if (injectState) {
                ClientStateReflector.injectGhost(channelUuid, displayName, api, null);
            }
            ClientStateReflector.updateUsernameCache(channelUuid, displayName);
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS audio channel for '{}' ({}{}, {})",
                    displayName, tsUniqueId, sameServer ? ", same-server player" : "",
                    mcUuid != null ? "cross-server player" : "TS user");
            return new GhostAudio(channelUuid, displayName, mcUuid, injectState);
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to init ghost audio: {}", e.getMessage());
            return null;
        }
    }

    // ---- SVC 包注入 ----

    private static synchronized boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        try {
            Class<?> soundPacketClass = Class.forName("de.maxhenkel.voicechat.voice.common.SoundPacket");
            Class<?> clientClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechat");
            processSoundPacket = clientClass.getMethod("processSoundPacket", soundPacketClass);
            groupCtor = Class.forName("de.maxhenkel.voicechat.voice.common.GroupSoundPacket")
                    .getConstructor(UUID.class, UUID.class, byte[].class, long.class, String.class);
            reflectionReady = true;
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Ghost audio uses SVC packet pipeline (jitter buffer + PLC/FEC)");
            return true;
        } catch (Throwable e) {
            reflectionFailed = true;
            TeamspeakSvcClient.LOGGER.error("[teamspeak-svc] TS audio disabled (SVC packet reflection failed): {}", e.toString());
            return false;
        }
    }

    private static Object clientVoicechat() {
        try {
            Class<?> clientManager = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
            return clientManager.getMethod("getClient").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void injectSoundPacket(GhostAudio ghost, long sequence, byte[] opus) {
        if (!ensureReflection()) {
            return;
        }
        Object client = clientVoicechat();
        if (client == null) {
            if (!clientNullLogged) {
                clientNullLogged = true;
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] SVC ClientVoicechat not ready — TS audio dropped");
            }
            return;
        }
        try {
            UUID sender = ghost.mcUuid() != null ? ghost.mcUuid() : ghost.channelUuid();
            Object packet = groupCtor.newInstance(ghost.channelUuid(), sender, opus, sequence, (String) null);
            processSoundPacket.invoke(client, packet);
            injectedFrames.incrementAndGet();
        } catch (Throwable t) {
            if (!injectErrorLogged) {
                injectErrorLogged = true;
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] SVC packet injection failed: {}", t.toString());
            }
        }
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
            de.maxhenkel.voicechat.api.Group g = api.getGroup();
            myGroup = g != null ? g.getId() : null;
        } catch (Throwable ignored) {
        }
        int myChannel = client.getMyChannelId();

        for (Map.Entry<String, GhostAudio> e : ghosts.entrySet()) {
            String tsUid = e.getKey();
            GhostAudio ghost = e.getValue();
            // 同服玩家的 PlayerState 属于 SVC；其 TS 频道变化不能覆盖该状态。
            if (!ghost.ownsState()) {
                continue;
            }
            int clid = client.getClidOfUid(tsUid);
            UUID ghostUuid = ghost.channelUuid();

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

    /** TS 用户离开频道/会话断开时释放其注入状态。 */
    public static void remove(String tsUniqueId) {
        GhostAudio ghost = ghosts.remove(tsUniqueId);
        sequences.remove(tsUniqueId);
        if (ghost == null) {
            return;
        }
        if (ghost.ownsState()) {
            ClientStateReflector.closeAudioChannel(ghost.channelUuid());
            ClientStateReflector.removeGhost(ghost.channelUuid());
            GhostSkins.unregister(ghost.channelUuid());
        }
        TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] Ghost released ({})", tsUniqueId);
    }

    /** 成功注入 SVC 播放管线的帧数（诊断用）。 */
    public static long getInjectedFrames() {
        return injectedFrames.get();
    }

    /** 会话断开时释放全部 Ghost。 */
    public static void removeAll() {
        for (String id : ghosts.keySet().toArray(new String[0])) {
            remove(id);
        }
        sequences.clear();
    }

    /**
     * 与 TS 频道成员对账：移除已不在服务器的 Ghost（幂等，由群组映射周期调用）。
     */
    public static void reconcile(Ts3VoiceClient client) {
        for (String tsUid : ghosts.keySet().toArray(new String[0])) {
            boolean stillOnServer = client.hasUid(tsUid);
            if (!stillOnServer) {
                remove(tsUid);
            }
        }
    }
}
