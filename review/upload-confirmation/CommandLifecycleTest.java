package com.tsvoice.client;

import com.tsvoice.client.ts3voice.TsCommandTracker;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * R06 regression (T05), command lifecycle: return_code association is exact,
 * wrong/unassociated errors reach nobody, timeouts release the registration and
 * are never completed later, session close cancels immediately, and in-flight
 * return codes are never reused.
 */
public class CommandLifecycleTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static int extractedReturnCode(Ts3VoiceClient client, int index) {
        String cmd = client.sentCommands.get(index);
        for (String token : cmd.split(" ")) {
            if (token.startsWith("return_code=")) {
                return Integer.parseInt(token.substring("return_code=".length()));
            }
        }
        throw new AssertionError("no return_code on " + cmd);
    }

    public static void main(String[] args) throws Exception {
        Ts3VoiceClient client = new Ts3VoiceClient(null, "t", "");
        TsCommandTracker tracker = new TsCommandTracker(client);

        // ---- L1: two concurrent in-flight commands keep distinct codes ----
        var futureA = tracker.executeAsync("clientupdate a=1", 5000);
        var futureB = tracker.executeAsync("clientupdate b=2", 5000);
        check(tracker.inFlight() == 2 && client.sentCommands.size() == 2, "L1: two commands registered");
        int codeA = extractedReturnCode(client, 0);
        int codeB = extractedReturnCode(client, 1);
        check(codeA != codeB, "L1: in-flight return codes are never reused");

        // ---- L2: a wrong-ID error completes nobody ----
        tracker.onError("error id=1 msg=wrong return_code=" + (codeA + 1000));
        check(!futureA.isDone() && !futureB.isDone(), "L2: unassociated error completes nobody");

        // ---- L3: exact association - rejection and success route by return_code ----
        tracker.onError("error id=1540 msg=permission_denied return_code=" + codeB);
        check(futureB.isDone() && !futureB.join().succeeded()
                        && futureB.join().errorCode() == 1540,
                "L3: rejection routes to its own request with the server error");
        tracker.onError("error id=0 msg=ok return_code=" + codeA);
        check(futureA.isDone() && futureA.join().succeeded(),
                "L3: success (error id=0) completes the matching future");

        // ---- L4: timeout releases the registration and ignores the late reply ----
        var futureC = tracker.executeAsync("clientupdate c=3", 120);
        int codeC = extractedReturnCode(client, 2);
        try {
            futureC.get(2, TimeUnit.SECONDS);
            check(false, "L4: timeout should fail the future");
        } catch (ExecutionException e) {
            check(e.getCause() instanceof TimeoutException, "L4: timeout completes exceptionally");
        }
        check(tracker.inFlight() == 0, "L4: registration released after timeout");
        tracker.onError("error id=0 msg=late_reply return_code=" + codeC);
        check(futureC.isCompletedExceptionally(),
                "L4: late reply after timeout does not resurrect the future");
        check(tracker.inFlight() == 0, "L4: late reply does not re-register anything");

        // ---- L5: session close cancels all in-flight futures immediately ----
        var futureE = tracker.executeAsync("clientupdate e=5", 30000);
        tracker.cancelSession();
        check(futureE.isCompletedExceptionally(), "L5: cancelSession completes in-flight futures");
        try {
            futureE.get(1, TimeUnit.SECONDS);
            check(false, "L5: cancelled future must not return a result");
        } catch (java.util.concurrent.CancellationException e) {
            check(true, "L5: cancellation is the completion cause");
        } catch (ExecutionException e) {
            check(e.getCause() instanceof java.util.concurrent.CancellationException,
                    "L5: cancellation is the completion cause");
        }
        check(tracker.inFlight() == 0, "L5: registry empty after cancel");

        // ---- L6: send failure fails the future and releases the registration ----
        Ts3VoiceClient broken = new Ts3VoiceClient(null, "t2", "");
        broken.failNextSend = true;
        TsCommandTracker brokenTracker = new TsCommandTracker(broken);
        var futureF = brokenTracker.executeAsync("clientupdate f=6", 5000);
        try {
            futureF.get(1, TimeUnit.SECONDS);
            check(false, "L6: send failure must fail the future");
        } catch (ExecutionException e) {
            check(e.getCause() instanceof Exception && brokenTracker.inFlight() == 0,
                    "L6: send failure releases the registration");
        }

        if (failures != 0) {
            throw new AssertionError(failures + " command lifecycle checks failed");
        }
        System.out.println("Command lifecycle regression: all checks passed");
    }
}
