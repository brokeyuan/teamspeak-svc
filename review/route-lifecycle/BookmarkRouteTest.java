package com.tsvoice.client;

import com.tsvoice.client.config.TsBookmarkStore;
import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.TsChannelRoute;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * R02 regression (T02): explicit bookmarks must be able to obtain a confirmed
 * transmit route. The legacy SVC-group mapper never runs for bookmarks
 * (TS_NATIVE_FOLLOW); the initial bookmark channel is joined through the same
 * confirmation service, the server-confirmed self channel grants the route,
 * and voice + channel text read the exact same readiness.
 */
public class BookmarkRouteTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
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
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class},
                (p, m, a) -> m.getName().equals("createEncoder") ? encoder : null);
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.config().autoConnect = false;
        TeamspeakSvcClient.api = apiWithEncoder(); // no SVC group; encoder available for the TS path
        short[] pcm = new short[960];
        boolean[] cancelled = new boolean[1];

        TsBookmarkStore.put(new TsBookmarkStore.Bookmark("bm-main", "Main", "127.0.0.1",
                null, "Lobby", null, null, false));
        TsVoiceManager.onServerChanged("bm.mc");
        TsVoiceManager.onSvcConnected();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Channel data is available from the moment the session client exists, so the
        // production session tick (route + mapper) sees a consistent fixture.
        Ts3VoiceClient.connectScripts.addLast(c -> {
            c.channelNames.put(1, "Default");
            c.channelNames.put(5, "Lobby");
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // ---- B1: bookmark connect -> TS pinned; join requested but NOT sendable yet ----
        TsVoiceManager.manualConnect("bm-main");
        check(started.await(5, TimeUnit.SECONDS), "B1: bookmark connect reaches the network");
        release.countDown();
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "B1: bookmark session connects");
        var selection = BackendCoordinator.snapshot().selection();
        check(selection.intent() == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PINNED_TS
                        && "bookmark:bm-main".equals(selection.targetRef()),
                "B1: bookmark intent committed with its own target reference");
        Ts3VoiceClient c = TsVoiceManager.currentClient();
        check(waitFor(() -> c.joinCalls == 1 && c.lastJoinCid == 5, 5000),
                "B1: initial bookmark channel joined via the confirmation service");
        check(!BackendCoordinator.route().transmitReady(),
                "B1: join request alone does not grant transmit permission");
        check(waitFor(() -> TsVoiceManager.currentClient().joinCalls == 1, 100)
                && GroupChannelMapper.isReadyForVoice(c) == false,
                "B1: legacy group mapper does not drive bookmark channels");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 0 && !TsVoiceManager.sendChannelText("hi"),
                "B1: voice and channel text both blocked while the move is unconfirmed");

        // ---- B2: server-confirmed self channel grants the route (R02 core) ----
        c.simulateSelfMove(5);
        check(waitFor(() -> BackendCoordinator.route().transmitReady()
                        && Integer.valueOf(5).equals(BackendCoordinator.route().channelId())
                        && "fixture-server".equals(BackendCoordinator.route().authority()), 2000),
                "B2: confirmed self channel publishes authority+cid route");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 1 && TsVoiceManager.sendChannelText("hi"),
                "B2: bookmark mode can send voice and channel text without any SVC group");

        // ---- B3: unconfirmed admin move auto-follows (default channel is not a lock) ----
        c.channelNames.put(7, "Elsewhere");
        long epochBefore = BackendCoordinator.route().routeEpoch();
        c.simulateSelfMove(7);
        check(waitFor(() -> BackendCoordinator.route().transmitReady()
                        && Integer.valueOf(7).equals(BackendCoordinator.route().channelId())
                        && BackendCoordinator.route().routeEpoch() == epochBefore + 1, 2000),
                "B3: confirmed server move auto-follows and bumps the route epoch");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 2, "B3: follow mode keeps sending from the new confirmed channel");

        // ---- B4: locked target pauses on a foreign move and recovers on return ----
        BackendCoordinator.setChannelTarget(TsChannelRoute.currentToken(),
                com.tsvoice.client.connection.VoiceState.ChannelPolicy.LOCKED_TARGET, 5);
        c.simulateSelfMove(5); // (re)join the locked target: confirmed and sendable
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(c)
                        && Integer.valueOf(5).equals(BackendCoordinator.route().channelId()), 2000),
                "B4: locked target confirmed and sendable");
        c.simulateSelfMove(7); // admin moves the user away from the locked channel
        check(waitFor(() -> !TsChannelRoute.isReadyForVoice(c), 2000),
                "B4: admin move away from the locked channel pauses sending");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 2, "B4: locked mismatch consumes frames without TS send");
        c.simulateSelfMove(5);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(c)
                        && Integer.valueOf(5).equals(BackendCoordinator.route().channelId()), 2000),
                "B4: returning to the locked target re-arms sending");

        // ---- B5: a newer local move request invalidates stale observations ----
        BackendCoordinator.setChannelTarget(TsChannelRoute.currentToken(),
                com.tsvoice.client.connection.VoiceState.ChannelPolicy.TS_NATIVE_FOLLOW, null);
        TsChannelRoute.requestMove(c, 6, null);
        check(!BackendCoordinator.route().transmitReady(), "B5: requesting another channel revokes the permit");
        c.myChannel = 5; // late notification from the previous position
        TsChannelRoute.observeSelf(c);
        check(!BackendCoordinator.route().transmitReady(),
                "B5: stale observation for cid 5 does not approve while cid 6 is pending");
        c.myChannel = 6;
        TsChannelRoute.observeSelf(c);
        check(waitFor(() -> BackendCoordinator.route().transmitReady()
                        && Integer.valueOf(6).equals(BackendCoordinator.route().channelId()), 2000),
                "B5: the requested channel's own confirmation completes the move");

        // ---- B6: rejected move pauses with a reason and never guesses a fallback ----
        TsVoiceManager.disconnectTeamSpeak();
        TsBookmarkStore.put(new TsBookmarkStore.Bookmark("bm-bad", "Bad", "127.0.0.1",
                null, "Lobby", null, null, false));
        Ts3VoiceClient.connectScripts.addLast(c2 -> {
            c2.channelNames.put(1, "Default");
            c2.channelNames.put(5, "Lobby");
            c2.throwOnJoin = true;
        });
        TsVoiceManager.manualConnect("bm-bad");
        boolean b6Connected = waitForState(TsVoiceManager.State.CONNECTED, 5000);
        if (!b6Connected) {
            System.err.println("[dbg] B6 state=" + TsVoiceManager.getState()
                    + " error=" + TsVoiceManager.getLastError());
        }
        check(b6Connected && TsVoiceManager.currentClient() != null,
                "B6: second bookmark session connects");
        Ts3VoiceClient bad = TsVoiceManager.currentClient();
        if (bad == null) {
            throw new AssertionError("B6: session did not start: " + TsVoiceManager.getLastError());
        }
        check(waitFor(() -> TsChannelRoute.statusReason().contains("failed"), 5000),
                "B6: failed join request pauses with a reason, no guessed channel");
        check(!BackendCoordinator.route().transmitReady(), "B6: no transmit permission after the rejected move");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && bad.sent == 0, "B6: frames stay dropped after the rejected move");

        TsVoiceManager.disconnectTeamSpeak();
        if (failures != 0) {
            throw new AssertionError(failures + " bookmark route checks failed");
        }
        System.out.println("Bookmark route regression: all checks passed");
    }
}
