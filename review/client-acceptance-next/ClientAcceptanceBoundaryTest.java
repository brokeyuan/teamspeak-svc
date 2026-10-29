package com.tsvoice;

import com.tsvoice.client.backend.SvcMcChannelSocket;
import com.tsvoice.client.music.MusicSession;
import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.SelectionPolicy;
import com.tsvoice.client.backend.SvcBackendAdapter;
import com.tsvoice.protocol.music.TestToneSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F0 回归：0072d00 复核三反例转正式测试（修复后必须全 PASS）。
 *
 * <ul>
 *   <li>F1：read() 已阻塞 UDP receive 时 activateMcChannel() 的受控换态
 *       —— 同一线程必须拿到 MC 帧，不得把 SocketException 抛给 SVC 读循环。</li>
 *   <li>F3：PAUSED 无选中后端时不放行原生 SVC 收听（Optional.empty 不是
 *       用户选择 SVC）。</li>
 *   <li>F4：编码器中阻塞的帧在 cancel() 返回后不得再调用 sender
 *       （编码后最终许可复核）。</li>
 * </ul>
 */
public class ClientAcceptanceBoundaryTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        // ===== F1：UDP→MC 受控换态 =====
        {
            SvcMcChannelSocket s = new SvcMcChannelSocket();
            s.open();
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    s.read();
                    result.complete("frame");
                } catch (Exception e) {
                    result.complete(e.getClass().getName() + ": " + e.getMessage());
                }
            }, "audit-svc-read");
            reader.setDaemon(true);
            reader.start();
            boolean inUdp = false;
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (!inUdp && System.nanoTime() < deadline) {
                for (StackTraceElement f : reader.getStackTrace()) {
                    if (f.getMethodName().equals("readUdp")) {
                        inUdp = true;
                    }
                }
                if (!inUdp) {
                    Thread.sleep(1);
                }
            }
            check(inUdp, "F1: reader thread blocks in native UDP receive");
            s.activateMcChannel();
            s.onMcDatagram(new byte[]{1});
            String got = result.get(2, TimeUnit.SECONDS);
            check("frame".equals(got),
                    "F1: controlled UDP-to-MC switch delivers the MC frame to the same reader thread (got: " + got + ")");
            check(!s.isClosed() && s.isMcChannelMode(),
                    "F1: socket remains open and in MC mode after the switch");
            s.close();
        }

        // ===== F3：PAUSED 无后端不恢复 SVC 收听 =====
        {
            BackendCoordinator.select(SelectionPolicy.IntentKind.PINNED_TS, "audit");
            BackendCoordinator.disconnectTeamSpeak();
            var sel = BackendCoordinator.snapshot().selection();
            check(sel.intent() == SelectionPolicy.IntentKind.PAUSED
                            && sel.selectedBackend().isEmpty(),
                    "F3: precondition - PAUSED with no selected backend");
            check(SvcBackendAdapter.isNativeReceiveSuppressed(),
                    "F3: PAUSED with no backend suppresses native SVC receive (no implicit SVC listen)");
        }

        // ===== F4：编码后最终许可复核 =====
        {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger sent = new AtomicInteger();
            MusicSession.OpusEncoderAdapter encoder = new MusicSession.OpusEncoderAdapter() {
                volatile boolean closed;

                @Override
                public byte[] encode(short[] pcm) {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("timeout");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    return new byte[]{1};
                }

                @Override
                public void close() {
                    closed = true;
                }

                @Override
                public boolean isClosed() {
                    return closed;
                }
            };
            MusicSession s = new MusicSession("local", 9987, "bot", null);
            s.connectForTest(new MusicSession.ConnectionBundle());
            s.startPlaybackForTest(TestToneSource.sine(440, 0.04), encoder,
                    opus -> {
                        sent.incrementAndGet();
                        return true;
                    }, System::nanoTime);
            check(entered.await(1, TimeUnit.SECONDS), "F4: encoder entered");
            s.cancel();
            release.countDown();
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (sent.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            Thread.sleep(100);
            check(sent.get() == 0,
                    "F4: frame blocked in the encoder is NOT submitted after cancel() returned (got " + sent.get() + " sends)");
            check(s.state() == MusicSession.State.CONNECTED,
                    "F4: cancel returns to CONNECTED");
            s.close();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
