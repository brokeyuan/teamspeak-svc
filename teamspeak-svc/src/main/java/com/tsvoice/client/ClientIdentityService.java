package com.tsvoice.client;

import com.tsvoice.client.net.identity.IdentityPayloads;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.protocol.IdentityProtocol;
import com.tsvoice.protocol.SignedPlayerClaim;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;

import java.security.PublicKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional proof that a TS identity belongs to an online MC player. Failure,
 * absence and revocation only fall back to an ordinary TS Ghost; they never
 * block voice, text or group communication.
 */
public final class ClientIdentityService {
    private record VerifiedIdentity(UUID mcUuid, long revision, long expiresAtEpochSeconds,
                                    String claimKey) {
    }

    private static volatile Ts3VoiceClient client;
    private static volatile Ts3Identity identity;
    private static final Map<String, VerifiedIdentity> verified = new ConcurrentHashMap<>();

    private ClientIdentityService() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(IdentityPayloads.IdentityChallenge.TYPE,
                IdentityPayloads.IdentityChallenge.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(IdentityPayloads.SignedClaim.TYPE,
                IdentityPayloads.SignedClaim.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(IdentityPayloads.ClaimRevoked.TYPE,
                IdentityPayloads.ClaimRevoked.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(IdentityPayloads.IdentityProofRequest.TYPE,
                IdentityPayloads.IdentityProofRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(IdentityPayloads.IdentityProof.TYPE,
                IdentityPayloads.IdentityProof.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(IdentityPayloads.IdentityChallenge.TYPE, (payload, context) ->
                context.client().execute(() -> handleChallenge(payload)));
        ClientPlayNetworking.registerGlobalReceiver(IdentityPayloads.SignedClaim.TYPE, (payload, context) ->
                context.client().execute(() -> handleClaim(payload)));
        ClientPlayNetworking.registerGlobalReceiver(IdentityPayloads.ClaimRevoked.TYPE, (payload, context) ->
                context.client().execute(() -> verified.values().removeIf(entry ->
                        entry.claimKey().equals(payload.claimKey())
                                && payload.revision() >= entry.revision())));
    }

    public static void beginProof(Ts3VoiceClient tsClient, Ts3Identity tsIdentity) {
        client = tsClient;
        identity = tsIdentity;
        // Fabric allows the call off-thread, but queueing on the client thread keeps
        // payload ordering with the rest of the mod's networking explicit.
        Minecraft.getInstance().execute(() -> ClientPlayNetworking.send(
                new IdentityPayloads.IdentityProofRequest(
                        tsIdentity.getClientUid(), tsIdentity.getPublicKeyString())));
    }

    public static void tsDisconnected(Ts3VoiceClient tsClient) {
        if (client == tsClient) {
            client = null;
            identity = null;
            verified.clear();
        }
    }

    public static void clearVerified() {
        verified.clear();
    }

    public static UUID getVerifiedMcUuid(String tsVirtualServerUid, String tsClientUid) {
        if (tsVirtualServerUid == null || tsClientUid == null) {
            return null;
        }
        VerifiedIdentity verifiedIdentity = verified.get(key(tsVirtualServerUid, tsClientUid));
        if (verifiedIdentity == null
                || verifiedIdentity.expiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L) {
            return null;
        }
        return verifiedIdentity.mcUuid();
    }

    private static void handleChallenge(IdentityPayloads.IdentityChallenge challenge) {
        Ts3VoiceClient current = client;
        Ts3Identity currentIdentity = identity;
        Minecraft mc = Minecraft.getInstance();
        UUID playerUuid = mc.player != null ? mc.player.getUUID() : null;
        if (current == null || currentIdentity == null || playerUuid == null
                || challenge.expiresAtEpochSeconds() <= System.currentTimeMillis() / 1000L
                || !playerUuid.equals(challenge.playerUuid())
                || !currentIdentity.getClientUid().equals(challenge.tsClientUid())
                || !safeEquals(current.getVirtualServerUid(), challenge.tsVirtualServerUid())) {
            return;
        }
        byte[] signature = currentIdentity.signSha256(IdentityProtocol.proofBytes(
                challenge.tsVirtualServerUid(), challenge.tsClientUid(), challenge.nonce(), playerUuid));
        ClientPlayNetworking.send(new IdentityPayloads.IdentityProof(challenge.nonce(), playerUuid,
                challenge.tsClientUid(), challenge.tsVirtualServerUid(),
                currentIdentity.getPublicKeyString(), signature));
    }

    private static void handleClaim(IdentityPayloads.SignedClaim message) {
        Ts3VoiceClient current = client;
        if (current == null) {
            return;
        }
        try {
            SignedPlayerClaim claim = SignedPlayerClaim.decode(message.claim());
            PublicKey issuer = SignedPlayerClaim.decodePublicKey(message.issuerPublicKey());
            long now = System.currentTimeMillis() / 1000L;
            if (claim == null || !claim.verify(issuer, now)
                    || !safeEquals(claim.tsVirtualServerUid(), current.getVirtualServerUid())) {
                return;
            }
            String mapKey = key(claim.tsVirtualServerUid(), claim.tsClientUid());
            VerifiedIdentity existing = verified.get(mapKey);
            if (existing != null && existing.revision() > claim.revision()) {
                return;
            }
            verified.put(mapKey, new VerifiedIdentity(claim.mcUuid(), claim.revision(), now,
                    com.tsvoice.protocol.ServerTrustDirectory.claimKey(claim)));
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] Ignoring invalid identity claim: {}", e.getMessage());
        }
    }

    private static String key(String serverUid, String tsClientUid) {
        return serverUid + "\0" + tsClientUid;
    }

    private static boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }
}
