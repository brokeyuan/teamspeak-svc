package com.tsvoice.client.ts3voice;

import java.util.Arrays;

/** Offline checks of the real RSA handshake path; no server connection. */
public final class RsaRegression {
    public static void main(String[] args) throws Exception {
        Ts3Identity identity = Ts3Identity.generate(0, 1);
        Ts3Crypt crypt = new Ts3Crypt(identity);
        byte[] request = new byte[1 + 64 + 64 + 4 + 100];
        Arrays.fill(request, (byte) 0x55);
        request[0] = 3;
        // x = 0x40,0x55...; n = 0x82,0x00...; level 0 -> y = 0x81 (high bit set).
        request[1] = (byte) 0x40;
        Arrays.fill(request, 65, 129, (byte) 0);
        request[128] = (byte) 0x82;
        java.nio.ByteBuffer.wrap(request, 129, 4).putInt(0);
        byte[] expectedEcho = Arrays.copyOfRange(request, 1, 233);

        byte[] response = crypt.processInit1(request);
        byte[] actualEcho = Arrays.copyOfRange(response, 5, 237);
        require(Arrays.equals(expectedEcho, actualEcho), "server echo must remain byte-for-byte unchanged");
        byte[] y = Arrays.copyOfRange(response, 237, 301);
        require(y.length == 64 && y[63] == (byte) 0x81 && y[62] == 0,
                "RSA result must be unsigned and left padded to 64 bytes");
        String invalid = crypt.cryptoInit(null, "AAAAAAAAAAAAAA==", identity.getPublicKeyString());
        require(invalid != null && invalid.contains("alpha"), "missing alpha is a controlled handshake error");
        String shortAlpha = crypt.cryptoInit("AA==", "AAAAAAAAAAAAAA==", identity.getPublicKeyString());
        require(shortAlpha != null && shortAlpha.contains("alpha"), "short alpha is rejected");
        System.out.println("RSA and handshake regression checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
