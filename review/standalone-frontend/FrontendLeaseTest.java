package com.tsvoice.client.frontend;

import com.tsvoice.client.TeamspeakSvcClient;
import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.VoiceState;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.voice.client.AudioChannel;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import de.maxhenkel.voicechat.voice.client.ClientVoicechat;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T08/N2 regression: the frontend lease is a single-owner resource and the TS
 * playback path truly bypasses the native null-connection gate.
 *
 * N2 additions (D01): the SVC stub's processSoundPacket now replicates the real
 * 2.6.25 bytecode's null-connection early exit; play() must NOT depend on it —
 * it owns the per-stream AudioChannel directly (null InitializationData, which
 * the real bytecode never reads) and reports attempts/enqueued/resource
 * failures separately.
 * N2 additions (D02): a dead lease is released BEFORE the new mic is created,
 * repeated recoveries never leak a capture owner, and the SVC connect/disconnect
 * handoff keeps exactly one live capture source at any moment.
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

    static Svc2625FrontendAdapter.MicSource isolatedSource(AtomicInteger created) {
        return new Svc2625FrontendAdapter.MicSource() {
            @Override
            public FrontendLease.MicHandle borrowNativeMic() {
                return null; // no native mic: isolated path
            }

            @Override
            public FrontendLease.MicHandle createIsolatedMic() {
                created.incrementAndGet();
                return new StubMic();
            }
        };
    }

    static VoicechatClientApi nullApi() {
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class}, (p, m, a) -> null);
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.api = nullApi();
        Svc2625FrontendAdapter adapter = Svc2625FrontendAdapter.instance();
        adapter.setMicSource(isolatedSource(new AtomicInteger()));
        VoiceState.SessionToken token = BackendCoordinator.beginSession();

        // F1: inspection with the real SVC stub classes present -> usable
        FrontendCapabilities caps = adapter.inspect();
        check(caps.tsUsable(), "F1: SVC stubs satisfy the critical hooks");
        check(caps.playbackInjectionUsable(), "F1: direct AudioChannel ownership is available");

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
        adapter.setMicSource(isolatedSource(new AtomicInteger()));
        FrontendLease recovered = adapter.acquire(token);
        check(recovered != null, "F3: reservation is released on failure (no deadlock)");
        adapter.release(recovered);

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
        adapter.release(borrowedLease);

        // ---- N2/D02: dead-lease recovery takes over ownership before creating
        // the replacement mic (the D02 probe reproduced the old create-then-discard
        // churn with the dead lease still occupying activeLease).
        AtomicInteger isolatedCreated = new AtomicInteger();
        adapter.setMicSource(isolatedSource(isolatedCreated));
        FrontendLease dying = adapter.acquire(token);
        check(dying != null && dying.micAlive(), "R1: healthy lease acquired");
        ((StubMic) fieldMic(dying)).alive.set(false); // the input device makes the mic exit
        check(dying.micAlive() == false && !dying.isClosed(),
                "R1: the lease still exists but its mic is dead (held/micAlive are distinct)");
        FrontendLease takeover = adapter.acquire(token);
        check(takeover != null, "R1: reacquire over a dead lease succeeds (D02 probe fix)");
        check(dying.isClosed(), "R1: the dead lease was released before the new mic was created");
        check(takeover.micAlive(), "R1: the replacement mic is alive, not created-then-closed");
        // exactly one live capture source: every mic created except the current one is closed
        FrontendLease churnA = takeover;
        for (int i = 0; i < 3; i++) { // repeated device death / recovery churn
            ((StubMic) fieldMic(churnA)).alive.set(false);
            FrontendLease next = adapter.acquire(token);
            check(next != null && next.micAlive() && churnA.isClosed(),
                    "R1: recovery cycle " + (i + 1) + " keeps exactly one live owner");
            churnA = next;
        }
        check(isolatedCreated.get() == 5 && churnA != null,
                "R1: each recovery created exactly one replacement mic (no churn leak)");

        // ---- N2/D02: SVC connect handoff — isolated lease yields to the native
        // mic when it becomes available; nothing happens while it is absent.
        AtomicBoolean nativeUp = new AtomicBoolean(false);
        adapter.setMicSource(new Svc2625FrontendAdapter.MicSource() {
            @Override
            public FrontendLease.MicHandle borrowNativeMic() {
                return nativeUp.get() ? new StubMic() : null;
            }

            @Override
            public FrontendLease.MicHandle createIsolatedMic() {
                return new StubMic();
            }
        });
        FrontendLease isolatedLease = adapter.acquire(token);
        check(isolatedLease != null && !isolatedLease.isBorrowedFromNativeSvc(),
                "R2: isolated lease while the native mic is down");
        adapter.onSvcConnected();
        check(!isolatedLease.isClosed(), "R2: no handoff while the native mic is still absent");
        nativeUp.set(true); // the SVC connection event / session tick re-runs the same probe
        adapter.onSvcConnected();
        check(isolatedLease.isClosed(),
                "R2: the isolated lease is explicitly handed over when the native mic appears");
        FrontendLease nativeLease = adapter.acquire(token);
        check(nativeLease != null && nativeLease.isBorrowedFromNativeSvc(),
                "R2: the reacquire after the handoff borrows the native mic (single capture source)");
        adapter.release(nativeLease);

        // ---- N2/D01: play() bypasses the native null-connection gate entirely.
        ClientVoicechat.processReceived.clear();
        ClientVoicechat.droppedNullConnection.set(0);
        ClientManager.present = true;
        AudioChannel.startedChannels.clear();
        VoiceState.StreamKey stream = new VoiceState.StreamKey(
                new VoiceState.ParticipantKey("srv", "uid"), 1);
        UUID channelUuid = UUID.randomUUID();
        check(adapter.play(stream, channelUuid, new byte[]{1}, 5),
                "F5: play() reports a completed enqueue on the owned channel");
        check(ClientVoicechat.processReceived.isEmpty()
                        && ClientVoicechat.droppedNullConnection.get() == 0,
                "F5: the native null-connection gate is never consulted (no silent drop)");
        check(waitFor(() -> AudioChannel.startedChannels.size() == 1
                        && AudioChannel.startedChannels.get(0).uuid.equals(channelUuid)),
                "F5: the owned AudioChannel for the stream's uuid was created and started");
        AudioChannel owned = AudioChannel.startedChannels.get(0);
        check(owned.initializationData == null,
                "F5: no InitializationData/secret was fabricated (real bytecode never reads it)");
        check(owned.queue.size() == 1 && adapter.ownedPlaybackChannel(channelUuid) == owned,
                "F5: the packet is queued on the owned channel and ownership is snapshotable");
        long attemptsBefore = playbackAttempts(adapter);
        check(adapter.play(stream, channelUuid, new byte[]{2}, 6),
                "F5: the second frame reuses the same owned channel");
        check(AudioChannel.startedChannels.size() == 1 && owned.queue.size() == 2,
                "F5: no duplicate channel per stream uuid");
        check(adapter.injectedFrames() >= 2, "F5: confirmed enqueues are counted");
        check(playbackAttempts(adapter) == attemptsBefore + 1,
                "F5: attempts/enqueued are tracked separately (N2 evidence grading)");
        adapter.retirePlaybackChannel(channelUuid);
        check(owned.isClosed() && adapter.ownedPlaybackChannel(channelUuid) == null,
                "F5: retiring the stream closes the owned channel (registry cleanup path)");

        // F5b: resource creation failure is reported, not faked as success.
        ClientManager.present = false;
        UUID failedUuid = UUID.randomUUID();
        check(!adapter.play(stream, failedUuid, new byte[]{3}, 7),
                "F5b: play() with a failing playback resource refuses (no fake success)");
        check(adapter.ownedPlaybackChannel(failedUuid) == null,
                "F5b: the failed resource is not cached as if it existed");
        ClientManager.present = true;
        check(adapter.play(stream, failedUuid, new byte[]{3}, 8),
                "F5b: playback recovers once the resource is available again");

        // F6: play() refuses invalid input without fake success (null opus).
        check(!adapter.play(stream, UUID.randomUUID(), null, 5),
                "F6: play() with a null frame refuses");

        if (failures != 0) {
            throw new AssertionError(failures + " frontend lease checks failed");
        }
        System.out.println("Frontend lease regression: all checks passed");
    }

    static boolean waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    static Object fieldMic(FrontendLease lease) throws Exception {
        java.lang.reflect.Field f = FrontendLease.class.getDeclaredField("mic");
        f.setAccessible(true);
        return f.get(lease);
    }

    static long playbackAttempts(Svc2625FrontendAdapter adapter) throws Exception {
        java.lang.reflect.Field f = Svc2625FrontendAdapter.class.getDeclaredField("playAttempts");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) f.get(adapter)).get();
    }
}
