package com.svcb.tsbridge.transport;

import com.svcb.tsbridge.TsBridgeMod;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步消费 MC 麦克风帧队列，调用 {@link TsAudioTransport#sendMcAudio} 转发到 TS。
 *
 * <p>设计要点：</p>
 * <ul>
 *     <li>SVC 插件线程（{@code MicrophonePacketEvent}）调用 {@link #enqueue} 入队，
 *         立即返回不阻塞 SVC 主循环；</li>
 *     <li>独立的 daemon 线程 "TsBridge-McToTs" 阻塞拉取队列并调用 transport；
 *         transport 内部又是 HTTP 异步发送，因此 forwarder 线程主要做"批量提交"工作；</li>
 *     <li>队列容量上限 1000，溢出时丢弃并计数（{@link #droppedCount}），
 *         每 100 次丢弃打印一次 WARN，避免内存爆掉；</li>
 *     <li>{@link #stop} 关闭线程并清空队列，幂等。</li>
 * </ul>
 */
public class McToTsForwarder {

    /** 队列容量上限，防止背压导致内存爆掉。 */
    private static final int QUEUE_CAPACITY = 1000;
    /** poll 超时时间，平衡 CPU 占用与响应延迟。 */
    private static final long POLL_TIMEOUT_MS = 10L;
    /** 丢弃日志采样间隔，每 N 次丢弃打一次 WARN。 */
    private static final int DROP_LOG_INTERVAL = 100;

    private final TsAudioTransport transport;
    private final BlockingQueue<McAudioFrame> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong droppedCount = new AtomicLong(0);

    private volatile Thread forwarderThread;
    private volatile boolean running = false;

    public McToTsForwarder(TsAudioTransport transport) {
        this.transport = transport;
    }

    /**
     * 启动 forwarder daemon 线程。幂等（重复调用不会创建多个线程）。
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::forwardLoop, "TsBridge-McToTs");
        t.setDaemon(true);
        t.start();
        this.forwarderThread = t;
        TsBridgeMod.LOGGER.info("[tsbridge] McToTsForwarder started (queueCapacity={})", QUEUE_CAPACITY);
    }

    /**
     * 入队一帧 MC 音频。不阻塞调用方；队列满时丢弃并计数。
     */
    public void enqueue(UUID mcUuid, byte[] opusData, long sequence) {
        if (!running) {
            return;
        }
        if (mcUuid == null || opusData == null || opusData.length == 0) {
            return;
        }
        if (!queue.offer(new McAudioFrame(mcUuid, opusData, sequence))) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped % DROP_LOG_INTERVAL == 1) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Audio queue full, dropping frames (totalDropped={})", dropped);
            }
        }
    }

    /**
     * 停止 forwarder 线程并清空队列。幂等。
     */
    public void stop() {
        running = false;
        Thread t = forwarderThread;
        if (t != null) {
            t.interrupt();
        }
        queue.clear();
        TsBridgeMod.LOGGER.info("[tsbridge] McToTsForwarder stopped (droppedTotal={})", droppedCount.get());
    }

    /** 返回累计丢弃帧数。 */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /** 返回当前队列剩余帧数。 */
    public int getQueueSize() {
        return queue.size();
    }

    private void forwardLoop() {
        while (running) {
            McAudioFrame frame;
            try {
                frame = queue.poll(POLL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running) {
                    break;
                }
                continue;
            }
            if (frame == null) {
                continue;
            }
            try {
                transport.sendMcAudio(frame.mcUuid(), frame.opusData(), frame.sequence());
            } catch (Exception e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Forwarder sendMcAudio threw (continuing): {}", e.getMessage());
            }
        }
        TsBridgeMod.LOGGER.info("[tsbridge] McToTsForwarder loop exited");
    }

    /** 内部不可变音频帧记录。 */
    private record McAudioFrame(UUID mcUuid, byte[] opusData, long sequence) {
    }
}
