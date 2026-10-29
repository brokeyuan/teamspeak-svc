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

    // ---- S0.5/R24：按引用（credentialRef/identityRef）的独立凭据 ----
    //
    // 同 host:port 的多个书签各自持 ref，互不串用；旧 endpoint 键读取保留兼容
    // 回退。ref 键与 endpoint 键共存同一文件（"ref:<id>" 前缀隔离），迁移为两个
    // 书签分配独立 ref 时复制原值、不删除旧条目，未修改的引用原样保存。

    /** ref 命名空间前缀：与 endpoint 键隔离，不参与 endpoint 兼容读取。 */
    static final String REF_PREFIX = "ref:";

    /** 书签身份引用（identityRef）的受控目录名；只允许安全标识符。 */
    private static final String IDENTITY_DIR = "identities";

    /** ref 键：ref 值为空时回落 endpoint 键（旧书签兼容）。 */
    private static String keyFor(String credentialRef, String host, Integer port) {
        return credentialRef != null && !credentialRef.isBlank()
                ? REF_PREFIX + credentialRef
                : normalizeEndpoint(host, port);
    }

    /**
     * 按引用读取凭据；ref 未记录且书签带 ref 时返回 null —— 不静默回落到
     * endpoint 共享条目（引用存在就意味着"独立凭据"，缺失是配置错误）。
     * ref 为空时按旧 endpoint 语义读取（兼容回退）。
     */
    public static Credential getByRef(String credentialRef, String host, Integer port) {
        ensureLoaded();
        if (credentialRef == null || credentialRef.isBlank()) {
            return get(host, port);
        }
        return credentials.get(REF_PREFIX + credentialRef);
    }

    /** 按引用写入凭据（null=清除字段；语义与 {@link #set} 一致，键为 ref）。 */
    public static synchronized void setByRef(String credentialRef, String serverPassword,
                                             String channelPassword) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException("credentialRef required");
        }
        ensureLoaded();
        String key = REF_PREFIX + credentialRef;
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
     * 迁移辅助：为书签分配独立 ref 时复制原 endpoint 条目（存在才复制；
     * 不删除旧 endpoint 条目，未迁移路径继续可用）。返回是否复制。
     */
    public static synchronized boolean copyEndpointToRef(String host, Integer port, String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return false;
        }
        ensureLoaded();
        Credential existing = credentials.get(normalizeEndpoint(host, port));
        String refKey = REF_PREFIX + credentialRef;
        if (existing == null || credentials.containsKey(refKey)) {
            return false; // 无原值可复制，或 ref 已有独立凭据（绝不覆盖）
        }
        credentials.put(refKey, new Credential(existing.serverPassword(), existing.channelPassword(), 1));
        save();
        return true;
    }

    /**
     * 身份引用解析（S0.5/R24）：identityRef 只允许引用本地受控身份目录
     * {@code config/teamspeak-svc/identities/<ref>.txt} 中的记录 —— 不接受任意
     * 磁盘路径、路径分隔符或网络地址；ref 缺失返回明确 null，调用方报错而不
     * 静默换身份。全局 identity.txt 的迁移由书签保存路径显式复制，绝不自动替换。
     */
    public static java.nio.file.Path identityRefPath(String identityRef) {
        if (identityRef == null || identityRef.isBlank()) {
            return null;
        }
        String safe = identityRef.strip();
        if (safe.isEmpty() || !safe.matches("[A-Za-z0-9._-]{1,64}")
                || safe.startsWith(".") || safe.endsWith(".")) {
            return null; // 非法引用：不是受控目录内的记录
        }
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc").resolve(IDENTITY_DIR).resolve(safe + ".txt");
    }

    /** 加载身份引用指向的 TS 身份；缺失/非法引用返回 null（调用方明确报错）。 */
    public static com.tsvoice.client.ts3voice.Ts3Identity loadIdentityRef(String identityRef) {
        java.nio.file.Path path = identityRefPath(identityRef);
        if (path == null || !java.nio.file.Files.exists(path)) {
            return null;
        }
        return com.tsvoice.client.ts3voice.Ts3Identity.load(path);
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
