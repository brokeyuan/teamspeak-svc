package com.tsvoice.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** Reassembles authenticated TS3 command packets in a monotonic 64-bit sequence space. */
public final class BoundedCommandAssembler {
    public record Fragment(int packetId, long generation, boolean boundary,
                           boolean compressed, byte[] data) {
        public Fragment {
            if (packetId < 0 || packetId >= Seq16.MODULUS || generation < 0 || data == null) {
                throw new IllegalArgumentException("Invalid command fragment");
            }
        }
        long absoluteId() {
            return Math.addExact(Math.multiplyExact(generation, Seq16.MODULUS), packetId);
        }
    }

    @FunctionalInterface
    public interface CommandFactory {
        String create(List<byte[]> fragments, boolean compressed) throws Exception;
    }

    @FunctionalInterface
    public interface AckSender {
        void send(int packetId) throws Exception;
    }

    private final TreeMap<Long, Fragment> pending = new TreeMap<>();
    private final CommandFactory commandFactory;
    private final LongSupplier nanoTime;
    private final int receiveWindow;
    private final int maxPendingPackets;
    private final int maxPendingBytes;
    private final int maxCommandBytes;
    private final long maxGroupAgeNanos;
    private long nextAbsolute = -1;
    private long highestAbsolute = -1;
    private long waitingSince = -1;
    private long pendingBytes;
    private boolean initialBoundaryUnknown;

    public BoundedCommandAssembler(CommandFactory commandFactory, LongSupplier nanoTime) {
        this(commandFactory, nanoTime, 128, 256, 2 * 1024 * 1024,
                1024 * 1024, 5_000_000_000L);
    }

    public BoundedCommandAssembler(CommandFactory commandFactory, LongSupplier nanoTime,
                                   int receiveWindow, int maxPendingPackets, int maxPendingBytes,
                                   int maxCommandBytes, long maxGroupAgeNanos) {
        if (commandFactory == null || nanoTime == null || receiveWindow < 1
                || receiveWindow >= Seq16.HALF_RANGE || maxPendingPackets < 1
                || maxPendingBytes < 1 || maxCommandBytes < 1 || maxGroupAgeNanos < 1) {
            throw new IllegalArgumentException("Invalid assembler limits");
        }
        this.commandFactory = commandFactory;
        this.nanoTime = nanoTime;
        this.receiveWindow = receiveWindow;
        this.maxPendingPackets = maxPendingPackets;
        this.maxPendingBytes = maxPendingBytes;
        this.maxCommandBytes = maxCommandBytes;
        this.maxGroupAgeNanos = maxGroupAgeNanos;
    }

    public List<String> receive(Fragment fragment, AckSender acknowledgements) throws Exception {
        acknowledgements.send(fragment.packetId());
        long absolute = fragment.absoluteId();
        long now = nanoTime.getAsLong();
        if (nextAbsolute < 0) {
            nextAbsolute = absolute;
            highestAbsolute = absolute;
            waitingSince = now;
            initialBoundaryUnknown = fragment.boundary();
        }
        if (absolute < highestAbsolute - receiveWindow || absolute > highestAbsolute + receiveWindow
                || absolute < nextAbsolute && !initialBoundaryUnknown) {
            return List.of();
        }
        if (pending.containsKey(absolute) || fragment.data().length > maxCommandBytes) {
            return List.of();
        }
        if (pending.size() >= maxPendingPackets || pendingBytes + fragment.data().length > maxPendingBytes) {
            resynchronize(highestAbsolute + 1, now);
            return List.of();
        }
        pending.put(absolute, fragment);
        pendingBytes += fragment.data().length;
        highestAbsolute = Math.max(highestAbsolute, absolute);
        List<String> commands = new ArrayList<>();
        while (true) {
            String command = initialBoundaryUnknown ? tryInitialAssembly(now) : tryAssemble(now);
            if (command == null) {
                return commands;
            }
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
    }

    private String tryInitialAssembly(long now) throws Exception {
        if (pending.isEmpty()) {
            return null;
        }
        long start = pending.firstKey();
        if (!pending.get(start).boundary()) {
            if (now - waitingSince >= maxGroupAgeNanos) {
                resynchronize(highestAbsolute + 1, now);
                return "";
            }
            return null;
        }
        if (!pending.containsKey(start + 1)) {
            if (now - waitingSince >= maxGroupAgeNanos) {
                resynchronize(highestAbsolute + 1, now);
                return "";
            }
            return null;
        }
        return assembleFrom(start, now);
    }

    private String tryAssemble(long now) throws Exception {
        Fragment first = pending.get(nextAbsolute);
        if (first == null) {
            if (now - waitingSince >= maxGroupAgeNanos) {
                resynchronize(highestAbsolute + 1, now);
                return "";
            }
            return null;
        }
        if (!first.boundary()) {
            remove(nextAbsolute++);
            waitingSince = now;
            return commandFactory.create(List.of(first.data()), first.compressed());
        }
        return assembleFrom(nextAbsolute, now);
    }

    private String assembleFrom(long start, long now) throws Exception {
        List<Fragment> fragments = new ArrayList<>();
        long bytes = 0;
        long current = start;
        while (true) {
            Fragment fragment = pending.get(current);
            if (fragment == null) {
                if (now - waitingSince >= maxGroupAgeNanos) {
                    resynchronize(highestAbsolute + 1, now);
                    return "";
                }
                return null;
            }
            fragments.add(fragment);
            bytes += fragment.data().length;
            if (bytes > maxCommandBytes) {
                resynchronize(highestAbsolute + 1, now);
                return "";
            }
            if (fragment.boundary() && fragments.size() > 1) {
                for (Fragment part : fragments) {
                    remove(part.absoluteId());
                }
                nextAbsolute = current + 1;
                initialBoundaryUnknown = false;
                waitingSince = now;
                return commandFactory.create(fragments.stream().map(Fragment::data).toList(),
                        fragments.get(0).compressed());
            }
            current++;
        }
    }

    private void resynchronize(long next, long now) {
        pending.clear();
        pendingBytes = 0;
        nextAbsolute = next;
        waitingSince = now;
        initialBoundaryUnknown = false;
    }

    private void remove(long absolute) {
        Fragment removed = pending.remove(absolute);
        if (removed != null) {
            pendingBytes -= removed.data().length;
        }
    }

    public int pendingPackets() {
        return pending.size();
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    public void clear() {
        pending.clear();
        pendingBytes = 0;
        nextAbsolute = -1;
        highestAbsolute = -1;
        waitingSince = -1;
        initialBoundaryUnknown = false;
    }
}
