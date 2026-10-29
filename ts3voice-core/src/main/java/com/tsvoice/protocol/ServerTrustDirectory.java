package com.tsvoice.protocol;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local trust policy. Self-signed servers may join, but are not globally trusted. */
public final class ServerTrustDirectory {
    private final Map<String, PublicKey> trustedIssuers = new ConcurrentHashMap<>();
    private final Map<String, Long> revokedClaims = new ConcurrentHashMap<>();
    private long directoryRevision;

    public static java.security.KeyPair generateIssuerKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate issuer key", e);
        }
    }

    public synchronized void trust(PublicKey issuer) {
        trustedIssuers.put(SignedPlayerClaim.issuerKeyId(issuer.getEncoded()), issuer);
        directoryRevision++;
    }

    public synchronized void untrust(PublicKey issuer) {
        trustedIssuers.remove(SignedPlayerClaim.issuerKeyId(issuer.getEncoded()));
        directoryRevision++;
    }

    public synchronized void revoke(String claimKey, long revision) {
        revokedClaims.merge(claimKey, revision, Math::max);
        directoryRevision++;
    }

    /** 按 issuerKeyId 查询信任状态（客户端在只有声明头时使用）。 */
    public synchronized boolean isTrusted(String issuerKeyId) {
        return issuerKeyId != null && trustedIssuers.containsKey(issuerKeyId);
    }

    /** 返回指定声明的撤销墓碑 revision（未撤销为 null）。 */
    public synchronized Long revocationWatermark(String claimKey) {
        return revokedClaims.get(claimKey);
    }

    public synchronized Verification verify(SignedPlayerClaim claim, long nowEpochSeconds) {
        PublicKey issuer = trustedIssuers.get(claim.issuerKeyId());
        if (issuer == null || !claim.verify(issuer, nowEpochSeconds)) {
            return Verification.UNTRUSTED;
        }
        String key = claimKey(claim);
        Long revoked = revokedClaims.get(key);
        if (revoked != null && claim.revision() <= revoked) {
            return Verification.UNTRUSTED;
        }
        return Verification.TRUSTED;
    }

    public synchronized long directoryRevision() {
        return directoryRevision;
    }

    public static String claimKey(SignedPlayerClaim claim) {
        return claim.issuerKeyId() + ":" + claim.tsVirtualServerUid() + ":" + claim.tsClientUid();
    }

    public enum Verification {
        TRUSTED,
        UNTRUSTED
    }
}
