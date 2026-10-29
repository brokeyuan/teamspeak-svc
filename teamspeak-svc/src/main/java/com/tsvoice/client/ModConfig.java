package com.tsvoice.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端配置：<client config dir>/teamspeak-svc.json。
 */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** TeamSpeak 服务器地址（语音 UDP）。 */
    public String tsHost = "";
    /** TeamSpeak 语音端口。 */
    public int tsVoicePort = 9987;
    /** 主频道名（群组子频道为 <主频道>/<群组名>）。 */
    public String channelName = "Minecraft";
    /** SVC 连接后自动连接 TS。 */
    public boolean autoConnect = true;
    /** 调试日志。 */
    public boolean debug = false;
    // ===== v0.4 音频增强（缝合自 SVC-Extra，GPLv3；默认全部关闭，使用 SVC 原生处理）=====
    /** 路径追踪混响（接收侧环境空间感）。 */
    public boolean reverbEnabled = false;
    /** Ducking：TS 音频播放时压低游戏音量（比例 0.05~1.0）。 */
    public boolean duckingEnabled = false;
    public float duckingLevel = 0.35f;
    /** WebRTC 回声消除（外放防回声；需 webrtc native）。 */
    public boolean echoCancelEnabled = false;
    /** 发送侧降噪：OFF=用 SVC 原生 / WEBRTC_LOW|MEDIUM|HIGH|AGGRESSIVE。 */
    public String denoiseMode = "OFF";
    /** WebRTC AGC 自动增益。 */
    public boolean agcEnabled = false;
    /** AEC 参考信号延迟（毫秒，播放→麦克风路径延迟估计）。 */
    public int aecDelayMs = 120;
    /** NVIDIA AI 降噪（NVIDIA_AI 档位）：NVAudioEffects.dll 路径覆盖（空=自动发现，需装 NVIDIA Broadcast/RTX Voice）。 */
    public String nvidiaDllPath = "";
    /** NVIDIA 模型目录覆盖（空=按 dll 位置推导）。 */
    public String nvidiaModelDir = "";
    // ===== v0.5 =====
    /** 传输层：udp（默认/最低延迟）| tcp（UDP 受限网络兜底，走 tsbridge 隧道端口）。 */
    public String transport = "udp";
    /** 低延迟模式：TS 会话期间把 SVC 播放缓冲压到 2 包（≈40ms）；关闭则用 SVC 原配置。 */
    public boolean lowLatencyMode = true;
    /** MC↔TS 文字互通：TS 频道消息→MC 聊天栏；/tsvoice say →TS 频道。 */
    public boolean chatBridgeEnabled = true;
    /** 全局默认 TS 服务器密码（每服可覆盖）。 */
    public String serverPassword = "";
    /** 全局默认主频道密码；与服务器密码分别使用。 */
    public String channelPassword = "";
    /** 配置版本：0/缺失 = v0.9.1 及更早（需一次性迁移音频默认值）。 */
    public Integer configVersion = 0;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("teamspeak-svc.json");
    }

    public static ModConfig load() {
        Path p = path();
        if (!Files.exists(p)) {
            ModConfig config = new ModConfig();
            config.migrateAudioDefaults();
            config.save();
            return config;
        }
        try {
            ModConfig config = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), ModConfig.class);
            if (config == null) {
                config = new ModConfig();
            }
            if (config.migrateAudioDefaults()) {
                config.save();
            }
            return config;
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            TeamspeakSvcClient.LOGGER.error("[teamspeak-svc] Failed to load config: {}", e.getMessage());
            ConfigFileUtil.backupBroken(p);
            return new ModConfig();
        }
    }

    /**
     * v0.9.2 一次性迁移：音频增强默认全关、发送侧改用 SVC 原生降噪。
     * 旧配置（无 configVersion）里这些值已被写成开启，仅改字段初始值不会生效。
     */
    private boolean migrateAudioDefaults() {
        if (configVersion != null && configVersion >= 1) {
            return false;
        }
        reverbEnabled = false;
        duckingEnabled = false;
        echoCancelEnabled = false;
        agcEnabled = false;
        denoiseMode = "OFF";
        configVersion = 1;
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Config migrated: audio extras default to OFF (SVC native denoise)");
        return true;
    }

    public void save() {
        try {
            ConfigFileUtil.writePrivate(path(), GSON.toJson(this));
        } catch (IOException e) {
            TeamspeakSvcClient.LOGGER.error("[teamspeak-svc] Failed to save config: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return tsHost != null && !tsHost.isBlank();
    }

    /** 当前 MC 玩家名。 */
    public static String playerName() {
        return net.minecraft.client.Minecraft.getInstance().getUser().getName();
    }
}
