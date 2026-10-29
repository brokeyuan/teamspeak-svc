package com.tsvoice.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Versioned, expiring, single-use transport tickets independent of MC identity. */
public record TunnelTicket(int version, long issuedAtEpochSeconds, long expiresAtEpochSeconds,
                           byte[] nonce, byte[] signature) {
    public static final int CURRENT_VERSION = 1;
    public static final int NONCE_BYTES = 16;
    public static final int SIGNATURE_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static TunnelTicket issue(byte[] secret, long nowEpochSeconds, long lifetimeSeconds) {
        requireSecret(secret);
        if (lifetimeSeconds < 1 || lifetimeSeconds > 300) {
            throw new IllegalArgumentException("Tunnel ticket lifetime must be 1..300 seconds");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return new TunnelTicket(CURRENT_VERSION, nowEpochSeconds, nowEpochSeconds + lifetimeSeconds,
                nonce, sign(secret, CURRENT_VERSION, nowEpochSeconds, nowEpochSeconds + lifetimeSeconds, nonce));
    }

    public boolean verify(byte[] secret, long nowEpochSeconds) {
        requireSecret(secret);
        if (version != CURRENT_VERSION || nonce == null || nonce.length != NONCE_BYTES
                || signature == null || signature.length != SIGNATURE_BYTES
                || issuedAtEpochSeconds < 0 || expiresAtEpochSeconds <= issuedAtEpochSeconds
                || nowEpochSeconds < issuedAtEpochSeconds || nowEpochSeconds > expiresAtEpochSeconds) {
            return false;
        }
        return MessageDigest.isEqual(signature,
                sign(secret, version, issuedAtEpochSeconds, expiresAtEpochSeconds, nonce));
    }

    public byte[] encode() {
        byte[] result = new byte[1 + 8 + 8 + NONCE_BYTES + SIGNATURE_BYTES];
        result[0] = (byte) version;
        writeLong(result, 1, issuedAtEpochSeconds);
        writeLong(result, 9, expiresAtEpochSeconds);
        System.arraycopy(nonce, 0, result, 17, NONCE_BYTES);
        System.arraycopy(signature, 0, result, 17 + NONCE_BYTES, SIGNATURE_BYTES);
        return result;
    }

    public static TunnelTicket decode(byte[] encoded) {
        if (encoded == null || encoded.length != 1 + 8 + 8 + NONCE_BYTES + SIGNATURE_BYTES) {
            return null;
        }
        return new TunnelTicket(encoded[0] & 0xFF, readLong(encoded, 1), readLong(encoded, 9),
                Arrays.copyOfRange(encoded, 17, 17 + NONCE_BYTES),
                Arrays.copyOfRange(encoded, 17 + NONCE_BYTES, encoded.length));
    }

    private static byte[] sign(byte[] secret, int version, long issuedAt, long expiresAt, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update("tsvoice-tunnel-v1".getBytes(StandardCharsets.UTF_8));
            mac.update((byte) version);
            mac.update(java.nio.ByteBuffer.allocate(16).putLong(issuedAt).putLong(expiresAt).array());
            mac.update(nonce);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static void writeLong(byte[] target, int offset, long value) {
        java.nio.ByteBuffer.wrap(target, offset, 8).putLong(value);
    }

    private static long readLong(byte[] source, int offset) {
        return java.nio.ByteBuffer.wrap(source, offset, 8).getLong();
    }

    private static void requireSecret(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("Tunnel secret must contain at least 32 bytes");
        }
    }
}
