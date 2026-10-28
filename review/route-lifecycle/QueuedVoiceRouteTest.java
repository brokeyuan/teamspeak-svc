package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.TsChannelRoute;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

/**
 * R08 regression (T03), manager level: every outbound mic frame is bound to
 * the route epoch of the same coordinator snapshot that granted the permit;
 * after a channel change the epoch moves and pre-switch frames cannot be sent
 * again. The real queue/writer linearization is covered by
 * ConnectionLinearizationTest (real Ts3Connection + blocking transport).
 */
public class QueuedVoiceRouteTest {
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

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.config().autoConnect = false;
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
        TeamspeakSvcClient.api = (VoicechatClientApi) Proxy.newProxyInstance(
                VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class},
                (p, m, a) -> m.getName().equals("createEncoder") ? encoder : null);
        short[] pcm = new short[960];
        boolean[] cancelled = new boolean[1];

        ServerConfigStore.get("queue.mc").host = "127.0.0.1";
        TsVoiceManager.onServerChanged("queue.mc");
        TsVoiceManager.onSvcConnected();
        check(waitFor(() -> TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED, 5000),
                "Q: legacy config session connects");

        Ts3VoiceClient c = TsVoiceManager.currentClient();
        c.channelNames.put(5, "Minecraft");
        check(waitFor(() -> c.joinCalls == 1 && c.lastJoinCid == 5, 5000),
                "Q: mapper requests the main channel move");
        c.simulateSelfMove(5);
        check(waitFor(() -> BackendCoordinator.route().transmitReady()
                && Integer.valueOf(5).equals(BackendCoordinator.route().channelId()), 2000),
                "Q: route confirmed at cid 5");

        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 1 && c.lastSendEpoch == BackendCoordinator.route().routeEpoch(),
                "Q: sent frame carries the current route epoch");

        // Server-initiated move under legacy mapping: the mapper pulls the user back
        // to the group target — the permit is revoked (epoch bump) until the
        // pull-back is confirmed by the server.
        long epochBefore = BackendCoordinator.route().routeEpoch();
        c.myChannel = 7; // admin moved the user without a local request
        GroupChannelMapper.tick(c);
        check(BackendCoordinator.route().routeEpoch() == epochBefore + 1 && c.joinCalls == 2
                        && c.lastJoinCid == 5 && !BackendCoordinator.route().transmitReady(),
                "Q: legacy pull-back revokes the permit and bumps the route epoch");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 1,
                "Q: frames during the pull-back are consumed, never sent");
        c.simulateSelfMove(5);
        check(waitFor(() -> BackendCoordinator.route().transmitReady()
                        && Integer.valueOf(5).equals(BackendCoordinator.route().channelId()), 2000),
                "Q: pull-back re-confirmed at the group target");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 2 && c.lastSendEpoch == BackendCoordinator.route().routeEpoch()
                        && c.droppedByEpoch == 0,
                "Q: frames after the pull-back carry the current epoch");

        // Local move request: permit revoked, legal target retargeted, not-ready
        // frames dropped fail-closed until the server confirms the new channel.
        TsChannelRoute.requestMove(c, 6, null);
        check(!BackendCoordinator.route().transmitReady(), "Q: requesting cid 6 revokes the permit");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 2,
                "Q: frames during an unconfirmed move are consumed, never sent");
        c.simulateSelfMove(6);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(c), 2000), "Q: move to cid 6 confirmed");
        cancelled[0] = false;
        MicRedirector.onMicSound(event(pcm, cancelled));
        check(cancelled[0] && c.sent == 3, "Q: sending resumes on the confirmed new channel");

        TsVoiceManager.disconnectTeamSpeak();
        if (failures != 0) {
            throw new AssertionError(failures + " queued voice route checks failed");
        }
        System.out.println("Queued voice route regression: all checks passed");
    }
}
