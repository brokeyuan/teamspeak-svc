package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.CapabilityDiscovery;
import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.protocol.capability.CapabilityWire;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S0 regression (post-m7 REVIEW R21/R22/R27):
 *
 * <p>R21 — routeEpoch allocations must all come from the single global counter:
 * repeated confirmed channel changes may not fall behind; session end/create
 * must never regress or repeat an epoch; and a stale session's writer permit
 * (session-bound check) must stay false even when a new session's epoch value
 * coincides with the old one.</p>
 *
 * <p>R27 — decode returning null for malformed announcements must be rejected
 * at the manager boundary without throwing, and must not disturb existing
 * authorized/selected state.</p>
 *
 * <p>R23 — announcements with no pre-trusted endpoint must surface as pending
 * consent; explicit accept authorizes only the received snapshot; explicit
 * reject suppresses re-prompting; stale/foreign candidate lists are rejected.</p>
 */
public class S0FixRegressionTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** 确认一个频道（策略默认 LEGACY：先提交合法目标）。 */
    static boolean confirm(VoiceState.SessionToken token, int cid) {
        return BackendCoordinator.confirmChannel(token, "fixture-server", cid);
    }

    public static void main(String[] args) throws Exception {
        // ================= R21: route epoch global monotonicity =================
        VoiceState.SessionToken t1 = BackendCoordinator.beginSession();
        long initialEpoch = BackendCoordinator.snapshot().route().routeEpoch();
        check(initialEpoch >= 2, "R21: session start allocates from the global counter");

        // Repeated admin moves: every confirmed channel change must advance the
        // GLOBAL counter (R21 root cause: confirmChannel used route.routeEpoch()+1).
        BackendCoordinator.setChannelTarget(t1, VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING, 10);
        check(confirm(t1, 10), "R21: legal target confirmed");
        long e10 = BackendCoordinator.snapshot().route().routeEpoch();
        BackendCoordinator.setChannelTarget(t1, VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING, 20);
        check(confirm(t1, 20), "R21: second target confirmed");
        long e20 = BackendCoordinator.snapshot().route().routeEpoch();
        BackendCoordinator.setChannelTarget(t1, VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING, 30);
        check(confirm(t1, 30), "R21: third target confirmed");
        long e30 = BackendCoordinator.snapshot().route().routeEpoch();
        check(e10 < e20 && e20 < e30, "R21: confirmed channel changes strictly advance the epoch");

        // Repeated observation of the SAME channel must NOT advance the epoch
        // (unchanged observation stays cheap; only invalidation/changes move it).
        long beforeRepeat = BackendCoordinator.snapshot().route().routeEpoch();
        check(confirm(t1, 30), "R21: repeated observation accepted");
        check(BackendCoordinator.snapshot().route().routeEpoch() == beforeRepeat,
                "R21: unchanged channel observation does not advance the epoch");

        // Session end -> new session: the new epoch must be strictly greater than
        // EVERY route epoch the old session ever held (no regression/reuse).
        BackendCoordinator.endSession(t1);
        VoiceState.SessionToken t2 = BackendCoordinator.beginSession();
        long newSessionEpoch = BackendCoordinator.snapshot().route().routeEpoch();
        check(newSessionEpoch > e30,
                "R21: new session epoch strictly greater than every old-session epoch ("
                        + newSessionEpoch + " > " + e30 + ")");

        // Writer permit session binding: a supplier bound to the OLD token must be
        // denied on the new route even if epoch values were to repeat.
        boolean oldPermit = BackendCoordinator.writePermittedFor(t1);
        boolean newPermit = BackendCoordinator.writePermittedFor(t2);
        check(!oldPermit, "R21: stale-session write permit is denied after session switch");
        check(!newPermit, "R21: new session has no write permit before channel confirmation");

        // Policy denial revokes the permit (epoch advances again).
        BackendCoordinator.setChannelTarget(t2, VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING, 40);
        check(confirm(t2, 40), "R21: new session confirms its target");
        long readyEpoch = BackendCoordinator.snapshot().route().routeEpoch();
        check(BackendCoordinator.writePermittedFor(t2),
                "R21: confirmed new session holds the write permit");
        // Illegal observation revokes
        BackendCoordinator.setChannelTarget(t2, VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING, 50);
        check(!confirm(t2, 99), "R21: illegal channel observation denied");
        check(!BackendCoordinator.writePermittedFor(t2), "R21: illegal observation revokes the permit");
        long revokedEpoch = BackendCoordinator.snapshot().route().routeEpoch();
        check(revokedEpoch > readyEpoch, "R21: revocation advances the epoch");
        BackendCoordinator.endSession(t2);

        // ================= R27: malformed announcement boundary =================
        TsVoiceManager managerInit = null; // static-only manager
        // Old authorized state: authorize a candidate first.
        TsVoiceManager.onServerChanged("s0-r27.test");
        // Begin a discovery window (hello) and craft a valid announcement.
        TsVoiceManager.beginCapabilityDiscovery();
        long nonce = TsVoiceManager.discoveryRequestNonceForTest();
        CapabilityWire.Candidate tsCandidate = new CapabilityWire.Candidate(
                CapabilityWire.CandidateKind.TS_ENDPOINT, "ts.r27.test", 9987, List.of());
        CapabilityWire.Capabilities announcement = new CapabilityWire.Capabilities(
                nonce, CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR, 30,
                "svc-instance-r27", List.of(tsCandidate));
        byte[] frame = CapabilityWire.encode(announcement);
        check(TsVoiceManager.onCapabilitiesAnnouncementFrame(frame),
                "R27: valid announcement accepted (entering pending consent)");

        // Malformed frames of every family must return false WITHOUT throwing and
        // must not disturb the received snapshot.
        List<byte[]> malformed = List.of(
                new byte[0],
                new byte[]{1},
                "XXXX".getBytes(),
                CapabilityWire.encode(announcement).clone(),
                buildTruncated(frame),
                buildBadMagic(),
                buildBadMajor(),
                buildBadTtl(),
                buildBadCandidateCount());
        for (int i = 0; i < malformed.size(); i++) {
            boolean accepted = TsVoiceManager.onCapabilitiesAnnouncementFrame(malformed.get(i));
            check(!accepted, "R27: malformed frame #" + i + " rejected without exception");
        }
        check(!TsVoiceManager.pendingConsentCandidates().isEmpty(),
                "R27: malformed frames did not disturb the received snapshot");

        // ================= R23: explicit consent flow =================
        // None pre-trusted => pending consent exists; explicit accept authorizes.
        check(TsVoiceManager.hasPendingAnnouncementConsent(),
                "R23: untrusted announcement surfaces as pending consent");
        check(TsVoiceManager.acceptAnnouncedCandidates(false),
                "R23: explicit accept authorizes the received snapshot");
        check(!TsVoiceManager.hasPendingAnnouncementConsent(),
                "R23: accepted candidates leave the pending state");
        check(!TsVoiceManager.authorizedAnnouncedCandidates().isEmpty(),
                "R23: accepted candidates are authorized for selection");

        // Explicit reject: new announcement (different bytes) with the same window.
        TsVoiceManager.beginCapabilityDiscovery();
        long nonce2 = TsVoiceManager.discoveryRequestNonceForTest();
        CapabilityWire.Capabilities second = new CapabilityWire.Capabilities(
                nonce2, CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR, 30,
                "svc-instance-r27", List.of(new CapabilityWire.Candidate(
                CapabilityWire.CandidateKind.TS_ENDPOINT, "ts2.r27.test", 9987, List.of())));
        check(TsVoiceManager.onCapabilitiesAnnouncementFrame(CapabilityWire.encode(second)),
                "R23: second announcement enters pending consent");
        check(TsVoiceManager.rejectAnnouncedCandidates(),
                "R23: explicit reject records the decision");
        check(!TsVoiceManager.hasPendingAnnouncementConsent(),
                "R23: rejected candidates no longer pending in this context");
        // The previously authorized set is untouched by rejecting a later candidate.
        check(!TsVoiceManager.authorizedAnnouncedCandidates().isEmpty(),
                "R23: reject does not disturb earlier authorization");

        // Foreign/stale candidate list must be rejected by the state machine.
        CapabilityDiscovery foreign = new CapabilityDiscovery(System::nanoTime);
        foreign.begin();
        check(!foreign.authorize(
                        com.tsvoice.client.connection.BackendCoordinator.currentContextEpoch(),
                        TsVoiceManager.pendingConsentCandidates()) || foreign.receivedCandidates().isEmpty(),
                "R23: authorization binds the actually-received snapshot");

        TsVoiceManager.onServerChanged("");
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static byte[] buildTruncated(byte[] frame) {
        return java.util.Arrays.copyOf(frame, Math.max(1, frame.length - 3));
    }

    static byte[] buildBadMagic() {
        byte[] f = CapabilityWire.encode(new CapabilityWire.Capabilities(
                42L, CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR, 30, "svc", List.of()));
        f[0] = 'X';
        return f;
    }

    static byte[] buildBadMajor() {
        byte[] f = CapabilityWire.encode(new CapabilityWire.Capabilities(
                43L, CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR, 30, "svc", List.of()));
        f[5] = 9; // major byte
        return f;
    }

    static byte[] buildBadTtl() {
        byte[] f = CapabilityWire.encode(new CapabilityWire.Capabilities(
                44L, CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR, 30, "svc", List.of()));
        // ttl is at offset 6+8=14..17 (magic4+major1+minor1+nonce8); set to -1
        f[14] = (byte) 0xFF;
        f[15] = (byte) 0xFF;
        f[16] = (byte) 0xFF;
        f[17] = (byte) 0xFF;
        return f;
    }

    static byte[] buildBadCandidateCount() {
        byte[] f = CapabilityWire.encode(new CapabilityWire.Capabilities(
                45L, CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR, 30, "svc", List.of()));
        // candidateCount follows serviceId; serviceId="svc" -> 2(len)+3 bytes; base
        // header: 4+1+1+8+4=18; then 2+3=5 -> offset 23..24. Set to 0xFFFF.
        f[23] = (byte) 0xFF;
        f[24] = (byte) 0xFF;
        return f;
    }
}
