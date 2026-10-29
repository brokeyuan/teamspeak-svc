package com.tsvoice.client.music;

import com.tsvoice.protocol.music.MusicEngine;
import com.tsvoice.protocol.music.PcmSource;

import java.util.ArrayList;
import java.util.function.LongSupplier;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


/**
 * T38/E7 回归：独立音乐会话（不共用玩家语音会话/身份/编码器）。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li>音乐会话持有自己的 TS identity/client/connection/transport ——
 *       不调用玩家全局 endSession、不覆盖玩家 currentClient。</li>
 *   <li>编码器独立创建/关闭（SVC API 可用时是其中一个实现；不可用时明确
 *       失败，玩家语音不受影响）。</li>
 *   <li>发送许可绑定音乐会话的 routeEpoch/generation；暂停、取消、被移出
 *       房间后旧帧不再获得发送许可。</li>
 *   <li>MusicEngine 队列 → 编码 → sendVoice(CODEC_OPUS_MUSIC) 真实接线，
 *       帧内容可验证（不是 stub-through）。</li>
 * </ul>
 */
public class MusicSessionTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }


    /** 编号 PCM 源。 */
    static PcmSource numbered(int frames) {
        AtomicInteger i = new AtomicInteger();
        return new PcmSource() {
            @Override
            public short[] readFrame() {
                if (i.get() >= frames) return null;
                short[] f = new short[PcmSource.FRAME_SAMPLES];
                f[0] = (short) (i.incrementAndGet() % Short.MAX_VALUE);
                return f;
            }
            @Override public void close() {}
        };
    }

    /** 记录型编码器：记录输入帧并产出可识别的输出（帧号写进首字节）。 */
    static final class RecordingEncoder implements MusicSession.OpusEncoderAdapter {
        final List<short[]> inputs = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public byte[] encode(short[] pcm) {
            inputs.add(pcm);
            byte[] out = new byte[3];
            out[0] = (byte) (pcm[0] & 0xFF);
            out[1] = (byte) (pcm[0] >> 8);
            return out;
        }

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }
    }

    static void independentLifecycleDoesNotTouchPlayerSession() {
        AtomicBoolean playerSessionEnded = new AtomicBoolean();
        // Dependencies 记录玩家会话停止句柄是否被触碰（生产代码路径绝不调用）。
        MusicSession session = new MusicSession("ts.example.com", 9987, "music-bot",
                new MusicSession.Dependencies() {
                    @Override
                    public Object createIdentity() {
                        throw new AssertionError("identity must not be created in unit test");
                    }
                    @Override
                    public Runnable playerSessionEndHandle() {
                        playerSessionEnded.set(true);
                        return () -> {
                            throw new AssertionError("player session must not be ended by music");
                        };
                    }
                });
        session.connectForTest(new MusicSession.ConnectionBundle());
        check(session.isConnected() && !playerSessionEnded.get(), "L1: independent session does not touch the player lifecycle");
    }

    static void engineToEncoderToSenderRealWiring() throws Exception {
        RecordingEncoder encoder = new RecordingEncoder();
        List<byte[]> sent = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        MusicSession session = new MusicSession("ts.example.com", 9987, "music-bot", null);
        session.connectForTest(new MusicSession.ConnectionBundle());
        session.startPlaybackForTest(numbered(5), encoder,
                opus -> {
                    sent.add(opus);
                    return true;
                },
                clock::get);
        long deadline = System.currentTimeMillis() + 5000;
        while (sent.size() < 5 && System.currentTimeMillis() < deadline) {
            clock.addAndGet(MusicEngine.FRAME_NANOS);
            Thread.sleep(10);
        }
        check(sent.size() == 5, "W1: all 5 encoded frames reached the sender (got " + sent.size() + ")");
        check(encoder.inputs.size() == 5, "W1: encoder consumed exactly 5 PCM frames");
        // 帧内容逐帧可验证（first sample → bytes 0/1）
        check((sent.get(0)[0] & 0xFF) == 1 && (sent.get(4)[0] & 0xFF) == 5, "W1: frame bytes verify the PCM origin (1..5 in order)");
        check(session.state() == MusicSession.State.ENDED, "W1: playback ends naturally");
        session.closeForTest();
        check(encoder.isClosed(), "W1: session close releases the owned encoder");
    }

    static void pauseStopsSendingWithoutEncoderChurn() throws Exception {
        RecordingEncoder encoder = new RecordingEncoder();
        AtomicInteger sent = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        MusicSession session = new MusicSession("ts", 9987, "bot", null);
        session.connectForTest(new MusicSession.ConnectionBundle());
        session.startPlaybackForTest(numbered(50), encoder, opus -> {
            sent.incrementAndGet();
            return true;
        }, clock::get);
        long deadline = System.currentTimeMillis() + 3000;
        while (sent.get() < 3 && System.currentTimeMillis() < deadline) {
            clock.addAndGet(MusicEngine.FRAME_NANOS);
            Thread.sleep(5);
        }
        session.pause();
        int atPause = sent.get();
        Thread.sleep(150);
        check(sent.get() == atPause, "P1: paused session sends no further frames");
        session.resumeForTest(clock::get);
        deadline = System.currentTimeMillis() + 5000;
        while (sent.get() < atPause + 3 && System.currentTimeMillis() < deadline) {
            clock.addAndGet(MusicEngine.FRAME_NANOS);
            Thread.sleep(5);
        }
        check(sent.get() > atPause, "P1: resume continues sending");
        session.closeForTest();
    }

    static void cancelInvalidatesInFlightFrames() throws Exception {
        RecordingEncoder encoder = new RecordingEncoder();
        List<Byte> sentValues = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        MusicSession session = new MusicSession("ts", 9987, "bot", null);
        session.connectForTest(new MusicSession.ConnectionBundle());
        // 慢发送器：第一帧阻塞模拟发送许可等待
        java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Byte> stale = new AtomicReference<>();
        session.startPlaybackForTest(numbered(100), encoder, opus -> {
            if (first.getCount() > 0) {
                stale.set(opus[0]);
                first.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
            synchronized (sentValues) {
                sentValues.add(opus[0]);
            }
            return true;
        }, clock::get);
        check(first.await(2, TimeUnit.SECONDS), "C1: first send entered");
        session.cancel(); // 取消旧播放（in-flight 帧废弃）
        release.countDown();
        Thread.sleep(150);
        synchronized (sentValues) {
            check(sentValues.stream().noneMatch(b -> b.equals(stale.get())),
                    "C1: cancelled generation's in-flight frame is not re-sent after cancel");
        }
        check(session.state() == MusicSession.State.CONNECTED, "C1: cancel returns to CONNECTED, session reusable");
        session.closeForTest();
        check(encoder.isClosed(), "C1: close after cancel releases the owned encoder");
    }

    static void missingEncoderFailsExplicitlyWithoutAffectingPlayerVoice() {
        MusicSession session = new MusicSession("ts", 9987, "bot", null);
        session.connectForTest(new MusicSession.ConnectionBundle());
        boolean failed = false;
        try {
            session.startPlaybackForTest(numbered(5), null, opus -> true,
                    () -> 1_000_000_000L);
        } catch (MusicSession.EncodingUnavailableException e) {
            failed = true;
            check(e.getMessage().toLowerCase().contains("opus"), "F1: reason names the encoder");
        }
        check(failed, "F1: missing encoder fails explicitly (not silently skipped)");
        check(session.state() == MusicSession.State.FAILED, "F1: state is FAILED");
        session.closeForTest();
    }

    public static void main(String[] args) throws Exception {
        independentLifecycleDoesNotTouchPlayerSession();
        engineToEncoderToSenderRealWiring();
        pauseStopsSendingWithoutEncoderChurn();
        cancelInvalidatesInFlightFrames();
        missingEncoderFailsExplicitlyWithoutAffectingPlayerVoice();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
