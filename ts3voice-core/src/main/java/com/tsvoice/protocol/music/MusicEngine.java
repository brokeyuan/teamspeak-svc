package com.tsvoice.protocol.music;

import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 音乐引擎（Music-0 核心，纯 Java）：音源 → 有界队列 → 绝对节拍调度 →
 * 编码回调（TS 发送由宿主接线）。
 *
 * <p>T37/E6 修订：</p>
 * <ul>
 *   <li><b>无损背压</b>：容量判定先于 {@code readFrame} —— 队列满时停止预读，
 *       不消耗音源帧；编号源在任意容量下完整、有序、恰好一次输出。</li>
 *   <li><b>CLOSED 终态</b>：{@link #close()} 后 {@link #play} 抛
 *       {@link IllegalStateException}；CLOSED 不可复活。</li>
 *   <li><b>PAUSED 阻塞等待</b>：worker 在暂停时 {@code await} 唤醒信号，不
 *       自旋（CPU 时间可验证）。</li>
 *   <li><b>playback generation</b>：每次 {@link #play}/{@link #cancel} 递增；
 *       sink 回调持有取出时绑定的代际 —— 返回后只有当前代际的帧计入提交，
 *       旧代帧按 {@code framesDroppedByPermit} 丢弃（旧源帧不混入新播放）。</li>
 *   <li><b>source 生命周期</b>：自然结束/解码失败/cancel/替换/close 均关闭
 *       owned source 恰好一次。</li>
 *   <li><b>控制操作不等待 sink</b>：pause/cancel/close 即时返回；sink 在锁外
 *       执行，慢/阻塞 sink 不拖垮控制面。</li>
 * </ul>
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
    /** playback generation（T37-4）：play/cancel 递增；出队帧绑定取出时代际。 */
    private long generation;

    public MusicEngine(FrameSink sink, LongSupplier nanoClock) {
        this(sink, nanoClock, DEFAULT_QUEUE_CAPACITY);
    }

    public MusicEngine(FrameSink sink, LongSupplier nanoClock, int capacity) {
        this.sink = sink;
        this.nanoClock = nanoClock;
        this.capacity = Math.max(1, capacity);
    }

    // ---- 播放控制 ----

    /** 开始播放一个音源（替换当前；旧音源关闭）。CLOSED 引擎拒绝。 */
    public void play(PcmSource newSource) {
        if (newSource == null) {
            throw new IllegalArgumentException("source required");
        }
        lock.lock();
        try {
            if (state == State.CLOSED) {
                throw new IllegalStateException("engine closed: play() is not allowed after close()");
            }
            PcmSource old = source;
            source = newSource;
            queue.clear();
            framesDecoded = 0;
            framesSubmitted = 0;
            framesDroppedByQueue = 0;
            framesDroppedByPermit = 0;
            failureReason = "";
            nextFrameAtNanos = nanoClock.getAsLong();
            generation++; // 新播放代：旧代在途帧废弃
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
                wakeup.signalAll(); // worker 从节拍等待转入暂停阻塞
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

    /** 取消播放：清队列、关音源，回到 IDLE（引擎可复用）。代际递增废弃旧帧。 */
    public void cancel() {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            queue.clear();
            PcmSource old = source;
            source = null;
            state = State.IDLE;
            failureReason = "";
            generation++;
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
            long myGeneration = 0;
            boolean endOfSource = false;
            lock.lock();
            try {
                // 暂停：阻塞等待（T37-3 不忙循环）；CLOSED/FAILED/ENDED/IDLE 退出
                while (state == State.PAUSED) {
                    try {
                        wakeup.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (state != State.PLAYING) {
                    return; // IDLE(取消)/CLOSED/FAILED/ENDED：退出
                }
                // 解码预读：填满队列（T37-1：容量判定先于 readFrame —— 满时
                // 停止预读，绝不消耗并丢弃音源帧）
                while (state == State.PLAYING && source != null && !endOfSource) {
                    if (queue.size() >= capacity) {
                        break; // 无损背压：队列满即停，源帧保留在音源
                    }
                    try {
                        short[] decoded = source.readFrame();
                        if (decoded == null) {
                            endOfSource = true;
                            break;
                        }
                        framesDecoded++;
                        queue.offer(decoded);
                    } catch (Exception e) {
                        state = State.FAILED;
                        failureReason = e.getMessage() == null ? e.toString() : e.getMessage();
                        PcmSource failed = source;
                        source = null;
                        if (failed != null) {
                            failed.close(); // T37-5b：失败释放 owned source
                        }
                        wakeup.signalAll();
                        return;
                    }
                }
                if (endOfSource && queue.isEmpty()) {
                    state = State.ENDED;
                    PcmSource old = source;
                    source = null;
                    if (old != null) {
                        old.close(); // T37-5a：自然结束释放
                    }
                    wakeup.signalAll();
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
                    myGeneration = generation; // 出队时绑定代际
                    nextFrameAtNanos += FRAME_NANOS;
                    break;
                }
                if (frame == null) {
                    if (state != State.PLAYING && state != State.PAUSED) {
                        return; // IDLE(取消)/CLOSED/FAILED/ENDED：退出
                    }
                    continue; // PAUSED 或队列暂时空：回到外层等待
                }
            } finally {
                lock.unlock();
            }
            // sink 在锁外执行（T37-6：慢/阻塞 sink 不拖垮控制面）
            final short[] outFrame = frame;
            final long frameGeneration = myGeneration;
            boolean accepted;
            try {
                accepted = sink.accept(outFrame);
            } catch (Exception e) {
                lock.lock();
                try {
                    if (state != State.CLOSED && frameGeneration == generation) {
                        state = State.FAILED;
                        failureReason = "sink failed: " + e.getMessage();
                    }
                    wakeup.signalAll();
                } finally {
                    lock.unlock();
                }
                return;
            }
            lock.lock();
            try {
                if (frameGeneration == generation && state != State.CLOSED && state != State.IDLE) {
                    // 当前代际的帧才计入提交/许可丢弃
                    framesSubmitted++;
                    if (!accepted) {
                        framesDroppedByPermit++;
                    }
                } else {
                    // T37-4：取消/替换后返回的旧代帧 —— 不进新播放
                    framesDroppedByPermit++;
                }
                wakeup.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            queue.clear();
            PcmSource old = source;
            source = null;
            if (old != null) {
                old.close(); // T37-5d：close 释放 owned source
            }
            wakeup.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
