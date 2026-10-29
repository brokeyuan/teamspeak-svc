package com.svcb.tsbridge.audio;

import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.TsVoicechatPlugin;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * 多人混音器（MC→TS 方向）。
 * 将多个 MC 玩家的 Opus 帧解码为 PCM 后饱和加法混音为一路，再编码为 Opus 输出。
 *
 * <p>混音算法参考 voicechat-discord-main 的 {@code combine_audio_parts}（Rust 版）：
 * 逐采样点 i32 求和 → clamp 到 i16 范围。</p>
 *
 * <p>v2.1 修复：</p>
 * <ul>
 *     <li><b>节拍</b>：用绝对时钟调度（nextTick += 20ms），不再用 sleep(20) 累积漂移
 *         导致队列持续积压、延迟无限增长；</li>
 *     <li><b>单人透传</b>：本 tick 只有 1 个活跃 sender 时直接转发原始 Opus，
 *         零编解码开销；</li>
 *     <li><b>解码位置</b>：解码移到混音线程（入队只存原始 Opus，热路径更轻）；</li>
 *     <li><b>队列上限</b>：每 sender 有界队列（满时丢最旧帧并计数），防止慢消费积压 OOM。</li>
 * </ul>
 */
public class McAudioMixer {

    /** SVC 标准帧采样数：48kHz × 20ms */
    public static final int FRAME_SIZE = 960;
    /** 混音间隔（毫秒），与 Opus 帧长一致 */
    private static final int MIX_INTERVAL_MS = 20;
    /** 每 sender 帧缓冲上限（≈ 2 秒 @20ms） */
    private static final int SENDER_QUEUE_CAPACITY = 100;
    /** 节拍落后超过该值时重置基准，避免突发追赶（纳秒） */
    private static final long MAX_CATCHUP_NS = 100_000_000L;
    /** sender 空闲多久后回收队列与解码器（原生 Opus 解码器持有本地内存，不能无限累积） */
    private static final long SENDER_IDLE_EVICTION_MS = 300_000L;
    /** 突发积压开始丢弃旧帧的阈值（约 160ms） */
    private static final int BACKLOG_DRAIN_THRESHOLD = 8;
    /** 丢弃旧帧后保留的低延迟队列水位 */
    private static final int BACKLOG_TARGET_FRAMES = 3;

    /** 每个 MC 玩家的原始 Opus 帧队列（有界） */
    private final Map<UUID, ArrayBlockingQueue<byte[]>> senderQueues = new ConcurrentHashMap<>();
    /** 每个 MC 玩家的 Opus 解码器（仅在混音线程使用） */
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    /** 每个 MC 玩家最近一次说话时间（回收依据） */
    private final Map<UUID, Long> senderLastSeen = new ConcurrentHashMap<>();
    /** Opus 编码器（混音输出用，仅混音线程使用） */
    private OpusEncoder outputEncoder;
    /** 丢弃帧计数器 */
    private final AtomicLong droppedCount = new AtomicLong(0);

    /** 混音输出回调（通常直接调用 TsAudioTransport.sendMcAudio） */
    private volatile Consumer<byte[]> outputConsumer;
    /** 当前活跃的 sender 数量（用于判断单人/多人） */
    private volatile int activeSenderCount = 0;

    private volatile Thread mixerThread;
    private volatile boolean running = false;

    /**
     * 设置混音输出回调。在 start 前调用。
     *
     * @param consumer 接收混音后的 Opus 字节数组
     */
    public void setOutputConsumer(Consumer<byte[]> consumer) {
        this.outputConsumer = consumer;
    }

    /**
     * 启动混音线程。幂等。
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::mixLoop, "TsBridge-Mixer");
        t.setDaemon(true);
        t.start();
        mixerThread = t;
        TsBridgeMod.LOGGER.info("[tsbridge] McAudioMixer started (interval={}ms)", MIX_INTERVAL_MS);
    }

    /**
     * 停止混音线程并清空所有队列。先等待混音线程退出，再关闭原生编解码器。
     */
    public void stop() {
        synchronized (this) {
            running = false;
        }
        Thread t = mixerThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (t != null && t.isAlive()) {
            TsBridgeMod.LOGGER.error("[tsbridge] Mixer thread did not stop in 5s; native codecs will be closed by its finally block");
            return;
        }
        closeResources();
        mixerThread = null;
        TsBridgeMod.LOGGER.info("[tsbridge] McAudioMixer stopped (droppedTotal={})", droppedCount.get());
    }

