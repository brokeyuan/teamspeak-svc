package com.tsvoice.client;

import com.tsvoice.client.backend.SvcBackendAdapter;
import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.SelectionPolicy;
import com.tsvoice.client.config.TsBookmarkStore;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.voice.client.AudioChannel;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import de.maxhenkel.voicechat.voice.client.ClientVoicechat;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * N3/D03 regression: "close all voice" (OFF) covers the NATIVE SVC receive path,
 * not just the extension's own TS pipeline.
 *
 * The gate reads the coordinator's intent directly (mixin at
 * ClientVoicechat.processSoundPacket HEAD in production), so the assertions here
 * drive the same production predicate: OFF suppresses, PAUSED does not (it is
 * not an implicit OFF or an implicit SVC switch), user settings are untouched,
 * and queued native playback is flushed when OFF engages.
 */
public class OffGateTest {
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
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class}, (p, m, a) ->
                        m.getName().equals("createEncoder") ? encoder : null);
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.config().autoConnect = false;
        TeamspeakSvcClient.api = apiWithEncoder();

        // ---- O1: the gate follows the OFF intent, and only OFF ----
        BackendCoordinator.select(SelectionPolicy.IntentKind.PINNED_SVC, "vanilla-svc");
        check(!SvcBackendAdapter.isNativeReceiveSuppressed(),
                "O1: native SVC receive is open in SVC mode");
        TsVoiceManager.disableAllVoice();
        check(SvcBackendAdapter.isNativeReceiveSuppressed(),
                "O1: OFF engages the native receive gate");
        check(SvcBackendAdapter.isVoiceMuted(), "O1: OFF records the extension's own mute override");
        TsVoiceManager.switchToSvc();
        check(!SvcBackendAdapter.isNativeReceiveSuppressed(),
                "O1: switching back to SVC reopens the gate");
        check(!SvcBackendAdapter.isVoiceMuted(),
                "O1: recovery clears only the extension override, never user settings");

        // ---- O2: OFF flushes queued native playback through the real reflection path ----
        ClientVoicechat svcClient = ClientManager.getClient();
        UUID nativeUuid = UUID.randomUUID();
        AudioChannel queued = new AudioChannel(svcClient, null, svcClient.getSoundManager(), nativeUuid);
        svcClient.getAudioChannels().put(nativeUuid, queued);
        TsVoiceManager.disableAllVoice();
        check(queued.isClosed() && svcClient.getAudioChannels().isEmpty(),
                "O2: OFF closes and clears queued native playback channels");

        // ---- O3: PAUSED is not an implicit OFF — native SVC receive stays open ----
        TsBookmarkStore.put(new TsBookmarkStore.Bookmark("bm-off", "Off", "127.0.0.1",
                null, "Default", null, null, false));
        TsVoiceManager.onServerChanged("off.mc");
        TsVoiceManager.onSvcConnected();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Ts3VoiceClient.connectScripts.addLast(c -> {
            c.channelNames.put(1, "Default");
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TsVoiceManager.manualConnect("bm-off");
        check(started.await(5, TimeUnit.SECONDS), "O3: session reaches the network");
        release.countDown();
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "O3: TS session connects");
        // E5 single-backend listen default: TS selected -> native SVC receive
        // suppressed (the old always-open behavior is superseded by the
        // confirmed product rule; dual-listen would be a separate explicit option).
        check(SvcBackendAdapter.isNativeReceiveSuppressed(),
                "O3: TS mode suppresses native SVC receive (single-listen default)");
        TsVoiceManager.disconnectTeamSpeak();
        // F3 (confirmed rule): PAUSED has NO selected backend - "not chosen" is
        // not "chose SVC". Native SVC receive stays suppressed until the user
        // explicitly switches to SVC. PAUSED is not OFF (intent differs, user
        // settings untouched) but it also is not a silent SVC listen.
        check(BackendCoordinator.snapshot().selection().intent() == SelectionPolicy.IntentKind.PAUSED
                        && SvcBackendAdapter.isNativeReceiveSuppressed(),
                "O3: PAUSED with no selected backend does not listen to SVC (no implicit restore)");

        // ---- O4: OFF -> explicit TS reconnect reopens everything ----
        TsVoiceManager.disableAllVoice();
        check(SvcBackendAdapter.isNativeReceiveSuppressed() && TsVoiceManager.getState() == TsVoiceManager.State.VOICE_OFF,
                "O4: OFF engages and stops the TS session");
        TsVoiceManager.manualConnect("bm-off");
        // E5: reconnecting to TS keeps the native gate closed (TS is listening);
        // the OFF mute override itself is cleared (user settings untouched).
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000)
                        && SvcBackendAdapter.isNativeReceiveSuppressed()
                        && !SvcBackendAdapter.isVoiceMuted(),
                "O4: an explicit TS reconnect clears OFF but keeps single-listen (SVC gate closed)");

        // ---- O5: OFF survives an MC server change / announcement while off ----
        TsVoiceManager.disableAllVoice();
        TsVoiceManager.onServerChanged("elsewhere.mc");
        check(SvcBackendAdapter.isNativeReceiveSuppressed()
                        && BackendCoordinator.snapshot().selection().intent() == SelectionPolicy.IntentKind.OFF,
                "O5: OFF persists across a server change (gate still engaged)");

        // ---- O6: inbound TS frames are gated during OFF (TS side of the same op) ----
        TsVoiceManager.manualConnect("bm-off");
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "O6: session reconnected");
        TsVoiceManager.disableAllVoice();
        check(!TsVoiceManager.isSessionConnected(),
                "O6: OFF stops the TS session (inbound and outbound)");
        TsVoiceManager.switchToSvc();
        check(!SvcBackendAdapter.isNativeReceiveSuppressed() && !SvcBackendAdapter.isVoiceMuted(),
                "O6: switch back to SVC restores the pre-OFF receive state");

        if (failures != 0) {
            throw new AssertionError(failures + " off-gate checks failed");
        }
        System.out.println("Off gate regression: all checks passed");
    }
}
