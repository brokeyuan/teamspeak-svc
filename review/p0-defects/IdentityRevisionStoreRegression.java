package com.svcb.tsbridge.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IdentityRevisionStore: revisions are strictly monotonic across restarts,
 * reserved blocks are skipped (never reused) after a crash, and persistence
 * failures pause allocation instead of falling back to in-memory counting.
 */
public class IdentityRevisionStoreRegression {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** Fake disk: remembers only the latest persisted recovery point. */
    static final class FakePersistence implements IdentityRevisionStore.Persistence {
        Long onDisk;
        boolean failWrites;

        @Override
        public void persistRecoveryPoint(long recoveryPoint) throws IOException {
            if (failWrites) {
                throw new IOException("injected write failure");
            }
            onDisk = recoveryPoint;
        }

        @Override
        public long loadRecoveryPoint() {
            return onDisk == null ? 0L : onDisk;
        }
    }

    public static void main(String[] args) throws Exception {
        // First allocation starts at 2 (legacy compatibility) and is monotonic.
        FakePersistence disk = new FakePersistence();
        IdentityRevisionStore store = new IdentityRevisionStore(disk);
        long first = store.allocate();
        long second = store.allocate();
        check(first == 2 && second == 3, "first allocations start at 2 and increase");

        // Restart: the recovery point skips the whole reserved block.
        for (int i = 0; i < 50; i++) {
            store.allocate();
        }
        long lastBeforeRestart = store.allocate();
        long recoveryPointAtRestart = disk.onDisk;
        IdentityRevisionStore restarted = new IdentityRevisionStore(disk);
        long afterRestart = restarted.allocate();
        check(afterRestart > lastBeforeRestart,
                "restart resumes beyond every previously issued revision");
        check(afterRestart >= recoveryPointAtRestart,
                "restart resumes at or beyond the persisted recovery point");

        // Persistent failure pauses allocation (fail-closed, no in-memory fallback).
        IdentityRevisionStore failing = new IdentityRevisionStore(disk);
        disk.failWrites = true;
        boolean threw = false;
        try {
            failing.allocate();
        } catch (IdentityRevisionStore.RevisionAllocationException e) {
            threw = true;
        }
        check(threw, "persistence failure raises RevisionAllocationException");
        disk.failWrites = false;
        long afterRecovery = failing.allocate();
        check(afterRecovery > lastBeforeRestart,
                "allocation after recovered persistence stays monotonic");

        // Many allocations across multiple restarts never reuse a revision.
        List<Long> all = new ArrayList<>();
        IdentityRevisionStore chain = new IdentityRevisionStore(disk);
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < IdentityRevisionStore.RESERVE_BLOCK + 10; i++) {
                all.add(chain.allocate());
            }
            chain = new IdentityRevisionStore(disk);
        }
        boolean monotonic = true;
        for (int i = 1; i < all.size(); i++) {
            if (all.get(i) <= all.get(i - 1)) {
                monotonic = false;
                break;
            }
        }
        check(monotonic, "long allocation chain across restarts is strictly monotonic");

        // File-backed persistence round trip.
        Path file = Path.of("review/p0-defects/issuing-config/identity-revision.txt");
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        IdentityRevisionStore fileStore = new IdentityRevisionStore(new IdentityRevisionStore.FilePersistence(file));
        long fileLast = 0;
        for (int i = 0; i < IdentityRevisionStore.RESERVE_BLOCK + 5; i++) {
            fileLast = fileStore.allocate();
        }
        IdentityRevisionStore fileRestart = new IdentityRevisionStore(new IdentityRevisionStore.FilePersistence(file));
        check(fileRestart.allocate() > fileLast, "file persistence survives a restart");

        if (failures != 0) {
            throw new AssertionError(failures + " revision store checks failed");
        }
        System.out.println("IdentityRevisionStore regression: all checks passed");
    }
}