    private synchronized void closeResources() {
        senderQueues.clear();
        senderLastSeen.clear();
        for (OpusDecoder decoder : decoders.values()) {
            try {
                if (!decoder.isClosed()) {
                    decoder.close();
                }
            } catch (Exception ignored) {
            }
        }
        decoders.clear();
        OpusEncoder enc = outputEncoder;
        if (enc != null && !enc.isClosed()) {
            try {
                enc.close();
            } catch (Exception ignored) {
            }
        }
        outputEncoder = null;
    }

    /**
     * 入队一帧 MC 玩家的原始 Opus 数据。
     * 不解码不阻塞（SVC 主线程热路径）；队列满时丢最旧帧并计数。
     *
     * @param senderUuid MC 玩家 UUID
     * @param opusData   Opus 编码的音频数据
     */
    public void enqueue(UUID senderUuid, byte[] opusData) {
        if (!running || senderUuid == null || opusData == null || opusData.length == 0) {
            return;
        }
        senderLastSeen.put(senderUuid, System.currentTimeMillis());
        ArrayBlockingQueue<byte[]> q = senderQueues.computeIfAbsent(senderUuid,
                k -> new ArrayBlockingQueue<>(SENDER_QUEUE_CAPACITY));
        if (!q.offer(opusData)) {
            q.poll();
            q.offer(opusData);
            droppedCount.incrementAndGet();
        }
    }

    /**
     * 返回累计丢弃帧数（status 命令用）。
     */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /**
     * 返回当前活跃 sender 数。
     */
    public int getActiveSenderCount() {
        return activeSenderCount;
    }

    /**
     * 返回是否正在运行。
     */
    public boolean isRunning() {
        return running;
    }

    // ---- 混音主循环 ----

    private void mixLoop() {
        // 绝对时钟节拍：每 20ms 一个 tick，处理耗时不会累积漂移
        long nextTickNs = System.nanoTime() + MIX_INTERVAL_MS * 1_000_000L;
        while (running) {
            long now = System.nanoTime();
            if (now < nextTickNs) {
                LockSupport.parkNanos(nextTickNs - now);
                if (!running) {
                    break;
                }
            }
            // 落后太多（如 GC 停顿/系统挂起）时重置基准，避免突发追赶
            if (nextTickNs - now < -MAX_CATCHUP_NS) {
                nextTickNs = now + MIX_INTERVAL_MS * 1_000_000L;
            } else {
                nextTickNs += MIX_INTERVAL_MS * 1_000_000L;
            }

            try {
                tick();
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Mixer tick failed: {}", e.getMessage());
            }
        }
        TsBridgeMod.LOGGER.info("[tsbridge] McAudioMixer loop exited");
        closeResources();
    }

    /**
     * 单个混音 tick：每 sender 取一帧 → 单人透传 / 多人混音 → 输出；
     * 顺带回收长期空闲的 sender 资源（队列 + 原生解码器）。
     */
    private void tick() {
        evictIdleSenders();

        Map<UUID, byte[]> frames = new LinkedHashMap<>();
        for (Map.Entry<UUID, ArrayBlockingQueue<byte[]>> entry : senderQueues.entrySet()) {
            ArrayBlockingQueue<byte[]> queue = entry.getValue();
            drainStaleBacklog(queue);
            byte[] opus = queue.poll();
            if (opus != null) {
                frames.put(entry.getKey(), opus);
            }
        }
        activeSenderCount = frames.size();

        if (frames.isEmpty()) {
            // 无声：可配置发送静音帧
            if (TsBridgeMod.config != null && TsBridgeMod.config.sendSilenceFrames) {
                emitMixedOpus(new short[FRAME_SIZE]);
            }
            return;
        }

        if (frames.size() == 1) {
            // 单人：透传原始 Opus（零编解码）
            emitRawOpus(frames.values().iterator().next());
            return;
        }

        // 多人：解码 → 饱和加法 → 编码
        short[][] parts = new short[frames.size()][];
        int i = 0;
        for (Map.Entry<UUID, byte[]> e : frames.entrySet()) {
            parts[i++] = decode(e.getKey(), e.getValue());
        }
        emitMixedOpus(combineAudioParts(parts));
    }

