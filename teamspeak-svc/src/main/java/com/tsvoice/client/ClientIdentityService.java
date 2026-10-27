package com.tsvoice.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tsvoice.client.net.identity.IdentityPayloads;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.protocol.IdentityProtocol;
import com.tsvoice.protocol.SignedPlayerClaim;
import com.tsvoice.protocol.ServerTrustDirectory;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional proof that a TS identity belongs to an online MC player. Failure,
 * absence and revocation only fall back to an ordinary TS Ghost; they never
 * block voice, text or group communication.
 *
 * <p>信任策略（D10 默认）：只有显式 pin 进本地信任目录的 issuer 才能提升 MC 身份
 * 展示；未知 issuer 的声明一律不存储（语音保持 Ghost）。无论是否受信，所有通过
 * 策略校验的声明都会推进持久化 revision 高水位，撤销写入持久墓碑 —— 高水位与
 * 墓碑不因换 MC 服务器或 TS 断线清除，旧声明重放始终被拒。</p>
 */
public final class ClientIdentityService {
    private record VerifiedIdentity(UUID mcUuid, long revision, long expiresAtEpochSeconds,
                                    String claimKey) {
    }

    private static volatile Ts3VoiceClient client;
    private static volatile Ts3Identity identity;
    private static final Map<String, VerifiedIdentity> verified = new ConcurrentHashMap<>();
    private static final ServerTrustDirectory trustDirectory = new ServerTrustDirectory();
    /** 持久防重放高水位：claimKey → 已接受/已撤销的最高 revision。 */
    private static final Map<String, Long> watermarks = new ConcurrentHashMap<>();
    private static volatile Path trustStatePath;

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
                context.client().execute(() -> handleRevocation(payload)));
        loadTrustState();
    }

    /** 显式信任签发方（信任配置 UI 未获批前保持为空；仅由明确的信任操作调用）。 */
    public static void pinTrustedIssuer(PublicKey issuer) {
        trustDirectory.trust(issuer);
    }

    /** 显式解除信任；已记录的墓碑与高水位不清除。 */
    public static void unpinTrustedIssuer(PublicKey issuer) {
        trustDirectory.untrust(issuer);
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
            // watermarks 保留：防重放状态跨会话持久，不随 TS 断线丢失。
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
            if (claim == null || !claim.verifyWithPolicy(issuer, now)
                    || !safeEquals(claim.tsVirtualServerUid(), current.getVirtualServerUid())) {
                return;
            }
            String claimKey = ServerTrustDirectory.claimKey(claim);
            Long watermark = watermarks.get(claimKey);
            if (watermark != null && claim.revision() <= watermark) {
                return; // 旧 revision：已过期重放或已被更高版本取代
            }
            if (!trustDirectory.isTrusted(claim.issuerKeyId())) {
                // 未知 issuer 不提升 MC 身份；仍记录高水位，使该 issuer 日后被
                // 信任时旧声明也无法重放。
                recordWatermark(claimKey, claim.revision());
                return;
            }
            recordWatermark(claimKey, claim.revision());
            verified.put(key(claim.tsVirtualServerUid(), claim.tsClientUid()),
                    new VerifiedIdentity(claim.mcUuid(), claim.revision(),
                            claim.expiresAtEpochSeconds(), claimKey));
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] Ignoring invalid identity claim: {}", e.getMessage());
        }
    }

    private static void handleRevocation(IdentityPayloads.ClaimRevoked payload) {
        if (payload.claimKey() == null || payload.claimKey().isEmpty() || payload.revision() <= 0) {
            return;
        }
        // 墓碑持久化（含撤销时刻语义的 revision 高水位），再移除已验证条目。
        // 墓碑至少需覆盖声明 TTL 窗口（24h+偏差）；本实现无限期保留最高 revision，
        // 严格强于该下限，且只由显式解除信任操作清除。
        recordWatermark(payload.claimKey(), payload.revision());
        verified.values().removeIf(entry -> entry.claimKey().equals(payload.claimKey())
                && payload.revision() >= entry.revision());
    }

    private static void recordWatermark(String claimKey, long revision) {
        watermarks.merge(claimKey, revision, Math::max);
        saveTrustState();
    }

    // ---- 持久化（identity-trust.json：claimKey → 最高 revision） ----

    private static Path trustStateFile() {
        Path path = trustStatePath;
        if (path == null) {
            path = FabricLoader.getInstance().getConfigDir()
                    .resolve("teamspeak-svc").resolve("identity-trust.json");
            trustStatePath = path;
        }
        return path;
    }

    private static void loadTrustState() {
        try {
            Path path = trustStateFile();
            watermarks.clear();
            if (java.nio.file.Files.exists(path)) {
                JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(path))
                        .getAsJsonObject();
                if (root.has("watermarks") && root.get("watermarks").isJsonObject()) {
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : root
                            .getAsJsonObject("watermarks").entrySet()) {
                        try {
                            watermarks.put(entry.getKey(), entry.getValue().getAsLong());
                        } catch (RuntimeException ignored) {
                            // 跳过损坏条目；其余高水位仍生效
                        }
                    }
                }
            }
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to load identity trust state: {}", e.getMessage());
            ConfigFileUtil.backupBroken(trustStateFile());
        }
    }

    private static void saveTrustState() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", 1);
            JsonObject marks = new JsonObject();
            for (Map.Entry<String, Long> entry : watermarks.entrySet()) {
                marks.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("watermarks", marks);
            ConfigFileUtil.writePrivate(trustStateFile(), root.toString());
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to save identity trust state: {}", e.getMessage());
        }
    }

    private static String key(String serverUid, String tsClientUid) {
        return serverUid + "\0" + tsClientUid;
    }

    private static boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }
}
