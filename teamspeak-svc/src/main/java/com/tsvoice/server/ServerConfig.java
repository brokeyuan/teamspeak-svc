package com.tsvoice.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tsvoice.protocol.capability.CapabilityWire;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端配置（M7-T14.4）：config/teamspeak-svc/server.json。
 * 只暴露已实现能力（能力公告）；未来传输/音乐条目给出"该版本未实现"诊断，
 * 不创建虚假状态。旧 voice-capabilities.json 只在新文件不存在时备份迁移；
 * 损坏输入不覆盖用户原内容 —— 公告停止并显示配置错误。
 */
public final class ServerConfig {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TARGETS = CapabilityWire.MAX_CANDIDATES;
    public static final long MAX_TTL_SECONDS = CapabilityWire.MAX_TTL_SECONDS;
    /** 旧公告配置中的模板示例：精确匹配视为未配置，保持关闭。 */
    static final String TEMPLATE_HOST = "voice.example.org";
    static final int TEMPLATE_PORT = 9987;

    /** targets 单项：沿用上一轮 kind/host/port 三字段（M7 不发明新传输类型）。 */
    public static final class Target {
        public String kind;
        public String host;
        public Integer port;

        public Target() {
        }

        public Target(String kind, String host, Integer port) {
            this.kind = kind;
            this.host = host;
            this.port = port;
        }
    }

    public static final class CapabilitiesSection {
        public boolean enabled = false;
        public String serviceInstanceId = null; // 首次创建时生成并持久保存
        public long ttlSeconds = 30;
        public List<Target> targets = new ArrayList<>();
    }

    /** TS 中继 allowlist 目标（S1.3/P6）：targetId → host/port；DATA 不携带地址。 */
    public static final class RelayTarget {
        public String host;
        public Integer port;

        public RelayTarget() {
        }

        public RelayTarget(String host, Integer port) {
            this.host = host;
            this.port = port;
        }
    }

    /** TS 中继配置节（S1.3/P6）：默认关闭；限额为工程初值，管理员可调小。 */
    public static final class RelaySection {
        public boolean enabled = false;
        public List<RelayTarget> targets = new ArrayList<>();
        /** targetId → host/port；key 小写规范化。 */
        public Map<String, RelayTarget> targetIds = new java.util.LinkedHashMap<>();
        public int maxDatagramBytes = 4096;
        public int maxDatagramsPerSecond = 256;
        public int maxBytesPerSecond = 512 * 1024;
        public int maxSessions = 64;
        /** SVC_MC_CHANNEL 服务端适配（S2/P5）：默认关闭；需要 SVC 服务端在场。 */
        public boolean svcMcChannelEnabled = false;
    }

    public int schemaVersion = SCHEMA_VERSION;
    public boolean integratedServerEnabled = false;
    public CapabilitiesSection capabilities = new CapabilitiesSection();
    public RelaySection relay = new RelaySection();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 配置文件位置：config/teamspeak-svc/server.json。 */
    public static Path file(Path configDir) {
        return configDir.resolve("teamspeak-svc").resolve("server.json");
    }

    /** 旧公告配置位置：config/voice-capabilities.json（只读，迁移后保留）。 */
    public static Path legacyFile(Path configDir) {
        return configDir.resolve("voice-capabilities.json");
    }

    /**
     * 读取并迁移：新文件存在则优先使用（损坏则报告 INVALID_CONFIG 不覆盖）；
     * 仅当新文件不存在且旧文件存在时备份迁移。返回结果带来源与诊断。
     */
    public static LoadResult load(Path configDir) {
        Path file = file(configDir);
        if (Files.exists(file)) {
            try {
                ServerConfig parsed = GSON.fromJson(Files.readString(file), ServerConfig.class);
                if (parsed == null) {
                    return LoadResult.invalid(file, "empty config file");
                }
                String problem = parsed.validate();
                if (problem != null) {
                    return LoadResult.invalid(file, problem);
                }
                return LoadResult.loaded(parsed, file, "existing server.json");
            } catch (Exception e) {
                // 损坏输入：不覆盖用户原内容
                return LoadResult.invalid(file, "unreadable server.json: " + e.getMessage());
            }
        }
        Path legacy = legacyFile(configDir);
        if (Files.exists(legacy)) {
            return migrateLegacy(configDir, file, legacy);
        }
        ServerConfig fresh = new ServerConfig();
        fresh.capabilities.serviceInstanceId = UUID.randomUUID().toString();
        return LoadResult.loaded(fresh, file, "fresh install (defaults: announcements off, no targets)");
    }

