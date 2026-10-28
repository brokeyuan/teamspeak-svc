package com.tsvoice.server;

import com.tsvoice.protocol.capability.CapabilityWire;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M7-T14.4 服务端配置与公告迁移回归：全新安装、旧有效文件、旧模板、损坏输入、
 * 新旧并存、二次启动、未实现 kind、字段边界。新配置优先，旧文件保留。
 */
public final class ServerConfigTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static String legacyJson(String enabled, String candidates) {
        return "{\"enabled\":" + enabled + ",\"capabilities\":{\"serviceInstanceId\":\"legacy-id\","
                + "\"ttlSeconds\":25,\"candidates\":" + candidates + "}}";
    }

    public static void main(String[] args) throws Exception {
        // ---- fresh install: disabled + generated stable id ----
        Path dir = Files.createTempDirectory("m7-fresh");
        ServerConfig.LoadResult result = ServerConfig.load(dir);
        check(result.valid() && result.config() != null, "F1: fresh install loads with defaults");
        check(result.config().capabilities.serviceInstanceId != null
                        && !result.config().capabilities.enabled
                        && result.config().capabilities.targets.isEmpty(),
                "F1: fresh install is disabled with no targets and a generated service id");
        ServerConfig.save(result.config(), ServerConfig.file(dir));
        ServerConfig.LoadResult second = ServerConfig.load(dir);
        check(second.valid() && second.config().capabilities.serviceInstanceId
                        .equals(result.config().capabilities.serviceInstanceId),
                "F1: second launch reuses the persisted service id (no renewal)");

        // ---- legacy valid config: migrated enabled, id renewed, legacy kept ----
        Path legacyDir = Files.createTempDirectory("m7-legacy");
        Files.createDirectories(ServerConfig.legacyFile(legacyDir).getParent());
        Files.writeString(ServerConfig.legacyFile(legacyDir),
                legacyJson("true", "[{\"kind\":\"ts_endpoint\",\"host\":\"ts.real\",\"port\":9987}]"));
        ServerConfig.LoadResult migrated = ServerConfig.load(legacyDir);
        check(migrated.valid() && migrated.migratedFromLegacy()
                        && migrated.config().capabilities.enabled
                        && migrated.config().announceableTargets().size() == 1
                        && "ts.real".equals(migrated.config().capabilities.targets.get(0).host),
                "F2: a valid legacy config migrates enabled with its real endpoint");
        check(!"legacy-id".equals(migrated.config().capabilities.serviceInstanceId),
                "F2: the legacy default service id is renewed (re-authorization flagged)");
        check(Files.exists(ServerConfig.legacyFile(legacyDir))
                        && Files.exists(ServerConfig.legacyFile(legacyDir)
                        .resolveSibling("voice-capabilities.json.migrated-backup")),
                "F2: the legacy file is preserved and backed up");

        // ---- legacy template stays disabled ----
        Path templateDir = Files.createTempDirectory("m7-template");
        Files.createDirectories(ServerConfig.legacyFile(templateDir).getParent());
        Files.writeString(ServerConfig.legacyFile(templateDir),
                legacyJson("true", "[{\"kind\":\"ts_endpoint\",\"host\":\"voice.example.org\",\"port\":9987}]"));
        ServerConfig.LoadResult template = ServerConfig.load(templateDir);
        check(template.valid() && template.templateOnly() && !template.config().capabilities.enabled,
                "F3: the unconfigured template example stays disabled");

        // ---- corrupt new config: INVALID, not overwritten ----
        Path corruptDir = Files.createTempDirectory("m7-corrupt");
        Files.createDirectories(ServerConfig.file(corruptDir).getParent());
        Files.writeString(ServerConfig.file(corruptDir), "{ not json ");
        ServerConfig.LoadResult corrupt = ServerConfig.load(corruptDir);
        check(!corrupt.valid() && corrupt.problem().contains("unreadable"),
                "F4: a corrupt server.json reports INVALID_CONFIG");
        check("{ not json ".equals(Files.readString(ServerConfig.file(corruptDir))),
                "F4: the user's corrupt file is never overwritten");

        // ---- legacy migration failure keeps legacy intact ----
        Path brokenLegacyDir = Files.createTempDirectory("m7-brokenlegacy");
        Files.createDirectories(ServerConfig.legacyFile(brokenLegacyDir).getParent());
        Files.writeString(ServerConfig.legacyFile(brokenLegacyDir), "{ broken");
        ServerConfig.LoadResult brokenLegacy = ServerConfig.load(brokenLegacyDir);
        check(!brokenLegacy.valid() && brokenLegacy.problem().contains("legacy migration failed"),
                "F5: failed legacy migration reports INVALID_CONFIG");
        check("{ broken".equals(Files.readString(ServerConfig.legacyFile(brokenLegacyDir))),
                "F5: the broken legacy file is preserved");

        // ---- new file wins over legacy ----
        Path bothDir = Files.createTempDirectory("m7-both");
        ServerConfig existing = new ServerConfig();
        existing.capabilities.enabled = false;
        ServerConfig.save(existing, ServerConfig.file(bothDir));
        Files.createDirectories(ServerConfig.legacyFile(bothDir).getParent());
        Files.writeString(ServerConfig.legacyFile(bothDir),
                legacyJson("true", "[{\"kind\":\"ts_endpoint\",\"host\":\"ignored\",\"port\":1}]"));
        ServerConfig.LoadResult both = ServerConfig.load(bothDir);
        check(both.valid() && !both.migratedFromLegacy() && !both.config().capabilities.enabled,
                "F6: an existing server.json wins over the legacy file");

        // ---- unimplemented kinds: diagnostics, not targets ----
        ServerConfig config = new ServerConfig();
        config.capabilities.enabled = true;
        config.capabilities.targets = List.of(
                new ServerConfig.Target("ts_endpoint", "ok.host", 9987),
                new ServerConfig.Target("ts_relay", "future.host", 1),
                new ServerConfig.Target("svc_tcp", "future.host", 2),
                new ServerConfig.Target("music", "future.host", 3));
        check(config.announceableTargets().size() == 1,
                "F7: future transport kinds never become announceable targets in this version");
        check(ServerConfig.validateTarget(new ServerConfig.Target("ts_relay", "h", 1))
                        .contains("not implemented"),
                "F7: unknown kinds produce a 'not implemented' diagnostic");

        // ---- wire-bound field validation ----
        config.capabilities.ttlSeconds = 61;
        check(config.validate() != null && config.validate().contains("ttlSeconds"),
                "F8: ttl above the wire limit is rejected");
        config.capabilities.ttlSeconds = 60;
        config.capabilities.targets = List.of(new ServerConfig.Target("ts_endpoint", "ok.host", 9987));
        check(config.validate() == null, "F8: ttl at the wire limit is accepted");
        config.capabilities.targets = List.of(new ServerConfig.Target("ts_endpoint", "h", 0));
        check(config.validate() != null && config.validate().contains("port"), "F8: port 0 rejected");
        config.capabilities.targets = List.of(new ServerConfig.Target("ts_endpoint", "h", 65536));
        check(config.validate() != null && config.validate().contains("port"), "F8: port 65536 rejected");
        String longHost = "h".repeat(CapabilityWire.MAX_HOST_LENGTH + 1);
        config.capabilities.targets = List.of(new ServerConfig.Target("ts_endpoint", longHost, 9987));
        check(config.validate() != null && config.validate().contains("host"), "F8: oversized host rejected");

        if (failures != 0) {
            throw new AssertionError(failures + " server config checks failed");
        }
        System.out.println("Server config regression: all checks passed");
    }
}
