package com.tsvoice;

import com.tsvoice.protocol.music.MusicEngine;
import com.tsvoice.protocol.music.PcmSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T37/E6 回归：MusicEngine 正确性边界。
 *
 * <p>81c687c 复核反例 → 修复后必须 PASS：</p>
 * <ul>
 *   <li>T37-1：容量判定先于 readFrame —— 编号源 1..6 在容量 2 下完整、
 *       有序、恰好一次输出（旧实现满队列仍消耗一帧并静默丢弃，输出
 *       [1,2,4,6]）。</li>
 *   <li>T37-2：close 后 play 拒绝（CLOSED 是终态，不再复活引擎）。</li>
 *   <li>T37-3：PAUSED 阻塞等待不忙循环（暂停期间 worker CPU 时间不增长）。</li>
 *   <li>T37-4：cancel/replacement 独立 playback generation —— 旧代已取出
 *       的帧不得进入新播放（慢 sink 返回后旧帧被丢弃）。</li>
 *   <li>T37-5：自然结束/失败/取消/close 均释放 owned source（close 恰好一次）。</li>
 *   <li>T37-6：慢 sink 不让控制操作（pause/cancel/close）永久等待。</li>
 * </ul>
 */
public class MusicEngineBoundaryTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** FakeClock（确定性节拍）。 */
    static final class FakeClock implements java.util.function.LongSupplier {
        private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        void advance(long delta) {
            nanos.addAndGet(delta);
        }
        @Override
        public long getAsLong() {
            return nanos.get();
        }
    }

    /** 编号源（确定性序列 lo..hi，用于代际值域分离）。 */
    static PcmSource numbered(int lo, int hi, AtomicInteger closedCount) {
        AtomicInteger i = new AtomicInteger(lo - 1);
        return new PcmSource() {
            @Override
            public short[] readFrame() {
                if (i.get() >= hi) {
                    return null;
                }
                short[] f = new short[PcmSource.FRAME_SAMPLES];
                f[0] = (short) i.incrementAndGet();
                return f;
            }
            @Override
            public void close() {
                closedCount.incrementAndGet();
            }
        };
    }

    public static void main(String[] args) throws Exception {
        // ===== T37-1：容量判定先于 readFrame（无损背压） =====
        for (int capacity : new int[]{1, 2, 512}) {
            FakeClock clock = new FakeClock();
            List<Integer> got = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            MusicEngine e = new MusicEngine(frame -> {
                got.add((int) frame[0]);
                clock.advance(MusicEngine.FRAME_NANOS);
                if (got.size() >= 6) {
                    done.countDown();
                }
                return true;
            }, clock::getAsLong, capacity);
            AtomicInteger closed = new AtomicInteger();
            e.play(numbered(1, 6, closed));
            check(done.await(5, TimeUnit.SECONDS), "T37-1: capacity=" + capacity + " drains all 6 frames");
            Thread.sleep(100);
            check(got.equals(List.of(1, 2, 3, 4, 5, 6)),
                    "T37-1: capacity=" + capacity + " output complete, ordered, exactly-once (got " + got + ")");
            check(e.state() == MusicEngine.State.ENDED, "T37-1: capacity=" + capacity + " ends naturally");
            e.close();
        }

        // ===== T37-2：close 后 play 拒绝（终态） =====
        {
            FakeClock clock = new FakeClock();
            MusicEngine e = new MusicEngine(frame -> true, clock::getAsLong);
            AtomicInteger closed = new AtomicInteger();
            e.play(numbered(1, 3, closed));
            clock.advance(MusicEngine.FRAME_NANOS * 10);
            Thread.sleep(100);
            e.close();
            check(e.state() == MusicEngine.State.CLOSED, "T37-2: close is terminal");
            boolean rejected = false;
            try {
                e.play(numbered(1, 3, closed));
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            check(rejected, "T37-2: play() after close() is rejected (CLOSED is terminal)");
            check(e.state() == MusicEngine.State.CLOSED, "T37-2: state remains CLOSED after rejected play");
        }

        // ===== T37-3：PAUSED 阻塞等待（不忙循环） =====
        {
            FakeClock clock = new FakeClock();
            MusicEngine e = new MusicEngine(frame -> true, clock::getAsLong, 4);
            AtomicInteger closed = new AtomicInteger();
            e.play(numbered(1, 1000, closed));
            Thread.sleep(150); // 让 worker 进入运行
            e.pause();
            Thread worker = workerThread(e);
            java.lang.management.ThreadMXBean beans =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            long tid = worker == null ? -1 : worker.getId();
            boolean cpuSupported = tid > 0 && beans.isThreadCpuTimeSupported()
                    && beans.isThreadCpuTimeEnabled();
            if (!cpuSupported) {
                System.out.println("SKIP T37-3: CPU time not available on this platform");
            } else {
                long cpu0 = beans.getThreadCpuTime(tid);
                // 假时钟不动：真实 300ms 内 worker 不应自旋消耗 CPU
                Thread.sleep(300);
                long cpu1 = beans.getThreadCpuTime(tid);
                check(cpu1 - cpu0 < 50_000_000L,
                        "T37-3: paused worker does not busy-loop (cpu delta "
                                + (cpu1 - cpu0) / 1_000_000 + "ms < 50ms over 300ms wall)");
            }
            e.cancel();
            e.close();
        }

        // ===== T37-4：cancel/replacement 独立 playback generation =====
        {
            FakeClock clock = new FakeClock();
            List<Integer> got = new java.util.concurrent.CopyOnWriteArrayList<>();
            // 慢 sink：第一帧阻塞，模拟编码器卡顿期间用户 cancel + 换源
            CountDownLatch firstFrame = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<short[]> inFlight = new AtomicReference<>();
            MusicEngine e = new MusicEngine(frame -> {
                if (firstFrame.getCount() > 0) {
                    inFlight.set(frame);
                    firstFrame.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }
                got.add((int) frame[0]);
                clock.advance(MusicEngine.FRAME_NANOS);
                return true;
            }, clock::getAsLong, 8);
            AtomicInteger closedA = new AtomicInteger();
            e.play(numbered(100, 149, closedA)); // 源 A（值域 100..149，与 B 不相交）
            firstFrame.await(2, TimeUnit.SECONDS);
            short[] staleFrame = inFlight.get();
            check(staleFrame != null && staleFrame[0] >= 100,
                    "T37-4: a frame of generation A is in flight at the sink");
            // 替换：新源 B（值域 1..3）—— A 的所有在途/排队帧必须全部废弃
            e.play(numbered(1, 3, new AtomicInteger())); // 源 B
            clock.advance(MusicEngine.FRAME_NANOS * 4);
            release.countDown(); // 释放 A 代的慢帧
            Thread.sleep(200);
            boolean staleNotPlayed = got.stream().noneMatch(v -> v >= 100)
                    && got.equals(List.of(1, 2, 3));
            check(staleNotPlayed,
                    "T37-4: frames of the cancelled generation never reach playback after replacement (got " + got + ")");
            e.close();
        }

        // ===== T37-5：所有终态释放 owned source（close 恰好一次） =====
        {
            // 5a 自然结束
            FakeClock clock = new FakeClock();
            AtomicInteger closedA = new AtomicInteger();
            MusicEngine e = new MusicEngine(frame -> {
                clock.advance(MusicEngine.FRAME_NANOS);
                return true;
            }, clock::getAsLong, 4);
            e.play(numbered(1, 2, closedA));
            long deadline = System.currentTimeMillis() + 3000;
            while (e.state() != MusicEngine.State.ENDED && System.currentTimeMillis() < deadline) {
                clock.advance(MusicEngine.FRAME_NANOS);
                Thread.sleep(20);
            }
            Thread.sleep(100);
            check(closedA.get() == 1, "T37-5a: natural end closes the source exactly once");
            e.close();

            // 5b 失败释放
            AtomicInteger closedB = new AtomicInteger();
            MusicEngine bad = new MusicEngine(frame -> true, new FakeClock()::getAsLong, 4);
            bad.play(new PcmSource() {
                @Override
                public short[] readFrame() throws java.io.IOException {
                    throw new java.io.IOException("decoder exploded");
                }
                @Override
                public void close() {
                    closedB.incrementAndGet();
                }
            });
            Thread.sleep(200);
            check(bad.state() == MusicEngine.State.FAILED && closedB.get() == 1,
                    "T37-5b: decode failure -> FAILED and the source is closed exactly once");
            bad.close();

            // 5c cancel 释放
            AtomicInteger closedC = new AtomicInteger();
            FakeClock clock2 = new FakeClock();
            MusicEngine cc = new MusicEngine(frame -> true, clock2::getAsLong, 4);
            cc.play(numbered(1, 1000, closedC));
            Thread.sleep(100);
            cc.cancel();
            Thread.sleep(100);
            check(closedC.get() == 1, "T37-5c: cancel closes the source exactly once");
            cc.close();

            // 5d close 释放
            AtomicInteger closedD = new AtomicInteger();
            MusicEngine dd = new MusicEngine(frame -> true, new FakeClock()::getAsLong, 4);
            dd.play(numbered(1, 1000, closedD));
            Thread.sleep(100);
            dd.close();
            Thread.sleep(100);
            check(closedD.get() == 1, "T37-5d: close closes the source exactly once");
        }

        // ===== T37-6：慢 sink 不阻塞控制操作 =====
        {
            FakeClock clock = new FakeClock();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MusicEngine e = new MusicEngine(frame -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }, clock::getAsLong, 8);
            AtomicInteger closed = new AtomicInteger();
            e.play(numbered(1, 1000, closed));
            check(entered.await(2, TimeUnit.SECONDS), "T37-6: sink entered");
            long t0 = System.nanoTime();
            e.pause(); // 必须立即返回（不等待 sink 完成）
            long pauseMs = (System.nanoTime() - t0) / 1_000_000;
            check(pauseMs < 500, "T37-6: pause() returns promptly while the sink is stuck (" + pauseMs + "ms)");
            t0 = System.nanoTime();
            e.cancel();
            long cancelMs = (System.nanoTime() - t0) / 1_000_000;
            check(cancelMs < 500, "T37-6: cancel() returns promptly while the sink is stuck (" + cancelMs + "ms)");
            t0 = System.nanoTime();
            e.close();
            long closeMs = (System.nanoTime() - t0) / 1_000_000;
            check(closeMs < 500, "T37-6: close() returns promptly while the sink is stuck (" + closeMs + "ms)");
            release.countDown();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 找到引擎 worker 线程（按名）。 */
    static Thread workerThread(MusicEngine e) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("tsvoice-music-0".equals(t.getName()) && t.isAlive()) {
                return t;
            }
        }
        return null;
    }
}
