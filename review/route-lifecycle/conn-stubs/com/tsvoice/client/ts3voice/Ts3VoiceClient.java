package com.tsvoice.client.ts3voice;

/**
 * Connection-layer compile stub: Ts3Connection only touches the inbound
 * callback surface of the owning client. Full client behaviour is covered by
 * the manager-level suites (classes dir), not here.
 */
public class Ts3VoiceClient {

    public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("conn-linearization");

    void onCommand(String command, boolean lowPriority) {
    }

    void onVoicePacket(Ts3Packet packet) {
    }
}
