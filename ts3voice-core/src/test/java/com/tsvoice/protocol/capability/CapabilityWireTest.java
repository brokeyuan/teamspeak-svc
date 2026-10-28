package com.tsvoice.protocol.capability;

import com.tsvoice.protocol.CapabilityWire;

import java.util.List;

/**
 * Capability wire golden vectors and malformed-input rejection (P4).
 * Any failure exits nonzero.
 */
public final class CapabilityWireTest {
    private static int checks;

    public static void main(String[] args) {
        // Golden vector: encode once, decode back, fields intact.
        CapabilityWire.Capabilities original = new CapabilityWire.Capabilities(
                0x1234_5678_9ABC_DEF0L, 1, 0, 30, "gateway-1",
                List.of(
                        new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                                "voice.example.org", 9987, List.of()),
                        new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TUNNEL,
                                "tunnel.example.org", 443, List.of(new byte[]{0x01, 0x02})),
                        new CapabilityWire.Candidate(CapabilityWire.CandidateKind.SVC_MC_CHANNEL,
                                "mc.example.org", 25565, List.of())));
        byte[] encoded = CapabilityWire.encode(original);
        check(encoded.length <= CapabilityWire.MAX_FRAME_BYTES, "golden frame within size limit");
        CapabilityWire.Capabilities decoded = CapabilityWire.decode(encoded);
        check(decoded != null, "golden frame decodes");
        check(decoded.requestNonce() == original.requestNonce(), "nonce survives round trip");
        check(decoded.major() == 1 && decoded.minor() == 0, "version survives round trip");
        check(decoded.ttlSeconds() == 30 && "gateway-1".equals(decoded.serviceInstanceId()),
                "ttl and service id survive round trip");
        check(decoded.candidates().size() == 3, "candidate count survives round trip");
        check(decoded.candidates().get(0).host().equals("voice.example.org")
                        && decoded.candidates().get(0).port() == 9987
                        && decoded.candidates().get(0).kind() == CapabilityWire.CandidateKind.TS_ENDPOINT,
                "TS endpoint candidate intact");
        check(decoded.candidates().get(1).unknownFields().size() == 1
                        && decoded.candidates().get(1).unknownFields().get(0)[0] == 0x01,
                "unknown minor fields carried through untouched");

        // Deterministic encoding (golden stability).
        byte[] encodedAgain = CapabilityWire.encode(original);
        check(java.util.Arrays.equals(encoded, encodedAgain), "encoding is deterministic");

        // Malformed inputs all rejected without unbounded allocation.
        check(CapabilityWire.decode(null) == null, "null frame rejected");
        check(CapabilityWire.decode(new byte[0]) == null, "empty frame rejected");
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 3);
        check(CapabilityWire.decode(truncated) == null, "truncated frame rejected");
        byte[] badMagic = encoded.clone();
        badMagic[0] = 'X';
        check(CapabilityWire.decode(badMagic) == null, "bad magic rejected");
        byte[] futureMajor = encoded.clone();
        futureMajor[4] = 2;
        check(CapabilityWire.decode(futureMajor) == null, "unknown major rejected");
        byte[] badTtl = encoded.clone();
        badTtl[14] = 0x00;
        badTtl[15] = 0x00;
        badTtl[16] = 0x00;
        badTtl[17] = 0x7B; // big-endian ttl -> 123 > 60
        check(CapabilityWire.decode(badTtl) == null, "over-limit TTL rejected");
        byte[] trailing = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, trailing, 0, encoded.length);
        trailing[encoded.length] = 0;
        check(CapabilityWire.decode(trailing) == null, "trailing garbage rejected");
        // Host length pointing past the frame: truncated-field rejection (bounded).
        byte[] lyingHost = encoded.clone();
        lyingHost[4 + 2 + 8 + 4 + 2] = (byte) 0xFF; // serviceId length high byte (magic=4)
        lyingHost[4 + 2 + 8 + 4 + 2 + 1] = (byte) 0xFF;
        check(CapabilityWire.decode(lyingHost) == null, "length field beyond frame rejected");

        // Encoding-side validation.
        try {
            CapabilityWire.encode(new CapabilityWire.Capabilities(1, 1, 0, 61, "g", List.of()));
            check(false, "over-limit TTL rejected on encode");
        } catch (IllegalArgumentException e) {
            check(true, "over-limit TTL rejected on encode");
        }
        try {
            CapabilityWire.encode(new CapabilityWire.Capabilities(1, 2, 0, 30, "g", List.of()));
            check(false, "unknown major rejected on encode");
        } catch (IllegalArgumentException e) {
            check(true, "unknown major rejected on encode");
        }
        try {
            List<CapabilityWire.Candidate> tooMany = new java.util.ArrayList<>();
            for (int i = 0; i < 9; i++) {
                tooMany.add(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                        "h" + i, 9987, List.of()));
            }
            CapabilityWire.encode(new CapabilityWire.Capabilities(1, 1, 0, 30, "g", tooMany));
            check(false, "over-limit candidate count rejected on encode");
        } catch (IllegalArgumentException e) {
            check(true, "over-limit candidate count rejected on encode");
        }
        try {
            CapabilityWire.encode(new CapabilityWire.Capabilities(1, 1, 0, 30, "g",
                    List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                            "x".repeat(254), 9987, List.of()))));
            check(false, "over-limit host rejected on encode");
        } catch (IllegalArgumentException e) {
            check(true, "over-limit host rejected on encode");
        }
        try {
            CapabilityWire.encode(new CapabilityWire.Capabilities(1, 1, 0, 30, "g",
                    List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                            "h", 70000, List.of()))));
            check(false, "out-of-range port rejected on encode");
        } catch (IllegalArgumentException e) {
            check(true, "out-of-range port rejected on encode");
        }

        if (checks == 0) {
            throw new AssertionError("no capability wire checks ran");
        }
        System.out.println("Capability wire regression passed: " + checks + " checks");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
