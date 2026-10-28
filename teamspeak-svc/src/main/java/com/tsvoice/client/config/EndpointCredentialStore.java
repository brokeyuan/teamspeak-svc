package com.tsvoice.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tsvoice.client.ConfigFileUtil;
import com.tsvoice.client.TeamspeakSvcClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按规范化 endpoint 隔离保存的 TS 凭据（server/channel 密码）。
 *
 * <p>规范化：host 转小写并去尾点 + ":" + 端口 —— 不同域名别名不自动共用凭据，
 * 避免 DNS 解析变化造成密码误配。revision 在每次变更时递增，供会话层检测
 * 凭据变化触发重连。secret 不进入 toString/日志；迁移只复制到明确匹配的
 * endpoint，绝不把全局密码扩散到其他 host。</p>
 */
public final class EndpointCredentialStore {

    /** 凭据条目；toString 脱敏（secret 不出日志/调试输出）。 */
    public static final class Credential {
        String serverPassword;
        String channelPassword;
        int revision;

        Credential(String serverPassword, String channelPassword, int revision) {
            this.serverPassword = serverPassword;
            this.channelPassword = channelPassword;
            this.revision = revision;
        }

        public String serverPassword() {
            return serverPassword;
        }

        public String channelPassword() {
            return channelPassword;
        }

        public int revision() {
            return revision;
        }

        @Override
        public String toString() {
            return "Credential{serverPassword=" + (serverPassword == null ? "null" : "<set>")
                    + ", channelPassword=" + (channelPassword == null ? "null" : "<set>")
                    + ", revision=" + revision + "}";
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Credential> credentials = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private EndpointCredentialStore() {
    }

    /** 规范化 endpoint 键：host 小写去尾点 + ":" + 端口。 */
    public static String normalizeEndpoint(String host, Integer port) {
        String normalizedHost = host == null ? "" : host.strip().toLowerCase(java.util.Locale.ROOT);
        while (normalizedHost.endsWith(".")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        return normalizedHost + ":" + (port != null ? port : 9987);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc").resolve("endpoint-credentials.json");
    }

    public static synchronized void load() {
        loaded = true;
        credentials.clear();
        try {
            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            Map<String, Credential> parsed = GSON.fromJson(Files.readString(path),
                    new TypeToken<HashMap<String, Credential>>() {
                    }.getType());
            if (parsed != null) {
                credentials.putAll(parsed);
            }
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to load endpoint credentials: {}", e.getMessage());
            ConfigFileUtil.backupBroken(file());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            ConfigFileUtil.writePrivate(file(), GSON.toJson(credentials));
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to save endpoint credentials: {}", e.getMessage());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static Credential get(String host, Integer port) {
        ensureLoaded();
        return credentials.get(normalizeEndpoint(host, port));
    }

    /** 写入凭据（null=清除该字段）；变更递增 revision。 */
    public static synchronized void set(String host, Integer port,
                                         String serverPassword, String channelPassword) {
        ensureLoaded();
        String key = normalizeEndpoint(host, port);
        Credential existing = credentials.get(key);
        int revision = existing != null ? existing.revision() + 1 : 1;
        if (serverPassword != null && serverPassword.isEmpty()) {
            serverPassword = null;
        }
        if (channelPassword != null && channelPassword.isEmpty()) {
            channelPassword = null;
        }
        if (serverPassword == null && channelPassword == null) {
            credentials.remove(key);
        } else {
            credentials.put(key, new Credential(serverPassword, channelPassword, revision));
        }
        save();
    }

    /**
     * 一次性迁移：仅当旧来源 endpoint 与目标完全一致时复制凭据。
     * 旧配置文件原样保留（迁移是复制而非删除）；不能确定对应关系的密码不复制。
     */
    public static synchronized int migrateFrom(String legacyHost, Integer legacyPort,
                                                String serverPassword, String channelPassword) {
        ensureLoaded();
        if (legacyHost == null || legacyHost.isBlank()) {
            return 0;
        }
        if ((serverPassword == null || serverPassword.isEmpty())
                && (channelPassword == null || channelPassword.isEmpty())) {
            return 0;
        }
        String key = normalizeEndpoint(legacyHost, legacyPort);
        if (credentials.containsKey(key)) {
            return 0; // 已有凭据：绝不覆盖
        }
        String server = serverPassword == null || serverPassword.isEmpty() ? null : serverPassword;
        String channel = channelPassword == null || channelPassword.isEmpty() ? null : channelPassword;
        if (server == null && channel == null) {
            return 0;
        }
        credentials.put(key, new Credential(server, channel, 1));
        save();
        return 1;
    }

    /** 已记录的 endpoint 数（诊断/测试）。 */
    public static int size() {
        ensureLoaded();
        return credentials.size();
    }
}
