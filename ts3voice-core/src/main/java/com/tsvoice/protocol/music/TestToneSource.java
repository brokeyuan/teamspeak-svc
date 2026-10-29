package com.tsvoice.protocol.music;

/**
 * 内置测试音源（Music-0）：正弦/扫频，48kHz 单声道。授权本地音源之一
 * （实施记录 §2b）；无外部依赖、确定性输出（测试 fixture 直接断言）。
 */
public final class TestToneSource implements PcmSource {

    private final double frequency;
    private final double sweepTo;
    private final long totalSamples;
    private long position;
    private boolean closed;

    /** 固定频率正弦（Hz）。durationSeconds<=0 表示无限。 */
    public static TestToneSource sine(double frequencyHz, double durationSeconds) {
        return new TestToneSource(frequencyHz, frequencyHz, durationSeconds);
    }

    /** 线性扫频（startHz → endHz）。 */
    public static TestToneSource sweep(double startHz, double endHz, double durationSeconds) {
        return new TestToneSource(startHz, endHz, durationSeconds);
    }

    private TestToneSource(double startHz, double endHz, double durationSeconds) {
        this.frequency = startHz;
        this.sweepTo = endHz;
        this.totalSamples = durationSeconds <= 0
                ? Long.MAX_VALUE
                : (long) (durationSeconds * 48000.0);
    }

    @Override
    public synchronized short[] readFrame() {
        if (closed || position >= totalSamples) {
            return null;
        }
        short[] frame = new short[FRAME_SAMPLES];
        for (int i = 0; i < FRAME_SAMPLES && position < totalSamples; i++, position++) {
            double t = position / 48000.0;
            double freq = sweepTo == frequency
                    ? frequency
                    : frequency + (sweepTo - frequency) * (position / (double) Math.max(1, totalSamples));
            double value = Math.sin(2 * Math.PI * freq * t) * 0.5 * Short.MAX_VALUE;
            frame[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) value));
        }
        return frame;
    }

    /** 已生成采样数（测试断言用）。 */
    public synchronized long position() {
        return position;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
