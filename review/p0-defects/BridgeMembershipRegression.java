package com.svcb.tsbridge.svc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F06 regression: an UNKNOWN read state or a stale snapshot must never expand
 * a group's audio audience to members of other groups, and a snapshot past its
 * TTL must stop being used for sending.
 */
public class BridgeMembershipRegression {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static void ageSnapshotBySeconds(long seconds) throws Exception {
        Field field = BridgeMembershipService.class.getDeclaredField("sampledAtNanos");
        field.setAccessible(true);
        long current = field.getLong(null);
        field.setLong(null, current - seconds * 1_000_000_000L);
    }

    public static void main(String[] args) throws Exception {
        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        BridgeMembershipService.reset();

        // Authoritative snapshot: per-group filtering.
        BridgeMembershipService.publish(Map.of(alice, groupA, bob, groupB));
        check(BridgeMembershipService.getBridgeGroupMembers(groupA).equals(List.of(alice)),
                "authoritative snapshot filters group A members");
        check(BridgeMembershipService.getBridgeGroupMembers(groupB).equals(List.of(bob)),
                "authoritative snapshot filters group B members");
        check(BridgeMembershipService.getBridgeGroupMembers(null).isEmpty(),
                "null group id targets nobody");
        check(BridgeMembershipService.availability() == BridgeMembershipService.Availability.MEMBERS,
                "populated snapshot reports MEMBERS");

        // UNKNOWN: keep the last verified snapshot but never expand the audience.
        BridgeMembershipService.markUnknown();
        check(BridgeMembershipService.getBridgeGroupMembers(groupA).equals(List.of(alice)),
                "UNKNOWN keeps last snapshot filtered to the requested group");
        check(!BridgeMembershipService.getBridgeGroupMembers(groupA).contains(bob),
                "UNKNOWN never includes members of another group");
        check(BridgeMembershipService.getBridgeGroupMembers(groupB).equals(List.of(bob)),
                "UNKNOWN keeps other groups filtered as well");

        // UNKNOWN past the snapshot TTL: stop sending entirely.
        ageSnapshotBySeconds(6);
        check(BridgeMembershipService.getBridgeGroupMembers(groupA).isEmpty(),
                "UNKNOWN stale snapshot targets nobody");
        check(BridgeMembershipService.availability() == BridgeMembershipService.Availability.UNKNOWN,
                "stale snapshot reports UNKNOWN");

        // A fresh publish refreshes sampling and restores the filtered snapshot.
        BridgeMembershipService.publish(Map.of(alice, groupA, bob, groupB));
        check(BridgeMembershipService.getBridgeGroupMembers(groupA).equals(List.of(alice)),
                "republish refreshes the snapshot TTL");

        // Authoritative but stale: also stop sending.
        ageSnapshotBySeconds(6);
        check(BridgeMembershipService.getBridgeGroupMembers(groupA).isEmpty()
                        && BridgeMembershipService.getBridgeGroupMembers(groupB).isEmpty(),
                "authoritative stale snapshot targets nobody");
        check(BridgeMembershipService.availability() == BridgeMembershipService.Availability.UNKNOWN,
                "authoritative stale snapshot reports UNKNOWN");

        // Authoritative EMPTY is still authoritative.
        BridgeMembershipService.publish(Map.of());
        check(BridgeMembershipService.getBridgeGroupMembers(groupA).isEmpty(),
                "authoritative empty snapshot targets nobody");
        check(BridgeMembershipService.availability() == BridgeMembershipService.Availability.EMPTY,
                "authoritative empty snapshot reports EMPTY");

        // Group switches must move the member, not duplicate them.
        BridgeMembershipService.publish(Map.of(alice, groupA));
        BridgeMembershipService.publish(Map.of(alice, groupB));
        check(BridgeMembershipService.getBridgeGroupMembers(groupA).isEmpty()
                        && BridgeMembershipService.getBridgeGroupMembers(groupB).equals(List.of(alice)),
                "group switch moves the member between groups");

        // Reset: unknown and empty.
        BridgeMembershipService.reset();
        check(BridgeMembershipService.getBridgeGroupMembers(groupB).isEmpty()
                        && BridgeMembershipService.availability() == BridgeMembershipService.Availability.UNKNOWN,
                "reset returns to unknown and empty");

        if (failures != 0) {
            throw new AssertionError(failures + " bridge membership checks failed");
        }
        System.out.println("BridgeMembership regression: all checks passed");
    }
}
