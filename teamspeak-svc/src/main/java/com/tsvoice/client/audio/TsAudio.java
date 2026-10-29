// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
package com.tsvoice.client.audio;

import com.tsvoice.client.ModConfig;
import com.tsvoice.client.TeamspeakSvcClient;

/**
 * 音频增强配置桥：SVC-Extra 缝合代码的运行期配置视图。
 * 由 {@link ModConfig} 刷新（/tsvoice reload 热生效）。
 */
public final class TsAudio {

    public enum NoiseCancelMode { OFF, WEBRTC_LOW, WEBRTC_MEDIUM, WEBRTC_HIGH, WEBRTC_AGGRESSIVE, NVIDIA_AI }

    public static volatile boolean reverbEnabled = false;
    public static volatile int reverbIntervalMs = 500;
    public static volatile boolean showRayTrace = false;
    public static volatile boolean duckingEnabled = false;
    public static volatile float duckingLevel = 0.35f;
    public static volatile boolean echoCancelEnabled = false;
    public static volatile NoiseCancelMode denoiseMode = NoiseCancelMode.OFF;
    public static volatile boolean agcEnabled = false;
    public static volatile int micSampleRate = 48000;
    public static volatile int aecDelayMs = 120;
    /** 低延迟模式：TS 会话期间降低 SVC 播放缓冲（≈40ms）。 */
    public static volatile boolean lowLatencyMode = true;
    /** NVIDIA AFX：dll 与模型目录覆盖路径（空=自动发现 RTX Voice/SDK/NGX）。 */
    public static volatile String nvidiaDllPath = "";
    public static volatile String nvidiaModelDir = "";

    private TsAudio() {
    }

    /** 从全局 ModConfig 刷新（启动 + reload）。 */
    public static void refresh() {
        ModConfig config = TeamspeakSvcClient.config();
        if (config == null) {
            return;
        }
        reverbEnabled = config.reverbEnabled;
        duckingEnabled = config.duckingEnabled;
        duckingLevel = Math.max(0.05f, Math.min(1.0f, config.duckingLevel));
        echoCancelEnabled = config.echoCancelEnabled;
        agcEnabled = config.agcEnabled;
        denoiseMode = parseMode(config.denoiseMode);
        aecDelayMs = Math.max(0, Math.min(500, config.aecDelayMs));
        lowLatencyMode = config.lowLatencyMode;
        nvidiaDllPath = config.nvidiaDllPath != null ? config.nvidiaDllPath : "";
        nvidiaModelDir = config.nvidiaModelDir != null ? config.nvidiaModelDir : "";
    }

    public static NoiseCancelMode parseMode(String mode) {
        if (mode == null) {
            return NoiseCancelMode.OFF;
        }
        try {
            return NoiseCancelMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NoiseCancelMode.OFF;
        }
    }
}
