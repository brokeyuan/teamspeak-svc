package com.tsvoice.client.connection;

import com.tsvoice.protocol.CapabilityWire;

import java.lang.System;
import java.util.List;

/**
 * Discovery context binding and authorization flow (P4): stale-context
 * announcements and late authorizations are dropped, the collection window
 * expires deterministically, and the authorized-candidate inputs feed the
 * selection policy end to end.
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

        // Context mismatch dropped.
        discovery.onContextChanged(7);
        var announcement = new CapabilityWire.Capabilities(0xABC, 1, 0, 30, "gw",
                List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                        "voice.example.org", 9987, List.of())));
        check(!discovery.onAnnouncement(6, announcement), "announcement from an older context is dropped");
        check(discovery.onAnnouncement(7, announcement), "announcement for the current context is accepted");

        // Late authorization after a context change is refused.
        check(discovery.authorize(7, announcement.candidates()), "authorization in the same context succeeds");
        discovery.onContextChanged(8);
        check(!discovery.authorize(7, announcement.candidates()),
                "authorization for a stale context is refused");
        check(discovery.authorizedCandidates().isEmpty(), "context change clears authorized candidates");

        // Re-authorize, then exercise the policy end to end.
        discovery.onAnnouncement(8, announcement);
        check(discovery.authorize(8, announcement.candidates()), "re-authorization in the new context succeeds");
        check(discovery.hasAuthorizedTsRecommendation() && !discovery.hasAuthorizedTunnel(),
                "authorized TS endpoint detected, no tunnel");
        var withTunnel = new CapabilityWire.Capabilities(0xABD, 1, 0, 30, "gw",
                List.of(new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TS_ENDPOINT,
                                "voice.example.org", 9987, List.of()),
                        new CapabilityWire.Candidate(CapabilityWire.CandidateKind.TUNNEL,
                                "tunnel.example.org", 443, List.of())));
        check(discovery.authorize(8, withTunnel.candidates()), "tunnel authorization accepted");
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

        if (failures != 0) {
            throw new AssertionError(failures + " discovery flow checks failed");
        }
        System.out.println("Discovery flow regression: all checks passed");
    }
}
