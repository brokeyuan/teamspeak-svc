package com.tsvoice.client;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.voice.client.ClientManager;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

/**
 * Stable participant identity and channel ownership (P1):
 * the TS participant UUID is authority-scoped and clid-independent so personal
 * volume survives reconnects, and a stale stream's channel cleanup can never
 * close a newer stream's channel.
 */
public class StableParticipantVolumeTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static VoicechatClientApi api() {
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class}, (p, m, a) -> {
                    if (m.getName().equals("getGroup")) {
                        return null;
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    static Map<UUID, Object> channels() throws Exception {
        Object client = ClientManager.getClient();
        return (Map<UUID, Object>) client.getClass().getMethod("getAudioChannels").invoke(client);
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.api = api();
        ClientManager.current = new de.maxhenkel.voicechat.voice.client.ClientVoicechat();

        // Derivation: deterministic, authority-scoped, clid-independent.
        UUID a1 = TsAudioInjector.participantUuidFor("srv-A", "uid-x");
        check(a1.equals(TsAudioInjector.participantUuidFor("srv-A", "uid-x")),
                "same authority and uid derive the same stable UUID");
        check(!a1.equals(TsAudioInjector.participantUuidFor("srv-B", "uid-x")),
                "different TS servers isolate the same uid");
        check(!a1.equals(TsAudioInjector.participantUuidFor("srv-A", "uid-y")),
                "different uids derive different UUIDs");
        check(a1.equals(UUID.nameUUIDFromBytes(
                "tsvoice-participant-v2\0srv-A\0uid-x".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "UUID uses the documented tsvoice-participant-v2 derivation");

        // Injection: the ghost registers under the stable UUID and survives a
        // clid change / reconnect cycle without splitting the volume key.
        TsAudioInjector.onTsFrame("srv-A", "uid-x", "Alice", null, 1, 4, new byte[]{1, 2, 3});
        UUID injected = ClientStateReflector.activeGhosts().keySet().iterator().next();
        check(injected.equals(a1), "ghost registers under the stable participant UUID");
        Object channel1 = ClientStateReflector.ownedAudioChannel(a1);
        check(channel1 != null, "first injection registers the SVC channel object");

        // Sender reconnects (sequence reset marker) then keeps talking: same UUID.
        TsAudioInjector.onTsFrame("srv-A", "uid-x", "Alice", null, 7, 4, new byte[]{4});
        check(ClientStateReflector.activeGhosts().containsKey(a1)
                        && ClientStateReflector.ownedAudioChannel(a1) == channel1,
                "sequence reset keeps the same channel object");

        // Remove with the owned handle: the exact channel closes.
        TsAudioInjector.remove("uid-x");
        check(!channels().containsKey(a1), "owned close removes and closes the channel");
        check(!ClientStateReflector.activeGhosts().containsKey(a1), "ghost state removed");

        // Reconnect after removal: a fresh channel under the same stable UUID.
        TsAudioInjector.onTsFrame("srv-A", "uid-x", "Alice", null, 1, 4, new byte[]{5});
        check(ClientStateReflector.activeGhosts().containsKey(a1),
                "reconnect re-registers under the same stable UUID (volume persists)");
        Object channel2 = ClientStateReflector.ownedAudioChannel(a1);
        check(channel2 != null && channel2 != channel1, "new stream owns a new channel object");

        // Ownership guard: closing with a wrong or stale handle is a no-op.
        ClientStateReflector.closeAudioChannel(a1, new Object());
        check(channels().containsKey(a1) && ClientStateReflector.ownedAudioChannel(a1) == channel2,
                "close with a foreign handle never kills the live channel");
        // Stale-cleanup race: the old channel object re-inserted, cleanup must not
        // remove the NEW channel object registered for the same UUID.
        ClientStateReflector.closeAudioChannel(a1, channel1);
        check(ClientStateReflector.ownedAudioChannel(a1) == channel2,
                "stale cleanup with the old channel handle cannot remove the new channel");
        ClientStateReflector.closeAudioChannel(a1, channel2);
        check(!channels().containsKey(a1), "close with the correct owned handle removes the channel");

        // Authority isolation end to end (a server switch clears the session's
        // ghosts first, exactly as TsVoiceManager.stopSession does).
        TsAudioInjector.removeAll();
        TsAudioInjector.onTsFrame("srv-B", "uid-x", "Bob", null, 1, 4, new byte[]{6});
        UUID serverB = TsAudioInjector.participantUuidFor("srv-B", "uid-x");
        check(ClientStateReflector.activeGhosts().containsKey(serverB) && !serverB.equals(a1),
                "same uid on another server injects under an isolated UUID");

        TsAudioInjector.removeAll();
        if (failures != 0) {
            throw new AssertionError(failures + " stable participant volume checks failed");
        }
        System.out.println("Stable participant volume regression: all checks passed");
    }
}
