package com.tsvoice.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Small dependency-free regression entry point; Gradle test fails on any assertion. */
public final class ProtocolTestMain {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testSeq16Wraparound();
        testUnsignedEncoding();
        testQuickLz();
        testTunnelTickets();
        testSignedPlayerClaims();
        testIdentityWireRoundTrip();
        testSequenceWindow();
        testSingleAndFragmentedCommands();
        testOutOfOrderAndWraparound();
        testCompressedFlagAndLargeCommand();
        testBoundsAndTimeout();
        testWeakNetworkRetransmission();
        testConcurrentTicketConsumption();
        System.out.println("Protocol tests passed: " + checks);
    }

    private static void testSeq16Wraparound() {
        check(Seq16.increment(65535) == 0, "65535 wraps to zero");
        check(Seq16.distance(65535, 0) == 1, "next generation distance");
        check(Seq16.distance(0, 65535) == -1, "previous generation distance");
        check(Seq16.distance(65534, 1) == 3, "multi-packet wrap distance");
        check(Seq16.unwrap(32768, 65536) == 98304, "half-range follows newest generation");
        check(Seq16.unwrap(32767, 65536) == 98303, "adjacent packet remains in same generation");
        check(Seq16.unwrap(0, 32768) == 0, "half-range does not advance generation");
    }

    private static void testIdentityWireRoundTrip() throws Exception {
        var request = new IdentityProtocol.IdentityProofRequest("client-a", "public-key");
        check(IdentityProtocol.decodeIdentityProofRequest(
                IdentityProtocol.encodeIdentityProofRequest(request)).equals(request),
                "identity proof request wire round trip");
        var challenge = new IdentityProtocol.IdentityChallenge(12345L, UUID.randomUUID(),
                "client-a", "server-a", 999L);
        check(IdentityProtocol.decodeIdentityChallenge(
                IdentityProtocol.encodeIdentityChallenge(challenge)).equals(challenge),
                "identity challenge wire round trip");
        var proof = new IdentityProtocol.IdentityProof(12345L, UUID.randomUUID(), "client-a", "server-a",
                "public-key", new byte[]{1, 2, 3});
        var decodedProof = IdentityProtocol.decodeIdentityProof(
                IdentityProtocol.encodeIdentityProof(proof));
        check(decodedProof.nonce() == proof.nonce()
                && decodedProof.playerUuid().equals(proof.playerUuid())
                && decodedProof.tsClientUid().equals(proof.tsClientUid())
                && decodedProof.tsVirtualServerUid().equals(proof.tsVirtualServerUid())
                && decodedProof.publicKeyString().equals(proof.publicKeyString())
                && java.util.Arrays.equals(decodedProof.signature(), proof.signature()),
                "identity proof wire round trip");
        UUID player = UUID.randomUUID();
        check(java.util.Arrays.equals(IdentityProtocol.proofBytes("server-a", "client-a", 12345L, player),
                IdentityProtocol.proofBytes("server-a", "client-a", 12345L, player)), "proof bytes deterministic");
        check(!java.util.Arrays.equals(IdentityProtocol.proofBytes("server-a", "client-a", 12345L, player),
                IdentityProtocol.proofBytes("server-b", "client-a", 12345L, player)), "proof bytes bind server");
        check(!java.util.Arrays.equals(IdentityProtocol.proofBytes("server-a", "client-a", 12345L, player),
                IdentityProtocol.proofBytes("server-a", "client-a", 12345L, UUID.randomUUID())),
                "proof bytes bind player");
        var signed = new IdentityProtocol.SignedClaimMessage("claim", new byte[]{4, 5});
        var decodedSigned = IdentityProtocol.decodeSignedClaim(
                IdentityProtocol.encodeSignedClaim(signed));
        check(decodedSigned.claim().equals(signed.claim())
                && java.util.Arrays.equals(decodedSigned.issuerPublicKey(), signed.issuerPublicKey()),
                "signed claim wire round trip");
        var revocation = new IdentityProtocol.ClaimRevocation("key", 7);
        check(IdentityProtocol.decodeClaimRevocation(
                IdentityProtocol.encodeClaimRevocation(revocation)).equals(revocation),
                "claim revocation wire round trip");
    }

    private static void testSignedPlayerClaims() {
        var issuer = ServerTrustDirectory.generateIssuerKeyPair();
        var other = ServerTrustDirectory.generateIssuerKeyPair();
        UUID mc = UUID.randomUUID();
        SignedPlayerClaim claim = SignedPlayerClaim.issue(issuer, mc, "server-a", "client-a", 1, 100, 500);
        check(claim.verify(issuer.getPublic(), 200), "valid optional player claim");
        check(!claim.verify(other.getPublic(), 200), "self-signed does not imply arbitrary issuer trust");
        SignedPlayerClaim decoded = SignedPlayerClaim.decode(claim.encode());
        check(decoded != null && decoded.verify(issuer.getPublic(), 200), "claim binary/text round trip");
        check(!claim.verify(issuer.getPublic(), 501), "expired claim rejected");
        ServerTrustDirectory directory = new ServerTrustDirectory();
        check(directory.verify(claim, 200) == ServerTrustDirectory.Verification.UNTRUSTED,
                "unknown issuer only untrusted mapping");
        directory.trust(issuer.getPublic());
        check(directory.verify(claim, 200) == ServerTrustDirectory.Verification.TRUSTED,
                "explicit local trust enables mapping");
        directory.revoke(ServerTrustDirectory.claimKey(claim), 1);
        check(directory.verify(claim, 200) == ServerTrustDirectory.Verification.UNTRUSTED,
                "revocation only downgrades mapping");
        check(directory.directoryRevision() == 2, "trust directory revision is monotonic");
    }

    private static void testTunnelTickets() {
        byte[] secret = "test-only-secret-that-is-long-enough-1234567890".getBytes(StandardCharsets.UTF_8);
        TunnelTicket ticket = TunnelTicket.issue(secret, 1000, 30);
        check(ticket.verify(secret, 1010), "valid tunnel ticket");
        check(!ticket.verify(secret, 1031), "expired ticket rejected");
        check(!ticket.verify("another-test-secret-that-is-long-enough-123".getBytes(StandardCharsets.UTF_8), 1010),
                "wrong secret rejected");
        TunnelTicket decoded = TunnelTicket.decode(ticket.encode());
        check(decoded != null && decoded.verify(secret, 1010), "ticket binary round trip");
        byte[] tampered = ticket.encode();
        tampered[tampered.length - 1] ^= 1;
        check(!TunnelTicket.decode(tampered).verify(secret, 1010), "tampered ticket rejected");
        TunnelTicketStore store = new TunnelTicketStore();
        check(store.verifyAndConsume(ticket, secret, 1010), "first valid ticket use accepted");
        check(!store.verifyAndConsume(ticket, secret, 1010), "replayed ticket rejected atomically");
    }

    private static void testQuickLz() throws Exception {
        byte[] stored = {0x04, 0x07, 0x04, 1, 2, 3, 4};
        check(java.util.Arrays.equals(QuickerLz.decompress(stored, 16), new byte[]{1, 2, 3, 4}),
                "stored QuickLZ payload round trip");
        check(invalidQuickLz(new byte[]{0x04, 0x02, 0x04, 1, 2, 3, 4, 5, 6, 7}), "stored declared length is enforced");
        check(invalidQuickLz(new byte[]{0x05, 0x09, 0x10, 0, 0, 0, 0, 0, 0, 0, 1}), "compressed short input rejected");
        check(invalidQuickLz(new byte[]{0x04, 0x04, (byte) 0xFF, 1, 2, 3, 4}), "stored length mismatch rejected");
    }

    private static boolean invalidQuickLz(byte[] input) {
        try {
            QuickerLz.decompress(input, 1024);
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }

    private static void testUnsignedEncoding() {
        byte[] high = UnsignedIntegers.toFixedWidth(new java.math.BigInteger("8000000000000000", 16), 8);
        check(high.length == 8 && high[0] == (byte) 0x80, "unsigned high bit has no sign padding");
        check(UnsignedIntegers.toFixedWidth(java.math.BigInteger.ONE, 4)[3] == 1, "short integer is left padded");
        check(UnsignedIntegers.toFixedWidth(java.math.BigInteger.ZERO, 4).length == 4, "zero has fixed width");
        boolean rejected = false;
        try {
            UnsignedIntegers.toFixedWidth(java.math.BigInteger.ONE.shiftLeft(16), 2);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check(rejected, "oversized integer is rejected");
    }

    private static void testSequenceWindow() {
        PacketSequenceWindow window = new PacketSequenceWindow(8);
        check(window.accept(65534), "accept pre-wrap packet");
        check(window.accept(65535), "accept wrap boundary");
        check(window.generationFor(0) == 1, "decrypt next id with next generation");
        check(window.accept(0), "accept wrapped packet");
        check(!window.accept(65535), "reject duplicate old packet");
        check(!window.accept(65534), "reject duplicate pre-wrap packet");
        check(window.accept(1), "continue after wrap");
        for (int i = 2; i < 30; i++) {
            check(window.accept(i), "advance receive window " + i);
        }
        check(!window.accept(0), "displaced ring slot cannot revive old packet");
    }

    private static void testSingleAndFragmentedCommands() throws Exception {
        BoundedCommandAssembler assembler = assembler(new AtomicLong());
        check(assembler.receive(fragment(1, 0, false, "serverinfo"), ack()).equals(List.of("serverinfo")),
                "unfragmented command");
        check(assembler.receive(fragment(2, 0, true, "alpha|"), ack()).isEmpty(), "first fragment waits");
        check(assembler.receive(fragment(3, 0, false, "beta|"), ack()).isEmpty(), "middle fragment waits");
        check(assembler.receive(fragment(4, 0, true, "omega"), ack()).equals(List.of("alpha|beta|omega")),
                "last boundary completes command");
    }

    private static void testOutOfOrderAndWraparound() throws Exception {
        BoundedCommandAssembler assembler = assembler(new AtomicLong());
        check(assembler.receive(fragment(65534, 1, false, "anchor"), ack()).equals(List.of("anchor")),
                "pre-wrap command anchors generation");
        check(assembler.receive(fragment(0, 2, true, "end"), ack()).isEmpty(), "tail arrives first");
        check(assembler.receive(fragment(65535, 1, true, "start-"), ack())
                .equals(List.of("start-end")), "two-fragment wrap command");
        check(assembler.receive(fragment(1, 2, false, "second"), ack()).equals(List.of("second")),
                "command after wrap continues");
        check(assembler.receive(fragment(65535, 0, false, "old"), ack()).isEmpty(),
                "old generation is ignored");

        BoundedCommandAssembler reverse = assembler(new AtomicLong());
        check(reverse.receive(fragment(21, 0, true, "last"), ack()).isEmpty(), "out-of-order tail waits");
        check(reverse.receive(fragment(20, 0, false, "middle-"), ack()).isEmpty(), "out-of-order middle waits");
        check(reverse.receive(fragment(19, 0, true, "first-"), ack())
                .equals(List.of("first-middle-last")), "buffered fragments assemble in packet order");
    }

    private static void testCompressedFlagAndLargeCommand() throws Exception {
        AtomicLong now = new AtomicLong();
        List<Boolean> compressed = new ArrayList<>();
        BoundedCommandAssembler assembler = new BoundedCommandAssembler((parts, flag) -> {
            compressed.add(flag);
            return join(parts);
        }, now::get);
        check(assembler.receive(new BoundedCommandAssembler.Fragment(10, 0, true, true, bytes("large-")), ack())
                .isEmpty(), "compressed first fragment waits");
        check(assembler.receive(new BoundedCommandAssembler.Fragment(11, 0, true, false, bytes("payload")), ack())
                .equals(List.of("large-payload")), "compressed last fragment completes");
        check(compressed.equals(List.of(true)), "compression comes from first packet");
    }

    private static void testBoundsAndTimeout() throws Exception {
        AtomicLong now = new AtomicLong();
        BoundedCommandAssembler bounded = new BoundedCommandAssembler((parts, compressed) -> join(parts),
                now::get, 128, 2, 32, 16, 100);
        bounded.receive(fragment(1, 0, true, "1234567890"), ack());
        bounded.receive(fragment(2, 0, false, "1234567890"), ack());
        bounded.receive(fragment(3, 0, true, "1234567890"), ack());
        check(bounded.pendingBytes() <= 32 && bounded.pendingPackets() <= 2, "pending memory is bounded");
        now.set(200);
        bounded.receive(fragment(4, 0, false, "after-timeout"), ack());
        check(bounded.pendingPackets() == 0, "expired fragment group is discarded and stream resynchronizes");
    }

    private static void testWeakNetworkRetransmission() throws Exception {
        PacketSequenceWindow window = new PacketSequenceWindow(8);
        check(window.accept(65535), "weak network accepts boundary packet");
        check(!window.accept(65535), "weak network drops boundary retransmission");
        check(window.accept(1), "weak network accepts next packet after loss");
        check(window.accept(15), "weak network advances the receive window");
        check(!window.accept(0), "weak network drops late previous packet");
        check(window.accept(16), "weak network continues after out-of-order packet");

        BoundedCommandAssembler assembler = assembler(new AtomicLong());
        check(assembler.receive(fragment(10, 0, true, "alpha-"), ack()).isEmpty(),
                "reordered first fragment waits");
        check(assembler.receive(fragment(12, 0, true, "omega"), ack()).isEmpty(),
                "reordered last fragment waits");
        check(assembler.receive(fragment(11, 0, false, "beta-"), ack())
                .equals(List.of("alpha-beta-omega")), "retransmission completes exactly one command");
        check(assembler.pendingPackets() == 0, "completed command leaves no pending fragments");
        check(assembler.receive(fragment(13, 0, false, "next"), ack()).equals(List.of("next")),
                "stream continues after reordered command");

        AtomicLong now = new AtomicLong();
        BoundedCommandAssembler timeout = new BoundedCommandAssembler(
                (parts, compressed) -> join(parts), now::get, 128, 4, 64, 32, 100);
        check(timeout.receive(fragment(20, 0, true, "lost-middle"), ack()).isEmpty(),
                "incomplete command waits before timeout");
        now.set(101);
        check(timeout.receive(fragment(21, 0, false, "later"), ack()).isEmpty(),
                "timed-out command resynchronizes without emitting");
        check(timeout.pendingPackets() == 0, "timed-out command clears pending fragments");
        check(timeout.receive(fragment(22, 0, false, "fresh"), ack()).equals(List.of("fresh")),
                "fresh command works after weak-network timeout");
    }

    private static void testConcurrentTicketConsumption() throws Exception {
        byte[] secret = "concurrent-test-secret-that-is-long-enough-123".getBytes(StandardCharsets.UTF_8);
        TunnelTicket ticket = TunnelTicket.issue(secret, 1000, 30);
        TunnelTicketStore store = new TunnelTicketStore();
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return store.verifyAndConsume(ticket, secret, 1010);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            check(accepted == 1, "concurrent ticket consumers admit exactly one connection");
            check(store.size() == 1, "concurrent ticket consumption stores one nonce");
        } finally {
            executor.shutdownNow();
        }
    }

    private static BoundedCommandAssembler assembler(AtomicLong now) {
        return new BoundedCommandAssembler((parts, compressed) -> join(parts), now::get);
    }

    private static BoundedCommandAssembler.Fragment fragment(int id, long generation,
                                                              boolean boundary, String value) {
        return new BoundedCommandAssembler.Fragment(id, generation, boundary, false, bytes(value));
    }

    private static BoundedCommandAssembler.AckSender ack() {
        return id -> {
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String join(List<byte[]> fragments) {
        int length = fragments.stream().mapToInt(part -> part.length).sum();
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] fragment : fragments) {
            System.arraycopy(fragment, 0, joined, offset, fragment.length);
            offset += fragment.length;
        }
        return new String(joined, StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