    private static LoadResult migrateLegacy(Path configDir, Path newFile, Path legacy) {
        try {
            String legacyJson = Files.readString(legacy);
            LegacyAnnouncerConfig old = GSON.fromJson(legacyJson, LegacyAnnouncerConfig.class);
            if (old == null) {
                return LoadResult.invalid(newFile, "legacy voice-capabilities.json is empty — announcements off");
            }
            boolean templateOnly = old.isTemplateOnly();
            ServerConfig migrated = new ServerConfig();
            migrated.capabilities.serviceInstanceId = UUID.randomUUID().toString();
            if (old.capabilities != null) {
                migrated.capabilities.ttlSeconds = clampTtl(old.capabilities.ttlSeconds);
                if (old.capabilities.candidates != null) {
                    for (LegacyCandidate c : old.capabilities.candidates) {
                        if (c == null || c.kind == null || c.host == null || c.host.isBlank()) {
                            continue;
                        }
                        migrated.capabilities.targets.add(new Target(c.kind, c.host, c.port));
                    }
                }
            }
            // 旧默认服务标识 → 新持久 ID：授权方可能需要重新确认（记录，不隐藏）
            migrated.capabilities.enabled = old.effectiveEnabled() && !templateOnly;
            backupLegacy(legacy);
            save(migrated, newFile);
            return LoadResult.migrated(migrated, newFile, templateOnly
                    ? "legacy config was the unconfigured template — kept disabled"
                    : "migrated from voice-capabilities.json (serviceInstanceId renewed; re-authorization may be needed)",
                    templateOnly);
        } catch (Exception e) {
            // 迁移失败：不覆盖、不删除旧文件；公告停止并显示配置错误
            return LoadResult.invalid(newFile, "legacy migration failed: " + e.getMessage());
        }
    }

