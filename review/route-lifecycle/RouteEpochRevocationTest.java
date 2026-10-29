package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.PacketType;
import com.tsvoice.client.ts3voice.Ts3Connection;
import com.tsvoice.client.ts3voice.Ts3Crypt;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.TsTransport;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * N1/D04 regression at the real write point: the REAL Ts3Connection writer
 * consumes PRODUCTION BackendCoordinator route state. When an observed channel
 * is denied by the policy (locked target left via an admin move), the denial
 * itself revokes the permit and advances the route epoch; queued old-epoch
 * frames then die at the final write point without any extra bookkeeping.
 * A new session issues a globally fresh epoch, so frames left over from a dead
 * session can never match the new route (session-bound write permit).
 */
public class RouteEpochRevocationTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** Controllable transport: sends park while blocked; every sent packet is recorded. */
    static final class BlockingTransport implements TsTransport {
        final List<byte[]> sent = new CopyOnWriteArrayList<>();
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch closedLatch = new CountDownLatch(1);
        final AtomicInteger inFlight = new AtomicInteger(0);
        volatile boolean blocking = false;

        @Override
        public void connect(InetAddress address, int port) {
        }

        @Override
        public void send(byte[] data) throws java.io.IOException {
            if (blocking) {
                inFlight.incrementAndGet();
                parked.countDown();
                while (blocking) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("interrupted while parked");
                    }
                }
                inFlight.decrementAndGet();
            }
            sent.add(data);
        }

        @Override
        public byte[] receive(int maxLen) throws java.io.IOException {
            try {
                closedLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("closed");
            }
            throw new java.io.IOException("closed");
        }

        @Override
        public boolean isClosed() {
            return closedLatch.getCount() == 0;
        }

        @Override
        public void close() {
            closedLatch.countDown();
        }
    }

    static byte[] voice(byte marker) {
        return new byte[]{0, 0, 4, marker, marker, marker, marker, marker};
    }

    static boolean sentContains(List<byte[]> sent, byte marker) {
        for (byte[] raw : sent) {
            for (int i = 0; i + 4 < raw.length; i++) {
                if (raw[i] == marker && raw[i + 1] == marker && raw[i + 2] == marker
                        && raw[i + 3] == marker && raw[i + 4] == marker) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        // Production route state: locked target 5 confirmed and ready.
        VoiceState.SessionToken token = BackendCoordinator.beginSession();
        BackendCoordinator.setChannelTarget(token, VoiceState.ChannelPolicy.LOCKED_TARGET, 5);
        check(BackendCoordinator.confirmChannel(token, "fixture-server", 5),
                "R: locked target 5 is confirmed by the production coordinator");
        long epoch1 = BackendCoordinator.route().routeEpoch();
        check(BackendCoordinator.route().transmitReady()
                        && Integer.valueOf(5).equals(BackendCoordinator.route().channelId()),
                "R: the route is ready for the confirmed locked channel");

        BlockingTransport transport = new BlockingTransport();
        Ts3Connection conn = new Ts3Connection(new Ts3Crypt(Ts3Identity.generate(1, 500)), transport);
        conn.setClientNid(1);
        // Same supplier wiring as the production session (TsVoiceManager.connect).
        conn.setVoiceRouteEpochSupplier(() -> {
            var route = BackendCoordinator.snapshot().route();
            return route.transmitReady()
                    && route.backend().orElse(null) == VoiceState.BackendKind.TS3
                    ? route.routeEpoch() : Long.MIN_VALUE;
        });
        conn.connect(InetAddress.getByName("127.0.0.1"), 1);
        check(waitFor(() -> !transport.sent.isEmpty(), 5000), "R: writer drains the init packet");

        // Park the writer on an old-epoch frame (in-flight = not recallable), then
        // queue two more old-epoch frames while it is parked.
        transport.blocking = true;
        conn.addOutgoingPacket(voice((byte) 10), PacketType.VOICE, epoch1);
        check(transport.parked.await(5, TimeUnit.SECONDS),
                "R: writer is parked inside the in-flight old-epoch frame");
        conn.addOutgoingPacket(voice((byte) 11), PacketType.VOICE, epoch1);
        conn.addOutgoingPacket(voice((byte) 12), PacketType.VOICE, epoch1);

        // D04 boundary: an admin moves the user to channel 7 and the observation
        // arrives. The policy denial ITSELF revokes the permit and bumps the epoch.
        boolean approved = BackendCoordinator.confirmChannel(token, "fixture-server", 7);
        check(!approved, "R: the illegal channel observation is denied by the locked policy");
        check(!BackendCoordinator.route().transmitReady(),
                "R: the denial revoked the transmit permit (D04 probe reproduction)");
        long epoch2 = BackendCoordinator.route().routeEpoch();
        check(epoch2 != epoch1, "R: the denial advanced the route epoch");
        transport.blocking = false;
        check(waitFor(() -> transport.inFlight.get() == 0, 5000), "R: writer resumes after the boundary");
        check(waitFor(() -> sentContains(transport.sent, (byte) 10), 5000),
                "R: the frame already in flight before the denial is sent (recorded limitation)");
        Thread.sleep(200);
        check(!sentContains(transport.sent, (byte) 11) && !sentContains(transport.sent, (byte) 12),
                "R: queued old-epoch frames die at the real write point after the denial");

        // Session binding: a fresh session issues a globally fresh epoch; frames
        // left over from the dead session can never match the new route.
        VoiceState.SessionToken token2 = BackendCoordinator.beginSession();
        BackendCoordinator.setChannelTarget(token2, VoiceState.ChannelPolicy.LOCKED_TARGET, 5);
        check(BackendCoordinator.confirmChannel(token2, "fixture-server", 5),
                "R: the new session re-confirms its locked target");
        long epoch3 = BackendCoordinator.route().routeEpoch();
        check(epoch3 != epoch1 && epoch3 != epoch2,
                "R: a new session issues a globally fresh write epoch");
        conn.addOutgoingPacket(voice((byte) 13), PacketType.VOICE, epoch1);
        Thread.sleep(200);
        check(!sentContains(transport.sent, (byte) 13),
                "R: an old-session frame can never match the new route epoch");
        conn.addOutgoingPacket(voice((byte) 14), PacketType.VOICE, epoch3);
        check(waitFor(() -> sentContains(transport.sent, (byte) 14), 5000),
                "R: frames bound to the live session's epoch flow through");

        conn.stop();
        if (failures != 0) {
            throw new AssertionError(failures + " route epoch revocation checks failed");
        }
        System.out.println("Route epoch revocation regression: all checks passed");
    }
}
