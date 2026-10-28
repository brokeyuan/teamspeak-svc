package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

/**
 * R07 regression (T04): asynchronous tasks queued by a live TS session must be
 * re-validated at execution time. The controlled window is "old callback passes
 * the delivery check -> task queued on the main thread -> session/MC context
 * changes -> queued task runs": chat display and avatar side effects of the old
 * session never reach the new state.
 */
public class SessionConsumptionTest {
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

        // ---- C1: chat queued in session A must not print after the session swap ----
        ServerConfigStore.get("consume.mc").host = "127.0.0.1";
        TsVoiceManager.onServerChanged("consume.mc");
        TsVoiceManager.onSvcConnected();
        check(waitFor(() -> TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED, 5000),
                "C1: session A connects");
        Ts3VoiceClient sessionA = TsVoiceManager.currentClient();
        sessionA.textListener.accept("Alice", "hello from A");
        check(!net.minecraft.client.Minecraft.MESSAGES.isEmpty() == false
                        && net.minecraft.client.Minecraft.TASKS.size() == 1,
                "C1: chat task queued, not yet consumed");
        TsVoiceManager.disconnectTeamSpeak();
        TsVoiceManager.manualConnect(); // explicit reconnect exits PAUSED (D1/E14)
        check(waitFor(() -> TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED
                && TsVoiceManager.currentClient() != sessionA, 5000), "C1: session B replaces A");
        net.minecraft.client.Minecraft.TASKS.removeIf(r -> { r.run(); return true; });
        check(net.minecraft.client.Minecraft.MESSAGES.isEmpty(),
                "C1: queued chat from session A never reaches the new session's chat");

        // ---- C2: live session chat still flows (the gate is not over-broad) ----
        Ts3VoiceClient sessionB = TsVoiceManager.currentClient();
        sessionB.textListener.accept("Bob", "hello from B");
        check(net.minecraft.client.Minecraft.TASKS.size() == 1, "C2: live chat queued");
        net.minecraft.client.Minecraft.TASKS.removeIf(r -> { r.run(); return true; });
        check(net.minecraft.client.Minecraft.MESSAGES.size() == 1
                        && net.minecraft.client.Minecraft.MESSAGES.get(0).contains("Bob"),
                "C2: current session's chat reaches the MC chat");

        // ---- C3: avatar task submitted for a dying session aborts without side effects ----
        long abortedBefore = AvatarPipeline.abortedAvatarTasks();
        java.util.UUID playerUuid = java.util.UUID.randomUUID();
        AvatarPipeline.uploadAvatarAsync(sessionA, playerUuid, "ns", "127.0.0.1");
        // Deterministic for both scheduling outcomes: either the task aborts at its
        // initial gate, or it passes the gate and aborts after the namespace wait
        // once the session is gone.
        TsVoiceManager.disconnectTeamSpeak();
        check(waitFor(() -> AvatarPipeline.abortedAvatarTasks() > abortedBefore, 8000),
                "C3: avatar task for the dead session aborts (no side effects)");
        long abortedMid = AvatarPipeline.abortedAvatarTasks();

        // ---- C4: avatar task for the live session passes the gates (control) ----
        TsVoiceManager.manualConnect(); // C3's disconnect paused voice; reconnect for the control case
        check(waitFor(() -> TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED, 5000),
                "C4: control session connects");
        Ts3VoiceClient sessionC = TsVoiceManager.currentClient();
        AvatarPipeline.uploadAvatarAsync(sessionC, playerUuid, "ns", "127.0.0.1");
        check(waitFor(() -> AvatarPipeline.startedAvatarTasks() >= 1, 5000),
                "C4: live-session avatar task passes the initial gate");
        check(waitFor(() -> AvatarPipeline.abortedAvatarTasks() == abortedMid, 9000),
                "C4: live-session avatar task is not aborted while the session stays current");

        TsVoiceManager.disconnectTeamSpeak();
        if (failures != 0) {
            throw new AssertionError(failures + " session consumption checks failed");
        }
        System.out.println("Session consumption regression: all checks passed");
    }
}
