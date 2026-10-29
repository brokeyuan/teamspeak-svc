package com.svcb.tsbridge.identity;

import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.net.identity.IdentityPayloads;
import com.svcb.tsbridge.ts.TsQueryClient;
import com.tsvoice.protocol.SignedPlayerClaim;
import com.tsvoice.protocol.ServerTrustDirectory;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional proof that a TS identity belongs to an online MC player. A successful
 * proof only publishes a signed display mapping; it never gates voice, text or groups.
 */
public final class IdentityProofService {
    private static final long CHALLENGE_TTL_SECONDS = 120;
    private static final long CLAIM_TTL_SECONDS = 24 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Pending(long nonce, String tsClientUid, String publicKeyString,
                           String serverUid, long expiresAtEpochSeconds) {
    }

    private record IssuedClaim(SignedPlayerClaim claim, byte[] issuerPublicKey) {
    }

    private static KeyPair issuerKeyPair;
    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private static final Map<String, IssuedClaim> issued = new ConcurrentHashMap<>();
    /**
     * revision 计数：同一签发公钥跨进程重启单调（预留块持久化，崩溃跳过不复用）。
     * null 表示存储不可用 —— 身份提升暂停，语音保持 Ghost。
     */
    private static volatile IdentityRevisionStore revisionStore;

    private IdentityProofService() {
    }

