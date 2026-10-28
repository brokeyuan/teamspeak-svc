package com.tsvoice.client;

import com.tsvoice.client.config.EndpointCredentialStore;
import com.tsvoice.client.config.TsBookmarkStore;
import com.tsvoice.client.net.BeaconPayload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Bookmarks and explicit intent (P3/F08): a manual bookmark connects through
 * the endpoint-isolated credential store and overrides a server-bridge
 * announcement; autoConnect=false never blocks manual connect; migration
 * copies credentials only to exactly matching endpoints.
 */
public class BookmarkMigrationTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("review/p3-config/config/teamspeak-svc");
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("bookmarks.json"));
        Files.deleteIfExists(dir.resolve("endpoint-credentials.json"));
        Files.deleteIfExists(dir.resolve("servers.json"));
        TsBookmarkStore.load();
        EndpointCredentialStore.load();

        ModConfig global = TeamspeakSvcClient.config();
        global.autoConnect = false;
        global.tsHost = "";
        TsVoiceManager.manualDisconnect();

        String bookmarkId = "bm-" + UUID.randomUUID();
        TsBookmarkStore.put(new TsBookmarkStore.Bookmark(bookmarkId, "Community TS",
                "ts-bm.local", 9987, "Lobby", "tcp", null, false));
        EndpointCredentialStore.set("ts-bm.local", 9987, "bm-secret", " bm-channel ");

        // Control: a trusted server-bridge beacon dormant the automatic path.
        TsVoiceManager.onServerChanged("p3-bookmarks");
        ServerConfigStore.Entry entry = ServerConfigStore.get("p3-bookmarks");
        entry.host = "elsewhere.local";
        entry.trustedBeaconHost = "bridge.local";
        entry.trustedBeaconPort = 9987;
        ServerConfigStore.save();
        TsVoiceManager.onBeacon(new BeaconPayload("bridge.local", 9987, "Main", "server-bridge", 0));
        check(TsVoiceManager.resolveSessionConfig() == null,
                "trusted server-bridge beacon keeps the automatic path dormant");

        // Explicit bookmark intent overrides the server-bridge announcement.
        TsVoiceManager.manualConnect(bookmarkId);
        TsVoiceManager.SessionConfig resolved = TsVoiceManager.resolveSessionConfig();
        check(resolved != null && "ts-bm.local".equals(resolved.host()) && resolved.port() == 9987,
                "manual bookmark connects despite the server-bridge beacon");
        check("manual".equals(resolved.source()) && resolved.tcp(),
                "bookmark session uses manual source and its transport policy");
        check("bm-secret".equals(resolved.password()) && " bm-channel ".equals(resolved.channelPassword()),
                "bookmark credentials come from the endpoint-isolated store with exact spacing");

        // autoConnect=false did not block the manual connect (already proven above,
        // but assert the intent survives a server change per D05).
        TsVoiceManager.onServerChanged("p3-other-server");
        resolved = TsVoiceManager.resolveSessionConfig();
        check(resolved != null && "ts-bm.local".equals(resolved.host()),
                "explicit bookmark intent survives an MC server change");

        // A server-bridge beacon does not clear the explicit intent.
        TsVoiceManager.onBeacon(new BeaconPayload("bridge.local", 9987, "Main", "server-bridge", 0));
        resolved = TsVoiceManager.resolveSessionConfig();
        check(resolved != null && "ts-bm.local".equals(resolved.host()),
                "late server-bridge beacon does not override the explicit intent");

        // manualDisconnect clears the intent: the dormant rule applies again.
        TsVoiceManager.manualDisconnect();
        check(TsVoiceManager.resolveSessionConfig() == null,
                "manual disconnect clears the bookmark intent");

        // Unknown bookmark reports an operable error instead of connecting.
        TsVoiceManager.manualConnect("no-such-bookmark");
        check(TsVoiceManager.getState() == TsVoiceManager.State.ERROR
                        && TsVoiceManager.getLastError().contains("no-such-bookmark"),
                "unknown bookmark id reports an operable error");

        // Migration from the legacy global config: exact endpoint only.
        global.tsHost = "Global.Example.COM.";
        global.tsVoicePort = 9987;
        global.serverPassword = "global-secret";
        int migrated = EndpointCredentialStore.migrateFrom(global.tsHost, global.tsVoicePort,
                global.serverPassword, global.channelPassword);
        check(migrated == 1 && "global-secret".equals(
                        EndpointCredentialStore.get("global.example.com", 9987).serverPassword()),
                "legacy global credential migrates to its exact endpoint");
        check(EndpointCredentialStore.get("ts-bm.local", 9987).serverPassword().equals("bm-secret"),
                "existing bookmark credentials are untouched by migration");
        global.tsHost = "";
        global.serverPassword = "";

        // F08 (legacy half): a manual per-server host must NOT inherit the global
        // password unless it IS the global endpoint.
        entry.host = "manual.other.com";
        entry.port = 9987;
        entry.serverPassword = null;
        entry.channelPassword = null;
        ServerConfigStore.save();
        TsVoiceManager.onServerChanged("p3-bookmarks");
        global.tsHost = "global.example.com";
        global.tsVoicePort = 9987;
        global.serverPassword = "global-secret";
        global.channelPassword = "global-channel";
        TsVoiceManager.manualDisconnect();
        TsVoiceManager.onBeacon(new BeaconPayload("bridge.local", 9987, "Main", "direct", 0));
        TsVoiceManager.onServerChanged("p3-bookmarks"); // clears the beacon: exercise the manual branch
        resolved = TsVoiceManager.resolveSessionConfig();
        check(resolved != null && "manual.other.com".equals(resolved.host())
                        && resolved.password() == null && resolved.channelPassword() == null,
                "unrelated manual host does not inherit the global endpoint password (F08)");
        // The global endpoint itself keeps inheritance (same endpoint, safe).
        entry.host = "global.example.com";
        entry.port = 9987;
        ServerConfigStore.save();
        TsVoiceManager.onServerChanged("p3-bookmarks"); // beacon still cleared
        resolved = TsVoiceManager.resolveSessionConfig();
        check(resolved != null && "global-secret".equals(resolved.password()),
                "the global endpoint itself still inherits the global password");
        entry.host = null;
        entry.port = null;
        entry.serverPassword = null;
        entry.channelPassword = null;
        global.tsHost = "";
        global.serverPassword = "";
        global.channelPassword = "";

        TsVoiceManager.manualDisconnect();
        if (failures != 0) {
            throw new AssertionError(failures + " bookmark migration checks failed");
        }
        System.out.println("Bookmark migration regression: all checks passed");
    }
}
