package com.svcb.tsbridge.net.identity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Wire payloads for optional, non-gating MC-player identity claims. */
public final class IdentityPayloads {
    private IdentityPayloads() {
    }

    public record IdentityProofRequest(String tsClientUid, String publicKeyString)
            implements CustomPacketPayload {
        public static final Type<IdentityProofRequest> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "identity_proof_request"));
        public static final StreamCodec<FriendlyByteBuf, IdentityProofRequest> CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, IdentityProofRequest value) {
                IdentityPayloads.writeRequest(buf, value);
            }

            @Override
            public IdentityProofRequest decode(FriendlyByteBuf buf) {
                return IdentityPayloads.readRequest(buf);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record IdentityChallenge(long nonce, UUID playerUuid, String tsClientUid,
                                    String tsVirtualServerUid, long expiresAtEpochSeconds)
            implements CustomPacketPayload {
        public static final Type<IdentityChallenge> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "identity_challenge"));
        public static final StreamCodec<FriendlyByteBuf, IdentityChallenge> CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, IdentityChallenge value) {
                IdentityPayloads.writeChallenge(buf, value);
            }

            @Override
            public IdentityChallenge decode(FriendlyByteBuf buf) {
                return IdentityPayloads.readChallenge(buf);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record IdentityProof(long nonce, UUID playerUuid, String tsClientUid, String tsVirtualServerUid,
                                String publicKeyString, byte[] signature)
            implements CustomPacketPayload {
        public static final Type<IdentityProof> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "identity_proof"));
        public static final StreamCodec<FriendlyByteBuf, IdentityProof> CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, IdentityProof value) {
                IdentityPayloads.writeProof(buf, value);
            }

            @Override
            public IdentityProof decode(FriendlyByteBuf buf) {
                return IdentityPayloads.readProof(buf);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SignedClaim(String claim, byte[] issuerPublicKey) implements CustomPacketPayload {
        public static final Type<SignedClaim> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "signed_claim"));
        public static final StreamCodec<FriendlyByteBuf, SignedClaim> CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, SignedClaim value) {
                IdentityPayloads.writeClaim(buf, value);
            }

            @Override
            public SignedClaim decode(FriendlyByteBuf buf) {
                return IdentityPayloads.readClaim(buf);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ClaimRevoked(String claimKey, long revision) implements CustomPacketPayload {
        public static final Type<ClaimRevoked> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "claim_revoked"));
        public static final StreamCodec<FriendlyByteBuf, ClaimRevoked> CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, ClaimRevoked value) {
                IdentityPayloads.writeRevocation(buf, value);
            }

            @Override
            public ClaimRevoked decode(FriendlyByteBuf buf) {
                return IdentityPayloads.readRevocation(buf);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeRequest(FriendlyByteBuf buf, IdentityProofRequest value) {
        buf.writeUtf(value.tsClientUid());
        buf.writeUtf(value.publicKeyString());
    }

    private static IdentityProofRequest readRequest(FriendlyByteBuf buf) {
        return new IdentityProofRequest(buf.readUtf(), buf.readUtf());
    }

    private static void writeChallenge(FriendlyByteBuf buf, IdentityChallenge value) {
        buf.writeLong(value.nonce());
        buf.writeLong(value.playerUuid().getMostSignificantBits());
        buf.writeLong(value.playerUuid().getLeastSignificantBits());
        buf.writeUtf(value.tsClientUid());
        buf.writeUtf(value.tsVirtualServerUid());
        buf.writeLong(value.expiresAtEpochSeconds());
    }

    private static IdentityChallenge readChallenge(FriendlyByteBuf buf) {
        return new IdentityChallenge(buf.readLong(), new UUID(buf.readLong(), buf.readLong()),
                buf.readUtf(), buf.readUtf(), buf.readLong());
    }

    private static void writeProof(FriendlyByteBuf buf, IdentityProof value) {
        buf.writeLong(value.nonce());
        buf.writeLong(value.playerUuid().getMostSignificantBits());
        buf.writeLong(value.playerUuid().getLeastSignificantBits());
        buf.writeUtf(value.tsClientUid());
        buf.writeUtf(value.tsVirtualServerUid());
        buf.writeUtf(value.publicKeyString());
        buf.writeByteArray(value.signature());
    }

    private static IdentityProof readProof(FriendlyByteBuf buf) {
        return new IdentityProof(buf.readLong(), new UUID(buf.readLong(), buf.readLong()),
                buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readByteArray());
    }

    private static void writeClaim(FriendlyByteBuf buf, SignedClaim value) {
        buf.writeUtf(value.claim());
        buf.writeByteArray(value.issuerPublicKey());
    }

    private static SignedClaim readClaim(FriendlyByteBuf buf) {
        return new SignedClaim(buf.readUtf(), buf.readByteArray());
    }

    private static void writeRevocation(FriendlyByteBuf buf, ClaimRevoked value) {
        buf.writeUtf(value.claimKey());
        buf.writeLong(value.revision());
    }

    private static ClaimRevoked readRevocation(FriendlyByteBuf buf) {
        return new ClaimRevoked(buf.readUtf(), buf.readLong());
    }
}
