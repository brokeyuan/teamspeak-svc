package com.tsvoice.client;

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
import java.util.concurrent.atomic.AtomicLong;

/**
 * R08 regression (T03), connection level: the REAL Ts3Connection writer must
 * check every queued voice frame against the current valid route epoch at the
 * final write point. Old-epoch frames queued before a channel switch (and
 * frames without a valid epoch) never reach the transport; the move control
 * command keeps its writer-order priority; already-in-flight frames are not
 * recallable (recorded limitation).
 */
public class ConnectionLinearizationTest {
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
            // No peer in this test: park until close() so the recv thread idles.
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

    /** Voice payload with a unique marker byte (sent unencrypted on the wire). */
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
        BlockingTransport transport = new BlockingTransport();
        Ts3Connection conn = new Ts3Connection(new Ts3Crypt(Ts3Identity.generate(1, 500)), transport);
        conn.setClientNid(1);
        AtomicLong currentEpoch = new AtomicLong(0);
        conn.setVoiceRouteEpochSupplier(currentEpoch::get);
        conn.connect(InetAddress.getByName("127.0.0.1"), 1);

        // Wait for the writer to drain the INIT1 control packet.
        check(waitFor(() -> !transport.sent.isEmpty(), 5000), "L: writer drains the init packet");

        // Park the writer on an old-epoch voice frame (already started = not recallable).
        transport.blocking = true;
        conn.addOutgoingPacket(voice((byte) 10), PacketType.VOICE, 0);
        check(parkedAwait(transport.parked), "L: writer is parked inside the in-flight frame");

        // Queue two more old-epoch frames while the writer is parked.
        conn.addOutgoingPacket(voice((byte) 11), PacketType.VOICE, 0);
        conn.addOutgoingPacket(voice((byte) 12), PacketType.VOICE, 0);

        // Channel switch boundary: revoke (epoch bump) + clear queued old frames.
        currentEpoch.set(1);
        conn.clearQueuedVoice();
        transport.blocking = false;

        check(waitFor(() -> transport.inFlight.get() == 0, 5000), "L: writer resumes after the boundary");
        check(waitFor(() -> sentContains(transport.sent, (byte) 10), 5000),
                "L: the frame already in flight before the boundary is sent (recorded limitation)");
        check(waitFor(() -> transport.sent.size() >= 2, 2000) && !sentContains(transport.sent, (byte) 11)
                        && !sentContains(transport.sent, (byte) 12),
                "L: queued old-epoch frames never reach the transport after the switch");

        // New-route frames flow; stale and unbound frames are dropped at the write point.
        conn.addOutgoingPacket(voice((byte) 13), PacketType.VOICE, 1);
        check(waitFor(() -> sentContains(transport.sent, (byte) 13), 5000),
                "L: new-route frames flow after the channel switch");
        conn.addOutgoingPacket(voice((byte) 14), PacketType.VOICE, 0);
        conn.addOutgoingPacket(voice((byte) 15), PacketType.VOICE, Long.MIN_VALUE);
        Thread.sleep(200);
        check(!sentContains(transport.sent, (byte) 14) && !sentContains(transport.sent, (byte) 15),
                "L: stale-epoch and unbound (not-ready) frames are dropped fail-closed");

        // Control keeps its writer-order priority (controlQueue polled before voice):
        // a command queued on an idle writer goes out via the same boundary.
        int sentBeforeCommand = transport.sent.size();
        conn.addOutgoingPacket("clientmove clid=1 cid=6".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                PacketType.COMMAND);
        check(waitFor(() -> transport.sent.size() > sentBeforeCommand, 5000),
                "L: control command crosses the same writer boundary");

        conn.stop();
        if (failures != 0) {
            throw new AssertionError(failures + " connection linearization checks failed");
        }
        System.out.println("Connection linearization regression: all checks passed");
    }

    private static boolean parkedAwait(CountDownLatch latch) throws Exception {
        return latch.await(5, TimeUnit.SECONDS);
    }
}
