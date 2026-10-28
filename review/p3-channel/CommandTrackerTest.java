package com.tsvoice.client.ts3voice;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Command tracker and channel snapshot (P3): return_code routing through the
 * real handleError dispatch, unassociated errors not impersonating requests,
 * snapshot ambiguity listing and ordered rendering.
 */
public class CommandTrackerTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Object> pendingMap(TsCommandTracker tracker) throws Exception {
        Field field = TsCommandTracker.class.getDeclaredField("pending");
        field.setAccessible(true);
        return (Map<Integer, Object>) field.get(tracker);
    }

    static Object newPending(int code, long deadlineNanos) throws Exception {
        var ctor = Class.forName("com.tsvoice.client.ts3voice.TsCommandTracker$Pending")
                .getDeclaredConstructor(int.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(code, deadlineNanos);
    }

    static void deliverError(Ts3VoiceClient client, String errorRow) throws Exception {
        var method = Ts3VoiceClient.class.getDeclaredMethod("handleError", String.class);
        method.setAccessible(true);
        method.invoke(client, errorRow);
    }

    static TsCommandTracker.TsCommandResult resultOf(Object pending) throws Exception {
        Field field = pending.getClass().getDeclaredField("result");
        field.setAccessible(true);
        return (TsCommandTracker.TsCommandResult) ((AtomicReference<?>) field.get(pending)).get();
    }

    public static void main(String[] args) throws Exception {
        Ts3VoiceClient client = new Ts3VoiceClient(Ts3Identity.generate(0, 1), "t", "", false);
        TsCommandTracker tracker = new TsCommandTracker(client);
        Field trackerField = Ts3VoiceClient.class.getDeclaredField("commandTracker");
        trackerField.setAccessible(true);
        trackerField.set(client, tracker);
        Map<Integer, Object> pending = pendingMap(tracker);

        // Success routing: a pending satisfied by error id=0 with matching return_code.
        int code = tracker.nextReturnCode();
        Object entry = newPending(code, System.nanoTime() + 5_000_000_000L);
        pending.put(code, entry);
        deliverError(client, "error id=0 msg=ok return_code=" + code);
        TsCommandTracker.TsCommandResult ok = resultOf(entry);
        check(ok != null && ok.succeeded(), "error id=0 with matching return_code satisfies the request");
        check(tracker.inFlight() == 0, "satisfied request is removed from the map");

        // Error routing: precise error delivery to the matching request.
        int code2 = tracker.nextReturnCode();
        Object entry2 = newPending(code2, System.nanoTime() + 5_000_000_000L);
        pending.put(code2, entry2);
        deliverError(client, "error id=1541 msg=invalid\\sparameter return_code=" + code2);
        TsCommandTracker.TsCommandResult failed = resultOf(entry2);
        check(failed != null && !failed.succeeded() && failed.errorCode() == 1541
                        && "invalid parameter".equals(failed.errorMessage()),
                "error id!=0 routes to the matching request");
        check(tracker.inFlight() == 0, "failed request is also consumed");

        // Unassociated error: delivered to nobody.
        int code3 = tracker.nextReturnCode();
        Object entry3 = newPending(code3, System.nanoTime() + 5_000_000_000L);
        pending.put(code3, entry3);
        deliverError(client, "error id=768 msg=connection lost");
        check(tracker.inFlight() == 1 && resultOf(entry3) == null,
                "unassociated error does not satisfy any request");
        pending.clear();

        // Return code sequence: monotonically increasing.
        int first = tracker.nextReturnCode();
        int second = tracker.nextReturnCode();
        check(second == first + 1, "return codes increment within a session");

        // Channel snapshot: ambiguity, ordering, remove, password flag, serverUid guard.
        TsChannelSnapshot snapshot = new TsChannelSnapshot("fixture-server");
        check("fixture-server".equals(snapshot.serverUid()), "snapshot is keyed by server UID");
        snapshot.replaceAll(List.of(
                new TsChannelSnapshot.Channel(2, 0, "Lobby", false, 1, 0, -1),
                new TsChannelSnapshot.Channel(1, 0, "Default", false, 0, 3, 32),
                new TsChannelSnapshot.Channel(5, 2, "Minecraft", false, 0, 1, -1),
                new TsChannelSnapshot.Channel(9, 7, "Minecraft", true, 0, 0, -1)));
        check(snapshot.size() == 4, "replaceAll upserts all channels");
        check(snapshot.byName("minecraft").size() == 2,
                "ambiguous names list every match (never silently pick one)");
        check(snapshot.byName("minecraft").stream().anyMatch(c -> c.cid() == 9 && c.passwordKnown()),
                "password-known flag is preserved on the match");
        List<TsChannelSnapshot.Channel> ordered = snapshot.ordered();
        check(ordered.get(0).cid() == 1 && ordered.get(1).cid() == 2 && ordered.get(3).cid() == 9,
                "ordered view groups by parent then order");
        check(snapshot.byCid(5).parentCid() == 2, "lookup by primary key cid works");
        snapshot.remove(5);
        check(snapshot.byCid(5) == null, "remove drops the channel");
        snapshot.upsert(new TsChannelSnapshot.Channel(5, 2, "Minecraft", false, 0, 2, -1));
        check(snapshot.byCid(5).totalClients() == 2, "upsert refreshes a channel");
        snapshot.refreshServerUid("other");
        check("fixture-server".equals(snapshot.serverUid()),
                "serverUid refresh only upgrades from blank");

        if (failures != 0) {
            throw new AssertionError(failures + " command tracker checks failed");
        }
        System.out.println("Command tracker regression: all checks passed");
    }
}
