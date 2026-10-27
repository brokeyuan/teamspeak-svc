package com.tsvoice.protocol;

/** Utilities for the unsigned 16-bit packet id space used by TS3. */
public final class Seq16 {
    public static final int MODULUS = 1 << 16;
    public static final int HALF_RANGE = MODULUS / 2;

    private Seq16() {
    }

    public static int mask(int id) {
        return id & 0xFFFF;
    }

    public static int increment(int id) {
        return mask(id + 1);
    }

    /** Signed distance from {@code reference} to {@code id}, in [-32768, 32767]. */
    public static int distance(int reference, int id) {
        int distance = mask(id) - mask(reference);
        if (distance >= HALF_RANGE) {
            distance -= MODULUS;
        } else if (distance < -HALF_RANGE) {
            distance += MODULUS;
        }
        return distance;
    }

    public static long generationOf(long absoluteId) {
        return Math.floorDiv(absoluteId, MODULUS);
    }

    public static long unwrap(int packetId, long highestAbsolute) {
        int id = mask(packetId);
        if (highestAbsolute < 0) {
            return id;
        }
        long reference = (highestAbsolute / MODULUS) * MODULUS + id;
        long distance = reference - highestAbsolute;
        if (distance > HALF_RANGE) {
            reference -= MODULUS;
        } else if (distance < -HALF_RANGE) {
            reference += MODULUS;
        }
        return reference;
    }
}
