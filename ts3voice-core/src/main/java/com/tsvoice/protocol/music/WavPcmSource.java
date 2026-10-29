package com.tsvoice.protocol.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;

/**
 * WAV 文件音源（Music-0）：本地 PCM WAV → 48kHz 单声道 20ms 帧流。
 *
 * <p>授权边界（实施记录 §2）：只接受本地文件、javax.sound 支持的 PCM 编码；
 * 多声道折叠与任意输入采样率由 javax.sound 重采样到 48k/16-bit。非 PCM 编码
 * （如 MP3）明确拒绝 —— 引入第三方解码器须先按总任务书第 6 节记录版本/许可。
 * 不读网络路径。</p>
 *
 * <p>实现注记：重采样/折叠交给 AudioSystem.getAudioInputStream（目标格式单步
 * 完成），本类只做 16-bit LE 切帧 —— 避免手工滑动窗口重采样误差；整段预读有
 * 界（默认 10 分钟上限，约 57.6 MB bytes），超长输入明确拒绝；流式版本等
 * Music-1 真实音源需求再评估。</p>
 */
public final class WavPcmSource implements PcmSource {

    private static final int TARGET_RATE = 48000;
    /** 预读上限：10 分钟（有界内存；超长明确拒绝）。 */
    static final long MAX_SOURCE_SECONDS = 600;

    private final short[] samples;
    private int position;
    private boolean closed;

    private WavPcmSource(short[] samples) {
        this.samples = samples;
    }

    /**
     * 打开本地 WAV（仅 PCM；拒绝其他编码与不存在的文件）。
     *
     * @throws IOException 文件不存在/IO 失败/超过时长上限
     * @throws UnsupportedAudioFileException 非 WAV 或非 PCM 编码（明确拒绝，不静默）
     */
    public static WavPcmSource open(Path file) throws IOException, UnsupportedAudioFileException {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            throw new IOException("WAV source not found: " + file);
        }
        AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile());
        try {
            AudioFormat format = raw.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    && !AudioFormat.Encoding.PCM_UNSIGNED.equals(format.getEncoding())) {
                throw new UnsupportedAudioFileException(
                        "only PCM WAV is supported (got " + format.getEncoding()
                                + "; decode libraries need a separate license review)");
            }
            double seconds = raw.getFrameLength() >= 0
                    ? raw.getFrameLength() / format.getSampleRate() : Double.MAX_VALUE;
            if (seconds > MAX_SOURCE_SECONDS) {
                throw new IOException("WAV source exceeds " + MAX_SOURCE_SECONDS
                        + "s limit (" + Math.round(seconds) + "s) — bounded-memory design");
            }
            AudioInputStream target = AudioSystem.getAudioInputStream(
                    new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                            TARGET_RATE, 16, 1, 2, TARGET_RATE, false),
                    raw);
            byte[] bytes = target.readAllBytes();
            short[] samples = new short[bytes.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
            }
            return new WavPcmSource(samples);
        } catch (IOException | UnsupportedAudioFileException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("WAV decode failed: " + e.getMessage(), e);
        } finally {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public synchronized short[] readFrame() {
        if (closed) {
            throw new IllegalStateException("WAV source closed");
        }
        if (position >= samples.length) {
            return null;
        }
        int frameSamples = Math.min(FRAME_SAMPLES, samples.length - position);
        short[] frame = new short[FRAME_SAMPLES];
        System.arraycopy(samples, position, frame, 0, frameSamples);
        position += frameSamples;
        return frame;
    }

    /** 总样本数（测试断言用）。 */
    public synchronized int totalSamples() {
        return samples.length;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
