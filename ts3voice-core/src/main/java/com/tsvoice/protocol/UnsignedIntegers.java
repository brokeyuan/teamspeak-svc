package com.tsvoice.protocol;

import java.math.BigInteger;
import java.util.Arrays;

public final class UnsignedIntegers {
    private UnsignedIntegers() {
    }

    /** Encodes a nonnegative integer without a signed padding byte, left padded to width. */
    public static byte[] toFixedWidth(BigInteger value, int width) {
        if (value == null || value.signum() < 0 || width < 0) {
            throw new IllegalArgumentException("Value must be nonnegative and width valid");
        }
        byte[] encoded = value.toByteArray();
        if (encoded.length > 1 && encoded[0] == 0) {
            encoded = Arrays.copyOfRange(encoded, 1, encoded.length);
        }
        if (encoded.length > width) {
            throw new IllegalArgumentException("Value does not fit in " + width + " bytes");
        }
        byte[] result = new byte[width];
        System.arraycopy(encoded, 0, result, width - encoded.length, encoded.length);
        return result;
    }
}
