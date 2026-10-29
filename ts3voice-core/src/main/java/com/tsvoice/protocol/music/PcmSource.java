package com.tsvoice.protocol.music;

/**
 * 音乐引擎核心接口（Music-0，纯 Java 零依赖）：音源 → PCM 帧流。
 *
 * <p>48kHz 单声道 16-bit little-endian 短整型帧；每帧 {@link #FRAME_SAMPLES}
 * 个采样点（20ms）。实现必须线程安全地支持 {@link #close()} 后续调用失败
 * （读已关闭音源抛 {@link java.io.IOException}）。</p>
 */
public interface PcmSource extends AutoCloseable {

    /** 20ms @ 48kHz。 */
    int FRAME_SAMPLES = 960;

    /**
     * 读取下一帧；返回 null = 音源自然结束。
     *
     * @throws java.io.IOException 音源已关闭或读取失败
     */
    short[] readFrame() throws java.io.IOException;

    /** 关闭（幂等）；close 后 readFrame 必须抛 IOException。 */
    @Override
    void close();
}
