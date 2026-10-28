// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
package com.tsvoice.client.audio;

import com.tsvoice.client.TeamspeakSvcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 发送侧音频处理管线（麦克风帧 → [NVIDIA NS →] AEC → SVC 编码/拦截 → TS，或
 * 非 AEC 模式下的 NS → AGC）。
 *
 * <p>由 {@code MicProcessorMixin.preprocess} 每帧调用；所有初始化/重配/处理路径
 * 共用 AudioPipeline 类锁，避免 reconfigure 与麦克风线程竞争导致 NPE 或
 * native 对象在途释放。</p>
 */
public class AudioPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioPipeline.class);

    private static AecProcessor aec;
    private static WebRtcNative webrtc;
    private static NvidiaDenoiser nvidia;
    private static AgcProcessor agc;
    private static boolean isInitialized = false;
    private static int sampleRate = 48000;

    public static synchronized void init() {
        if (isInitialized) {
            return;
        }
        sampleRate = TsAudio.micSampleRate > 0 ? TsAudio.micSampleRate : 48000;

        if (TsAudio.echoCancelEnabled) {
            try {
                AecReferenceMixer.clear();
                aec = new AecProcessor(TsAudio.denoiseMode);
                LOGGER.info("[teamspeak-svc] AEC initialized (synchronous), delay={}ms", TsAudio.aecDelayMs);
            } catch (Throwable e) {
                LOGGER.warn("[teamspeak-svc] AEC init failed: {}", e.getMessage());
                aec = null;
            }
        }
        if (TsAudio.denoiseMode == TsAudio.NoiseCancelMode.NVIDIA_AI) {
            try {
                nvidia = new NvidiaDenoiser(sampleRate);
                LOGGER.info("[teamspeak-svc] NVIDIA AI denoiser initialized");
            } catch (Throwable e) {
                // native 初始化失败（含 LinkageError/UnsatisfiedLinkError）必须降级而非击穿 init
                LOGGER.warn("[teamspeak-svc] NVIDIA AI init failed (RTX/Broadcast installed?): {}", e.getMessage());
                nvidiaUnavailableNotice();
            }
        }
        // AEC 模式下降噪由 APM（WebRTC 模式）或 NVIDIA 处理器承担，不重复建 NS 实例
        if (!TsAudio.echoCancelEnabled && nvidia == null) {
            try {
                int level = mapNoiseLevel(TsAudio.denoiseMode);
                if (level >= 0) {
                    webrtc = new WebRtcNative(level, false);
                    LOGGER.info("[teamspeak-svc] WebRTC NS initialized, level={}", level);
                }
            } catch (Throwable e) {
                LOGGER.warn("[teamspeak-svc] WebRTC NS init failed: {}", e.getMessage());
            }
        }
        try {
            agc = new AgcProcessor(sampleRate);
        } catch (Throwable e) {
            LOGGER.warn("[teamspeak-svc] AGC init failed: {}", e.getMessage());
        }
        isInitialized = true;
        LOGGER.info("[teamspeak-svc] AudioPipeline initialized");
    }

    public static synchronized void shutdown() {
        if (aec != null) {
            try { aec.close(); } catch (Throwable ignored) {}
            aec = null;
        }
        AecReferenceMixer.clear();
        if (webrtc != null) {
            try { webrtc.close(); } catch (Throwable ignored) {}
            webrtc = null;
        }
        if (nvidia != null) {
            try { nvidia.close(); } catch (Throwable ignored) {}
            nvidia = null;
        }
        agc = null;
        isInitialized = false;
        LOGGER.info("[teamspeak-svc] AudioPipeline shutdown");
    }

    /** 配置热切换（/tsvoice reload）：重建管线。 */
    public static synchronized void reconfigure() {
        shutdown();
        TsAudio.refresh();
        init();
    }

    /** 任一主动降噪器是否真实可用（WebRTC / NVIDIA / AEC 内置 NS）——决定是否接管 SVC 原生降噪。 */
    public static synchronized boolean isNsActive() {
        return webrtc != null || nvidia != null || (aec != null && aec.hasNoiseSuppression());
    }

    /** NVIDIA AI 是否可用（供 UI/日志判断）。 */
    public static synchronized boolean isNvidiaAvailable() {
        return nvidia != null;
    }

    private static volatile boolean nvidiaNoticeShown = false;

    /**
     * NVIDIA_AI 被选择但 dll 缺失时给玩家一条可点击引导（聊天栏，仅一次）。
     * NVIDIA 许可禁止随 mod 分发 dll/模型 —— 唯一合法途径是玩家自行安装
     * NVIDIA Broadcast（免费，NVIDIA App 内可装）。期间自动回退 SVC 原生降噪。
     */
    private static void nvidiaUnavailableNotice() {
        if (nvidiaNoticeShown) {
            return;
        }
        nvidiaNoticeShown = true;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player == null) {
                    return;
                }
                var msg = net.minecraft.network.chat.Component.literal(
                        "[TSVoice] NVIDIA AI 降噪不可用：未检测到 NVAudioEffects.dll。");
                var link = net.minecraft.network.chat.Component.literal(" [点击打开 NVIDIA Broadcast 下载页]")
                        .withStyle(style -> style
                                .withColor(net.minecraft.ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(
                                        java.net.URI.create("https://www.nvidia.com/en-us/geforce/broadcasting/broadcast-app/")))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                        net.minecraft.network.chat.Component.literal(
                                                "RTX 显卡免费；装完重启游戏，denoiseMode 保持 NVIDIA_AI 即自动启用"))));
                mc.player.sendSystemMessage(msg);
                mc.player.sendSystemMessage(link);
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[TSVoice] 已暂时回退 SVC 原生降噪（仍有效）。也可改用内置 WebRTC 降噪：denoiseMode=WEBRTC_MEDIUM"));
            });
        } catch (Throwable ignored) {
        }
    }

    static boolean isWebRtcNsMode(TsAudio.NoiseCancelMode mode) {
        return switch (mode) {
            case WEBRTC_LOW, WEBRTC_MEDIUM, WEBRTC_HIGH, WEBRTC_AGGRESSIVE -> true;
            default -> false;
        };
    }

    private static int mapNoiseLevel(TsAudio.NoiseCancelMode mode) {
        return switch (mode) {
            case WEBRTC_LOW -> 0;
            case WEBRTC_MEDIUM -> 1;
            case WEBRTC_HIGH -> 2;
            case WEBRTC_AGGRESSIVE -> 3;
            default -> -1;
        };
    }

    public static synchronized void processShort(short[] audio) {
        if (audio == null || audio.length == 0) {
            return;
        }
        init();

        if (TsAudio.echoCancelEnabled) {
            if (TsAudio.agcEnabled && agc != null) {
                agc.process(audio);
            }
            if (nvidia != null) {
                try {
                    nvidia.process(audio, sampleRate);
                } catch (Throwable e) {
                    LOGGER.warn("[teamspeak-svc] NVIDIA denoise failed, disabling: {}", e.toString());
                    try { nvidia.close(); } catch (Throwable ignored) {}
                    nvidia = null;
                }
            }
            // 同步 AEC：先喂参考帧，再处理麦克风帧并写回原数组
            if (aec != null && !aec.process(audio)) {
                try { aec.close(); } catch (Throwable ignored) {}
                aec = null;
                LOGGER.warn("[teamspeak-svc] AEC disabled after native failure");
            }
            return;
        }

        if (TsAudio.denoiseMode == TsAudio.NoiseCancelMode.NVIDIA_AI) {
            if (nvidia != null) {
                try {
                    nvidia.process(audio, sampleRate);
                } catch (Throwable e) {
                    LOGGER.warn("[teamspeak-svc] NVIDIA denoise failed, disabling: {}", e.toString());
                    try { nvidia.close(); } catch (Throwable ignored) {}
                    nvidia = null;
                }
            }
        } else if (isWebRtcNsMode(TsAudio.denoiseMode)) {
            if (webrtc != null) {
                if (webrtc.isHealthy()) {
                    webrtc.process(audio, sampleRate);
                } else {
                    // 持续失败（native 异常/设备变化）：丢弃实例，isNsActive 随之
                    // 返回 false，SVC 原生降噪自动接管，而不是持续静默无降噪。
                    LOGGER.warn("[teamspeak-svc] WebRTC NS unhealthy after repeated failures — releasing (SVC native NS takes over)");
                    try { webrtc.close(); } catch (Throwable ignored) {}
                    webrtc = null;
                }
            }
        }
        if (TsAudio.agcEnabled && agc != null) {
            agc.process(audio);
        }
    }
}
