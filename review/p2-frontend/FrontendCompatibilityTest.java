package com.tsvoice.client.frontend;

import java.util.List;

/**
 * Frontend self-check (P2): the inspect() probe must match the real 2.6.25
 * ABI (stub classes here model the javap-verified signatures), report critical
 * hooks separately from optional ones, and cache its result for acquire().
 */
public class FrontendCompatibilityTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Svc2625FrontendAdapter adapter = Svc2625FrontendAdapter.instance();

        // The stub world models the 2.6.25 ABI (MicThread 3-arg ctor,
        // ClientVoicechat.processSoundPacket, Fabric lifecycle events present).
        FrontendCapabilities capabilities = adapter.inspect();
        check(capabilities.micThreadUsable(), "MicThread 3-arg constructor probe succeeds (ABI match)");
        check(capabilities.playbackInjectionUsable(), "processSoundPacket probe succeeds (ABI match)");
        check(capabilities.clientStoppingHookPresent(), "CLIENT_STOPPING lifecycle events probe succeeds");
        check(capabilities.tsUsable() && capabilities.missingCriticalHooks().isEmpty(),
                "no critical hooks missing on a 2.6.25-shaped runtime");
        check(capabilities.missingOptionalHooks().isEmpty(), "no optional hooks missing");

        // The cached capabilities feed acquire(); a hook-less runtime must disable
        // acquisition even when the mic source would work.
        java.lang.reflect.Field capsField = Svc2625FrontendAdapter.class.getDeclaredField("lastCapabilities");
        capsField.setAccessible(true);
        FrontendCapabilities broken = new FrontendCapabilities(false, false, false,
                List.of("MicThread constructor: NoClassDefFoundError", "processSoundPacket: NoSuchMethodException"),
                List.of("CLIENT_STOPPING lifecycle events: ClassNotFoundException"));
        capsField.set(adapter, broken);
        check(adapter.acquire(new com.tsvoice.client.connection.VoiceState.SessionToken(1)) == null,
                "acquire refuses to run when critical hooks are missing");
        // inspect() re-evaluates against the live classpath rather than trusting the cache.
        FrontendCapabilities refreshed = adapter.inspect();
        check(refreshed.tsUsable(),
                "inspect re-evaluates against the live classpath");

        if (failures != 0) {
            throw new AssertionError(failures + " frontend compatibility checks failed");
        }
        System.out.println("Frontend compatibility regression: all checks passed");
    }
}
