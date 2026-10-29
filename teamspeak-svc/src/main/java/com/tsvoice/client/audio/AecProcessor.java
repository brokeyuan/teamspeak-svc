// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Synchronous redesign: 处理在麦克风线程内完成，结果直接写回发送缓冲。
package com.tsvoice.client.audio;

import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig.NoiseSuppression.Level;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * WebRTC 回声消除（同步）。
 *
 * <p>麦克风线程每 20ms 调用一次 {@link #process(short[])}：按 10ms 帧拆分，
 * 每帧先以 {@link #pollReference()} 的远端参考喂 {@code processReverseStream}，
 * 再处理麦克风帧并把结果<b>写回原数组</b>。参考帧与麦克风帧 1:1 消费，
 * 不再有异步调度线程与结果丢失问题。</p>
 *
 * <p>限制：多个说话人同时播放时参考信号按到达顺序串联（非真正混音），
 * 对 AEC 是次优参考；单说话人场景与扬声器输出一致。</p>
 */
public final class AecProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AecProcessor.class);
    /** 10ms @ 48kHz。 */
    private static final int FRAME_SIZE = 480;
    private static final int BYTES = FRAME_SIZE * 2;
    private static final int MAX_DELAY_MS = 500;

    private final AudioProcessing apm;
    private final AudioProcessingStreamConfig cfg;
    private final boolean noiseSuppression;
    private final byte[] refBytes = new byte[BYTES];
    private final byte[] micBytes = new byte[BYTES];
    private final byte[] outBytes = new byte[BYTES];
    private boolean failed = false;

    public AecProcessor(TsAudio.NoiseCancelMode denoiseMode) {
        this.noiseSuppression = AudioPipeline.isWebRtcNsMode(denoiseMode);
        AudioProcessing created = new AudioProcessing();
        try {
            var config = new AudioProcessingConfig();
            config.noiseSuppression.enabled = noiseSuppression;
            if (noiseSuppression) {
                config.noiseSuppression.level = noiseLevel(denoiseMode);
            }
            config.gainControl.enabled = false;
            config.echoCanceller.enabled = true;
            config.echoCanceller.enforceHighPassFiltering = true;
            created.applyConfig(config);
            created.setStreamDelayMs(Math.max(0, Math.min(MAX_DELAY_MS, TsAudio.aecDelayMs)));
        } catch (Throwable t) {
            try { created.dispose(); } catch (Throwable ignored) {}
            throw t;
        }
        this.apm = created;
        this.cfg = new AudioProcessingStreamConfig(48000, 1);
    }

    private static Level noiseLevel(TsAudio.NoiseCancelMode mode) {
        return switch (mode) {
            case WEBRTC_LOW -> Level.LOW;
            case WEBRTC_MEDIUM -> Level.MODERATE;
            case WEBRTC_HIGH -> Level.HIGH;
            default -> Level.VERY_HIGH;
        };
    }

    /** AEC 内部是否同时启用了降噪（决定是否接管 SVC 原生降噪）。 */
    public boolean hasNoiseSuppression() {
        return noiseSuppression;
    }

    /**
     * 同步处理一帧麦克风音频（20ms = 2×10ms）。
     *
     * @return false 表示 native 处理器已失效（调用方应关闭并降级）
     */
    public boolean process(short[] audio) {
        if (failed || audio == null || audio.length == 0) {
            return !failed;
        }
        int total = (audio.length / FRAME_SIZE) * FRAME_SIZE;
        if (total == 0) {
            return true;
        }
        for (int off = 0; off < total; off += FRAME_SIZE) {
            short[] ref = AecReferenceMixer.poll();
            if (ref != null && ref.length == FRAME_SIZE) {
                ByteBuffer.wrap(refBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(ref);
            } else {
                Arrays.fill(refBytes, (byte) 0);
            }
            try {
                // 反向流输出不使用，但必须提供输出缓冲
                apm.processReverseStream(refBytes, cfg, cfg, outBytes);
                ByteBuffer.wrap(micBytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().put(audio, off, FRAME_SIZE);
                apm.processStream(micBytes, cfg, cfg, outBytes);
                ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().get(audio, off, FRAME_SIZE);
            } catch (Throwable e) {
                LOGGER.warn("[teamspeak-svc] AEC frame failed, disabling AEC: {}", e.toString());
                failed = true;
                return false;
            }
        }
        return true;
    }

    public void close() {
        try {
            apm.dispose();
        } catch (Throwable ignored) {
        }
    }
}
