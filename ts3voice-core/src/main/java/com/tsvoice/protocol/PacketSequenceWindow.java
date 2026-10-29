package com.tsvoice.protocol;

import java.util.TreeSet;

/** Tracks authenticated packet ids in a bounded, monotonic 64-bit receive window. */
public final class PacketSequenceWindow {
    private final int window;
    private final TreeSet<Long> accepted = new TreeSet<>();
    private long highestAbsolute = -1;

    public PacketSequenceWindow() {
        this(128);
    }

    public PacketSequenceWindow(int window) {
        if (window < 1 || window >= Seq16.HALF_RANGE) {
            throw new IllegalArgumentException("Invalid receive window");
        }
        this.window = window;
    }

    /** Predicts a generation for authentication; a failed decryption never mutates this window. */
    public long generationFor(int packetId) {
        long absolute = absoluteFor(packetId);
        return absolute < 0 ? 0 : Seq16.generationOf(absolute);
    }

    /** Call only after successful authentication/decryption. */
    public boolean accept(int packetId) {
        long absolute = absoluteFor(packetId);
        if (absolute < highestAbsolute - window || !accepted.add(absolute)) {
            return false;
        }
        highestAbsolute = Math.max(highestAbsolute, absolute);
        accepted.headSet(highestAbsolute - window, false).clear();
        return true;
    }

    public long absoluteFor(int packetId) {
        return Seq16.unwrap(packetId, highestAbsolute);
    }

    public long highestAbsolute() {
        return highestAbsolute;
    }

    public int size() {
        return accepted.size();
    }

    public void clear() {
        accepted.clear();
        highestAbsolute = -1;
    }
}
