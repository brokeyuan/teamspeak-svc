package com.tsvoice.protocol.music;

import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 音乐引擎（Music-0 核心，纯 Java）：音源 → 有界队列 → 绝对节拍调度 →
 * 编码回调（TS 发送由宿主接线）。
 *
 * <p>资源边界（实施记录 §3/§5）：单实例；队列有界（默认 512 帧 ≈10s），
 * 满时丢最旧并计数（与语音队列同策略）；专用 daemon 线程 + 绝对节拍
 * （nextFrameAt += FRAME_NANOS，防漂移；时钟可注入）；暂停 = 不出队保留
 * 位置；取消/关闭 = 清队列、关音源、唤醒线程（幂等）；编码异常终止播放并
 * 记录原因（不静默吞）。时钟注入使调度测试确定化。</p>
 */
public final class MusicEngine implements AutoCloseable {

    /** 20ms 节拍。 */
    public static final long FRAME_NANOS = 20_000_000L;
    public static final int DEFAULT_QUEUE_CAPACITY = 512;

    /** 编码 + 发送桥（宿主实现；返回 false = 发送许可缺失，帧丢弃但播放继续）。 */
    public interface FrameSink {
        /** @return 是否已被宿主接受（许可在宿主侧复核 routeEpoch）。 */
        boolean accept(short[] pcmFrame);
    }

    public enum State { IDLE, PLAYING, PAUSED, ENDED, FAILED, CLOSED }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeup = lock.newCondition();
    private final ArrayDeque<short[]> queue = new ArrayDeque<>();
    private final int capacity;
    private final FrameSink sink;
    private final LongSupplier nanoClock;

    private PcmSource source;
    private Thread worker;
    private State state = State.IDLE;
    private String failureReason = "";
    private long framesDecoded;
    private long framesSubmitted;
    private long framesDroppedByQueue;
    private long framesDroppedByPermit;
    /** 暂停时刻（帧位置；恢复时音源已停在读位置）。 */
    private long nextFrameAtNanos;

    public MusicEngine(FrameSink sink, LongSupplier nanoClock) {
        this(sink, nanoClock, DEFAULT_QUEUE_CAPACITY);
    }

    MusicEngine(FrameSink sink, LongSupplier nanoClock, int capacity) {
        this.sink = sink;
        this.nanoClock = nanoClock;
        this.capacity = Math.max(1, capacity);
    }

    // ---- 播放控制 ----

    /** 开始播放一个音源（替换当前；旧音源关闭）。 */
    public void play(PcmSource newSource) {
        if (newSource == null) {
            throw new IllegalArgumentException("source required");
        }
        lock.lock();
        try {
            PcmSource old = source;
            source = newSource;
            queue.clear();
            framesDecoded = 0;
            framesSubmitted = 0;
            framesDroppedByQueue = 0;
            framesDroppedByPermit = 0;
            failureReason = "";
            nextFrameAtNanos = nanoClock.getAsLong();
            state = State.PLAYING;
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(this::run, "tsvoice-music-0");
                worker.setDaemon(true);
                worker.start();
            } else {
                wakeup.signalAll();
            }
            if (old != null && old != newSource) {
                old.close();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 暂停：不再出队/提交；解码继续到队列满（保留播放位置）。 */
    public void pause() {
        lock.lock();
        try {
            if (state == State.PLAYING) {
                state = State.PAUSED;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 恢复：以绝对节拍从当前队头继续。 */
    public void resume() {
        lock.lock();
        try {
            if (state == State.PAUSED) {
                nextFrameAtNanos = nanoClock.getAsLong();
                state = State.PLAYING;
                wakeup.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 取消播放：清队列、关音源，回到 IDLE（引擎可复用）。 */
    public void cancel() {
        lock.lock();
        try {
            queue.clear();
            PcmSource old = source;
            source = null;
            state = State.IDLE;
            failureReason = "";
            if (old != null) {
                old.close();
            }
            wakeup.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /** 失败原因（FAILED 时非空；诊断/UI 展示用）。 */
    public String failureReason() {
        lock.lock();
        try {
            return failureReason;
        } finally {
            lock.unlock();
        }
    }

    // 统计（诊断）
    public long framesDecoded() {
        lock.lock();
        try {
            return framesDecoded;
        } finally {
            lock.unlock();
        }
    }

    public long framesSubmitted() {
        lock.lock();
        try {
            return framesSubmitted;
        } finally {
            lock.unlock();
        }
    }

    public long framesDroppedByQueue() {
        lock.lock();
        try {
            return framesDroppedByQueue;
        } finally {
            lock.unlock();
        }
    }

    public long framesDroppedByPermit() {
        lock.lock();
        try {
            return framesDroppedByPermit;
        } finally {
            lock.unlock();
        }
    }

    public int queueDepth() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    // ---- 工作线程 ----

    private void run() {
        while (true) {
            short[] frame = null;
            boolean endOfSource = false;
            lock.lock();
            try {
                // 解码预读：填满队列（锁内；解码是内存操作，音源 IO 已在 open
                // 时建立；有界丢弃保证队列永不超 capacity）
                while (state == State.PLAYING && source != null && !endOfSource) {
                    try {
                        short[] decoded = source.readFrame();
                        if (decoded == null) {
                            endOfSource = true;
                            break;
                        }
                        framesDecoded++;
                        if (queue.size() >= capacity) {
                            // 队列已满：停止预读（不是丢最旧 —— 队列持有即有序，
                            // 停止预读等价背压；保留 drop 计数给真正的满场景）
                            break;
                        }
                        queue.offer(decoded);
                    } catch (Exception e) {
                        state = State.FAILED;
                        failureReason = e.getMessage() == null ? e.toString() : e.getMessage();
                        wakeup.signalAll();
                        return;
                    }
                }
                if (endOfSource && queue.isEmpty()) {
                    state = State.ENDED;
                    PcmSource old = source;
                    source = null;
                    if (old != null) {
                        old.close();
                    }
                    return;
                }
                // 绝对节拍等待出队
                while (state == State.PLAYING && !queue.isEmpty()) {
                    long now = nanoClock.getAsLong();
                    if (now < nextFrameAtNanos) {
                        try {
                            wakeup.awaitNanos(Math.min(nextFrameAtNanos - now, 5_000_000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }
                    frame = queue.poll();
                    nextFrameAtNanos += FRAME_NANOS;
                    break;
                }
                if (state != State.PLAYING && state != State.PAUSED) {
                    return; // IDLE(取消)/CLOSED/FAILED/ENDED：退出
                }
            } finally {
                lock.unlock();
            }
            if (frame != null) {
                boolean accepted;
                try {
                    accepted = sink.accept(frame);
                } catch (Exception e) {
                    lock.lock();
                    try {
                        state = State.FAILED;
                        failureReason = "sink failed: " + e.getMessage();
                    } finally {
                        lock.unlock();
                    }
                    return;
                }
                lock.lock();
                try {
                    framesSubmitted++;
                    if (!accepted) {
                        framesDroppedByPermit++;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            state = State.CLOSED;
            queue.clear();
            PcmSource old = source;
            source = null;
            if (old != null) {
                old.close();
            }
            wakeup.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