    public static synchronized void init() {
        issuerKeyPair = IdentitySigningKeys.loadOrGenerate(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve("tsbridge").resolve("identity-signing-keypair.txt"));
        try {
            revisionStore = new IdentityRevisionStore(new IdentityRevisionStore.FilePersistence(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                            .resolve("tsbridge").resolve("identity-revision.txt")));
        } catch (RuntimeException e) {
            revisionStore = null;
            TsBridgeMod.LOGGER.error("[tsbridge] identity revision store unavailable — identity elevation paused", e);
        }

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

        ServerPlayNetworking.registerGlobalReceiver(IdentityPayloads.IdentityProofRequest.TYPE,
                (payload, context) -> issueChallenge(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(IdentityPayloads.IdentityProof.TYPE,
                (payload, context) -> verifyProof(context.player(), payload));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                revokeByMcUuid(handler.player.getUUID()));

        // Send currently valid claims to newly joining mod clients. Clients without
        // the mod ignore these payloads; ordinary TS users do not need any claim.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraftServer) -> {
            long now = System.currentTimeMillis() / 1000L;
            for (IssuedClaim entry : issued.values()) {
                if (entry.claim().expiresAtEpochSeconds() > now) {
                    ServerPlayNetworking.send(handler.player, new IdentityPayloads.SignedClaim(
                            entry.claim().encode(), entry.issuerPublicKey()));
                }
            }
        });
    }

    private static void issueChallenge(ServerPlayer player, IdentityPayloads.IdentityProofRequest request) {
        String serverUid = TsBridgeMod.tsQueryClient != null
                ? TsBridgeMod.tsQueryClient.getVirtualServerUid() : null;
        String derivedUid = clientUidFor(request.publicKeyString());
        if (serverUid == null || serverUid.isBlank() || derivedUid == null
                || !derivedUid.equals(request.tsClientUid())) {
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        pending.values().removeIf(entry -> entry.expiresAtEpochSeconds() <= now);
        long nonce = Math.abs(RANDOM.nextLong()) | 1L;
        long expires = now + CHALLENGE_TTL_SECONDS;
        pending.put(player.getUUID(), new Pending(nonce, request.tsClientUid(), request.publicKeyString(),
                serverUid, expires));
        ServerPlayNetworking.send(player, new IdentityPayloads.IdentityChallenge(
                nonce, player.getUUID(), request.tsClientUid(), serverUid, expires));
    }

    private static void verifyProof(ServerPlayer player,
                                    IdentityPayloads.IdentityProof proof) {
        Pending entry = pending.remove(player.getUUID());
        long now = System.currentTimeMillis() / 1000L;
        if (entry == null || entry.nonce() != proof.nonce() || entry.expiresAtEpochSeconds() <= now
                || !player.getUUID().equals(proof.playerUuid())
                || !entry.serverUid().equals(proof.tsVirtualServerUid())
                || !entry.tsClientUid().equals(proof.tsClientUid())
                || !entry.publicKeyString().equals(proof.publicKeyString())
                || !verifyTsIdentitySignature(proof)) {
            player.sendSystemMessage(Component.translatable("tsbridge.identity.failed"));
            return;
        }
        String claimKey = claimKey(entry.serverUid(), proof.tsClientUid());
        if (revisionStore == null) {
            TsBridgeMod.LOGGER.error("[tsbridge] identity revision store unavailable — identity elevation paused");
            player.sendSystemMessage(Component.translatable("tsbridge.identity.failed"));
            return;
        }
        long revision;
        try {
            revision = revisionStore.allocate();
        } catch (IdentityRevisionStore.RevisionAllocationException e) {
            TsBridgeMod.LOGGER.error("[tsbridge] identity revision allocation failed — elevation paused", e);
            player.sendSystemMessage(Component.translatable("tsbridge.identity.failed"));
            return;
        }
        SignedPlayerClaim claim = SignedPlayerClaim.issue(issuerKeyPair, player.getUUID(),
                entry.serverUid(), proof.tsClientUid(), revision, now, now + CLAIM_TTL_SECONDS);
        IssuedClaim issuedClaim = new IssuedClaim(claim, issuerKeyPair.getPublic().getEncoded());
        issued.put(claimKey, issuedClaim);
        broadcast(claimKey, issuedClaim);
        player.sendSystemMessage(Component.translatable("tsbridge.identity.verified"));
    }

    private static void broadcast(String claimKey, IssuedClaim entry) {
        MinecraftServer server = TsBridgeMod.mcServer;
        if (server == null) {
            return;
        }
        var payload = new IdentityPayloads.SignedClaim(entry.claim().encode(), entry.issuerPublicKey());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
        if (entry.claim().mcUuid() != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.claim().mcUuid());
            if (owner != null) {
                owner.sendSystemMessage(Component.translatable("tsbridge.identity.published")
                        .withStyle(Style.EMPTY.withColor(net.minecraft.ChatFormatting.AQUA)));
            }
        }
    }

    private static void revokeByMcUuid(UUID mcUuid) {
        if (mcUuid == null) {
            return;
        }
        var removed = new java.util.ArrayList<IssuedClaim>();
        issued.entrySet().removeIf(mapEntry -> {
            if (!mcUuid.equals(mapEntry.getValue().claim().mcUuid())) {
                return false;
            }
            removed.add(mapEntry.getValue());
            return true;
        });
        if (removed.isEmpty()) {
            return;
        }
        MinecraftServer server = TsBridgeMod.mcServer;
        if (server == null) {
            return;
        }
        // 按条目逐一广播：每个已签发声明一条 ClaimRevoked，客户端墓碑覆盖全部映射，
        // 不能只发同一 MC UUID 的第一条。
        for (IssuedClaim entry : removed) {
            long revision;
            if (revisionStore != null) {
                try {
                    revision = revisionStore.allocate();
                } catch (IdentityRevisionStore.RevisionAllocationException e) {
                    // 分配失败：以声明自身 revision 作墓碑，仍阻断该声明的重放。
                    TsBridgeMod.LOGGER.error("[tsbridge] revocation revision allocation failed — using claim revision", e);
                    revision = entry.claim().revision();
                }
            } else {
                revision = entry.claim().revision();
            }
            var payload = new IdentityPayloads.ClaimRevoked(
                    ServerTrustDirectory.claimKey(entry.claim()), revision);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static boolean verifyTsIdentitySignature(IdentityPayloads.IdentityProof proof) {
        try {
            ECPoint publicKey = parseTsPublicKey(proof.publicKeyString());
            if (publicKey == null
                    || !clientUidFor(proof.publicKeyString()).equals(proof.tsClientUid())) {
                return false;
            }
            byte[] digest = sha256(com.tsvoice.protocol.IdentityProtocol.proofBytes(
                    proof.tsVirtualServerUid(), proof.tsClientUid(), proof.nonce(), proof.playerUuid()));
            byte[] encoded = proof.signature();
            if (encoded.length != 64) {
                return false;
            }
            var signer = new ECDSASigner();
            var curve = org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getCurve();
            var domain = new org.bouncycastle.crypto.params.ECDomainParameters(
                    curve, org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getG(),
                    org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getN(),
                    org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getH());
            signer.init(false, new org.bouncycastle.crypto.params.ECPublicKeyParameters(publicKey, domain));
            return signer.verifySignature(digest, new BigInteger(1, encoded, 0, 32),
                    new BigInteger(1, encoded, 32, 32));
        } catch (Exception e) {
            return false;
        }
    }

    private static ECPoint parseTsPublicKey(String publicKeyString) {
        try {
            var sequence = (ASN1Sequence) ASN1Primitive.fromByteArray(
                    Base64.getDecoder().decode(publicKeyString));
            BigInteger x = ((ASN1Integer) sequence.getObjectAt(2)).getValue();
            BigInteger y = ((ASN1Integer) sequence.getObjectAt(3)).getValue();
            var curve = (ECCurve) org.bouncycastle.asn1.x9.ECNamedCurveTable
                    .getByName("prime256v1").getCurve();
            ECPoint point = curve.validatePoint(x, y);
            return point == null || point.isInfinity() ? null : point.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clientUidFor(String publicKeyString) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest(publicKeyString.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String claimKey(String serverUid, String tsClientUid) {
        return "local:" + serverUid + ":" + tsClientUid;
    }
}
