package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * R01 route lifecycle regression (T01): the user-visible connection entries
 * (manual connect / disconnect) and the microphone gate must agree at every
 * point of the lifecycle. While a TS route is selected — including while the
 * session is connecting, reconnecting, paused after an explicit disconnect —
 * the SVC native path stays cancelled. Disconnecting must never silently hand
 * the microphone to SVC, and reconnecting must re-arm the TS gate before the
 * network is ready.
 */
public class RouteLifecycleTest {
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

    static VoicechatClientApi api(final OpusEncoder encoder) {
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class}, (p, m, a) ->
                        m.getName().equals("createEncoder") ? encoder : null);
    }

    static OpusEncoder encoder() {
        return (OpusEncoder) Proxy.newProxyInstance(OpusEncoder.class.getClassLoader(),
                new Class<?>[]{OpusEncoder.class}, (p, m, a) -> {
                    if (m.getName().equals("encode")) {
                        return new byte[]{1};
                    }
                    if (m.getName().equals("isClosed")) {
                        return Boolean.FALSE;
                    }
                    return null;
                });
    }

    static void resetEncoderCache() throws Exception {
        Field field = MicRedirector.class.getDeclaredField("encoder");
        field.setAccessible(true);
        field.set(null, null);
    }

    /** Queue the behavior of the next session connect attempt. */
    static void pushConnectScript(Consumer<Ts3VoiceClient> script) {
        Ts3VoiceClient.connectScripts.addLast(script);
    }

    static boolean waitForState(TsVoiceManager.State expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (TsVoiceManager.getState() == expected) {
                return true;
            }
            Thread.sleep(20);
        }
        return TsVoiceManager.getState() == expected;
    }

    public static void main(String[] args) throws Exception {
        ModConfig global = TeamspeakSvcClient.config();
        global.autoConnect = false;
        TeamspeakSvcClient.api = api(encoder());
        short[] pcm = new short[960];
        boolean[] cancelled = new boolean[1];

        // ---- A: no TS config anywhere: SVC native path untouched ----
        TsVoiceManager.onServerChanged("route.mc");
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(!cancelled[0], "A: no TS config: SVC native microphone path untouched");

        // ---- B: configured server + SVC connect -> TS selected while still connecting ----
        ServerConfigStore.get("route.mc").host = "127.0.0.1"; // loopback so connect() skips DNS
        CountDownLatch attempt1Started = new CountDownLatch(1);
        CountDownLatch releaseAttempt1 = new CountDownLatch(1);
        pushConnectScript(c -> {
            attempt1Started.countDown();
            try {
                releaseAttempt1.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TsVoiceManager.onSvcConnected();
        check(attempt1Started.await(5, TimeUnit.SECONDS) && waitForState(TsVoiceManager.State.CONNECTING, 1000),
                "B: configured server reaches CONNECTING");
        check(!TsVoiceManager.isSessionConnected() && !BackendCoordinator.route().transmitReady(),
                "B: session and route not ready while connecting");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "B: frame consumed while TS connecting (no SVC leak)");

        // ---- C: connect completes -> the main channel must be confirmed before sending ----
        releaseAttempt1.countDown();
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "C: session reaches CONNECTED");
        Ts3VoiceClient connected1 = TsVoiceManager.currentClient();
        connected1.channelNames.put(5, "Minecraft");
        GroupChannelMapper.tick(connected1); // production legacy mapper resolves the main target
        check(connected1.joinCalls == 1 && connected1.lastJoinCid == 5
                        && !BackendCoordinator.route().transmitReady(),
                "C: clientmove requested; no transmit permission before server confirmation");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && connected1.sent == 0,
                "C: frame consumed while the channel move is unconfirmed");
        connected1.simulateSelfMove(5);
        check(BackendCoordinator.route().transmitReady()
                        && Integer.valueOf(5).equals(BackendCoordinator.route().channelId()),
                "C: server-confirmed self channel grants the transmit route");
        long sentBefore = connected1.sent;
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && connected1.sent == sentBefore + 1,
                "C: confirmed session carries the frame to TS only");

        // ---- D: explicit disconnect must NOT hand the microphone to SVC ----
        TsVoiceManager.manualDisconnect();
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "D: after explicit disconnect the frame is dropped, not sent to SVC");

        // ---- E (R01 probe): reconnect right after disconnect -> gate must be re-armed ----
        CountDownLatch attempt2Started = new CountDownLatch(1);
        CountDownLatch releaseAttempt2 = new CountDownLatch(1);
        pushConnectScript(c -> {
            attempt2Started.countDown();
            try {
                releaseAttempt2.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TsVoiceManager.manualConnect();
        check(attempt2Started.await(5, TimeUnit.SECONDS) && waitForState(TsVoiceManager.State.CONNECTING, 1000),
                "E: reconnect after disconnect reaches CONNECTING");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "E (R01): reconnecting TS consumes frames, gate not stuck on SVC_NATIVE");

        // ---- F: reconnect completes -> the route is re-confirmed on the new session ----
        releaseAttempt2.countDown();
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "F: reconnect reaches CONNECTED");
        Ts3VoiceClient connected2 = TsVoiceManager.currentClient();
        connected2.channelNames.put(5, "Minecraft");
        GroupChannelMapper.tick(connected2);
        connected2.simulateSelfMove(5);
        check(BackendCoordinator.route().transmitReady()
                        && Integer.valueOf(5).equals(BackendCoordinator.route().channelId()),
                "F: reconnected session re-confirms the channel route");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && connected2.sent == 1,
                "F: reconnected session carries the frame to TS only");

        // ---- G: disconnect survives server changes and SVC events without leaking ----
        TsVoiceManager.manualDisconnect();
        TsVoiceManager.onServerChanged("other.mc");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "G1: disconnect persists across MC server change (no SVC leak)");
        TsVoiceManager.onServerChanged("route.mc");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "G2: returning to a configured server does not auto-resume voice");
        TsVoiceManager.onSvcDisconnected();
        TsVoiceManager.onSvcConnected();
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "G3: SVC reconnect events do not override the pause");
        TsVoiceManager.onServerChanged("route.mc");

        // ---- H: connect failure keeps the TS selection and never leaks to SVC ----
        pushConnectScript(c -> {
            throw new RuntimeException("simulated TS unreachable");
        });
        TsVoiceManager.manualConnect();
        check(waitForState(TsVoiceManager.State.RECONNECTING, 5000), "H: failed connect enters RECONNECTING");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "H: frames stay dropped while the TS session retries");

        // ---- I: switchToSvc is an explicit operation and respects the speaking boundary ----
        MicRedirector.speakingBoundaryMillis = 60_000; // deterministic suppression window
        TsVoiceManager.switchToSvc();
        check(!TsVoiceManager.isSessionConnected(), "I: switchToSvc closes the TS session");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "I1: SVC selected but speaking boundary not crossed: frame suppressed");
        MicRedirector.speakingBoundaryMillis = 250;
        Thread.sleep(300); // silence longer than the boundary window
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(!cancelled[0], "I2: after the silence gap the SVC native path passes through");
        TsVoiceManager.onSvcConnected();
        check(!TsVoiceManager.isSessionConnected(), "I3: PINNED_SVC is not overridden by SVC lifecycle events");

        // ---- J: OFF stops all voice send and inbound playback until the user re-enables ----
        TsVoiceManager.manualConnect();
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "J: explicit reconnect leaves VOICE_OFF");
        check(!com.tsvoice.client.backend.SvcBackendAdapter.isVoiceMuted(),
                "J: OFF override is cleared by the explicit reconnect");
        Ts3VoiceClient live = TsVoiceManager.currentClient();
        int playbackBefore = TsAudioInjector.onTsFrameCalls;
        live.voiceListener.accept(new Ts3VoiceClient.TsVoiceIn(1, Ts3VoiceClient.CODEC_OPUS_VOICE, 0, new byte[]{9}));
        check(TsAudioInjector.onTsFrameCalls == playbackBefore + 1,
                "J: inbound TS frame reaches playback while voice is on");
        TsVoiceManager.disableAllVoice();
        check(com.tsvoice.client.backend.SvcBackendAdapter.isVoiceMuted(),
                "J: OFF records the voice-muted override");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "J: OFF suppresses mic frames (no SVC, no TS)");
        live.voiceListener.accept(new Ts3VoiceClient.TsVoiceIn(1, Ts3VoiceClient.CODEC_OPUS_VOICE, 0, new byte[]{9}));
        check(TsAudioInjector.onTsFrameCalls == playbackBefore + 1,
                "J: late inbound frame after OFF does not reach playback");
        check(TsAudioInjector.removeAllCalls >= 1, "J: OFF releases TS playback resources");
        TsVoiceManager.onServerChanged("route.mc");
        TsVoiceManager.onSvcConnected();
        TsVoiceManager.onSvcDisconnected();
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && com.tsvoice.client.backend.SvcBackendAdapter.isVoiceMuted(),
                "J: OFF survives announcements, server changes and device events");

        // ---- K: PAUSED intent is observable and only an explicit reconnect exits it ----
        TsVoiceManager.onSvcConnected();
        TsVoiceManager.manualConnect();
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000),
                "K: explicit reconnect leaves VOICE_OFF with a live session");
        TsVoiceManager.disconnectTeamSpeak();
        var paused = BackendCoordinator.snapshot().selection();
        check(paused.intent() == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PAUSED
                        && paused.selectedBackend().isEmpty(),
                "K: disconnect enters PAUSED with no active backend");
        TsVoiceManager.onServerChanged("route.mc");
        check(BackendCoordinator.snapshot().selection().intent()
                        == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PAUSED,
                "K: server change does not clear the pause");
        TsVoiceManager.manualConnect();
        check(BackendCoordinator.snapshot().selection().intent()
                        == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PINNED_TS,
                "K: explicit reconnect exits the pause");
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "K: reconnect completes");

        // ---- L: config/lifecycle changes do not override the explicit selection ----
        TsVoiceManager.onManualConfigChanged();
        check(BackendCoordinator.snapshot().selection().intent()
                        == com.tsvoice.client.connection.SelectionPolicy.IntentKind.PINNED_TS,
                "L: activation re-runs do not steal an explicit selection");
        check(TsVoiceManager.isSessionConnected(), "L: unchanged config keeps the session alive");

        TsVoiceManager.disconnectTeamSpeak();
        if (failures != 0) {
            throw new AssertionError(failures + " route lifecycle checks failed");
        }
        System.out.println("Route lifecycle regression: all checks passed");
    }
}
