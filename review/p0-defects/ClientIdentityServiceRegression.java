package com.tsvoice.client;

import com.tsvoice.client.net.identity.IdentityPayloads;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.protocol.SignedPlayerClaim;
import com.tsvoice.protocol.ServerTrustDirectory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.PublicKey;
import java.util.Map;
import java.util.UUID;

/**
 * F04/F05 regression: claims must store their real expiry, only explicitly
 * trusted issuers may be stored at all, over-long/future claims are rejected,
 * and revocation tombstones persistently block replays.
 *
 * <p>Rejection cases assert on the stored map, not on
 * {@link ClientIdentityService#getVerifiedMcUuid}: F04 alone makes every lookup
 * return null, which would let broken storage pass vacuously.</p>
 */
public class ClientIdentityServiceRegression {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static void setClient(Ts3VoiceClient tsClient) throws Exception {
        // T04：会话绑定必须经生产入口 beginProof 建立（直设字段不再构成有效绑定）。
        ClientIdentityService.beginProof(tsClient, Ts3Identity.generate(0, 1));
    }

    static Ts3VoiceClient newClient() throws Exception {
        Ts3VoiceClient client = new Ts3VoiceClient(Ts3Identity.generate(0, 1), "audit", "");
        Field server = Ts3VoiceClient.class.getDeclaredField("virtualServerUid");
        server.setAccessible(true);
        server.set(client, "fixture-server");
        setClient(client);
        return client;
    }

