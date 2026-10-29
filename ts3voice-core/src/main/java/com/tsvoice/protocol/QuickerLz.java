package com.tsvoice.protocol;

import java.io.IOException;
import java.util.Arrays;

/** TS3 level-1 QuickLZ decompression with strict declared-input and output bounds. */
public final class QuickerLz {
    private static final int TABLE_SIZE = 4096;

    private QuickerLz() {
    }

    public static byte[] decompress(byte[] data, int maxSize) throws IOException {
        if (data == null || data.length < 3 || maxSize < 1) {
            throw new IOException("Invalid QuickLZ input or output limit");
        }
        try {
            return decompressChecked(data, maxSize);
        } catch (IndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new IOException("Malformed QuickLZ stream", e);
        }
    }

    private static byte[] decompressChecked(byte[] data, int maxSize) throws IOException {
        int flags = data[0] & 0xFF;
        int level = (flags >> 2) & 0b11;
        if (level != 1) {
            throw new IOException("Unsupported QuickLZ level " + level);
        }
        int headerLength = (flags & 0x02) != 0 ? 9 : 3;
        if (data.length < headerLength) {
            throw new IOException("QuickLZ header is truncated");
        }
        int compressedSize = readSize(data, 1, (flags & 0x02) != 0);
        int decompressedSize = readSize(data, (flags & 0x02) != 0 ? 5 : 2, (flags & 0x02) != 0);
        if (compressedSize < headerLength || compressedSize > data.length) {
            throw new IOException("Invalid compressed length");
        }
        if (decompressedSize < 0 || decompressedSize > maxSize) {
            throw new IOException("QuickLZ output exceeds limit");
        }
        byte[] input = Arrays.copyOf(data, compressedSize);
        byte[] output = new byte[decompressedSize];
        if ((flags & 0x01) == 0) {
            if (compressedSize - headerLength != decompressedSize) {
                throw new IOException("QuickLZ stored lengths do not match");
            }
            System.arraycopy(input, headerLength, output, 0, decompressedSize);
            return output;
        }

        int[] hashtable = new int[TABLE_SIZE];
        long control = 1;
        int source = headerLength;
        int destination = 0;
        int nextHashed = 0;
        while (true) {
            if (control == 1) {
                require(input.length - source >= 4, "Missing control word");
                control = readUInt32LE(input, source);
                source += 4;
            }
            if ((control & 1) != 0) {
                control >>>= 1;
                require(source < input.length, "Missing match token");
                int next = input[source++] & 0xFF;
                require(source < input.length, "Missing match hash");
                int hash = (next >> 4) | (input[source++] & 0xFF) << 4;
                int matchLength = next & 0x0F;
                if (matchLength != 0) {
                    matchLength += 2;
                } else {
                    require(source < input.length, "Missing extended match length");
                    matchLength = input[source++] & 0xFF;
                }
                if (matchLength < 3) {
                    throw new IOException("QuickLZ match is shorter than three bytes");
                }
                destination = copyMatch(output, destination, hashtable[hash], matchLength);
                updateHashtable(output, nextHashed, destination + 1 - matchLength, hashtable);
                nextHashed = destination;
            } else if (destination >= Math.max(decompressedSize, 10) - 10) {
                while (destination < decompressedSize) {
                    if (control == 1) {
                        require(input.length - source >= 4, "Missing trailing control word");
                        source += 4;
                    }
                    control >>>= 1;
                    require(source < input.length, "Missing trailing literal");
                    output[destination++] = input[source++];
                }
                return output;
            } else {
                require(source < input.length, "Missing literal");
                output[destination++] = input[source++];
                control >>>= 1;
                int end = Math.max(destination - 2, 0);
                updateHashtable(output, nextHashed, end, hashtable);
                nextHashed = Math.max(nextHashed, end);
            }
        }
    }

    private static int readSize(byte[] data, int offset, boolean longSize) throws IOException {
        if (offset < 0 || data.length - offset < (longSize ? 4 : 1)) {
            throw new IOException("QuickLZ size field is truncated");
        }
        if (!longSize) {
            return data[offset] & 0xFF;
        }
        long size = readUInt32LE(data, offset);
        if (size > Integer.MAX_VALUE) {
            throw new IOException("QuickLZ size exceeds Java array limits");
        }
        return (int) size;
    }

    private static int copyMatch(byte[] output, int destination, int start, int length) throws IOException {
        if (destination < 0 || start < 0 || length < 3
                || destination + length > output.length || start + length > output.length) {
            throw new IOException("Malformed QuickLZ back reference");
        }
        for (int i = 0; i < length; i++) {
            output[destination + i] = output[start + i];
        }
        return destination + length;
    }

    private static void updateHashtable(byte[] output, int start, int end, int[] hashtable) throws IOException {
        if (start >= end) {
            return;
        }
        if (start < 0 || end < 0 || end + 2 > output.length) {
            throw new IOException("QuickLZ hash range is invalid");
        }
        int next = read24(output, start);
        hashtable[hash(next)] = start;
        for (int i = start + 1; i < end; i++) {
            next = (next >>> 8) | ((output[i + 2] & 0xFF) << 16);
            hashtable[hash(next)] = i;
        }
    }

    private static int read24(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16;
    }

    private static long readUInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8
                | (data[offset + 2] & 0xFFL) << 16 | (data[offset + 3] & 0xFFL) << 24;
    }

    private static int hash(int value) {
        return ((value >>> 12) ^ value) & 0xFFF;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}
