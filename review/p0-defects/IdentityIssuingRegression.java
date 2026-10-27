package com.svcb.tsbridge.identity;

import com.svcb.tsbridge.net.identity.IdentityPayloads;
import com.tsvoice.protocol.IdentityProtocol;
import com.tsvoice.protocol.SignedPlayerClaim;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECCurve;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issuing-side regression: revoking a player must broadcast one ClaimRevoked
 * per issued claim (not just the first), each tombstone must exceed its
 * claim's revision, and revisions must keep growing after a restart.
 */
public class IdentityIssuingRegression {
    static int failures;
    static final SecureRandom RANDOM = new SecureRandom();

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static String publicKeyString(org.bouncycastle.math.ec.ECPoint point) throws Exception {
        var seq = new DERSequence(new ASN1Encodable[]{
                new DERBitString(new byte[]{0b0000_0000}, 7),
                new ASN1Integer(32),
                new ASN1Integer(point.getAffineXCoord().toBigInteger()),
                new ASN1Integer(point.getAffineYCoord().toBigInteger())});
        return Base64.getEncoder().encodeToString(seq.getEncoded("DER"));
    }

    static String clientUidFor(String publicKeyString) throws Exception {
        return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(publicKeyString.getBytes(StandardCharsets.US_ASCII)));
    }

    static byte[] rawSignature(AsymmetricCipherKeyPair pair, byte[] digest) {
        var signer = new ECDSASigner();
        signer.init(true, pair.getPrivate());
        BigInteger[] sig = signer.generateSignature(digest);
        byte[] out = new byte[64];
        copyLeft(sig[0].toByteArray(), out, 0);
        copyLeft(sig[1].toByteArray(), out, 32);
        return out;
    }

    static void copyLeft(byte[] value, byte[] out, int offset) {
        int start = Math.max(0, value.length - 32);
        int length = Math.min(32, value.length - start);
        System.arraycopy(value, start, out, offset + 32 - length, length);
    }

    @SuppressWarnings("unchecked")
    static java.util.function.BiConsumer<Object, Object> receiver(Object type) throws Exception {
        Field receivers = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.class
                .getDeclaredField("RECEIVERS");
        receivers.setAccessible(true);
        Object handler = ((Map<Object, Object>) receivers.get(null)).get(type);
        if (handler == null) {
            throw new AssertionError("receiver not registered for " + type);
        }
        return (java.util.function.BiConsumer<Object, Object>) handler;
    }

    static List<Object> sent() throws Exception {
        Field sent = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.class
                .getDeclaredField("SENT");
        sent.setAccessible(true);
        return (List<Object>) sent.get(null);
    }

    record TsKey(AsymmetricCipherKeyPair pair, String publicKeyString, String clientUid) {
    }

    static TsKey newTsIdentity() throws Exception {
        var curve = (ECCurve) org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getCurve();
        var domain = new ECDomainParameters(curve,
                org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getG(),
                org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getN(),
                org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getH());
        var generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(domain, RANDOM));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        var point = ((org.bouncycastle.crypto.params.ECPublicKeyParameters) pair.getPublic()).getQ();
        String pk = publicKeyString(point);
        return new TsKey(pair, pk, clientUidFor(pk));
    }

    public static void main(String[] args) throws Exception {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.SENT.clear();
        net.minecraft.server.level.ServerPlayer player = new net.minecraft.server.level.ServerPlayer();
        net.minecraft.server.level.ServerPlayer.CURRENT = player;
        net.minecraft.server.MinecraftServer server = new net.minecraft.server.MinecraftServer();
        net.minecraft.server.MinecraftServer.PlayerList.PLAYERS = List.of(player);
        com.svcb.tsbridge.TsBridgeMod.mcServer = server;
        com.svcb.tsbridge.TsBridgeMod.tsQueryClient = new com.svcb.tsbridge.ts.TsQueryClient();

        IdentityProofService.init();

        UUID playerUuid = player.getUUID();
        String serverUid = "fixture-server";

        // Issue claim #1 for (player, tsUid1).
        TsKey id1 = newTsIdentity();
        receiver(IdentityPayloads.IdentityProofRequest.TYPE).accept(
                new IdentityPayloads.IdentityProofRequest(id1.clientUid(), id1.publicKeyString()),
                new net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context());
        Object challengePayload = sent().stream()
                .filter(p -> p instanceof IdentityPayloads.IdentityChallenge)
                .reduce((a, b) -> b).orElseThrow();
        IdentityPayloads.IdentityChallenge challenge = (IdentityPayloads.IdentityChallenge) challengePayload;
        byte[] digest1 = MessageDigest.getInstance("SHA-256").digest(
                IdentityProtocol.proofBytes(serverUid, id1.clientUid(), challenge.nonce(), playerUuid));
        byte[] sig1 = rawSignature(id1.pair(), digest1);
        sent().clear();
        receiver(IdentityPayloads.IdentityProof.TYPE).accept(
                new IdentityPayloads.IdentityProof(challenge.nonce(), playerUuid, id1.clientUid(),
                        serverUid, id1.publicKeyString(), sig1),
                new net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context());
        SignedPlayerClaim claim1 = sent().stream()
                .filter(p -> p instanceof IdentityPayloads.SignedClaim)
                .map(p -> SignedPlayerClaim.decode(((IdentityPayloads.SignedClaim) p).claim()))
                .reduce((a, b) -> b).orElseThrow();
        check(claim1 != null && tsUid(claim1).equals(id1.clientUid()) && claim1.revision() >= 2,
                "first proof issues a signed claim with revision >= 2");

        // Issue claim #2 for the same player, different TS identity.
        TsKey id2 = newTsIdentity();
        receiver(IdentityPayloads.IdentityProofRequest.TYPE).accept(
                new IdentityPayloads.IdentityProofRequest(id2.clientUid(), id2.publicKeyString()),
                new net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context());
        IdentityPayloads.IdentityChallenge challenge2 = (IdentityPayloads.IdentityChallenge) sent().stream()
                .filter(p -> p instanceof IdentityPayloads.IdentityChallenge)
                .reduce((a, b) -> b).orElseThrow();
        byte[] digest2 = MessageDigest.getInstance("SHA-256").digest(
                IdentityProtocol.proofBytes(serverUid, id2.clientUid(), challenge2.nonce(), playerUuid));
        byte[] sig2 = rawSignature(id2.pair(), digest2);
        sent().clear();
        receiver(IdentityPayloads.IdentityProof.TYPE).accept(
                new IdentityPayloads.IdentityProof(challenge2.nonce(), playerUuid, id2.clientUid(),
                        serverUid, id2.publicKeyString(), sig2),
                new net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context());
        SignedPlayerClaim claim2 = sent().stream()
                .filter(p -> p instanceof IdentityPayloads.SignedClaim)
                .map(p -> SignedPlayerClaim.decode(((IdentityPayloads.SignedClaim) p).claim()))
                .reduce((a, b) -> b).orElseThrow();
        check(claim2.revision() > claim1.revision(), "second claim has a higher revision");

        // Disconnect: every issued claim for the player gets its own revocation.
        sent().clear();
        var disconnectHandler = net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
                .DISCONNECT.handler();
        disconnectHandler.accept(new net.minecraft.server.network.ServerPlayNetworkHandler(), server);
        List<IdentityPayloads.ClaimRevoked> revocations = sent().stream()
                .filter(p -> p instanceof IdentityPayloads.ClaimRevoked)
                .map(p -> (IdentityPayloads.ClaimRevoked) p)
                .toList();
        check(revocations.size() == 2,
                "revocation broadcasts one payload per issued claim (got " + revocations.size() + ")");
        check(revocations.stream().map(IdentityPayloads.ClaimRevoked::claimKey).distinct().count() == 2,
                "revocation payloads cover distinct claims");
        check(revocations.stream().allMatch(r -> r.revision() > Math.max(claim1.revision(), claim2.revision())),
                "each revocation tombstone exceeds all issued revisions");

        // Re-proof after revocation gets a revision beyond the tombstones.
        TsKey id3 = newTsIdentity();
        receiver(IdentityPayloads.IdentityProofRequest.TYPE).accept(
                new IdentityPayloads.IdentityProofRequest(id3.clientUid(), id3.publicKeyString()),
                new net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context());
        IdentityPayloads.IdentityChallenge challenge3 = (IdentityPayloads.IdentityChallenge) sent().stream()
                .filter(p -> p instanceof IdentityPayloads.IdentityChallenge)
                .reduce((a, b) -> b).orElseThrow();
        byte[] digest3 = MessageDigest.getInstance("SHA-256").digest(
                IdentityProtocol.proofBytes(serverUid, id3.clientUid(), challenge3.nonce(), playerUuid));
        byte[] sig3 = rawSignature(id3.pair(), digest3);
        sent().clear();
        receiver(IdentityPayloads.IdentityProof.TYPE).accept(
                new IdentityPayloads.IdentityProof(challenge3.nonce(), playerUuid, id3.clientUid(),
                        serverUid, id3.publicKeyString(), sig3),
                new net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context());
        SignedPlayerClaim claim3 = sent().stream()
                .filter(p -> p instanceof IdentityPayloads.SignedClaim)
                .map(p -> SignedPlayerClaim.decode(((IdentityPayloads.SignedClaim) p).claim()))
                .reduce((a, b) -> b).orElseThrow();
        check(claim3.revision() > revocations.stream().mapToLong(IdentityPayloads.ClaimRevoked::revision).max().orElse(0),
                "re-proof after revocation uses a revision beyond the tombstones");

        if (failures != 0) {
            throw new AssertionError(failures + " identity issuing checks failed");
        }
        System.out.println("IdentityIssuing regression: all checks passed");
    }

    static String tsUid(SignedPlayerClaim claim) {
        return claim.tsClientUid();
    }
}