    /** Bounds sudden queue growth by dropping the oldest stale frames, not playing them late. */
    private void drainStaleBacklog(ArrayBlockingQueue<byte[]> queue) {
        while (queue.size() > BACKLOG_DRAIN_THRESHOLD) {
            if (queue.poll() == null) {
                return;
            }
            droppedCount.incrementAndGet();
        }
        while (queue.size() > BACKLOG_TARGET_FRAMES) {
            if (queue.poll() == null) {
                return;
            }
            droppedCount.incrementAndGet();
        }
    }

    /**
     * 回收空闲 sender：队列已空且 5 分钟无新帧 → 移除队列、关闭并移除解码器。
     * 仅混音线程调用，无并发问题。
     */
    private void evictIdleSenders() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : senderLastSeen.entrySet()) {
            if (now - e.getValue() < SENDER_IDLE_EVICTION_MS) {
                continue;
            }
            UUID sender = e.getKey();
            ArrayBlockingQueue<byte[]> q = senderQueues.get(sender);
            if (q != null && !q.isEmpty()) {
                continue; // 仍有待处理帧，等排空后再回收
            }
            senderLastSeen.remove(sender);
            senderQueues.remove(sender);
            OpusDecoder decoder = decoders.remove(sender);
            if (decoder != null && !decoder.isClosed()) {
                try {
                    decoder.close();
                } catch (Exception ignored) {
                }
            }
            TsBridgeMod.LOGGER.debug("[tsbridge] Mixer evicted idle sender {}", sender);
        }
    }

    /**
     * 解码一帧 Opus（仅混音线程调用）。失败返回静音帧，保证混音对齐。
     */
    private short[] decode(UUID sender, byte[] opusData) {
        OpusDecoder decoder = decoders.computeIfAbsent(sender, k -> {
            if (TsVoicechatPlugin.api == null) {
                return null;
            }
            return TsVoicechatPlugin.api.createDecoder();
        });
        if (decoder == null || decoder.isClosed()) {
            return new short[FRAME_SIZE];
        }
        try {
            short[] pcm = decoder.decode(opusData);
            if (pcm != null && pcm.length == FRAME_SIZE) {
                return pcm;
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Mixer: Opus decode failed for {}: {}", sender, e.getMessage());
        }
        return new short[FRAME_SIZE];
    }

    /**
     * 直接输出原始 Opus 帧（单人透传路径）。
     */
    private void emitRawOpus(byte[] opus) {
        Consumer<byte[]> consumer = outputConsumer;
        if (consumer == null || opus == null || opus.length == 0) {
            return;
        }
        consumer.accept(opus);
    }

    /**
     * 将混音后的 PCM 编码为 Opus 并通过 outputConsumer 输出。
     */
    private void emitMixedOpus(short[] pcm) {
        Consumer<byte[]> consumer = outputConsumer;
        if (consumer == null) {
            return;
        }
        if (TsVoicechatPlugin.api == null) {
            return;
        }
        if (outputEncoder == null || outputEncoder.isClosed()) {
            outputEncoder = TsVoicechatPlugin.api.createEncoder();
        }
        if (outputEncoder == null) {
            return;
        }
        try {
            byte[] opus = outputEncoder.encode(pcm);
            if (opus != null && opus.length > 0) {
                consumer.accept(opus);
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Mixer: Opus encode failed: {}", e.getMessage());
        }
    }

    /**
     * 饱和加法混合多路 PCM。
     *
     * <p>参考 Discord bridge 的 {@code combine_audio_parts}：
     * {@code mixed[i] = clamp(sum(all inputs[i]), i16::MIN, i16::MAX)}</p>
     *
     * @param parts 多路 PCM short[960]
     * @return 混合后的 PCM short[960]
     */
    public static short[] combineAudioParts(short[]... parts) {
        if (parts == null || parts.length == 0) {
            return new short[FRAME_SIZE];
        }
        if (parts.length == 1) {
            return parts[0];
        }
        int[] sums = new int[FRAME_SIZE];
        for (short[] part : parts) {
            for (int i = 0; i < Math.min(FRAME_SIZE, part.length); i++) {
                sums[i] += part[i];
            }
        }
        short[] result = new short[FRAME_SIZE];
        for (int i = 0; i < FRAME_SIZE; i++) {
            result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sums[i]));
        }
        return result;
    }
}
