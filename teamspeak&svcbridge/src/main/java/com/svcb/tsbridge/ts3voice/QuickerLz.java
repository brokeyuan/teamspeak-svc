package com.svcb.tsbridge.ts3voice;

/** @deprecated Unified in ts3voice-core; retained only for source compatibility. */
@Deprecated(forRemoval = true)
public final class QuickerLz {
    private QuickerLz() {}

    public static byte[] decompress(byte[] data, int maxSize) throws Ts3VoiceException {
        try {
            return com.tsvoice.protocol.QuickerLz.decompress(data, maxSize);
        } catch (java.io.IOException e) {
            throw new Ts3VoiceException(e.getMessage(), e);
        }
    }
}
