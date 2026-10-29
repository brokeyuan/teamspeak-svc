package com.tsvoice.client.connection;

import com.tsvoice.protocol.capability.CapabilityWire;

import java.lang.System;
import java.util.List;

/**
 * Discovery context binding, nonce/window/TTL lifecycle (T11/R05) and
 * authorization flow: stale-context or wrong-nonce announcements are dropped,
 * the collection window starts at begin() and expires deterministically even
 * without announcements, authorization binds to the actually received snapshot,
 * and expired candidates leave the snapshot.
 */
public class DiscoveryFlowTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        long[] clock = {1_000_000_000L};
        CapabilityDiscovery discovery = new CapabilityDiscovery(() -> clock[0]);

        // T11: the collection window starts at begin() even without announcements.
        discovery.onContextChanged(7);
        long nonce = discovery.begin();
        check(discovery.currentRequestNonce() == nonce, "begin() issues the request nonce");
        clock[0] += 2_000_000_000L;
        check(discovery.windowExpired(),
                "window expires without any announcement (no-announcement context terminates)");

        // Fresh window for the announcement scenarios.
        discovery.onContextChanged(7);
        nonce = discovery.begin();
        var announcement = new CapabilityWire.Capabilities(nonce, 1, 0, 30, "gw",
                List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                        "voice.example.org", 9987, List.of())));
        // Context mismatch dropped.
        check(!discovery.onAnnouncement(6, nonce, announcement),
                "announcement from an older context is dropped");
        // Wrong nonce dropped (T11).
        check(!discovery.onAnnouncement(7, nonce + 1, announcement),
                "announcement with a wrong nonce is dropped");
        check(discovery.onAnnouncement(7, nonce, announcement),
                "announcement for the current context and nonce is accepted");

        // Authorization binds to the actually received snapshot (T11).
        var received = discovery.receivedCandidates();
        check(!received.isEmpty() && discovery.authorizedCandidates().isEmpty(),
                "received announcements are not authorized until the player accepts");
        check(!discovery.authorize(7, List.of(new CapabilityDiscovery.AnnouncedCandidate(
                        announcement.candidates().get(0), clock[0] + 999_999_999_999L))),
                "authorization of an arbitrary (never received) list is refused");
        check(discovery.authorize(7, received), "authorization of the received snapshot succeeds");
        check(discovery.hasAuthorizedTsRecommendation() && !discovery.hasAuthorizedTunnel(),
                "authorized TS endpoint detected, no tunnel");

        // Late authorization after a context change is refused.
        discovery.onContextChanged(8);
        check(!discovery.authorize(7, received), "authorization for a stale context is refused");
        check(discovery.authorizedCandidates().isEmpty(), "context change clears authorized candidates");

        // Re-authorize in the new context, then exercise the policy end to end.
        nonce = discovery.begin();
        var announcement8 = new CapabilityWire.Capabilities(nonce, 1, 0, 30, "gw",
                List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                        "voice.example.org", 9987, List.of())));
        check(discovery.onAnnouncement(8, nonce, announcement8),
                "re-announcement in the new context accepted");
        var received8 = discovery.authorizedCandidates();
        check(discovery.authorize(8, received8), "re-authorization in the new context succeeds");
        var withTunnel = new CapabilityWire.Capabilities(nonce, 1, 0, 30, "gw",
                List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                                "voice.example.org", 9987, List.of()),
                        new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TUNNEL,
                                "tunnel.example.org", 443, List.of())));
        check(discovery.onAnnouncement(8, nonce, withTunnel), "tunnel announcement accepted");
        check(discovery.authorize(8, discovery.receivedCandidates()), "tunnel authorization accepted");
        check(discovery.hasAuthorizedTunnel(), "authorized tunnel detected");

        var decision = SelectionPolicy.candidates(SelectionPolicy.IntentKind.AUTO,
                SelectionPolicy.TransportPolicy.AUTO,
                discovery.hasAuthorizedTsRecommendation(), discovery.hasAuthorizedTunnel());
        check(decision.backend() == VoiceState.BackendKind.TS3 && decision.steps().size() == 2,
                "authorized recommendation+tunnel yields the two-step TS decision");

        // Window expiry is deterministic on the injected clock.
        clock[0] += 1_000_000_000L;
        check(!discovery.windowExpired(), "window not expired before 1.5s");
        clock[0] += 600_000_000L;
        check(discovery.windowExpired(), "window expires after 1.5s on the injected clock");

        // TTL: expiry removes candidates from the snapshot; TTL=0 yields nothing.
        CapabilityDiscovery ttlDiscovery = new CapabilityDiscovery(() -> clock[0]);
        ttlDiscovery.onContextChanged(9);
        long ttlNonce = ttlDiscovery.begin();
        var shortTtl = new CapabilityWire.Capabilities(ttlNonce, 1, 0, 2, "gw",
                List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                        "voice.example.org", 9987, List.of())));
        check(ttlDiscovery.onAnnouncement(9, ttlNonce, shortTtl), "short-TTL announcement accepted");
        check(ttlDiscovery.authorize(9, ttlDiscovery.receivedCandidates()),
                "short-TTL authorization accepted while live");
        check(ttlDiscovery.hasAuthorizedTsRecommendation(), "short-TTL candidate is live before expiry");
        clock[0] += 3_000_000_000L;
        check(!ttlDiscovery.hasAuthorizedTsRecommendation(),
                "expired announcement is removed from the snapshot (TTL enforced)");
        var zeroTtl = new CapabilityWire.Capabilities(ttlNonce, 1, 0, 0, "gw",
                List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                        "voice.example.org", 9987, List.of())));
        check(!ttlDiscovery.onAnnouncement(9, ttlNonce, zeroTtl),
                "TTL=0 announcement produces no connectable candidates");
        check(!ttlDiscovery.authorize(9, List.of(new CapabilityDiscovery.AnnouncedCandidate(
                        shortTtl.candidates().get(0), clock[0] - 1))),
                "authorization of expired candidates is refused");

        if (failures != 0) {
            throw new AssertionError(failures + " discovery flow checks failed");
        }
        System.out.println("Discovery flow regression: all checks passed");
    }
}