    static void deliver(Object payload) throws Exception {
        Field receivers = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.class
                .getDeclaredField("RECEIVERS");
        receivers.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) receivers.get(null);
        Object type = payload instanceof IdentityPayloads.ClaimRevoked
                ? IdentityPayloads.ClaimRevoked.TYPE
                : IdentityPayloads.SignedClaim.TYPE;
        Object handler = map.get(type);
        if (handler == null) {
            throw new AssertionError("receiver not registered for " + type);
        }
        ((java.util.function.BiConsumer<Object, Object>) handler).accept(payload,
                new net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.Context());
    }

    static void pinIssuer(PublicKey key) {
        try {
            Method m = ClientIdentityService.class.getMethod("pinTrustedIssuer", PublicKey.class);
            m.invoke(null, key);
        } catch (NoSuchMethodException e) {
            // old code has no explicit trust: the negative assertions fail below
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static boolean mapContains(String serverUid, String tsClientUid) throws Exception {
        Field field = ClientIdentityService.class.getDeclaredField("verified");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).containsKey(serverUid + "\0" + tsClientUid);
    }

    static Long storedExpiry(String serverUid, String tsClientUid) throws Exception {
        Field field = ClientIdentityService.class.getDeclaredField("verified");
        field.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) field.get(null);
        Object value = map.get(serverUid + "\0" + tsClientUid);
        if (value == null) {
            return null;
        }
        return (Long) value.getClass().getMethod("expiresAtEpochSeconds").invoke(value);
    }

    static void simulateRestart() throws Exception {
        ClientIdentityService.clearVerified();
        try {
            Field field = ClientIdentityService.class.getDeclaredField("watermarks");
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        } catch (NoSuchFieldException e) {
            // old code has no persistent watermark state
        }
        try {
            Method m = ClientIdentityService.class.getDeclaredMethod("loadTrustState");
            m.setAccessible(true);
            m.invoke(null);
        } catch (NoSuchMethodException e) {
            // old code cannot restore persistent anti-replay state
        }
    }

    public static void main(String[] args) throws Exception {
        ClientIdentityService.register();
        Ts3VoiceClient client = newClient();
        long now = System.currentTimeMillis() / 1000L;
        String tsClientUid = "remote-uid";

        // Trusted issuer: valid claim stored with its REAL expiry (F04).
        java.security.KeyPair issuer = ServerTrustDirectory.generateIssuerKeyPair();
        UUID mcUuid = UUID.randomUUID();
        pinIssuer(issuer.getPublic());
        SignedPlayerClaim valid = SignedPlayerClaim.issue(issuer, mcUuid, "fixture-server", tsClientUid,
                1, now - 1, now + 3600);
        deliver(new IdentityPayloads.SignedClaim(valid.encode(), issuer.getPublic().getEncoded()));
        check(mcUuid.equals(ClientIdentityService.getVerifiedMcUuid("fixture-server", tsClientUid)),
                "trusted issuer claim elevates the MC identity");
        Long expiry = storedExpiry("fixture-server", tsClientUid);
        check(expiry != null && expiry == now + 3600,
                "stored expiry equals the signed claim expiry, not the arrival time");

        // Untrusted issuer: never stored, even with a valid signature (F05).
        java.security.KeyPair stranger = ServerTrustDirectory.generateIssuerKeyPair();
        SignedPlayerClaim untrusted = SignedPlayerClaim.issue(stranger, mcUuid, "fixture-server",
                "stranger-uid", 1, now - 1, now + 3600);
        deliver(new IdentityPayloads.SignedClaim(untrusted.encode(), stranger.getPublic().getEncoded()));
        check(!mapContains("fixture-server", "stranger-uid"),
                "untrusted issuer claim is not stored");

        // Future issuedAt beyond the skew is rejected.
        SignedPlayerClaim future = SignedPlayerClaim.issue(issuer, mcUuid, "fixture-server", "future-uid",
                2, now + 3600, now + 7200);
        deliver(new IdentityPayloads.SignedClaim(future.encode(), issuer.getPublic().getEncoded()));
        check(!mapContains("fixture-server", "future-uid"),
                "claim issued in the future is rejected");

        // Over-long TTL is rejected (including legacy over-long claims).
        SignedPlayerClaim longTtl = SignedPlayerClaim.issue(issuer, mcUuid, "fixture-server", "longttl-uid",
                3, now - 1, now + 48 * 3600);
        deliver(new IdentityPayloads.SignedClaim(longTtl.encode(), issuer.getPublic().getEncoded()));
        check(!mapContains("fixture-server", "longttl-uid"),
                "claim exceeding the 24h TTL is rejected");

        // Cross-server claims are rejected.
        SignedPlayerClaim crossServer = SignedPlayerClaim.issue(issuer, mcUuid, "other-server", "cross-uid",
                4, now - 1, now + 3600);
        deliver(new IdentityPayloads.SignedClaim(crossServer.encode(), issuer.getPublic().getEncoded()));
        check(!mapContains("other-server", "cross-uid"),
                "claim for another virtual server is rejected");

        // A higher-revision valid claim restores/updates the mapping.
        SignedPlayerClaim renewal = SignedPlayerClaim.issue(issuer, mcUuid, "fixture-server", tsClientUid,
                5, now - 1, now + 3600);
        deliver(new IdentityPayloads.SignedClaim(renewal.encode(), issuer.getPublic().getEncoded()));
        check(mcUuid.equals(ClientIdentityService.getVerifiedMcUuid("fixture-server", tsClientUid)),
                "higher-revision claim within policy restores the mapping");

        // Revocation removes the mapping and tombstones the revision (F05).
        deliver(new IdentityPayloads.ClaimRevoked(ServerTrustDirectory.claimKey(renewal), 6));
        check(!mapContains("fixture-server", tsClientUid),
                "revocation removes the verified mapping");

        // Replay of the revoked claim is rejected.
        deliver(new IdentityPayloads.SignedClaim(renewal.encode(), issuer.getPublic().getEncoded()));
        check(!mapContains("fixture-server", tsClientUid),
                "revoked claim replay is rejected");

        // The tombstone survives a restart (persistent anti-replay state).
        simulateRestart();
        deliver(new IdentityPayloads.SignedClaim(renewal.encode(), issuer.getPublic().getEncoded()));
        check(!mapContains("fixture-server", tsClientUid),
                "revoked claim replay is rejected after restart");

        // A genuinely newer revision after revocation is accepted again.
        SignedPlayerClaim reproof = SignedPlayerClaim.issue(issuer, mcUuid, "fixture-server", tsClientUid,
                7, now - 1, now + 3600);
        deliver(new IdentityPayloads.SignedClaim(reproof.encode(), issuer.getPublic().getEncoded()));
        check(mcUuid.equals(ClientIdentityService.getVerifiedMcUuid("fixture-server", tsClientUid)),
                "re-proof after revocation with a newer revision is accepted");

        // Controls: expired and malformed claims stay rejected.
        SignedPlayerClaim expired = SignedPlayerClaim.issue(issuer, mcUuid, "fixture-server", "gone",
                8, now - 7200, now - 3600);
        deliver(new IdentityPayloads.SignedClaim(expired.encode(), issuer.getPublic().getEncoded()));
        check(!mapContains("fixture-server", "gone"),
                "expired claim is rejected");
        SignedPlayerClaim malformedProbe = SignedPlayerClaim.issue(issuer, mcUuid, "fixture-server", "x",
                9, now - 1, now + 60);
        deliver(new IdentityPayloads.SignedClaim(malformedProbe.encode() + "x", issuer.getPublic().getEncoded()));
        check(!mapContains("fixture-server", "x"),
                "malformed claim payload is rejected");

        // Session-scoped display mappings clear on TS disconnect; watermarks persist.
        ClientIdentityService.tsDisconnected(client);
        check(ClientIdentityService.getVerifiedMcUuid("fixture-server", tsClientUid) == null,
                "display mappings clear when the TS client disconnects");
        setClient(client);
        deliver(new IdentityPayloads.SignedClaim(reproof.encode(), issuer.getPublic().getEncoded()));
        check(!mapContains("fixture-server", tsClientUid),
                "watermark still blocks an equal-revision replay after reconnect");

        if (failures != 0) {
            throw new AssertionError(failures + " client identity checks failed");
        }
        System.out.println("ClientIdentityService regression: all checks passed");
    }
}
