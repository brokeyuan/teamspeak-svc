package com.tsvoice.client.connection;

import java.util.List;

/**
 * P4 受控回退策略的确定性测试（fake clock 由退避的纯函数化替代）。
 */
public class FallbackPolicyTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        var policy = SelectionPolicy.class;

        // Candidate matrix (TR-16.1 core ordering).
        var autoNoRec = SelectionPolicy.candidates(SelectionPolicy.IntentKind.AUTO,
                SelectionPolicy.TransportPolicy.AUTO, false, false);
        check(autoNoRec.backend() == com.tsvoice.client.connection.VoiceState.BackendKind.SVC
                        && autoNoRec.steps().size() == 1,
                "AUTO without a recommendation goes straight to SVC");

        var autoRec = SelectionPolicy.candidates(SelectionPolicy.IntentKind.AUTO,
                SelectionPolicy.TransportPolicy.AUTO, true, true);
        check(autoRec.backend() == VoiceState.BackendKind.TS3
                        && autoRec.steps().equals(List.of(new SelectionPolicy.Step(VoiceState.BackendKind.TS3, false),
                        new SelectionPolicy.Step(VoiceState.BackendKind.TS3, true))),
                "AUTO with a recommendation and an authorized tunnel: UDP then tunnel");

        var autoRecNoTunnel = SelectionPolicy.candidates(SelectionPolicy.IntentKind.AUTO,
                SelectionPolicy.TransportPolicy.AUTO, true, false);
        check(autoRecNoTunnel.steps().size() == 1 && !autoRecNoTunnel.steps().get(0).viaTunnel(),
                "AUTO with a recommendation but no tunnel: UDP only");

        var pinnedTs = SelectionPolicy.candidates(SelectionPolicy.IntentKind.PINNED_TS,
                SelectionPolicy.TransportPolicy.AUTO, true, true);
        check(pinnedTs.backend() == VoiceState.BackendKind.TS3 && pinnedTs.steps().size() == 2,
                "PINNED_TS never lists SVC as a fallback");

        var pinnedUdpOnly = SelectionPolicy.candidates(SelectionPolicy.IntentKind.PINNED_TS,
                SelectionPolicy.TransportPolicy.UDP_ONLY, true, true);
        check(pinnedUdpOnly.steps().size() == 1 && !pinnedUdpOnly.steps().get(0).viaTunnel(),
                "UDP_ONLY forbids the tunnel step even when authorized");

        var pinnedTcpOnly = SelectionPolicy.candidates(SelectionPolicy.IntentKind.PINNED_TS,
                SelectionPolicy.TransportPolicy.TCP_ONLY, true, true);
        check(pinnedTcpOnly.steps().size() == 1 && pinnedTcpOnly.steps().get(0).viaTunnel(),
                "TCP_ONLY uses only the tunnel step");

        var tcpNoTunnel = SelectionPolicy.candidates(SelectionPolicy.IntentKind.PINNED_TS,
                SelectionPolicy.TransportPolicy.TCP_ONLY, true, false);
        check(tcpNoTunnel.steps().isEmpty(),
                "TCP_ONLY without an authorized tunnel has no candidates (no silent UDP downgrade)");

        var off = SelectionPolicy.candidates(SelectionPolicy.IntentKind.OFF,
                SelectionPolicy.TransportPolicy.AUTO, true, true);
        check(off.steps().isEmpty(), "explicit OFF never connects");

        // Failure gating (TR-16.2): only NETWORK_TIMEOUT advances UDP→tunnel.
        var tsUdp = new SelectionPolicy.Step(VoiceState.BackendKind.TS3, false);
        var tsTunnel = new SelectionPolicy.Step(VoiceState.BackendKind.TS3, true);
        check(SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.NETWORK_TIMEOUT, tsUdp, tsTunnel),
                "NETWORK_TIMEOUT on UDP may advance to the authorized tunnel");
        check(!SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.AUTH_DENIED, tsUdp, tsTunnel),
                "auth failure never switches transport (no bypass)");
        check(!SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.PERMISSION_DENIED, tsUdp, tsTunnel),
                "permission failure never switches transport");
        check(!SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.CHANNEL_PASSWORD, tsUdp, tsTunnel),
                "channel-password failure never switches transport");
        check(!SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.BANNED, tsUdp, tsTunnel),
                "ban never switches transport");
        check(!SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.UNSUPPORTED_PROTOCOL, tsUdp, tsTunnel),
                "protocol mismatch never switches transport");
        check(!SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.NETWORK_TIMEOUT, tsTunnel, tsUdp),
                "tunnel failure never falls back to UDP (no ping-pong)");
        check(!SelectionPolicy.mayAdvanceAfter(SelectionPolicy.FailureKind.NETWORK_TIMEOUT, tsUdp, null),
                "no next step means stop");

        // Backoff bounds: 2s..60s within ±20% jitter for any random sequence.
        var random = new java.util.Random(42);
        boolean bounded = true;
        for (int attempt = 0; attempt < 12; attempt++) {
            for (int sample = 0; sample < 50; sample++) {
                long backoff = SelectionPolicy.backoffMillis(attempt, random);
                long base = Math.min(2_000L << Math.min(attempt, 5), 60_000L);
                if (backoff < Math.max(1_000L, base * 8 / 10) || backoff > base * 12 / 10) {
                    bounded = false;
                }
            }
        }
        check(bounded, "backoff stays within base ±20% for every attempt");

        if (failures != 0) {
            throw new AssertionError(failures + " fallback policy checks failed");
        }
        System.out.println("Fallback policy regression: all checks passed");
    }
}
