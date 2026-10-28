package com.tsvoice.client.connection;

import com.tsvoice.client.MicRedirector;
import com.tsvoice.client.TsVoiceManager;
import com.tsvoice.client.config.TsBookmarkStore;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * N1/D04+D05 regression: channel audience and queued-voice boundaries.
 *
 * D04: any observed channel that the policy does not allow must revoke the
 * transmit permit and advance the route epoch, so queued old-epoch frames can
 * no longer be written into the new channel.
 * D05: clientmove rejections, timeouts, ambiguous/missing initial targets and
 * late replies of superseded requests must produce a persistent transmission
 * block (not a one-tick pause), and recovery requires either the confirmed
 * target, a fresh explicit request, or the user's "stay in this channel"
 * action.
 *
 * Runs against the production TsChannelRoute/BackendCoordinator with the
 * scripted Ts3VoiceClient harness stub; the real connection-queue write-point
 * version of the D04 epoch revocation lives in RouteEpochRevocationTest.
 */
public class RouteMoveLifecycleTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    static boolean waitForState(TsVoiceManager.State expected, long timeoutMillis) throws Exception {
        return waitFor(() -> TsVoiceManager.getState() == expected, timeoutMillis);
    }

    static de.maxhenkel.voicechat.api.VoicechatClientApi apiWithEncoder() {
        OpusEncoder encoder = (OpusEncoder) Proxy.newProxyInstance(OpusEncoder.class.getClassLoader(),
                new Class<?>[]{OpusEncoder.class}, (p, m, a) -> {
                    if (m.getName().equals("encode")) {
                        return new byte[]{1};
                    }
                    if (m.getName().equals("isClosed")) {
                        return Boolean.FALSE;
                    }
                    return null;
                });
        return (de.maxhenkel.voicechat.api.VoicechatClientApi) Proxy.newProxyInstance(
                de.maxhenkel.voicechat.api.VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{de.maxhenkel.voicechat.api.VoicechatClientApi.class},
                (p, m, a) -> m.getName().equals("createEncoder") ? encoder : null);
    }

    static ClientSoundEvent event(short[] pcm, boolean[] cancelled) {
        return (ClientSoundEvent) Proxy.newProxyInstance(ClientSoundEvent.class.getClassLoader(),
                new Class<?>[]{ClientSoundEvent.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRawAudio":
                            return pcm;
                        case "cancel":
                            cancelled[0] = true;
                            return Boolean.TRUE;
                        case "isCancelled":
                            return cancelled[0];
                        default:
                            return null;
                    }
                });
    }

    /** Session fixture: bookmark + scripted channels; returns once CONNECTED. */
    static Ts3VoiceClient connectBookmark(String bookmarkId, String label, String channelName,
                                          java.util.function.Consumer<Ts3VoiceClient> extra)
            throws Exception {
        TsBookmarkStore.put(new TsBookmarkStore.Bookmark(bookmarkId, label, "127.0.0.1",
                null, channelName, null, null, false));
        TsVoiceManager.onServerChanged("move.mc");
        TsVoiceManager.onSvcConnected();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Ts3VoiceClient.connectScripts.addLast(c -> {
            c.channelNames.put(1, "Default");
            started.countDown();
            if (extra != null) {
                extra.accept(c);
            }
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TsVoiceManager.manualConnect(bookmarkId);
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("session never reached the network");
        }
        release.countDown();
        if (!waitForState(TsVoiceManager.State.CONNECTED, 5000)) {
            throw new AssertionError("session did not connect: " + TsVoiceManager.getLastError());
        }
        return TsVoiceManager.currentClient();
    }

    public static void main(String[] args) throws Exception {
        com.tsvoice.client.TeamspeakSvcClient.config().autoConnect = false;
        com.tsvoice.client.TeamspeakSvcClient.api = apiWithEncoder();
        short[] pcm = new short[960];
        boolean[] cancelled = new boolean[1];

        // ---- M1 (D04): admin move away from a LOCKED confirmed channel revokes
        // the permit and the epoch; queued old-epoch frames cannot be written.
        Ts3VoiceClient locked = connectBookmark("bm-move-lock", "Locked", "Lobby", c -> {
            c.channelNames.put(5, "Lobby");
            c.channelNames.put(7, "Elsewhere");
        });
        check(waitFor(() -> locked.joinCalls == 1 && locked.lastJoinCid == 5, 5000),
                "M1: initial bookmark channel joined via the confirmation service");
        locked.simulateSelfMove(5);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(locked), 5000),
                "M1: locked channel 5 confirmed and sendable");
        long readyEpoch = BackendCoordinator.route().routeEpoch();
        BackendCoordinator.setChannelTarget(TsChannelRoute.currentToken(),
                VoiceState.ChannelPolicy.LOCKED_TARGET, 5);
        locked.sendVoice(pcmToBytes(pcm), Ts3VoiceClient.CODEC_OPUS_VOICE, readyEpoch);
        check(locked.sent == 1, "M1: frame with the confirmed epoch is written");
        locked.simulateSelfMove(7); // admin move: observed channel 7 is illegal for LOCKED 5
        check(waitFor(() -> !BackendCoordinator.route().transmitReady(), 5000),
                "M1: the illegal observation revokes the transmit permit");
        check(BackendCoordinator.route().routeEpoch() != readyEpoch,
                "M1: the revocation advanced the route epoch");
        check(!TsChannelRoute.isReadyForVoice(locked), "M1: readiness reads the same revoked state");
        locked.sendVoice(pcmToBytes(pcm), Ts3VoiceClient.CODEC_OPUS_VOICE, readyEpoch);
        check(locked.droppedByEpoch >= 1,
                "M1: a queued old-epoch frame is dropped at the write point after the move");
        check(TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.BLOCKED_BY_POLICY,
                "M1: the move phase reports BLOCKED_BY_POLICY");
        locked.sendVoice(pcmToBytes(pcm), Ts3VoiceClient.CODEC_OPUS_VOICE,
                BackendCoordinator.route().routeEpoch());
        check(locked.sent == 1,
                "M1: even the new epoch cannot write while the policy target is unmet");
        locked.simulateSelfMove(5);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(locked), 5000),
                "M1: returning to the locked target re-arms sending");
        TsVoiceManager.disconnectTeamSpeak();

        // ---- M2 (D04): under TS_NATIVE_FOLLOW a server-confirmed admin move is
        // approved without any extra confirmation and keeps voice flowing.
        Ts3VoiceClient follow = connectBookmark("bm-move-follow", "Follow", "Default", c -> {
            c.channelNames.put(9, "Later");
        });
        follow.simulateSelfMove(1);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(follow), 5000),
                "M2: follow mode confirms the initial self channel");
        long followEpoch = BackendCoordinator.route().routeEpoch();
        follow.simulateSelfMove(9); // normal admin move under FOLLOW
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(follow)
                && Integer.valueOf(9).equals(BackendCoordinator.route().channelId())
                && BackendCoordinator.route().routeEpoch() == followEpoch + 1, 5000),
                "M2: confirmed admin move auto-follows and bumps the epoch");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && follow.sent >= 1,
                "M2: voice keeps flowing from the followed channel without user action");
        TsVoiceManager.disconnectTeamSpeak();

        // ---- M3 (D05): async rejection of a move blocks persistently; several
        // reconciliation cycles must not re-approve the previous channel.
        Ts3VoiceClient rejected = connectBookmark("bm-move-reject", "Rejected", "Default", null);
        rejected.simulateSelfMove(1);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(rejected), 5000),
                "M3: baseline channel confirmed");
        int sentBefore = rejected.sent;
        check(TsChannelRoute.requestMove(rejected, 6, "wrong-password"),
                "M3: move request submitted");
        java.util.concurrent.CompletableFuture<Ts3VoiceClient.MoveResult> f =
                rejected.lastMoveFuture;
        check(f != null, "M3: the move command is tracked for its result");
        check(TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.MOVING,
                "M3: the request is in flight with the permit revoked");
        f.complete(Ts3VoiceClient.moveError(512, "invalid channel password"));
        check(waitFor(() -> TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.FAILED, 5000),
                "M3: the async error reply enters the FAILED phase");
        check(TsChannelRoute.failureReason().contains("invalid channel password"),
                "M3: the server's rejection reason is preserved for the UI");
        for (int i = 0; i < 4; i++) { // several reconciliation cycles after the failure
            TsChannelRoute.observeSelf(rejected);
            Thread.sleep(30);
            TsChannelRoute.observeSelf(rejected);
        }
        check(!BackendCoordinator.route().transmitReady(),
                "M3: repeated reconciliation cycles never re-approve the previous channel");
        check(rejected.sent == sentBefore, "M3: voice stays dropped across the failure window");
        // A fresh explicit request to a reachable target recovers without user re-entry.
        check(TsChannelRoute.requestMove(rejected, 7, null), "M3: a new request supersedes the failure");
        rejected.lastMoveFuture.complete(Ts3VoiceClient.moveOk());
        rejected.simulateSelfMove(7);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(rejected)
                && Integer.valueOf(7).equals(BackendCoordinator.route().channelId()), 5000),
                "M3: recovery through a fresh confirmed move request");
        TsVoiceManager.disconnectTeamSpeak();

        // ---- M4 (D05): the user's explicit "stay in this channel" action resumes
        // sending after a failed move; nothing resumes automatically before it.
        Ts3VoiceClient stay = connectBookmark("bm-move-stay", "Stay", "Default", null);
        stay.simulateSelfMove(1);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(stay), 5000),
                "M4: baseline channel confirmed");
        check(TsChannelRoute.requestMove(stay, 6, null), "M4: move requested");
        stay.lastMoveFuture.complete(Ts3VoiceClient.moveError(256, "insufficient client permissions"));
        check(waitFor(() -> TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.FAILED, 5000),
                "M4: permission denial enters the FAILED phase");
        for (int i = 0; i < 4; i++) {
            TsChannelRoute.observeSelf(stay);
            Thread.sleep(30);
        }
        check(!BackendCoordinator.route().transmitReady(),
                "M4: still blocked before the user acts");
        check(TsChannelRoute.acceptCurrentChannel(stay),
                "M4: the explicit stay-here action is accepted");
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(stay), 5000),
                "M4: sending resumes in the current channel after the user action");
        TsVoiceManager.disconnectTeamSpeak();

        // ---- M5 (D05): a move request that never receives a reply times out into
        // FAILED instead of silently re-approving the previous channel.
        TsChannelRoute.pendingTimeoutMs = 150L;
        try {
            Ts3VoiceClient timeout = connectBookmark("bm-move-timeout", "Timeout", "Default", null);
            timeout.simulateSelfMove(1);
            check(waitFor(() -> TsChannelRoute.isReadyForVoice(timeout), 5000),
                    "M5: baseline channel confirmed");
            check(TsChannelRoute.requestMove(timeout, 6, null), "M5: move requested, no reply scripted");
            Thread.sleep(250);
            TsChannelRoute.observeSelf(timeout);
            check(TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.FAILED,
                    "M5: the unanswered request ends in FAILED, not a silent re-approval");
            check(!BackendCoordinator.route().transmitReady(),
                    "M5: no transmit permission after the timeout");
            for (int i = 0; i < 3; i++) {
                TsChannelRoute.observeSelf(timeout);
                Thread.sleep(20);
            }
            check(!BackendCoordinator.route().transmitReady(),
                    "M5: the timeout block survives further reconciliation cycles");
            TsVoiceManager.disconnectTeamSpeak();
        } finally {
            TsChannelRoute.pendingTimeoutMs = 10_000L;
        }

        // ---- M6 (D05): an ambiguous initial bookmark channel never grants a
        // permit; the stay-here action is the only local recovery.
        Ts3VoiceClient ambiguous = connectBookmark("bm-move-amb", "Ambiguous", "Lobby", c -> {
            c.channelNames.put(5, "Lobby");
            c.channelNames.put(6, "Lobby");
        });
        check(waitFor(() -> TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.FAILED, 5000),
                "M6: an ambiguous initial target enters the FAILED phase");
        check(TsChannelRoute.statusReason().contains("ambiguous"),
                "M6: the ambiguity is reported with the offending name");
        for (int i = 0; i < 4; i++) {
            TsChannelRoute.observeSelf(ambiguous);
            Thread.sleep(30);
        }
        check(!BackendCoordinator.route().transmitReady(),
                "M6: no transmit permission leaks while the target is ambiguous");
        check(TsChannelRoute.acceptCurrentChannel(ambiguous), "M6: stay-here accepted");
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(ambiguous), 5000),
                "M6: the user can resume in the current channel");
        TsVoiceManager.disconnectTeamSpeak();

        // ---- M7 (D05): a missing initial target stays RESOLVING without a permit.
        Ts3VoiceClient missing = connectBookmark("bm-move-miss", "Missing", "Nowhere", null);
        for (int i = 0; i < 4; i++) {
            TsChannelRoute.tick(missing);
            Thread.sleep(30);
        }
        check(TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.RESOLVING,
                "M7: a missing initial target stays in RESOLVING");
        check(!BackendCoordinator.route().transmitReady(),
                "M7: no transmit permission while the initial target is unresolved");
        TsVoiceManager.disconnectTeamSpeak();

        // ---- M8 (D05): request A then B; the late reply of A is discarded and
        // never completes or fails B; B's own reply plus self-confirm finishes.
        Ts3VoiceClient racer = connectBookmark("bm-move-race", "Race", "Default", null);
        racer.simulateSelfMove(1);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(racer), 5000),
                "M8: baseline channel confirmed");
        check(TsChannelRoute.requestMove(racer, 6, null), "M8: request A (cid 6)");
        java.util.concurrent.CompletableFuture<Ts3VoiceClient.MoveResult> fa = racer.lastMoveFuture;
        check(TsChannelRoute.requestMove(racer, 7, null), "M8: request B (cid 7) supersedes A");
        java.util.concurrent.CompletableFuture<Ts3VoiceClient.MoveResult> fb = racer.lastMoveFuture;
        check(fa != fb, "M8: each request carries its own result future");
        fa.complete(Ts3VoiceClient.moveError(518, "late rejection of the superseded request"));
        Thread.sleep(50);
        check(TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.MOVING,
                "M8: the superseded request's late rejection is discarded");
        fa.complete(Ts3VoiceClient.moveOk());
        Thread.sleep(50);
        check(TsChannelRoute.movePhase() == TsChannelRoute.MovePhase.MOVING,
                "M8: the superseded request's late success is discarded too");
        racer.myChannel = 6; // stale self position belonging to request A
        TsChannelRoute.observeSelf(racer);
        check(!BackendCoordinator.route().transmitReady(),
                "M8: the stale observation for A's channel does not approve");
        fb.complete(Ts3VoiceClient.moveOk());
        racer.myChannel = 7;
        TsChannelRoute.observeSelf(racer);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(racer)
                && Integer.valueOf(7).equals(BackendCoordinator.route().channelId()), 5000),
                "M8: B's own reply and self-confirmation complete the move");
        TsVoiceManager.disconnectTeamSpeak();

        if (failures != 0) {
            throw new AssertionError(failures + " route move lifecycle checks failed");
        }
        System.out.println("Route move lifecycle regression: all checks passed");
    }

    static byte[] pcmToBytes(short[] pcm) {
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = (byte) pcm[i];
        }
        return out;
    }
}
