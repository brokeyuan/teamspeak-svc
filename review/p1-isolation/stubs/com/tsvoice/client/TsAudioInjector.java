package com.tsvoice.client;

import java.util.ArrayList;
import java.util.List;

/** Recording stub: captures injection and visibility-sync calls. */
public class TsAudioInjector {
    public static final List<String> FRAMES = new ArrayList<>();
    public static final List<String> SYNC = new ArrayList<>();

    public static void onTsFrame(String authority, String tsUniqueId, String nickname, java.util.UUID mcUuid,
                                 int sequence, int codec, byte[] opus) {
        FRAMES.add(tsUniqueId);
    }

    public static void syncAllGhostVisibility(com.tsvoice.client.ts3voice.Ts3VoiceClient client) {
        SYNC.add(client.identity());
    }

    public static void removeAll() {}
    public static void remove(String tsUniqueId) {}
    public static void reconcile(com.tsvoice.client.ts3voice.Ts3VoiceClient client) {}
    public static long getInjectedFrames() { return FRAMES.size(); }
}
