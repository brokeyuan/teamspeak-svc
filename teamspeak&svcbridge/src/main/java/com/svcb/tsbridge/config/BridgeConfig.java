package com.svcb.tsbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.svcb.tsbridge.TsBridgeMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 配置类，对应 &lt;游戏根目录&gt;/config/tsbridge.json。
 * 由 mod 自己读取（不依赖 Fabric Config API），用 Gson 序列化。
 */
public class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String tsHost = "127.0.0.1";
    public int tsQueryPort = 10011;          // ServerQuery 默认端口
    public int tsServerPort = 9987;          // 虚拟服务器端口（selectVirtualServerByPort 用）
    public int tsVoicePort = 9987;           // TS3 语音 UDP 端口（原生传输用）
    public String queryUsername = "serveradmin";
    public String queryPassword = "";
    /** @deprecated v1 遗留，从未被读取 */
    @Deprecated
    public String botApiUrl = "";
    /** @deprecated v1 遗留，从未被读取 */
    @Deprecated
    public String botApiToken = "";
    public String bridgeChannelName = "MC Bridge";   // TS 侧桥接频道名
    public String bridgeGroupName = "TeamSpeak互联";  // SVC 侧永久群组名
    public int pairCodeLength = 6;
    public int pairCodeExpiryMinutes = 5;
    public boolean autoPairing = false;      // v2: 自动配对模式（TS 用户进桥接频道即绑定 pending 配对码）
    public boolean sendSilenceFrames = false; // v2: 无人说话时是否发送静音帧
    /**
     * 原生 TS3 语音传输。完整协议实现（握手/license 加密/clientinit/语音收发），
     * 已对真实 TS3 服务器验证。关闭后回退到 TS3AudioBot（如已配置）。
     */
    public boolean nativeVoiceEnabled = true;
    /**
     * 运行模式：bridge（默认，完整音频桥接）| group-sync（仅把 TS 频道镜像为
     * SVC 常驻群组，不启动任何音频组件 —— 与客户端 teamspeak-svc mod 共存）。
     */
    public String mode = "bridge";
    /** group-sync 模式：其直接子频道镜像为 SVC 常驻群组的 TS 父频道名。 */
    public String groupSyncParentChannel = "MINECRAFT";
    /**
     * 对外下发给客户端 mod 的 TS 地址（公网 IP/域名；空 = 不下发信标）。
     * 注意不能填内网地址 —— 客户端要用它直连 TS。
     */
    public String publicTsHost = "";
    /**
     * TCP 隧道端口（UDP-over-TCP，给 UDP 受限网络的客户端用；0 = 关闭）。
     * 客户端 transport:"tcp" 时连接 publicTsHost:该端口。
     */
    public int tcpTunnelPort = 0;
    /** 隧道监听地址，默认仅回环；公开部署须同时设置 TBRIDGE_TUNNEL_SECRET。 */
    public String tcpTunnelBindAddress = "127.0.0.1";
    /**
     * 真 1:1 模式：为每个在线 MC 玩家建立专属 TS 语音客户端（以玩家名作为昵称），
     * 语音零混音直通。关闭则回退为单 bot 混音模式（MCBridge）。
     */
    public boolean perPlayerClients = true;
    public boolean debug = false;

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("tsbridge.json");
    }

    /**
     * 加载配置：文件不存在则创建默认并保存；存在则用 Gson 解析。
     * 解析失败时打印 ERROR 并返回默认配置（不抛异常）。
     */
    public static BridgeConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            BridgeConfig config = new BridgeConfig();
            config.save();
            TsBridgeMod.LOGGER.info("[tsbridge] Created default config at {}", path);
            return config;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            BridgeConfig config = GSON.fromJson(json, BridgeConfig.class);
            if (config == null) {
                TsBridgeMod.LOGGER.error("[tsbridge] Failed to parse config at {} (empty/invalid JSON), using defaults", path);
                return new BridgeConfig();
            }
            return config;
        } catch (IOException | JsonSyntaxException e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to load config at {}, using defaults: {}", path, e.getMessage());
            return new BridgeConfig();
        }
    }

    /**
     * 写回文件（PrettyPrinting，UTF-8）。
     */
    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(this);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to save config at {}: {}", path, e.getMessage());
        }
    }

    /**
     * 重新加载可热更的策略字段。连接、频道与模式等需重建组件的修改要求重启，
     * 检测到任一此类修改则拒绝整次 reload，避免部分更新信标/模式却保留旧组件。
     */
    public synchronized void reload() {
        Path path = configPath();
        if (!Files.exists(path)) {
            throw new IllegalStateException("Config file missing; current values kept");
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            BridgeConfig fresh = GSON.fromJson(json, BridgeConfig.class);
            if (fresh == null) {
                throw new IllegalStateException("Config is empty/invalid; current values kept");
            }
            List<String> restart = restartRequiredChanges(fresh);
            if (!restart.isEmpty()) {
                throw new IllegalStateException("Restart required for changed fields: "
                        + String.join(", ", restart) + "; current values kept");
            }
            this.pairCodeLength = fresh.pairCodeLength;
            this.pairCodeExpiryMinutes = fresh.pairCodeExpiryMinutes;
            this.autoPairing = fresh.autoPairing;
            this.sendSilenceFrames = fresh.sendSilenceFrames;
        } catch (IOException | JsonSyntaxException e) {
            throw new IllegalStateException("Failed to reload config; current values kept: " + e.getMessage(), e);
        }
    }

    private List<String> restartRequiredChanges(BridgeConfig fresh) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(tsHost, fresh.tsHost)) changed.add("tsHost");
        if (tsQueryPort != fresh.tsQueryPort) changed.add("tsQueryPort");
        if (tsServerPort != fresh.tsServerPort) changed.add("tsServerPort");
        if (tsVoicePort != fresh.tsVoicePort) changed.add("tsVoicePort");
        if (!Objects.equals(queryUsername, fresh.queryUsername)) changed.add("queryUsername");
        if (!Objects.equals(queryPassword, fresh.queryPassword)) changed.add("queryPassword");
        if (!Objects.equals(botApiUrl, fresh.botApiUrl)) changed.add("botApiUrl");
        if (!Objects.equals(botApiToken, fresh.botApiToken)) changed.add("botApiToken");
        if (!Objects.equals(bridgeChannelName, fresh.bridgeChannelName)) changed.add("bridgeChannelName");
        if (!Objects.equals(bridgeGroupName, fresh.bridgeGroupName)) changed.add("bridgeGroupName");
        if (nativeVoiceEnabled != fresh.nativeVoiceEnabled) changed.add("nativeVoiceEnabled");
        if (!Objects.equals(mode, fresh.mode)) changed.add("mode");
        if (!Objects.equals(groupSyncParentChannel, fresh.groupSyncParentChannel)) changed.add("groupSyncParentChannel");
        if (!Objects.equals(publicTsHost, fresh.publicTsHost)) changed.add("publicTsHost");
        if (tcpTunnelPort != fresh.tcpTunnelPort) changed.add("tcpTunnelPort");
        if (!Objects.equals(tcpTunnelBindAddress, fresh.tcpTunnelBindAddress)) changed.add("tcpTunnelBindAddress");
        if (perPlayerClients != fresh.perPlayerClients) changed.add("perPlayerClients");
        if (debug != fresh.debug) changed.add("debug");
        return changed;
    }
}
