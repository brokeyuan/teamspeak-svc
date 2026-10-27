package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.util.List;

/**
 * F03 regression: callbacks from a replaced TS session must not affect the new
 * session. Voice/text/membership callbacks are bound to their source client
 * and session, and dropped once that session is no longer current.
 */
public class SessionIsolationTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static Ts3VoiceClient currentSessionClient() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED) {
                List<Ts3VoiceClient> instances = Ts3VoiceClient.INSTANCES;
                if (!instances.isEmpty()) {
                    return instances.get(instances.size() - 1);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("session did not reach CONNECTED in time");
    }

    static boolean waitForBoolean(BooleanSupplier supplier) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (supplier.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return supplier.getAsBoolean();
    }

    interface BooleanSupplier {
        boolean getAsBoolean() throws InterruptedException;
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(java.nio.file.Path.of("review/p1-isolation/config/teamspeak-svc"));
        ModConfig global = TeamspeakSvcClient.config();
        global.autoConnect = false;
        GroupChannelMapper.ready = true;
        // onTsText only delivers chat when a local player exists.
        Minecraft.getInstance().player = new Minecraft.Player();

        // Session A: manual config for host A (loopback so connect() skips DNS).
        TsVoiceManager.onServerChanged("p1-isolation");
        ServerConfigStore.Entry entry = ServerConfigStore.get("p1-isolation");
        entry.host = "127.0.0.1";
        entry.port = 10011;
        TsVoiceManager.onSvcConnected();
        Ts3VoiceClient sessionA = currentSessionClient();
        sessionA.uidPrefix = "a";
        check(Ts3VoiceClient.INSTANCES.size() == 1, "session A client created");

        // Frames from the current session are delivered through its own client.
        TsAudioInjector.FRAMES.clear();
        sessionA.fireVoice(new Ts3VoiceClient.TsVoiceIn(7, 4, 1, new byte[]{1}));
        check(TsAudioInjector.FRAMES.equals(List.of("a7")),
                "current session voice frame resolves through its own client");

        // Replace the session with a new config (different port).
        entry.port = 10012;
        TsVoiceManager.onManualConfigChanged();
        final Ts3VoiceClient sessionB = currentSessionClient();
        sessionB.uidPrefix = "b";
        check(Ts3VoiceClient.INSTANCES.size() == 2, "session B client created after config change");

        TsAudioInjector.FRAMES.clear();
        TsAudioInjector.SYNC.clear();
        Minecraft.CHAT.clear();

        // Late callbacks from the replaced session A must be dropped entirely.
        sessionA.fireVoice(new Ts3VoiceClient.TsVoiceIn(7, 4, 2, new byte[]{2}));
        check(TsAudioInjector.FRAMES.isEmpty(),
                "stale session voice frame is not injected (and not resolved via the new client)");
        sessionA.fireText("old-invoker", "hello from old session");
        check(Minecraft.CHAT.isEmpty(), "stale session text does not reach the MC chat");
        sessionA.fireMembership(7);
        check(TsAudioInjector.SYNC.isEmpty(),
                "stale session membership callback does not sync ghost visibility");

        // The new session keeps working.
        sessionB.fireVoice(new Ts3VoiceClient.TsVoiceIn(9, 4, 1, new byte[]{3}));
        check(TsAudioInjector.FRAMES.equals(List.of("b9")),
                "new session voice frame still delivered after stale callbacks");
        sessionB.fireText("new-invoker", "hello from new session");
        check(Minecraft.CHAT.size() == 1, "new session text reaches the MC chat");

        // A late membership callback from A after B is active must not sync with A.
        sessionA.fireMembership(9);
        check(TsAudioInjector.SYNC.stream().noneMatch(id -> id.equals(sessionA.identity())),
                "stale membership callback never syncs with the replaced client");

        TsVoiceManager.manualDisconnect();
        check(waitForBoolean(() -> Ts3VoiceClient.INSTANCES.get(Ts3VoiceClient.INSTANCES.size() - 1).disconnected),
                "manual disconnect closes the active session client");

        if (failures != 0) {
            throw new AssertionError(failures + " session isolation checks failed");
        }
        System.out.println("Session isolation regression: all checks passed");
    }
}
