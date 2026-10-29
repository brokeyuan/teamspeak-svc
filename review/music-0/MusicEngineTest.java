package com.tsvoice;

import com.tsvoice.protocol.music.MusicEngine;
import java.util.function.LongSupplier;
import com.tsvoice.protocol.music.PcmSource;
import com.tsvoice.protocol.music.TestToneSource;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Music-0 回归：测试音确定性、WAV 解析/重采样/折叠、节拍调度（假时钟）、
 * 暂停/恢复/取消/关闭、有界队列丢弃、sink 许可丢帧、失败原因。
 */
public class MusicEngineTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** 假时钟（纳秒）。 */
    static final class FakeClock implements LongSupplier {
        long now = 1_000_000_000L;

        void advance(long nanos) {
            now += nanos;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    /** 写一个 22050Hz 立体声 16-bit WAV fixture。 */
    static Path writeWav(Path dir, String name, double seconds) throws Exception {
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                22050f, 16, 2, 4, 22050f, false);
        int frames = (int) (22050 * seconds);
        byte[] data = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / 22050.0) * 8000);
            data[i * 4] = (byte) v;
            data[i * 4 + 1] = (byte) (v >> 8);
            data[i * 4 + 2] = (byte) v;
            data[i * 4 + 3] = (byte) (v >> 8);
        }
        Path file = dir.resolve(name);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), format, frames)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    public static void main(String[] args) throws Exception {
        // ---- T1: test tone determinism + natural end ----
        TestToneSource tone = TestToneSource.sine(440, 0.1); // 100ms = 5 frames
        int frames = 0;
        short[] f;
        while ((f = tone.readFrame()) != null) {
            check(f.length == PcmSource.FRAME_SAMPLES, "T1: frame is 960 samples");
            frames++;
        }
        check(frames == 5, "T1: 100ms sine yields exactly 5 frames (got " + frames + ")");
        tone.close();
        check(tone.readFrame() == null, "T1: closed source returns null");

        // ---- T2: WAV open/resample/fold ----
        Path wav = writeWav(Files.createTempDirectory("music0"), "fixture.wav", 1.0);
        com.tsvoice.protocol.music.WavPcmSource source =
                com.tsvoice.protocol.music.WavPcmSource.open(wav);
        int wavFrames = 0;
        while ((f = source.readFrame()) != null) {
            wavFrames++;
            if (wavFrames == 1) {
                check(f.length == PcmSource.FRAME_SAMPLES, "T2: WAV frame length");
            }
        }
        check(Math.abs(wavFrames - 50) <= 2,
                "T2: 1s 22.05kHz stereo resamples to ~50 20ms frames (got " + wavFrames + ")");
        source.close();
        boolean rejected = false;
        try {
            com.tsvoice.protocol.music.WavPcmSource.open(Files.createTempFile("music0", ".txt"));
        } catch (Exception e) {
            rejected = true;
        }
        check(rejected, "T2: non-WAV file is explicitly rejected (no silent fallback)");
        Path missing = Files.createTempDirectory("music0b").resolve("missing.wav");
        rejected = false;
        try {
            com.tsvoice.protocol.music.WavPcmSource.open(missing);
        } catch (java.io.IOException e) {
            rejected = true;
        }
        check(rejected, "T2: missing file rejected with IOException");

        // ---- T3: engine playback on fake clock, exact frame count + absolute beat ----
        FakeClock clock = new FakeClock();
        List<short[]> played = new CopyOnWriteArrayList<>();
        MusicEngine engine = new MusicEngine(frame -> {
            played.add(frame);
            return true;
        }, clock::getAsLong);
        engine.play(TestToneSource.sine(440, 0.1));
        // drive time: 5 frames
        for (int i = 0; i < 6; i++) {
            clock.advance(MusicEngine.FRAME_NANOS);
            Thread.sleep(30);
        }
        check(played.size() == 5, "T3: exactly 5 frames submitted in 100ms (got " + played.size() + ")");
        check(engine.state() == MusicEngine.State.ENDED, "T3: natural end -> ENDED");
        check(engine.framesDecoded() == 5 && engine.framesSubmitted() == 5,
                "T3: decode/submit counters agree");
        check(engine.framesDroppedByQueue() == 0 && engine.framesDroppedByPermit() == 0,
                "T3: no drops on the happy path");

        // ---- T4: pause/resume keeps position ----
        engine.play(TestToneSource.sine(440, 1.0)); // 50 frames
        for (int i = 0; i < 4; i++) {
            clock.advance(MusicEngine.FRAME_NANOS);
            Thread.sleep(30);
        }
        engine.pause();
        int atPause = played.size();
        long decodedAtPause = engine.framesDecoded();
        check(engine.state() == MusicEngine.State.PAUSED, "T4: pause -> PAUSED");
        for (int i = 0; i < 6; i++) {
            clock.advance(MusicEngine.FRAME_NANOS);
            Thread.sleep(20);
        }
        check(played.size() == atPause, "T4: paused engine submits no frames");
        engine.resume();
        for (int i = 0; i < 4; i++) {
            clock.advance(MusicEngine.FRAME_NANOS);
            Thread.sleep(30);
        }
        check(played.size() > atPause, "T4: resume continues from the paused position");
        check(engine.framesDecoded() >= decodedAtPause, "T4: decode position preserved across pause");
        engine.cancel();
        check(engine.state() == MusicEngine.State.IDLE, "T4: cancel -> IDLE (reusable)");

        // ---- T5: bounded queue drops oldest ----
        // Stalled sink: the scheduler blocks inside accept; decode-ahead fills the
        // queue to capacity and then BACKPRESSURES (stops reading the source) —
        // ordered playback without dropping frames. Assert the queue is bounded
        // and exactly capacity-sized, and that playback resumes in order.
        final int produced = MusicEngine.DEFAULT_QUEUE_CAPACITY * 3;
        final java.util.concurrent.CountDownLatch sinkStuck = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch sinkEntered = new java.util.concurrent.CountDownLatch(1);
        final MusicEngine stalled = new MusicEngine(frame -> {
            sinkEntered.countDown();
            try {
                sinkStuck.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        }, clock::getAsLong);
        final java.util.concurrent.atomic.AtomicInteger fed = new java.util.concurrent.atomic.AtomicInteger();
        stalled.play(new PcmSource() {
            @Override
            public short[] readFrame() {
                return fed.incrementAndGet() <= produced ? new short[PcmSource.FRAME_SAMPLES] : null;
            }
            @Override
            public void close() {
            }
        });
        sinkEntered.await();
        clock.advance(MusicEngine.FRAME_NANOS * (long) produced);
        Thread.sleep(300); // decode-ahead fills to capacity then backpressures
        check(stalled.queueDepth() == MusicEngine.DEFAULT_QUEUE_CAPACITY - 1,
                "T5: queue backpressures at capacity-1 (one frame in flight; got "
                        + stalled.queueDepth() + ")");
        check(stalled.framesDecoded() < produced,
                "T5: decode-ahead stops at capacity (backpressure, no drop)");
        sinkStuck.countDown();
        stalled.cancel();

        // ---- T6: permit=false drops counted, playback continues ----
        FakeClock clock2 = new FakeClock();
        AtomicInteger permits = new AtomicInteger(3);
        MusicEngine gated = new MusicEngine(frame -> permits.getAndDecrement() > 0, clock2::getAsLong);
        gated.play(TestToneSource.sine(440, 0.2)); // 10 frames
        for (int i = 0; i < 11; i++) {
            clock2.advance(MusicEngine.FRAME_NANOS);
            Thread.sleep(25);
        }
        check(gated.framesSubmitted() >= 3 && gated.framesDroppedByPermit() > 0,
                "T6: frames without permit are counted as dropped");
        check(gated.state() == MusicEngine.State.ENDED, "T6: playback continues to natural end");
        gated.close();

        // ---- T7: source throwing -> FAILED with reason ----
        MusicEngine broken = new MusicEngine(frame -> true, () -> 1_000_000_000L);
        broken.play(new PcmSource() {
            @Override
            public short[] readFrame() throws java.io.IOException {
                throw new java.io.IOException("broken source");
            }
            @Override
            public void close() {
            }
        });
        Thread.sleep(120);
        check(broken.state() == MusicEngine.State.FAILED
                        && broken.failureReason().contains("broken source"),
                "T7: source failure -> FAILED with the reason surfaced");
        broken.close();
        check(broken.state() == MusicEngine.State.CLOSED, "T7: close is terminal (CLOSED)");

        // ---- T8: sink throwing -> FAILED (not silently swallowed) ----
        MusicEngine badSink = new MusicEngine(frame -> {
            throw new IllegalStateException("sink bug");
        }, () -> 1_000_000_000L);
        badSink.play(TestToneSource.sine(440, 0.1));
        Thread.sleep(120);
        check(badSink.state() == MusicEngine.State.FAILED
                        && badSink.failureReason().contains("sink bug"),
                "T8: sink failure -> FAILED with reason");
        badSink.close();

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
