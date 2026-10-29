package com.tsvoice.client;

import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.TsProtocolException;

/**
 * S0.7/R26 regression: version compatibility uses an explicit semantic predicate
 * (2.6.1 / 2.60.x / garbage do NOT pass "2.6" prefix matching), and failure
 * classification keeps transport symptoms retryable while server protocol
 * rejections and internal errors are distinct kinds — unknown exceptions never
 * default to NETWORK_TIMEOUT.
 */
public class R26CompatibilityTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- Version predicate: semantic comparison, not prefix matching ----
        check(R26VersionProbe.supported("2.6.25"), "R26: target 2.6.25 is supported");
        check(R26VersionProbe.supported("2.6.20"), "R26: the declared minimum 2.6.20 is supported");
        check(R26VersionProbe.supported("2.6.27"), "R26: newer 2.6.27 is supported");
        check(!R26VersionProbe.supported("2.7.0"), "R26: an unverified newer series (2.7.0) is NOT accepted");
        check(!R26VersionProbe.supported("2.6.1"), "R26: 2.6.1 does NOT pass (old prefix bug)");
        check(!R26VersionProbe.supported("2.60.1"), "R26: 2.60.x does NOT pass (prefix bug)");
        check(!R26VersionProbe.supported("2.6.19"), "R26: 2.6.19 is below the declared minimum");
        check(!R26VersionProbe.supported("2.5.9"), "R26: 2.5.x is below the declared minimum");
        check(!R26VersionProbe.supported("garbage"), "R26: unparseable versions are rejected");
        check(!R26VersionProbe.supported("2.6"), "R26: two-part versions need an explicit patch (parse to 2.6.0 < 2.6.20)");
        check(!R26VersionProbe.supported(null), "R26: null versions are rejected");
        check(R26VersionProbe.supported("2.6.25+build.7"), "R26: build suffixes are stripped before comparison");

        // ---- Failure classification: type-first, structured protocol errors ----
        check(VoiceState.FailureKind.NETWORK_TIMEOUT
                        == TsVoiceManager.classifyConnectFailure(new java.net.SocketTimeoutException("timed out")),
                "R26: socket timeouts stay retryable NETWORK_TIMEOUT");
        check(VoiceState.FailureKind.NETWORK_TIMEOUT
                        == TsVoiceManager.classifyConnectFailure(new java.io.IOException("connection reset")),
                "R26: transport IOExceptions stay retryable");
        check(VoiceState.FailureKind.INTERNAL
                        == TsVoiceManager.classifyConnectFailure(new Exception("connection refused")),
                "R26: unknown plain exceptions classify as INTERNAL, not NETWORK_TIMEOUT");
        check(VoiceState.FailureKind.DNS_FAILED
                        == TsVoiceManager.classifyConnectFailure(new java.net.UnknownHostException("x")),
                "R26: DNS failures classify as DNS_FAILED");

        // Structured server protocol rejections: numeric id preserved, kind terminal.
        TsProtocolException auth = new TsProtocolException(
                "server error id=525 msg=invalid_server_password", 525, "invalid_server_password", null);
        check(VoiceState.FailureKind.AUTH_DENIED == TsVoiceManager.classifyConnectFailure(auth),
                "R26: structured invalid_server_password classifies as terminal AUTH_DENIED");
        check(auth.errorCode() == 525, "R26: the numeric TS error id is preserved on the exception");
        TsProtocolException chan = new TsProtocolException(
                "server error id=779 msg=invalid_channel_password", 779, "invalid_channel_password", null);
        check(VoiceState.FailureKind.CHANNEL_PASSWORD == TsVoiceManager.classifyConnectFailure(chan),
                "R26: structured channel-password rejection classifies as CHANNEL_PASSWORD");
        TsProtocolException unknownReject = new TsProtocolException(
                "server error id=999 msg=some_future_error", 999, "some_future_error", null);
        VoiceState.FailureKind unknownKind = TsVoiceManager.classifyConnectFailure(unknownReject);
        check(unknownKind != VoiceState.FailureKind.NETWORK_TIMEOUT,
                "R26: an unlisted server rejection is NOT treated as a network timeout");
        check(TsVoiceManager.isTerminalFailure(unknownKind),
                "R26: an unlisted server rejection terminates retrying (server said no)");

        // Unknown/internal exceptions: explicit INTERNAL, never NETWORK_TIMEOUT.
        check(VoiceState.FailureKind.INTERNAL
                        == TsVoiceManager.classifyConnectFailure(new NullPointerException("bug")),
                "R26: runtime defects classify as INTERNAL");
        check(VoiceState.FailureKind.INTERNAL
                        == TsVoiceManager.classifyConnectFailure(new IllegalStateException("state")),
                "R26: illegal states classify as INTERNAL");
        check(VoiceState.FailureKind.INTERNAL
                        == TsVoiceManager.classifyConnectFailure(new Exception("totally unknown")),
                "R26: unknown exceptions classify as INTERNAL (not NETWORK_TIMEOUT)");
        check(!TsVoiceManager.isTerminalFailure(VoiceState.FailureKind.INTERNAL),
                "R26: INTERNAL stays retryable but is never misread as UDP-unreachable");

        if (failures != 0) {
            throw new AssertionError(failures + " R26 checks failed");
        }
        System.out.println("R26 compatibility regression: all checks passed");
    }
}
