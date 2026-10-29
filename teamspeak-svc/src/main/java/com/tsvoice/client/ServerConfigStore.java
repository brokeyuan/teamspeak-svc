package com.tsvoice.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按服务器地址键控的每服配置（`config/teamspeak-svc/servers.json`）。
 *
 * <p>解析优先级：本服信标（useServerBeacon=true 且收到过信标） &gt; 本服手动值 &gt;
 * 全局 `teamspeak-svc.json`（兼容 v0.1）。enabled=false 可对某服强制停用。</p>
 */
public final class ServerConfigStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Entry>>() {
    }.getType();

    public static class Entry {
        /** 该服是否启用本 mod（默认 null=跟随信标自动）。 */
        public Boolean enabled;
        public String host;
        public Integer port;
        public String channel;
        /** TS 服务器密码；null 继承全局，空字符串显式无密码。 */
        public String serverPassword;
        /** TS 主频道密码；null 继承全局，空字符串显式无密码。 */
        public String channelPassword;
        /** 本服传输层覆盖：null=跟随全局 transport；"udp"/"tcp"=本服强制。 */
        public String transport;
        /** 优先使用服务器下发值（默认 true）。 */
        public Boolean useServerBeacon;
        /** 玩家明确记住的信标 TS endpoint；不用于自动继承凭据。 */
        public String trustedBeaconHost;
        public Integer trustedBeaconPort;
    }

    private static final Map<String, Entry> store = new ConcurrentHashMap<>();

    private ServerConfigStore() {
    }

    public static synchronized void load() {
        store.clear();
        Path p = path();
        try {
            if (Files.exists(p)) {
                Map<String, Entry> data = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), TYPE);
                if (data != null) {
                    store.putAll(data);
                }
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to load servers.json: {}", e.getMessage());
            ConfigFileUtil.backupBroken(p);
        }
    }

    public static synchronized void save() {
        try {
            // 过滤“无地址且全空”的占位条目，避免持久化 "": {}
            Map<String, Entry> snapshot = new java.util.LinkedHashMap<>();
            for (var e : store.entrySet()) {
                if (e.getKey().isEmpty() && isEmpty(e.getValue())) {
                    continue;
                }
                snapshot.put(e.getKey(), e.getValue());
            }
            ConfigFileUtil.writePrivate(path(), GSON.toJson(snapshot));
        } catch (IOException e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to save servers.json: {}", e.getMessage());
        }
    }

    private static boolean isEmpty(Entry e) {
        return e != null && e.enabled == null && e.host == null && e.port == null
                && e.channel == null && e.serverPassword == null && e.channelPassword == null && e.transport == null
                && e.useServerBeacon == null && e.trustedBeaconHost == null && e.trustedBeaconPort == null;
    }

    public static Entry get(String address) {
        return store.computeIfAbsent(address == null ? "" : address, k -> new Entry());
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc").resolve("servers.json");
    }
}