    private static void backupLegacy(Path legacy) throws IOException {
        Path backup = legacy.resolveSibling("voice-capabilities.json.migrated-backup");
        if (!Files.exists(backup)) {
            Files.copy(legacy, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 校验；返回 null = 合法，否则为诊断文本（不静默抹掉未知字段以外的错误）。 */
    public String validate() {
        if (capabilities == null) {
            return "missing capabilities section";
        }
        if (capabilities.ttlSeconds < 0 || capabilities.ttlSeconds > MAX_TTL_SECONDS) {
            return "capabilities.ttlSeconds must be within 0.." + MAX_TTL_SECONDS;
        }
        if (capabilities.targets != null && capabilities.targets.size() > MAX_TARGETS) {
            return "capabilities.targets exceeds " + MAX_TARGETS;
        }
        if (capabilities.targets != null) {
            for (Target t : capabilities.targets) {
                String problem = validateTarget(t);
                if (problem != null) {
                    return problem;
                }
            }
        }
        String relayProblem = validateRelay();
        if (relayProblem != null) {
            return relayProblem;
        }
        return null;
    }

    /** relay 节校验（S1.3/P6）：allowlist 目标、限额上限。 */
    public String validateRelay() {
        if (relay == null) {
            return null; // 旧配置文件缺 relay 节：Gson 置 null 时按默认关闭处理
        }
        if (relay.targetIds == null) {
            relay.targetIds = new java.util.LinkedHashMap<>();
        }
        if (relay.targetIds.size() > 16) {
            return "relay.targetIds exceeds 16";
        }
        for (Map.Entry<String, RelayTarget> e : relay.targetIds.entrySet()) {
            String id = e.getKey();
            if (id == null || id.isBlank() || id.length() > 64
                    || !id.matches("[a-z0-9._-]{1,64}")) {
                return "relay target id '" + id + "' invalid (use 1..64 chars of a-z 0-9 . _ -)";
            }
            RelayTarget t = e.getValue();
            if (t == null || t.host == null || t.host.isBlank()
                    || t.host.length() > CapabilityWire.MAX_HOST_LENGTH) {
                return "relay target '" + id + "' host missing or too long";
            }
            if (t.port == null || t.port < 1 || t.port > 65535) {
                return "relay target '" + id + "' port must be within 1..65535";
            }
        }
        if (relay.maxDatagramBytes < 1 || relay.maxDatagramBytes > 4096) {
            return "relay.maxDatagramBytes must be within 1..4096";
        }
        if (relay.maxDatagramsPerSecond < 1 || relay.maxDatagramsPerSecond > 4096) {
            return "relay.maxDatagramsPerSecond must be within 1..4096";
        }
        if (relay.maxBytesPerSecond < 1024 || relay.maxBytesPerSecond > 16 * 1024 * 1024) {
            return "relay.maxBytesPerSecond must be within 1 KiB..16 MiB per second";
        }
        if (relay.maxSessions < 1 || relay.maxSessions > 64) {
            return "relay.maxSessions must be within 1..64";
        }
        return null;
    }

    /** 单目标校验：已知 kind 之外的取值为"该版本未实现"诊断。 */
    public static String validateTarget(Target t) {
        if (t == null || t.kind == null || t.kind.isBlank()) {
            return "target without kind";
        }
        String kind = t.kind.trim().toLowerCase();
        if (!"ts_endpoint".equals(kind)) {
            return "target kind '" + t.kind + "' is not implemented in this version (supported: ts_endpoint)";
        }
        if (t.host == null || t.host.isBlank() || t.host.length() > CapabilityWire.MAX_HOST_LENGTH) {
            return "target host missing or exceeds " + CapabilityWire.MAX_HOST_LENGTH;
        }
        if (t.port == null || t.port < 1 || t.port > 65535) {
            return "target port must be within 1..65535";
        }
        return null;
    }

    /** 校验合法的可公告目标（已实现的 kind）。 */
    public List<Target> announceableTargets() {
        List<Target> out = new ArrayList<>();
        if (capabilities == null || capabilities.targets == null) {
            return out;
        }
        for (Target t : capabilities.targets) {
            if (validateTarget(t) == null) {
                out.add(t);
            }
        }
        return out;
    }

    /** 原子保存（临时文件 + move；move 不被支持时退化为直接写）。 */
    public static void save(ServerConfig config, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(config));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long clampTtl(long ttl) {
        if (ttl < 0) {
            return 0;
        }
        return Math.min(ttl, MAX_TTL_SECONDS);
    }

    /** 读取结果（来源与诊断；状态由 ServerRuntime 映射）。 */
    public record LoadResult(ServerConfig config, Path file, String source, String problem,
                             boolean valid, boolean migratedFromLegacy, boolean templateOnly) {
        static LoadResult loaded(ServerConfig config, Path file, String source) {
            return new LoadResult(config, file, source, null, true, false, false);
        }

        static LoadResult migrated(ServerConfig config, Path file, String source, boolean templateOnly) {
            return new LoadResult(config, file, source, null, true, true, templateOnly);
        }

        static LoadResult invalid(Path file, String problem) {
            return new LoadResult(null, file, null, problem, false, false, false);
        }
    }

    /** 旧公告配置的最小读取面（只取迁移所需字段）。 */
    static final class LegacyAnnouncerConfig {
        public Boolean enabled;
        public LegacyCapabilities capabilities;

        boolean effectiveEnabled() {
            return enabled != null && enabled;
        }

        /** 精确匹配旧模板（voice.example.org:9987 单候选）视为未配置。 */
        boolean isTemplateOnly() {
            if (capabilities == null || capabilities.candidates == null
                    || capabilities.candidates.size() != 1) {
                return false;
            }
            LegacyCandidate only = capabilities.candidates.get(0);
            return only != null && TEMPLATE_HOST.equalsIgnoreCase(only.host)
                    && only.port != null && only.port == TEMPLATE_PORT;
        }
    }

    static final class LegacyCapabilities {
        public Long ttlSeconds;
        public String serviceInstanceId;
        public List<LegacyCandidate> candidates;
    }

    static final class LegacyCandidate {
        public String kind;
        public String host;
        public Integer port;
    }
}
