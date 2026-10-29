package com.tsvoice.client.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Endpoint credential isolation (P3/F08): normalized endpoints, revision bumps,
 * explicit clearing, redacted toString, persistence round-trip, and
 * match-only migration.
 */
public class EndpointCredentialsTest {
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
        Files.deleteIfExists(dir.resolve("endpoint-credentials.json"));
        EndpointCredentialStore.load();

        // Normalization: case, trailing dot and default port collapse to one key.
        check(EndpointCredentialStore.normalizeEndpoint("Example.COM.", 9987)
                        .equals(EndpointCredentialStore.normalizeEndpoint("example.com", null)),
                "normalization collapses case, trailing dot and default port");
        check(!EndpointCredentialStore.normalizeEndpoint("example.com", 9987)
                        .equals(EndpointCredentialStore.normalizeEndpoint("example.com", 9988)),
                "different ports stay separate credentials");
        check(!EndpointCredentialStore.normalizeEndpoint("voice.example.org", 9987)
                        .equals(EndpointCredentialStore.normalizeEndpoint("example.org", 9987)),
                "different host aliases do not share credentials");

        // Set, revision bumps, explicit empty clears.
        EndpointCredentialStore.set("ts-a.local", 9987, " server pw ", "ch pw");
        EndpointCredentialStore.Credential first = EndpointCredentialStore.get("ts-a.local", 9987);
        check(" server pw ".equals(first.serverPassword()) && "ch pw".equals(first.channelPassword()),
                "passwords preserve exact spaces");
        check(first.revision() == 1, "first write has revision 1");
        EndpointCredentialStore.set("ts-a.local", 9987, " server pw ", "ch pw2");
        EndpointCredentialStore.Credential second = EndpointCredentialStore.get("ts-a.local", 9987);
        check(second.revision() == first.revision() + 1 && "ch pw2".equals(second.channelPassword()),
                "credential change bumps the revision");
        EndpointCredentialStore.set("ts-a.local", 9987, "", "");
        check(EndpointCredentialStore.get("ts-a.local", 9987) == null,
                "explicit empty clears both fields and removes the entry");

        // toString redaction: secrets never appear in debug output.
        EndpointCredentialStore.set("ts-b.local", 9987, "supersecret", "chsecret");
        String debug = EndpointCredentialStore.get("ts-b.local", 9987).toString()
                + EndpointCredentialStore.class.getName();
        check(!debug.contains("supersecret") && !debug.contains("chsecret"),
                "toString redacts secrets");

        // Persistence round-trip.
        EndpointCredentialStore.save();
        EndpointCredentialStore.load();
        EndpointCredentialStore.Credential reloaded = EndpointCredentialStore.get("ts-b.local", 9987);
        check(reloaded != null && "supersecret".equals(reloaded.serverPassword()) && reloaded.revision() == 1,
                "credentials persist across reload");

        // Migration: only the exact endpoint, never overwriting existing entries.
        int migrated = EndpointCredentialStore.migrateFrom(
                "Global.Example.COM.", 9987, "global-secret", null);
        check(migrated == 1 && "global-secret".equals(
                        EndpointCredentialStore.get("global.example.com", 9987).serverPassword()),
                "migration copies to the exact normalized endpoint");
        check(EndpointCredentialStore.get("other.example.com", 9987) == null,
                "migration never diffuses passwords to other hosts");
        check(EndpointCredentialStore.migrateFrom("global.example.com", 9987, "over", null) == 0
                        && "global-secret".equals(EndpointCredentialStore.get("global.example.com", 9987).serverPassword()),
                "migration never overwrites existing credentials");
        check(EndpointCredentialStore.migrateFrom("", 9987, "x", null) == 0
                        && EndpointCredentialStore.migrateFrom("blank.example.com", 9987, "", "") == 0,
                "blank hosts and empty passwords are not migrated");

        if (failures != 0) {
            throw new AssertionError(failures + " endpoint credential checks failed");
        }
        System.out.println("Endpoint credentials regression: all checks passed");
    }
}
