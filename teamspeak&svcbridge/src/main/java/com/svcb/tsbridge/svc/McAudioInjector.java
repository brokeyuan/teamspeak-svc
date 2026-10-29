package com.svcb.tsbridge.svc;

import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.TsVoicechatPlugin;
import com.svcb.tsbridge.ghost.GhostPlayerInjector;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v2.1：将 TeamSpeak 侧的 Opus 音频注入到 SVC。
 *
 * <p>每个 TS 用户一个独立的 {@link StaticAudioChannel}（channelId = 该用户的 Ghost UUID），
 * 音频包的发送者即为该 Ghost —— MC 客户端据此显示说话高亮。通道的 targets 为
 * 桥接群组成员（修复 v2 创建 StaticAudioChannel 后从未 addTarget、无人能听到的问题）。</p>
 *
 * <p>AudioPlayer 的 audioSupplier 行为（修复 v2 在队列空时返回 null 导致播放线程
 * 被当作流结束而死亡的问题）：阻塞 poll 最多 {@link #IDLE_END_MS} 毫秒，取到帧则播放；
 * 超时返回 null 让播放线程自然结束（SVC 会 flush 通道、客户端停止说话高亮），
 * 下一帧音频到来时重建播放器，队列跨播放器生命周期保留，帧不丢失。</p>
 */
public final class McAudioInjector {

    private McAudioInjector() {
    }

    /** SVC 单帧 PCM 采样数：48kHz × 20ms = 960。 */
    private static final int FRAME_SIZE = 960;
    /** 每用户 PCM 帧缓冲上限（≈ 500ms），防止 TS 用户长时间静音后突发积压。 */
    private static final int QUEUE_CAPACITY = 25;
    /** 队列空闲多久后结束当前播放器（毫秒）。 */
    private static final long IDLE_END_MS = 500;

    /** ghostUuid → 注入上下文。 */
    private static final Map<UUID, TsUserAudio> users = new ConcurrentHashMap<>();

    /** targets 定时刷新（群组成员变化不产生事件，只能定期同步）。 */
    private static volatile ScheduledExecutorService refresher;
    private static final Object refresherLock = new Object();

    private static final AtomicLong droppedTotal = new AtomicLong(0);

    private static final class TsUserAudio {
        final UUID ghostUuid;
        final OpusDecoder decoder;
        final ArrayBlockingQueue<short[]> queue;
        final AtomicLong dropped = new AtomicLong(0);
        /** 跨帧余量：TS 帧长可配置（10-100ms），不足 960 的尾部拼到下一帧。仅在 playTsAudio（synchronized）中访问。 */
        short[] carry = new short[0];
        volatile StaticAudioChannel channel;
        volatile AudioPlayer player;
        volatile java.util.Set<UUID> appliedTargets = java.util.Set.of();

        TsUserAudio(UUID ghostUuid, OpusDecoder decoder) {
            this.ghostUuid = ghostUuid;
            this.decoder = decoder;
            this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        }
    }

    /**
     * 注入一帧 TS Opus 音频（在 transport 接收线程上调用）。
     *
     * <p>TS 客户端"每包帧数"可配置（10-100ms），解码长度不一定是 960；
     * SVC 的 AudioPlayer 硬性要求 960 采样帧，因此解码后按 960 重分块，
     * 不足一帧的尾部与下一帧拼接，保证不丢音也不变速。</p>
     *
     * @param ghostUuid TS 用户的 Ghost UUID（{@link GhostPlayerInjector#ghostUuidFor}）
     * @param opusData  TS 侧收到的 Opus 编码帧
     */
    public static void playTsAudio(UUID ghostUuid, byte[] opusData) {
        if (ghostUuid == null || opusData == null || opusData.length == 0) {
            return;
        }
        if (TsVoicechatPlugin.api == null) {
            return;
        }
        TsUserAudio user = users.computeIfAbsent(ghostUuid, McAudioInjector::createUser);
        if (user == null || user.channel == null || user.channel.isClosed()) {
            return;
        }
        synchronized (user) {

        // 1. 解码 Opus → PCM
        short[] pcm;
        try {
            pcm = user.decoder.decode(opusData);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to decode TS opus frame for {}: {}",
                    ghostUuid, e.getMessage());
            return;
        }
        if (pcm == null || pcm.length == 0) {
            return;
        }

        // 2. 拼接上帧余量后按 960 采样重分块（SVC AudioPlayer 只接受 960）
        short[] combined;
        if (user.carry.length > 0) {
            combined = new short[user.carry.length + pcm.length];
            System.arraycopy(user.carry, 0, combined, 0, user.carry.length);
            System.arraycopy(pcm, 0, combined, user.carry.length, pcm.length);
        } else {
            combined = pcm;
        }
        int fullFrames = combined.length / FRAME_SIZE;
        int carryLen = combined.length % FRAME_SIZE;
        if (fullFrames == 0) {
            user.carry = combined.clone();
            return;
        }
        user.carry = new short[carryLen];
        System.arraycopy(combined, combined.length - carryLen, user.carry, 0, carryLen);

        for (int i = 0; i < fullFrames; i++) {
            short[] frame = new short[FRAME_SIZE];
            System.arraycopy(combined, i * FRAME_SIZE, frame, 0, FRAME_SIZE);

            // 3. 入队（满了丢最旧的，保证新帧优先）
            if (!user.queue.offer(frame)) {
                user.queue.poll();
                user.queue.offer(frame);
                long dropped = droppedTotal.incrementAndGet();
                user.dropped.incrementAndGet();
                if (dropped % 100 == 1) {
                    TsBridgeMod.LOGGER.warn("[tsbridge] TS audio queue full, dropping oldest frames (totalDropped={})",
                            dropped);
                }
            }
        }

        // 4. 确保播放器处于运行态（空闲结束后重建）
        ensurePlayer(user);
        }
    }

    /**
     * 创建一个 TS 用户的注入上下文：解码器 + 独立 StaticAudioChannel（挂 targets）。
     * 返回 null 表示创建失败（SVC API 不可用）。
     */
    private static TsUserAudio createUser(UUID ghostUuid) {
        try {
            StaticAudioChannel channel = TsVoicechatPlugin.api.createStaticAudioChannel(ghostUuid);
            if (channel == null) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Failed to create StaticAudioChannel for TS user {} (api returned null)",
                        ghostUuid);
                return null;
            }
            OpusDecoder decoder = TsVoicechatPlugin.api.createDecoder();
            TsUserAudio user = new TsUserAudio(ghostUuid, decoder);
            user.channel = channel;
            applyTargets(user);
            startRefresher();
            TsBridgeMod.LOGGER.info("[tsbridge] TS audio injection channel created for ghost {}", ghostUuid);
            return user;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to create TS audio injection for {}: {}",
                    ghostUuid, e.getMessage(), e);
            return null;
        }
    }

    private static void ensurePlayer(TsUserAudio user) {
        AudioPlayer current = user.player;
        if (current != null && !current.isStopped()) {
            return;
        }
        // AudioPlayerImpl 结束时会 close 它的 encoder，因此每次重建都要新 encoder
        OpusEncoder encoder = TsVoicechatPlugin.api.createEncoder();
        if (encoder == null) {
            return;
        }
        AudioPlayer newPlayer = TsVoicechatPlugin.api.createAudioPlayer(
                user.channel, encoder, () -> supplyAudio(user));
        user.player = newPlayer;
        newPlayer.startPlaying();
    }

    /**
     * AudioPlayer 的 audioSupplier：阻塞取帧；空闲超时返回 null 结束播放器
     * （SVC 会 flush 通道并关闭自己的 encoder，客户端随即停止说话高亮）。
     */
    private static short[] supplyAudio(TsUserAudio user) {
        try {
            return user.queue.poll(IDLE_END_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 刷新所有 TS 音频通道的 targets 为当前桥接群组成员。
     * 群组成员查询失败时回退为全部在线 SVC 玩家。
     */
    public static void refreshTargets() {
        if (users.isEmpty()) {
            return;
        }
        List<UUID> members = GhostPlayerInjector.getBridgeGroupMemberUuids();
        for (TsUserAudio user : users.values()) {
            applyTargets(user, members);
        }
    }

    private static void applyTargets(TsUserAudio user) {
        applyTargets(user, GhostPlayerInjector.getBridgeGroupMemberUuids());
    }

    private static void applyTargets(TsUserAudio user, List<UUID> members) {
        StaticAudioChannel channel = user.channel;
        if (channel == null || channel.isClosed()) {
            return;
        }
        var next = new java.util.LinkedHashSet<UUID>();
        for (UUID member : members == null ? List.<UUID>of() : members) {
            var conn = OnlinePlayerRegistry.get(member);
            if (conn != null) {
                next.add(member);
            }
        }
        var immutableNext = java.util.Set.copyOf(next);
        if (immutableNext.equals(user.appliedTargets)) {
            return; // Avoid SVC's non-atomic clear/add window when membership is unchanged.
        }
        try {
            channel.clearTargets();
            for (UUID member : next) {
                var conn = OnlinePlayerRegistry.get(member);
                if (conn != null) {
                    channel.addTarget(conn);
                }
            }
            user.appliedTargets = immutableNext;
        } catch (Exception e) {
            user.appliedTargets = java.util.Set.of();
            TsBridgeMod.LOGGER.debug("[tsbridge] Failed to apply targets for TS audio channel {}: {}",
                    user.ghostUuid, e.getMessage());
        }
    }

    /**
     * 启动 targets 定时刷新（每 5 秒，daemon）。
     */
    private static void startRefresher() {
        synchronized (refresherLock) {
            if (refresher != null) {
                return;
            }
            refresher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TsBridge-TsAudioTargets");
                t.setDaemon(true);
                return t;
            });
            refresher.scheduleWithFixedDelay(() -> {
                try {
                    refreshTargets();
                } catch (Exception e) {
                    TsBridgeMod.LOGGER.debug("[tsbridge] TS audio target refresh failed: {}", e.getMessage());
                }
            }, 5, 5, TimeUnit.SECONDS);
        }
    }

    /**
     * 停止指定 TS 用户的音频注入并释放所有 SVC 资源。
     */
    public static void stopTsAudio(UUID ghostUuid) {
        if (ghostUuid == null) {
            return;
        }
        TsUserAudio user = users.remove(ghostUuid);
        if (user == null) {
            return;
        }
        synchronized (user) {
        AudioPlayer player = user.player;
        if (player != null) {
            try {
                player.stopPlaying();
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Error stopping AudioPlayer for TS {}: {}",
                        ghostUuid, e.getMessage());
            }
        }
        try {
            if (!user.decoder.isClosed()) {
                user.decoder.close();
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Error closing OpusDecoder for TS {}: {}",
                    ghostUuid, e.getMessage());
        }
        try {
            if (user.channel != null && !user.channel.isClosed()) {
                user.channel.flush();
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Error flushing channel for TS {}: {}",
                    ghostUuid, e.getMessage());
        }

        if (user.dropped.get() > 0) {
            TsBridgeMod.LOGGER.info("[tsbridge] TS audio for {} stopped (dropped {} frames)",
                    ghostUuid, user.dropped.get());
        }
        }
    }

    /**
     * 关闭所有 TS 用户的注入通道。在 SERVER_STOPPING 中调用。
     */
    public static synchronized void shutdownAll() {
        List<UUID> ids = new ArrayList<>(users.keySet());
        for (UUID id : ids) {
            stopTsAudio(id);
        }
        users.clear();
        ScheduledExecutorService r = refresher;
        if (r != null) {
            r.shutdownNow();
            synchronized (refresherLock) {
                refresher = null;
            }
        }
        TsBridgeMod.LOGGER.info("[tsbridge] McAudioInjector shut down (all TS audio channels released)");
    }
}
