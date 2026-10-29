package com.tsvoice.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Versioned wire records for optional MC-player identity claims. These records
 * never grant voice, channel or group permissions; they only support trusted
 * player-name/ skin mapping for clients that opt into the protocol.
 */
public final class IdentityProtocol {
    public static final int VERSION = 1;

    private IdentityProtocol() {
    }

    public record IdentityProofRequest(String tsClientUid, String publicKeyString) {
        public IdentityProofRequest {
            requireNonBlank(tsClientUid, "tsClientUid");
            requireNonBlank(publicKeyString, "publicKeyString");
        }
    }

    public record IdentityChallenge(long nonce, UUID playerUuid, String tsClientUid,
                                    String tsVirtualServerUid, long expiresAtEpochSeconds) {
        public IdentityChallenge {
            if (nonce == 0 || playerUuid == null) {
                throw new IllegalArgumentException("Invalid identity challenge");
            }
            requireNonBlank(tsClientUid, "tsClientUid");
            requireNonBlank(tsVirtualServerUid, "tsVirtualServerUid");
        }
        public boolean expired(long nowEpochSeconds) {
            return nowEpochSeconds >= expiresAtEpochSeconds;
        }
    }

    public record IdentityProof(long nonce, UUID playerUuid, String tsClientUid, String tsVirtualServerUid,
                                String publicKeyString, byte[] signature) {
        public IdentityProof {
            if (nonce == 0 || playerUuid == null) {
                throw new IllegalArgumentException("Invalid identity proof nonce");
            }
            requireNonBlank(tsClientUid, "tsClientUid");
            requireNonBlank(tsVirtualServerUid, "tsVirtualServerUid");
            requireNonBlank(publicKeyString, "publicKeyString");
            if (signature == null || signature.length == 0) {
                throw new IllegalArgumentException("Invalid identity proof signature");
            }
        }
        public byte[] signedBytes() {
            return proofBytes(tsVirtualServerUid, tsClientUid, nonce, playerUuid);
        }
    }

    public record SignedClaimMessage(String claim, byte[] issuerPublicKey) {
        public SignedClaimMessage {
            requireNonBlank(claim, "claim");
            if (issuerPublicKey == null || issuerPublicKey.length == 0) {
                throw new IllegalArgumentException("Invalid issuer public key");
            }
        }
    }

    public record ClaimRevocation(String claimKey, long revision) {
        public ClaimRevocation {
            requireNonBlank(claimKey, "claimKey");
            if (revision < 0) {
                throw new IllegalArgumentException("Invalid claim revocation revision");
            }
        }
    }

    /** Canonical bytes signed by the player's TS identity key. */
    public static byte[] proofBytes(String tsVirtualServerUid, String tsClientUid, long nonce, UUID playerUuid) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid must not be null");
        }
        try {
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(outBytes);
            out.writeUTF("tsvoice-ts-identity-proof-v1");
            out.writeUTF(nonNull(tsVirtualServerUid));
            out.writeUTF(nonNull(tsClientUid));
            out.writeLong(nonce);
            out.writeLong(playerUuid.getMostSignificantBits());
            out.writeLong(playerUuid.getLeastSignificantBits());
            return outBytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Identity proof encoding failed", e);
        }
    }

    public static byte[] encodeIdentityProofRequest(IdentityProofRequest request) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DataOutputStream out = out(encoded);
        out.writeUTF(request.tsClientUid());
        out.writeUTF(request.publicKeyString());
        return outBytes(encoded);
    }

    public static IdentityProofRequest decodeIdentityProofRequest(byte[] data) throws IOException {
        DataInputStream in = in(data);
        return new IdentityProofRequest(in.readUTF(), in.readUTF());
    }

    public static byte[] encodeIdentityChallenge(IdentityChallenge challenge) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DataOutputStream out = out(encoded);
        out.writeLong(challenge.nonce());
        out.writeLong(challenge.playerUuid().getMostSignificantBits());
        out.writeLong(challenge.playerUuid().getLeastSignificantBits());
        out.writeUTF(challenge.tsClientUid());
        out.writeUTF(challenge.tsVirtualServerUid());
        out.writeLong(challenge.expiresAtEpochSeconds());
        return outBytes(encoded);
    }

    public static IdentityChallenge decodeIdentityChallenge(byte[] data) throws IOException {
        DataInputStream in = in(data);
        long nonce = in.readLong();
        UUID player = new UUID(in.readLong(), in.readLong());
        return new IdentityChallenge(nonce, player, in.readUTF(), in.readUTF(), in.readLong());
    }

    public static byte[] encodeIdentityProof(IdentityProof proof) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DataOutputStream out = out(encoded);
        out.writeLong(proof.nonce());
        out.writeLong(proof.playerUuid().getMostSignificantBits());
        out.writeLong(proof.playerUuid().getLeastSignificantBits());
        out.writeUTF(proof.tsClientUid());
        out.writeUTF(proof.tsVirtualServerUid());
        out.writeUTF(proof.publicKeyString());
        writeBytes(out, proof.signature());
        return outBytes(encoded);
    }

    public static IdentityProof decodeIdentityProof(byte[] data) throws IOException {
        DataInputStream in = in(data);
        long nonce = in.readLong();
        UUID playerUuid = new UUID(in.readLong(), in.readLong());
        String tsClientUid = in.readUTF();
        String serverUid = in.readUTF();
        String publicKey = in.readUTF();
        return new IdentityProof(nonce, playerUuid, tsClientUid, serverUid, publicKey, readBytes(in));
    }

    public static byte[] encodeSignedClaim(SignedClaimMessage message) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DataOutputStream out = out(encoded);
        out.writeUTF(message.claim());
        writeBytes(out, message.issuerPublicKey());
        return outBytes(encoded);
    }

    public static SignedClaimMessage decodeSignedClaim(byte[] data) throws IOException {
        DataInputStream in = in(data);
        return new SignedClaimMessage(in.readUTF(), readBytes(in));
    }

    public static byte[] encodeClaimRevocation(ClaimRevocation revocation) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DataOutputStream out = out(encoded);
        out.writeUTF(revocation.claimKey());
        out.writeLong(revocation.revision());
        return outBytes(encoded);
    }

    public static ClaimRevocation decodeClaimRevocation(byte[] data) throws IOException {
        DataInputStream in = in(data);
        return new ClaimRevocation(in.readUTF(), in.readLong());
    }

    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decodeBase64(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static DataOutputStream out(ByteArrayOutputStream bytes) {
        return new DataOutputStream(bytes);
    }

    private static byte[] outBytes(ByteArrayOutputStream bytes) throws IOException {
        bytes.flush();
        return bytes.toByteArray();
    }

    private static DataInputStream in(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 65535) {
            throw new IOException("Invalid identity field length");
        }
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
