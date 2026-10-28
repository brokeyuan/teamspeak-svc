package com.tsvoice.client.frontend;

import com.tsvoice.client.connection.VoiceState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Frontend mic lease ownership (P2): one owner at a time, close is idempotent,
 * an SVC disconnect closes only the lease's own (created) mic while a borrowed
 * native mic is returned untouched, and critical-hook failure disables
 * acquisition entirely.
 */
public class FrontendOwnershipTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** Fake mic source: records borrow/create/close and can hand out borrowed handles. */
    static final class FakeMicSource implements Svc2625FrontendAdapter.MicSource {
        final List<String> closedHandles = new ArrayList<>();
        int created;
        int borrowedGiven;
        boolean nativePresent;

        FrontendLease.MicHandle handle(String id) {
            return new FrontendLease.MicHandle() {
                @Override
                public boolean isAlive() {
                    return !closedHandles.contains(id);
                }

                @Override
                public void close() {
                    closedHandles.add(id);
                }
            };
        }

        @Override
        public FrontendLease.MicHandle borrowNativeMic() {
            if (!nativePresent) {
                return null;
            }
            String id = "native-" + borrowedGiven++;
            return handle(id);
        }

        @Override
        public FrontendLease.MicHandle createIsolatedMic() {
            String id = "isolated-" + created++;
            return handle(id);
        }
    }

    static Svc2625FrontendAdapter adapterWithUsableCaps(Svc2625FrontendAdapter.MicSource source) throws Exception {
        Svc2625FrontendAdapter adapter = new Svc2625FrontendAdapter(source);
        Field caps = Svc2625FrontendAdapter.class.getDeclaredField("lastCapabilities");
        caps.setAccessible(true);
        caps.set(adapter, new FrontendCapabilities(true, true, true, List.of(), List.of()));
        return adapter;
    }

    public static void main(String[] args) throws Exception {
        VoiceState.SessionToken token = new VoiceState.SessionToken(1);

        // Single ownership: the second acquire is rejected WITHOUT touching the
        // device at all (N2/D02: the healthy lease is detected before any mic
        // resource is created, so a rejection cannot churn the input device).
        FakeMicSource source1 = new FakeMicSource();
        Svc2625FrontendAdapter adapter = adapterWithUsableCaps(source1);
        FrontendLease lease1 = adapter.acquire(token);
        check(lease1 != null && lease1.micAlive(), "first acquire owns a live mic");
        check(lease1.isBorrowedFromNativeSvc() == false, "no native mic: lease created an isolated mic");
        FrontendLease rejected = adapter.acquire(new VoiceState.SessionToken(2));
        check(rejected == null, "second acquire is rejected while one lease is active");
        check(source1.created == 1,
                "the rejected acquire never creates a mic resource (no device churn)");
        check(lease1.micAlive(), "the active lease is unaffected by the rejected acquire");

        // Close is idempotent and frees ownership.
        lease1.close();
        lease1.close();
        check(lease1.isClosed(), "lease closed");
        check(source1.closedHandles.contains("isolated-0"), "close releases the owned mic");
        FrontendLease lease2 = adapter.acquire(token);
        check(lease2 != null && lease2.micAlive(), "re-acquire after close succeeds");

        // SVC disconnect: only the lease's OWN mic closes; a borrowed native mic
        // is returned to the SVC lifecycle untouched.
        FakeMicSource source2 = new FakeMicSource();
        source2.nativePresent = true;
        Svc2625FrontendAdapter adapter2 = adapterWithUsableCaps(source2);
        FrontendLease borrowedLease = adapter2.acquire(token);
        check(borrowedLease != null && borrowedLease.isBorrowedFromNativeSvc(),
                "native mic present: lease borrows instead of creating");
        borrowedLease.onSvcDisconnected();
        check(borrowedLease.isClosed() && !source2.closedHandles.contains("native-0"),
                "svc disconnect closes the lease but never closes the borrowed native mic");
        FrontendLease reAcquired = adapter2.acquire(token);
        check(reAcquired != null, "coordinator can re-acquire after svc disconnect");

        // Critical-hook failure disables acquisition without touching the mic source.
        FakeMicSource source3 = new FakeMicSource();
        Svc2625FrontendAdapter adapter3 = new Svc2625FrontendAdapter(source3);
        Field caps = Svc2625FrontendAdapter.class.getDeclaredField("lastCapabilities");
        caps.setAccessible(true);
        caps.set(adapter3, new FrontendCapabilities(false, true, true,
                List.of("MicThread constructor: NoClassDefFoundError"), List.of()));
        check(adapter3.acquire(token) == null && source3.created == 0,
                "critical hook failure disables acquisition (FRONTEND_UNAVAILABLE)");

        if (failures != 0) {
            throw new AssertionError(failures + " frontend ownership checks failed");
        }
        System.out.println("Frontend ownership regression: all checks passed");
    }
}
