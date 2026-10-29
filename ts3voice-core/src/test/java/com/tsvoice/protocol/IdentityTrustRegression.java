package com.tsvoice.protocol;

import java.security.KeyPair;
import java.util.UUID;

/**
 * Dependency-free assertions for the claim trust policy: real expiry, future
 * issuance and over-long TTL rejection, and directory trust/revocation
 * watermark behavior. Any failure exits nonzero.
 */
public final class IdentityTrustRegression {
    private static int checks;

    public static void main(String[] args) {
        KeyPair issuer = ServerTrustDirectory.generateIssuerKeyPair();
        UUID mcUuid = UUID.randomUUID();
        long now = System.currentTimeMillis() / 1000L;
        String server = "server-a";
        String client = "ts-client-1";

        // Policy: a well-formed claim verifies.
        SignedPlayerClaim valid = SignedPlayerClaim.issue(issuer, mcUuid, server, client,
                1, now - 1, now + 3600);
        check(valid.verifyWithPolicy(issuer.getPublic(), now), "valid claim passes policy verification");
        check(!valid.verifyWithPolicy(issuer.getPublic(), now + 7200),
                "valid claim fails verification after expiry");

        // Policy: future issuance beyond the skew is rejected (it becomes usable
        // at its issuedAt, which the issuing side always sets to "now").
        SignedPlayerClaim future = SignedPlayerClaim.issue(issuer, mcUuid, server, client,
                2, now + 3600, now + 7200);
        check(!future.verifyWithPolicy(issuer.getPublic(), now), "future-issued claim is rejected");
        check(future.verifyWithPolicy(issuer.getPublic(), now + 3600),
                "future-issued claim becomes valid once its issuedAt passes within TTL");

        // Policy: over-long TTL is rejected (legacy over-long claims included).
        SignedPlayerClaim longTtl = SignedPlayerClaim.issue(issuer, mcUuid, server, client,
                3, now - 1, now + SignedPlayerClaim.MAX_TTL_SECONDS + 120);
        check(!longTtl.verifyWithPolicy(issuer.getPublic(), now), "claim exceeding max TTL is rejected");
        SignedPlayerClaim maxTtl = SignedPlayerClaim.issue(issuer, mcUuid, server, client,
                4, now - 1, now - 1 + SignedPlayerClaim.MAX_TTL_SECONDS);
        check(maxTtl.verifyWithPolicy(issuer.getPublic(), now), "claim at exactly max TTL is accepted");

        // Policy: unsupported version is rejected.
        SignedPlayerClaim unsupported = new SignedPlayerClaim(99, valid.issuerKeyId(), mcUuid, server,
                client, 5, now - 1, now + 3600, valid.signature());
        check(!unsupported.verifyWithPolicy(issuer.getPublic(), now),
                "unsupported claim version is rejected");

        // Directory: only explicitly trusted issuers verify; revocation watermarks block replays.
        ServerTrustDirectory directory = new ServerTrustDirectory();
        check(directory.verify(valid, now) == ServerTrustDirectory.Verification.UNTRUSTED,
                "unpinned issuer is untrusted even with a valid signature");
        check(!directory.isTrusted(valid.issuerKeyId()), "unknown issuer key id is not trusted");
        directory.trust(issuer.getPublic());
        check(directory.isTrusted(valid.issuerKeyId()), "pinned issuer key id is trusted");
        check(directory.verify(valid, now) == ServerTrustDirectory.Verification.TRUSTED,
                "pinned issuer claim verifies as trusted");
        directory.revoke(ServerTrustDirectory.claimKey(valid), 1);
        check(directory.revocationWatermark(ServerTrustDirectory.claimKey(valid)) == 1L,
                "revocation watermark is recorded");
        check(directory.verify(valid, now) == ServerTrustDirectory.Verification.UNTRUSTED,
                "revoked claim revision is rejected by the watermark");
        SignedPlayerClaim reproof = SignedPlayerClaim.issue(issuer, mcUuid, server, client,
                2, now - 1, now + 3600);
        check(directory.verify(reproof, now) == ServerTrustDirectory.Verification.TRUSTED,
                "higher-revision re-proof after revocation verifies as trusted");
        directory.untrust(issuer.getPublic());
        check(directory.verify(reproof, now) == ServerTrustDirectory.Verification.UNTRUSTED,
                "untrusted issuer claim no longer verifies");

        if (checks == 0) {
            throw new AssertionError("no identity trust checks ran");
        }
        System.out.println("Identity trust regression passed: " + checks + " checks");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
