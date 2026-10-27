package com.svcb.tsbridge.svc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, main-thread-published bridge membership state.
 *
 * <p>UNKNOWN deliberately keeps the last verified snapshot instead of falling back
 * to “everyone”; an EMPTY snapshot is authoritative and targets nobody.</p>
 */
public final class BridgeMembershipService {
    public enum Availability {
        UNKNOWN,
        EMPTY,
        MEMBERS
    }

    private static volatile Map<UUID, UUID> lastVerified = Map.of();
    private static volatile boolean authoritative = false;

    private BridgeMembershipService() {
    }

    /** Called only on the Minecraft main thread after a successful SVC state read. */
    public static synchronized void publish(Map<UUID, UUID> memberToGroup) {
        lastVerified = Map.copyOf(memberToGroup == null ? Map.of() : memberToGroup);
        authoritative = true;
    }

    /** Called when reflection or SVC state cannot be read; never expands the audience. */
    public static synchronized void markUnknown() {
        authoritative = false;
    }

    public static synchronized void reset() {
        lastVerified = Map.of();
        authoritative = false;
    }

    public static synchronized Availability availability() {
        if (!authoritative) {
            return Availability.UNKNOWN;
        }
        return lastVerified.isEmpty() ? Availability.EMPTY : Availability.MEMBERS;
    }

    /** Returns the last verified snapshot while UNKNOWN; an authoritative empty snapshot returns empty. */
    public static synchronized List<UUID> getBridgeGroupMembers(UUID bridgeGroupId) {
        if (bridgeGroupId == null) {
            return List.of();
        }
        if (!authoritative) {
            return List.copyOf(lastVerified.keySet());
        }
        List<UUID> members = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : lastVerified.entrySet()) {
            if (bridgeGroupId.equals(entry.getValue())) {
                members.add(entry.getKey());
            }
        }
        return List.copyOf(members);
    }
}
