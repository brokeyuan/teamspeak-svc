package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.net.identity.IdentityPayloads;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.protocol.SignedPlayerClaim;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * R07 regression (T04), identity slice: the proof lifecycle must bind to the
 * session it was started for. A queued proof request from an old session never
 * sends after the session is rebuilt, and a late signed claim for the old
 * session is never written into the new session's verified map.
 */
public class IdentityConsumptionTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static final java.security.KeyPair ISSUER = issuerKeyPair();

    static java.security.KeyPair issuerKeyPair() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Ts3VoiceClient newClient(String serverUid) throws Exception {
        Ts3VoiceClient client = new Ts3VoiceClient(Ts3Identity.generate(0, 1), "audit", "");
        Field server = Ts3VoiceClient.class.getDeclaredField("virtualServerUid");
        server.setAccessible(true);
        server.set(client, serverUid);
        return client;
    }

    static long proofRequestsFor(String tsClientUid) {
        long count = 0;
        for (Object sent : net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.SENT) {
            if (sent instanceof IdentityPayloads.IdentityProofRequest req
                    && req.tsClientUid().equals(tsClientUid)) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> receivers() throws Exception {
        Field receivers = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.class
                .getDeclaredField("RECEIVERS");
        receivers.setAccessible(true);
        return (Map<Object, Object>) receivers.get(null);
    }

    static void deliverNoDrain(Object payload) throws Exception {
        Object handler = receivers().get(IdentityPayloads.SignedClaim.TYPE);
        if (handler == null) {
            throw new AssertionError("receiver not registered");
        }
        ((java.util.function.BiConsumer<Object, Object>) handler).accept(payload,
                new net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.Context());
    }

    static void deliver(Object payload) throws Exception {
        Object handler = receivers().get(IdentityPayloads.SignedClaim.TYPE);
        if (handler == null) {
            throw new AssertionError("receiver not registered");
        }
        ((java.util.function.BiConsumer<Object, Object>) handler).accept(payload,
                new net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.Context());
        // The receiver queues handleClaim on the main thread; consume it now.
        net.minecraft.client.Minecraft.TASKS.removeIf(r -> { r.run(); return true; });
    }

    static void drainMainQueue() {
        net.minecraft.client.Minecraft.TASKS.removeIf(r -> { r.run(); return true; });
    }

    static boolean mapContains(String serverUid, String tsClientUid) throws Exception {
        Field field = ClientIdentityService.class.getDeclaredField("verified");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).containsKey(serverUid + "\0" + tsClientUid);
    }

    static SignedPlayerClaim signedClaim(String serverUid, String tsClientUid,
                                         UUID mcUuid, long revision, long expiresAt) {
        long now = System.currentTimeMillis() / 1000L;
        return SignedPlayerClaim.issue(ISSUER, mcUuid, serverUid, tsClientUid, revision, now, expiresAt);
    }

    public static void main(String[] args) throws Exception {
        ClientIdentityService.register();
        ClientIdentityService.pinTrustedIssuer(ISSUER.getPublic());
        UUID mcUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // ---- I1: queued proof request from session A never sends after the swap ----
        Ts3Identity identityA = Ts3Identity.generate(0, 1);
        Ts3Identity identityB = Ts3Identity.generate(0, 1);
        Ts3VoiceClient sessionA = newClient("server-a");
        Ts3VoiceClient sessionB = newClient("server-a");
        ClientIdentityService.beginProof(sessionA, identityA);
        check(net.minecraft.client.Minecraft.TASKS.size() == 1, "I1: proof request queued, not sent");
        // Session rebuilt: a new beginProof rebinds the identity service to session B.
        ClientIdentityService.beginProof(sessionB, identityB);
        drainMainQueue();
        check(proofRequestsFor(identityA.getClientUid()) == 0,
                "I1: old session's queued proof request never sends");
        check(proofRequestsFor(identityB.getClientUid()) == 1,
                "I1: the live session's proof request sends exactly once");

        // ---- I2: claim queued under session A, session rebuilt, then executed ----
        SignedPlayerClaim claimA = signedClaim("server-a", identityA.getClientUid(), mcUuid, 10,
                System.currentTimeMillis() / 1000L + 3600);
        IdentityPayloads.SignedClaim payloadA = new IdentityPayloads.SignedClaim(
                claimA.encode(), ISSUER.getPublic().getEncoded());
        BackendCoordinator.beginSession();          // session A becomes current
        ClientIdentityService.beginProof(sessionA, identityA);
        deliverNoDrain(payloadA);                   // arrival binding captured, task queued
        BackendCoordinator.beginSession();          // session rebuilt (token B)
        ClientIdentityService.beginProof(sessionB, identityB);
        drainMainQueue();                           // the old session's task runs now
        check(!mapContains("server-a", identityA.getClientUid()),
                "I2: claim captured for the dead session is not written into the new session");

        // ---- I3: a claim for the CURRENT session still applies (gate not over-broad) ----
        SignedPlayerClaim claimB = signedClaim("server-a", identityB.getClientUid(), mcUuid, 11,
                System.currentTimeMillis() / 1000L + 3600);
        IdentityPayloads.SignedClaim payloadB = new IdentityPayloads.SignedClaim(
                claimB.encode(), ISSUER.getPublic().getEncoded());
        deliver(payloadB);
        check(mapContains("server-a", identityB.getClientUid()),
                "I3: current session's claim is applied normally");

        // ---- I4: disconnect clears the binding; late claims after disconnect are dropped ----
        ClientIdentityService.tsDisconnected(sessionB);
        SignedPlayerClaim claimLate = signedClaim("server-a", identityB.getClientUid(), mcUuid, 12,
                System.currentTimeMillis() / 1000L + 3600);
        IdentityPayloads.SignedClaim payloadLate = new IdentityPayloads.SignedClaim(
                claimLate.encode(), ISSUER.getPublic().getEncoded());
        deliver(payloadLate);
        check(!mapContains("server-a", identityB.getClientUid()),
                "I4: no claim applies after the session was torn down");

        if (failures != 0) {
            throw new AssertionError(failures + " identity consumption checks failed");
        }
        System.out.println("Identity consumption regression: all checks passed");
    }
}
