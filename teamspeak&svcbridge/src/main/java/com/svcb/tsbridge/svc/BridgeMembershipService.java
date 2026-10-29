package com.svcb.tsbridge.svc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, main-thread-published bridge membership state.
 *
 * <p>UNKNOWN deliberately keeps the last verified snapshot — always filtered to
 * the requested group, never expanding to other groups — and an EMPTY snapshot
 * is authoritative and targets nobody. Snapshots expire: past the TTL they
 * target nobody regardless of state, so a dead reader can never keep sending
 * audio to a stale audience.</p>
 */
public final class BridgeMembershipService {
    public enum Availability {
        UNKNOWN,
        EMPTY,
        MEMBERS
    }

    /** BeaconSender 每 2 秒采样；超过 5 秒未刷新的快照不得再用于发送。 */
    static final long SNAPSHOT_TTL_NANOS = 5_000_000_000L;

    private static volatile Map<UUID, UUID> lastVerified = Map.of();
    private static volatile boolean authoritative = false;
    private static volatile long sampledAtNanos;

    private BridgeMembershipService() {
    }

    /** Called only on the Minecraft main thread after a successful SVC state read. */
    public static synchronized void publish(Map<UUID, UUID> memberToGroup) {
        lastVerified = Map.copyOf(memberToGroup == null ? Map.of() : memberToGroup);
        authoritative = true;
        sampledAtNanos = System.nanoTime();
    }

    /** Called when reflection or SVC state cannot be read; never expands the audience. */
    public static synchronized void markUnknown() {
        authoritative = false;
    }

    public static synchronized void reset() {
        lastVerified = Map.of();
        authoritative = false;
        sampledAtNanos = 0L;
    }

    public static synchronized Availability availability() {
        if (!authoritative || expired()) {
            return Availability.UNKNOWN;
        }
        return lastVerified.isEmpty() ? Availability.EMPTY : Availability.MEMBERS;
    }

    /**
     * Returns the members of the requested group from the last verified snapshot.
     * UNKNOWN still filters by group; an expired snapshot targets nobody.
     */
    public static synchronized List<UUID> getBridgeGroupMembers(UUID bridgeGroupId) {
        if (bridgeGroupId == null || expired()) {
            return List.of();
        }
        List<UUID> members = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : lastVerified.entrySet()) {
            if (bridgeGroupId.equals(entry.getValue())) {
                members.add(entry.getKey());
            }
        }
        return List.copyOf(members);
    }

    private static boolean expired() {
        return sampledAtNanos == 0L
                || System.nanoTime() - sampledAtNanos > SNAPSHOT_TTL_NANOS;
    }
}
