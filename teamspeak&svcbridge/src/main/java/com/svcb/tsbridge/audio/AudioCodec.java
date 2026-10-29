package com.svcb.tsbridge.audio;

import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.TsVoicechatPlugin;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.Objects;

/**
 * 音频编解码与格式适配工具。
 * SVC 使用 48kHz mono 20ms = 960 samples 的 Opus 格式。
 */
public final class AudioCodec {
    /** SVC 标准采样率 */
    public static final int SAMPLE_RATE = 48000;
    /** SVC 标准帧长（毫秒） */
    public static final int FRAME_MS = 20;
    /** SVC 每帧样本数 */
    public static final int SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000; // 960

    private AudioCodec() {}

    /** 创建一个新的 OpusEncoder（调用方负责 close） */
    public static OpusEncoder createEncoder() {
        Objects.requireNonNull(TsVoicechatPlugin.api, "SVC api not initialized");
        return TsVoicechatPlugin.api.createEncoder();
    }

    /** 创建一个新的 OpusDecoder（调用方负责 close） */
    public static OpusDecoder createDecoder() {
        Objects.requireNonNull(TsVoicechatPlugin.api, "SVC api not initialized");
        return TsVoicechatPlugin.api.createDecoder();
    }

    /**
     * 把 Opus 数据解码为 PCM short[960]。
     * 失败时返回长度为 960 的静音帧（全 0），不抛异常。
     */
    public static short[] decodeToFrame(OpusDecoder decoder, byte[] opusData) {
        try {
            short[] pcm = decoder.decode(opusData);
            if (pcm == null || pcm.length != SAMPLES_PER_FRAME) {
                TsBridgeMod.LOGGER.debug("[tsbridge] Unexpected PCM length {}, expected {}", pcm == null ? 0 : pcm.length, SAMPLES_PER_FRAME);
                return silenceFrame();
            }
            return pcm;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Opus decode failed: {}", e.getMessage());
            return silenceFrame();
        }
    }

    /**
     * 把 PCM short[960] 编码为 Opus byte[]。
     * 失败时返回空数组。
     */
    public static byte[] encodeFrame(OpusEncoder encoder, short[] pcm) {
        if (pcm == null || pcm.length != SAMPLES_PER_FRAME) {
            return new byte[0];
        }
        try {
            return encoder.encode(pcm);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Opus encode failed: {}", e.getMessage());
            return new byte[0];
        }
    }

    /** 返回长度为 960 的静音帧 */
    public static short[] silenceFrame() {
        return new short[SAMPLES_PER_FRAME];
    }

    /**
     * 线性插值重采样：把输入 PCM 从 srcRate 转换到 48kHz。
     * 仅支持 mono（输入为一维 short[]）。
     * 若 srcRate == SAMPLE_RATE，直接返回原数组。
     */
    public static short[] resampleTo48k(short[] input, int srcRate) {
        if (srcRate == SAMPLE_RATE) {
            return input;
        }
        if (srcRate <= 0 || input == null || input.length == 0) {
            return silenceFrame();
        }
        double ratio = (double) SAMPLE_RATE / srcRate;
        int outLen = (int) (input.length * ratio);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcPos = i / ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;
            if (srcIdx + 1 < input.length) {
                out[i] = (short) ((1 - frac) * input[srcIdx] + frac * input[srcIdx + 1]);
            } else if (srcIdx < input.length) {
                out[i] = input[srcIdx];
            }
        }
        return out;
    }

    /**
     * 校验 PCM 数组长度是否为 960 的倍数。
     */
    public static boolean isValidFrameLength(short[] pcm) {
        return pcm != null && pcm.length % SAMPLES_PER_FRAME == 0;
    }
}
