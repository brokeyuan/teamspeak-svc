package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * F02 regression: while a TS route is selected, the microphone event must be
 * cancelled before any encoding/sending work, so no failure mode can leak the
 * same speech into the SVC network. Without a TS selection the SVC native path
 * must stay untouched.
 */
public class MicRedirectorRegression {
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

    static VoicechatClientApi api(final OpusEncoder encoder, final RuntimeException onCreate) {
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class}, (p, m, a) -> {
                    if (m.getName().equals("createEncoder")) {
                        if (onCreate != null) {
                            throw onCreate;
                        }
                        return encoder;
                    }
                    return null;
                });
    }

    static OpusEncoder encoder(final byte[] out, final RuntimeException onEncode, final boolean[] called) {
        return (OpusEncoder) Proxy.newProxyInstance(OpusEncoder.class.getClassLoader(),
                new Class<?>[]{OpusEncoder.class}, (p, m, a) -> {
                    if (m.getName().equals("encode")) {
                        if (called != null) {
                            called[0] = true;
                        }
                        if (onEncode != null) {
                            throw onEncode;
                        }
                        return out;
                    }
                    if (m.getName().equals("isClosed")) {
                        return Boolean.FALSE;
                    }
                    return null;
                });
    }

    static void setClient(Ts3VoiceClient client) throws Exception {
        Field field = TsVoiceManager.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(null, client);
    }

    static void resetEncoderCache() throws Exception {
        Field field = MicRedirector.class.getDeclaredField("encoder");
        field.setAccessible(true);
        field.set(null, null);
    }

    public static void main(String[] args) throws Exception {
        ModConfig global = TeamspeakSvcClient.config();
        global.autoConnect = false;
        short[] pcm = new short[960];

        // TS not selected (no config): SVC native path must stay untouched.
        TsVoiceManager.onServerChanged("mic-none");
        boolean[] cancelled = new boolean[1];
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(!cancelled[0], "no TS selection: SVC native microphone path untouched");

        // TS selected (manual config) while the TS session is not connected yet:
        // the frame is consumed and dropped, never forwarded to SVC.
        ServerConfigStore.get("mic-none").host = "ts.local";
        TsVoiceManager.onServerChanged("mic-none");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "TS selected while session waiting: frame consumed without SVC leak");

        // TS client connected but channel mapping not confirmed: consume, no send.
        // (Control case: the old implementation already handled this correctly.)
        TeamspeakSvcClient.api = api(encoder(new byte[]{1, 2, 3}, null, null), null);
        Ts3VoiceClient client = new Ts3VoiceClient(new Ts3Identity(), "Test", "", false);
        client.connected = true;
        setClient(client);
        GroupChannelMapper.ready = false;
        long sentBefore = TsVoiceManager.getSentVoiceFrames();
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && TsVoiceManager.getSentVoiceFrames() == sentBefore,
                "unconfirmed channel mapping: frame consumed without TS send");

        // Encoder creation fails: event stays cancelled, SVC receives nothing.
        GroupChannelMapper.ready = true;
        resetEncoderCache();
        TeamspeakSvcClient.api = api(null, new IllegalStateException("encoder unavailable"));
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0], "encoder creation failure: no SVC leak");

        // Encoder returns null/empty: no SVC leak.
        resetEncoderCache();
        boolean[] encodeCalled = new boolean[1];
        TeamspeakSvcClient.api = api(encoder(null, null, encodeCalled), null);
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && encodeCalled[0], "null encoding: no SVC leak");
        encodeCalled[0] = false;
        TeamspeakSvcClient.api = api(encoder(new byte[0], null, encodeCalled), null);
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && encodeCalled[0], "empty encoding: no SVC leak");

        // Session drops between the connection check and the send: still no leak.
        resetEncoderCache();
        client.connectedScript = new Boolean[]{true, false};
        client.connectedCalls = 0;
        TeamspeakSvcClient.api = api(encoder(new byte[]{1, 2, 3}, null, null), null);
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && client.sent == 0, "session lost between checks: no SVC leak, no TS send");

        // sendVoice throws after successful encoding: frame is dropped, not leaked.
        Ts3VoiceClient failing = new Ts3VoiceClient(new Ts3Identity(), "Test", "", false);
        failing.connected = true;
        failing.throwOnSend = true;
        setClient(failing);
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && failing.sent == 0, "TS send failure: no SVC leak");

        // Encoder keeps throwing while PTT is held: every frame stays on the TS side.
        resetEncoderCache();
        TeamspeakSvcClient.api = api(encoder(null, new IllegalStateException("encoder died"), null), null);
        boolean allCancelled = true;
        for (int i = 0; i < 5; i++) {
            cancelled[0] = false;
            MicRedirector.onMicSound(event(pcm, cancelled));
            allCancelled &= cancelled[0];
        }
        check(allCancelled, "encoder failure across held PTT: no frame leaks to SVC");

        // Healthy path: one encoded frame reaches TS and the event is cancelled.
        resetEncoderCache();
        Ts3VoiceClient healthy = new Ts3VoiceClient(new Ts3Identity(), "Test", "", false);
        healthy.connected = true;
        setClient(healthy);
        TeamspeakSvcClient.api = api(encoder(new byte[]{4, 5, 6}, null, null), null);
        sentBefore = TsVoiceManager.getSentVoiceFrames();
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && TsVoiceManager.getSentVoiceFrames() == sentBefore + 1 && healthy.sent == 1,
                "healthy path: exactly one frame encoded once and sent to TS");

        TsVoiceManager.manualDisconnect();
        if (failures != 0) {
            throw new AssertionError(failures + " mic redirect checks failed");
        }
        System.out.println("MicRedirector regression: all checks passed");
    }
}
