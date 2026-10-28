package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;

import java.util.Map;
import java.util.UUID;

/**
 * R10 regression (T07): every TS playback channel derives from
 * (serverUID, TS uid) — never from a trusted MC UUID — so TS audio can never
 * occupy a native SVC player's channel, two TS identities on the same MC UUID
 * stay separate, and an identity upgrade never resets the volume key. Ghost
 * state is keyed by the full participant identity, not the bare TS uid.
 */
public class PlaybackOwnershipTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static void frame(String authority, String tsUid, String nick, UUID mcUuid) {
        TsAudioInjector.onTsFrame(authority, tsUid, nick, mcUuid, 1,
                Ts3VoiceClient.CODEC_OPUS_VOICE, new byte[]{1, 2, 3});
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.api = (de.maxhenkel.voicechat.api.VoicechatClientApi) java.lang.reflect.Proxy
                .newProxyInstance(de.maxhenkel.voicechat.api.VoicechatClientApi.class.getClassLoader(),
                        new Class<?>[]{de.maxhenkel.voicechat.api.VoicechatClientApi.class},
                        (p2, m, a) -> null); // no SVC group; injector only needs a non-null api
        UUID mcUuidX = UUID.randomUUID();

        // ---- O1: the trusted MC branch plays on the participant channel ----
        frame("srv-A", "uid-x", "Alice", mcUuidX);
        UUID participant = TsAudioInjector.participantUuidFor("srv-A", "uid-x");
        check(ClientStateReflector.activeGhosts().containsKey(participant),
                "O1: trusted-MC participant injects on its participant channel");
        check(!ClientStateReflector.activeGhosts().containsKey(mcUuidX),
                "O1: TS audio never occupies the native MC player's channel UUID");

        // ---- O2: the same TS uid on a different server is a distinct ghost ----
        frame("srv-B", "uid-x", "Alice", null);
        UUID participantB = TsAudioInjector.participantUuidFor("srv-B", "uid-x");
        check(ClientStateReflector.activeGhosts().containsKey(participantB),
                "O2: cross-server same-uid participant gets its own channel");
        check(ClientStateReflector.activeGhosts().containsKey(participant),
                "O2: the first server's ghost is untouched");

        // ---- O3: identity upgrade keeps the volume key (no channel reset) ----
        frame("srv-C", "uid-up", "Bob", null);
        UUID upgradeChannel = TsAudioInjector.participantUuidFor("srv-C", "uid-up");
        check(ClientStateReflector.activeGhosts().containsKey(upgradeChannel),
                "O3: unknown identity plays on the participant channel");
        UUID mcUuidBob = UUID.randomUUID();
        frame("srv-C", "uid-up", "Bob", mcUuidBob);
        check(ClientStateReflector.activeGhosts().containsKey(upgradeChannel),
                "O3: identity upgrade keeps the participant channel (volume survives)");
        check(!ClientStateReflector.activeGhosts().containsKey(mcUuidBob),
                "O3: identity upgrade does not migrate onto the MC channel");

        // ---- O4: two TS identities sharing one MC UUID stay separate streams ----
        UUID sharedMc = UUID.randomUUID();
        frame("srv-D", "uid-1", "Clone A", sharedMc);
        frame("srv-D", "uid-2", "Clone B", sharedMc);
        UUID ch1 = TsAudioInjector.participantUuidFor("srv-D", "uid-1");
        UUID ch2 = TsAudioInjector.participantUuidFor("srv-D", "uid-2");
        check(ClientStateReflector.activeGhosts().containsKey(ch1)
                        && ClientStateReflector.activeGhosts().containsKey(ch2),
                "O4: both identities play on their own participant channels");

        // ---- O5: stream registry — a stale retire never kills a newer stream ----
        // Reflective so the suite compiles against code without the registry.
        Class<?> registryClass;
        try {
            registryClass = Class.forName("com.tsvoice.client.TsPlaybackRegistry");
        } catch (ClassNotFoundException e) {
            registryClass = null;
        }
        if (registryClass != null) {
            Class<?> pkClass = Class.forName("com.tsvoice.client.connection.VoiceState$ParticipantKey");
            Class<?> skClass = Class.forName("com.tsvoice.client.connection.VoiceState$StreamKey");
            Object pk = pkClass.getDeclaredConstructor(String.class, String.class)
                    .newInstance("srv-E", "uid-r");
            Object oldStream = skClass.getDeclaredConstructor(pkClass, long.class).newInstance(pk, 1L);
            Object newStream = skClass.getDeclaredConstructor(pkClass, long.class).newInstance(pk, 2L);
            UUID channel = TsAudioInjector.participantUuidFor("srv-E", "uid-r");
            Object oldChannel = new Object();
            Object newChannel = new Object();
            java.lang.reflect.Method register = registryClass
                    .getMethod("register", skClass, UUID.class, Object.class);
            java.lang.reflect.Method retire = registryClass
                    .getMethod("retire", skClass, UUID.class);
            java.lang.reflect.Method isCurrent = registryClass
                    .getMethod("isCurrent", skClass, UUID.class);
            register.invoke(null, oldStream, channel, oldChannel);
            register.invoke(null, newStream, channel, newChannel);
            retire.invoke(null, oldStream, channel);
            check((boolean) isCurrent.invoke(null, newStream, channel),
                    "O5: a stale stream's retire cannot remove the newer stream");
            retire.invoke(null, newStream, channel);
            check(!(boolean) isCurrent.invoke(null, newStream, channel),
                    "O5: the current stream retires exactly its own registration");
        }

        // ---- O6: removeAll releases everything without leaking keys ----
        frame("srv-F", "uid-z", "Zed", null);
        TsAudioInjector.removeAll();
        check(ClientStateReflector.activeGhosts().isEmpty(),
                "O6: removeAll releases every ghost channel");

        if (failures != 0) {
            throw new AssertionError(failures + " playback ownership checks failed");
        }
        System.out.println("Playback ownership regression: all checks passed");
    }
}
