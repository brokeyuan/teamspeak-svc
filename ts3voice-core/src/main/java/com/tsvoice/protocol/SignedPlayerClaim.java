package com.tsvoice.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Optional signed MC-player mapping; it grants no voice or group permission. */
public record SignedPlayerClaim(int version, String issuerKeyId, UUID mcUuid,
                                String tsVirtualServerUid, String tsClientUid, long revision,
                                long issuedAtEpochSeconds, long expiresAtEpochSeconds, byte[] signature) {
    public static final int CURRENT_VERSION = 1;
    public static final String DOMAIN = "tsvoice-player-claim-v1";

    public SignedPlayerClaim {
        if (mcUuid == null || tsVirtualServerUid == null || tsVirtualServerUid.isBlank()
                || tsClientUid == null || tsClientUid.isBlank() || revision < 0
                || expiresAtEpochSeconds <= issuedAtEpochSeconds || signature == null) {
            throw new IllegalArgumentException("Invalid player claim");
        }
    }

    public static SignedPlayerClaim issue(java.security.KeyPair issuer, UUID mcUuid, String tsVirtualServerUid,
                                          String tsClientUid, long revision, long issuedAt, long expiresAt) {
        try {
            String issuerKeyId = issuerKeyId(issuer.getPublic().getEncoded());
            byte[] body = body(CURRENT_VERSION, issuerKeyId, mcUuid, tsVirtualServerUid, tsClientUid,
                    revision, issuedAt, expiresAt);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(issuer.getPrivate());
            signer.update(body);
            return new SignedPlayerClaim(CURRENT_VERSION, issuerKeyId, mcUuid, tsVirtualServerUid,
                    tsClientUid, revision, issuedAt, expiresAt, signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign player claim", e);
        }
    }

    public boolean verify(PublicKey issuerKey, long nowEpochSeconds) {
        if (version != CURRENT_VERSION || nowEpochSeconds > expiresAtEpochSeconds) {
            return false;
        }
        try {
            if (!issuerKeyId.equals(issuerKeyId(issuerKey.getEncoded()))) {
                return false;
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(issuerKey);
            verifier.update(body(version, issuerKeyId, mcUuid, tsVirtualServerUid, tsClientUid,
                    revision, issuedAtEpochSeconds, expiresAtEpochSeconds));
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body(version, issuerKeyId,
                mcUuid, tsVirtualServerUid, tsClientUid, revision, issuedAtEpochSeconds,
                expiresAtEpochSeconds)) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    public static SignedPlayerClaim decode(String encoded) {
        try {
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 2) {
                return null;
            }
            byte[] body = Base64.getUrlDecoder().decode(parts[0]);
            ByteBuffer buffer = ByteBuffer.wrap(body);
            byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
            if (body.length < domain.length + 1) {
                return null;
            }
            for (byte expected : domain) {
                if (buffer.get() != expected) {
                    return null;
                }
            }
            int version = buffer.get() & 0xFF;
            String issuer = readString(buffer);
            UUID mcUuid = new UUID(buffer.getLong(), buffer.getLong());
            String server = readString(buffer);
            String client = readString(buffer);
            long revision = buffer.getLong();
            long issued = buffer.getLong();
            long expires = buffer.getLong();
            if (buffer.hasRemaining()) {
                return null;
            }
            return new SignedPlayerClaim(version, issuer, mcUuid, server, client, revision, issued,
                    expires, Base64.getUrlDecoder().decode(parts[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String issuerKeyId(byte[] encodedPublicKey) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(encodedPublicKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static PrivateKey decodePrivateKey(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static byte[] body(int version, String issuer, UUID mcUuid, String server, String client,
                               long revision, long issuedAt, long expiresAt) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            buffer.put(DOMAIN.getBytes(StandardCharsets.UTF_8));
            buffer.put((byte) version);
            putString(buffer, issuer);
            buffer.putLong(mcUuid.getMostSignificantBits());
            buffer.putLong(mcUuid.getLeastSignificantBits());
            putString(buffer, server);
            putString(buffer, client);
            buffer.putLong(revision);
            buffer.putLong(issuedAt);
            buffer.putLong(expiresAt);
            return Arrays.copyOf(buffer.array(), buffer.position());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Claim is too large", e);
        }
    }

    private static void putString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 255) {
            throw new IllegalArgumentException("Claim string exceeds 255 bytes");
        }
        buffer.put((byte) bytes.length);
        buffer.put(bytes);
    }

    private static String readString(ByteBuffer buffer) {
        int length = buffer.get() & 0xFF;
        if (length > buffer.remaining()) {
            throw new IllegalArgumentException("Truncated claim string");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
