package com.tsvoice.client.frontend;

import com.tsvoice.client.TeamspeakSvcClient;
import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.VoiceState;
import de.maxhenkel.voicechat.api.VoicechatClientApi;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T08 regression: the frontend lease is a single-owner resource. Acquire fails
 * atomically when the mic source fails, a second acquire never doubles the mic,
 * releasing frees the slot, SVC disconnect closes only self-owned mics (borrowed
 * native mics return to the SVC lifecycle), and the play() entry routes frames
 * through the SVC pipeline when the hooks exist.
 */
public class FrontendLeaseTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static final class StubMic implements FrontendLease.MicHandle {
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void close() {
            closed.set(true);
            alive.set(false);
        }
    }

    static VoicechatClientApi nullApi() {
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class}, (p, m, a) -> null);
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.api = nullApi();
        Svc2625FrontendAdapter adapter = Svc2625FrontendAdapter.instance();
        adapter.setMicSource(new Svc2625FrontendAdapter.MicSource() {
            @Override
            public FrontendLease.MicHandle borrowNativeMic() {
                return null; // no native mic: isolated path
            }

            @Override
            public FrontendLease.MicHandle createIsolatedMic() {
                return new StubMic();
            }
        });
        VoiceState.SessionToken token = BackendCoordinator.beginSession();

        // F1: inspection with the real SVC stub classes present -> usable
        FrontendCapabilities caps = adapter.inspect();
        check(caps.tsUsable(), "F1: SVC stubs satisfy the critical hooks");

        // F2: acquire -> single ownership; second acquire rejected; release frees
        FrontendLease lease = adapter.acquire(token);
        check(lease != null, "F2: first acquire succeeds");
        check(lease != null && !lease.isBorrowedFromNativeSvc(), "F2: isolated mic is self-owned");
        check(adapter.acquire(token) == null, "F2: second acquire while owned is rejected (single mic)");
        adapter.release(lease);
        check(lease.isClosed(), "F2: release closes the lease");
        FrontendLease lease2 = adapter.acquire(token);
        check(lease2 != null, "F2: acquire works again after release");
        adapter.release(lease2);

        // F3: mic source failure -> atomic release (next acquire works)
        adapter.setMicSource(new Svc2625FrontendAdapter.MicSource() {
            @Override
            public FrontendLease.MicHandle borrowNativeMic() {
                return null;
            }

            @Override
            public FrontendLease.MicHandle createIsolatedMic() {
                throw new IllegalStateException("device gone");
            }
        });
        check(adapter.acquire(token) == null, "F3: failing mic source yields null (FRONTEND_UNAVAILABLE)");
        adapter.setMicSource(new Svc2625FrontendAdapter.MicSource() {
            @Override
            public FrontendLease.MicHandle borrowNativeMic() {
                return null;
            }

            @Override
            public FrontendLease.MicHandle createIsolatedMic() {
                return new StubMic();
            }
        });
        FrontendLease recovered = adapter.acquire(token);
        check(recovered != null, "F3: reservation is released on failure (no deadlock)");

        // F4: SVC disconnect closes a self-owned mic; borrowed mic is only returned
        StubMic ownedMic = new StubMic();
        adapter.setMicSource(new Svc2625FrontendAdapter.MicSource() {
            @Override
            public FrontendLease.MicHandle borrowNativeMic() {
                return null;
            }

            @Override
            public FrontendLease.MicHandle createIsolatedMic() {
                return ownedMic;
            }
        });
        FrontendLease ownedLease = adapter.acquire(token);
        adapter.onSvcDisconnected();
        check(ownedLease != null && ownedLease.isClosed() && ownedMic.closed.get(),
                "F4: SVC disconnect closes the self-owned isolated mic");
        StubMic borrowedMic = new StubMic();
        adapter.setMicSource(new Svc2625FrontendAdapter.MicSource() {
            @Override
            public FrontendLease.MicHandle borrowNativeMic() {
                return borrowedMic;
            }

            @Override
            public FrontendLease.MicHandle createIsolatedMic() {
                throw new IllegalStateException("not reached");
            }
        });
        FrontendLease borrowedLease = adapter.acquire(token);
        check(borrowedLease != null && borrowedLease.isBorrowedFromNativeSvc(), "F4: borrowed lease flagged");
        adapter.onSvcDisconnected();
        check(borrowedLease.isClosed() && !borrowedMic.closed.get(),
                "F4: borrowed mic is returned to SVC lifecycle without closing");

        // F5: play() routes through the SVC pipeline and counts verified injections
        VoiceState.StreamKey stream = new VoiceState.StreamKey(
                new VoiceState.ParticipantKey("srv", "uid"), 1);
        long before = adapter.injectedFrames();
        check(adapter.play(stream, UUID.randomUUID(), new byte[]{1}, 5),
                "F5: play() injects via the SVC pipeline");
        check(adapter.injectedFrames() == before + 1, "F5: verified injection counted");

        // F6: play() refuses invalid input without fake success (null opus).
        check(!adapter.play(stream, UUID.randomUUID(), null, 5),
                "F6: play() with a null frame refuses");

        if (failures != 0) {
            throw new AssertionError(failures + " frontend lease checks failed");
        }
        System.out.println("Frontend lease regression: all checks passed");
    }
}
