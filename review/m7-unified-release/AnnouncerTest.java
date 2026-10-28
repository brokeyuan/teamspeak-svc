package com.tsvoice.server;

import com.tsvoice.protocol.capability.CapabilityWire;

import java.nio.file.Files;
import java.util.List;
import java.nio.file.Path;

/**
 * M7-T14.4 公告器与 T14.5 运行时回归：enabled/disabled/invalid 状态、正确 nonce
 * 回显、限流、超大帧、无目标不回复、生命周期与重载、旧运行时不向新连接回复。
 */
public final class AnnouncerTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** 可注入时钟。 */
    static final class TickClock implements CapabilityAnnouncer.TimeSource {
        long now = 0;

        @Override
        public long nanoTime() {
            return now;
        }

        void advanceMillis(long ms) {
            now += ms * 1_000_000L;
        }
    }

    static ServerConfig config(boolean enabled, String host, Integer port, long ttl) {
        ServerConfig config = new ServerConfig();
        config.capabilities.enabled = enabled;
        config.capabilities.serviceInstanceId = "test-instance";
        config.capabilities.ttlSeconds = ttl;
        if (host != null) {
            config.capabilities.targets = List.of(new ServerConfig.Target("ts_endpoint", host, port));
        }
        return config;
    }

    static byte[] hello(long nonce) {
        return CapabilityWire.encode(new CapabilityWire.Capabilities(nonce,
                CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR, 0, "", List.of()));
    }

    static Path dirWith(ServerConfig config) throws Exception {
        Path dir = Files.createTempDirectory("m7-announcer");
        ServerConfig.save(config, ServerConfig.file(dir));
        return dir;
    }

    public static void main(String[] args) throws Exception {
        // ---- A1: hello echoes the nonce and announces the configured targets ----
        TickClock clock = new TickClock();
        CapabilityAnnouncer announcer = new CapabilityAnnouncer(config(true, "ts.real", 9987, 30), clock);
        byte[] response = announcer.onHello("conn-1", hello(12345));
        check(response != null, "A1: an active announcer answers a hello");
        CapabilityWire.Capabilities capabilities = CapabilityWire.decode(response);
        check(capabilities.requestNonce() == 12345, "A1: the response binds the request nonce");
        check("test-instance".equals(capabilities.serviceInstanceId())
                        && capabilities.candidates().size() == 1
                        && "ts.real".equals(capabilities.candidates().get(0).host())
                        && capabilities.candidates().get(0).port() == 9987,
                "A1: the announcement carries the config's service id and targets");
        check(response.length <= CapabilityWire.MAX_FRAME_BYTES, "A1: responses respect the wire limit");

        // ---- A2: disabled / no-targets / oversized / malformed never reply ----
        CapabilityAnnouncer disabled = new CapabilityAnnouncer(config(false, "ts.real", 9987, 30), clock);
        check(disabled.onHello("c", hello(1)) == null, "A2: a disabled announcer has no announcement side effects");
        CapabilityAnnouncer noTargets = new CapabilityAnnouncer(config(true, null, null, 30), clock);
        check(noTargets.onHello("c", hello(2)) == null, "A2: no valid targets -> no reply (clients keep their rules)");
        check(disabled.onHello("c", new byte[CapabilityWire.MAX_FRAME_BYTES + 1]) == null,
                "A2: oversized frames are rejected before processing");
        check(disabled.onHello("c", "{ broken".getBytes()) == null, "A2: malformed hello frames are dropped silently");

        // ---- A3: rate limit allows the burst, blocks, then recovers ----
        TickClock rateClock = new TickClock();
        CapabilityAnnouncer limited = new CapabilityAnnouncer(config(true, "ts.real", 9987, 30), rateClock);
        check(limited.onHello("c", hello(1)) != null && limited.onHello("c", hello(2)) != null
                        && limited.onHello("c", hello(3)) != null && limited.onHello("c", hello(4)) != null,
                "A3: a burst of 4 hellos is answered");
        check(limited.onHello("c", hello(5)) == null, "A3: beyond the burst the announcer stays silent");
        rateClock.advanceMillis(2_100);
        check(limited.onHello("c", hello(6)) != null,
                "A3: the limit recovers with time (only the reply is limited, players are never kicked)");

        // ---- A4: connection exit cleans its bucket ----
        CapabilityAnnouncer tracked = new CapabilityAnnouncer(config(true, "ts.real", 9987, 30), new TickClock());
        tracked.onHello("c1", hello(1));
        tracked.onHello("c2", hello(2));
        check(tracked.trackedConnections() == 2, "A4: connections are tracked for limiting");
        tracked.forgetConnection("c1");
        check(tracked.trackedConnections() == 1, "A4: disconnect removes the connection's records");

        // ---- A5: runtime states follow config reality ----
        ServerRuntime disabledRuntime = ServerRuntime.start(dirWith(config(false, "ts.real", 9987, 30)));
        check(disabledRuntime.status().state() == ServerRuntime.State.DISABLED && !disabledRuntime.announcing()
                        && disabledRuntime.onHello("c", hello(9)) == null,
                "A5: a DISABLED runtime never answers");
        disabledRuntime.close();

        ServerRuntime activeRuntime = ServerRuntime.start(dirWith(config(true, "ts.real", 9987, 30)));
        check(activeRuntime.status().state() == ServerRuntime.State.ACTIVE && activeRuntime.announcing()
                        && activeRuntime.onHello("c", hello(9)) != null,
                "A5: a valid enabled config yields an ACTIVE runtime");
        activeRuntime.close();

        ServerRuntime emptyRuntime = ServerRuntime.start(dirWith(config(true, null, null, 30)));
        check(emptyRuntime.status().state() == ServerRuntime.State.DISABLED,
                "A5: enabled without targets stays DISABLED (no fake announcements)");
        emptyRuntime.close();

        // ---- A6: reload with a broken config stops announcements and keeps the file ----
        Path reloadDir = Files.createTempDirectory("m7-reload");
        ServerConfig good = config(true, "ts.real", 9987, 30);
        ServerConfig.save(good, ServerConfig.file(reloadDir));
        ServerRuntime reloading = ServerRuntime.start(reloadDir);
        check(reloading.status().state() == ServerRuntime.State.ACTIVE, "A6: runtime starts ACTIVE");
        Files.writeString(ServerConfig.file(reloadDir), "{ broken");
        ServerRuntime.ReloadResult failed = reloading.reload();
        check(!failed.applied() && reloading.status().state() == ServerRuntime.State.INVALID_CONFIG
                        && reloading.onHello("c", hello(1)) == null,
                "A6: a failed reload stops the (no longer trustworthy) announcements");
        check("{ broken".equals(Files.readString(ServerConfig.file(reloadDir))),
                "A6: the failed reload keeps the admin's file");
        ServerConfig.save(good, ServerConfig.file(reloadDir));
        check(reloading.reload().applied() && reloading.status().state() == ServerRuntime.State.ACTIVE,
                "A6: fixing the config and reloading restores announcements");
        reloading.close();

        // ---- A7: closed runtime is inert; close is idempotent ----
        Path closedDir = dirWith(config(true, "ts.real", 9987, 30));
        ServerRuntime closing = ServerRuntime.start(closedDir);
        closing.close();
        closing.close();
        check(closing.onHello("c", hello(1)) == null && !closing.announcing()
                        && !closing.reload().applied(),
                "A7: a closed (old-world) runtime never answers new connections");

        // ---- A8: integrated-server gate ----
        Path integratedDir = dirWith(config(true, "ts.real", 9987, 30));
        ServerRuntime integrated = ServerRuntime.start(integratedDir);
        integrated.applyIntegratedServerGate();
        check(integrated.status().state() == ServerRuntime.State.DISABLED
                        && integrated.status().reason().contains("integrated server"),
                "A8: integrated servers default to disabled unless explicitly enabled in config");

        if (failures != 0) {
            throw new AssertionError(failures + " announcer/runtime checks failed");
        }
        System.out.println("Announcer & runtime regression: all checks passed");
    }
}
