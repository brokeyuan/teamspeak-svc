package com.tsvoice.client.ts3voice;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F09 regression: closing a connection must never wait behind a blocked write.
 * The voice send path is a bounded drop-oldest queue drained by a writer
 * thread, so the mic thread never blocks on the transport; stop() closes the
 * socket first (unblocking any stalled write) and finishes within a deadline.
 * Unsupported inbound codecs are rejected at the boundary.
 */
public class TransportShutdownTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** Reads the dropped-frame counter reflectively (absent on the old code = 0). */
    static long droppedFrames(Ts3Connection connection) {
        try {
            Field field = Ts3Connection.class.getDeclaredField("droppedVoiceFramesCounter");
            field.setAccessible(true);
            return ((AtomicLong) field.get(connection)).get();
        } catch (NoSuchFieldException e) {
            return 0; // old code has no queue and no drop counter
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Runs stop() on a helper thread; returns the measured duration, or -1 if it did not finish. */
    static long stopWithDuration(final Ts3Connection connection) throws InterruptedException {
        final AtomicLong duration = new AtomicLong(-1);
        Thread stopper = new Thread(() -> {
            long start = System.nanoTime();
            connection.stop();
            duration.set((System.nanoTime() - start) / 1_000_000);
        }, "connection-stopper");
        stopper.setDaemon(true);
        stopper.start();
        stopper.join(5_000);
        return stopper.isAlive() ? -1 : duration.get();
    }

    /** Deterministic transport: the first send blocks until close() releases it. */
    static final class BlockingTransport implements TsTransport {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger sent = new AtomicInteger();

        @Override
        public void connect(InetAddress address, int port) {
        }

        @Override
        public void send(byte[] data) throws IOException {
            sent.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("send interrupted");
            }
        }

        @Override
        public byte[] receive(int maxLen) throws IOException {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("receive interrupted");
            }
            throw new IOException("transport closed");
        }

        @Override
        public boolean isClosed() {
            return release.getCount() == 0;
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    /** Accepts one connection and never reads from it, so real TCP writes eventually stall. */
    static ServerSocket blackHoleServer() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            try {
                Socket s = server.accept();
                Thread.sleep(60_000); // hold the connection open, read nothing
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("tsbridge.tunnel.secret", "0123456789abcdef0123456789abcdef");

        // Scenario 1 (deterministic): a stalled writer must not block the sender or stop().
        BlockingTransport blocking = new BlockingTransport();
        Ts3Connection connection = new Ts3Connection(new Ts3Crypt(Ts3Identity.generate(0, 1)), blocking);
        connection.connect(java.net.InetAddress.getByName("127.0.0.1"), 1);

        AtomicInteger sendErrors = new AtomicInteger();
        Thread sender = new Thread(() -> {
            for (int i = 0; i < 50 && !connection.isClosed(); i++) {
                try {
                    connection.addOutgoingPacket(new byte[400], PacketType.VOICE);
                } catch (Exception e) {
                    sendErrors.incrementAndGet();
                    return;
                }
            }
        }, "voice-flood");
        sender.setDaemon(true);
        sender.start();
        sender.join(5_000);
        check(!sender.isAlive() && sendErrors.get() == 0,
                "mic-thread send path stays non-blocking while the writer stalls");
        check(droppedFrames(connection) > 0,
                "bounded voice queue drops oldest frames while the writer stalls (dropped="
                        + droppedFrames(connection) + ")");

        long stopMillis = stopWithDuration(connection);
        check(stopMillis >= 0 && stopMillis < 2_000,
                "stop() finishes within 2s despite a blocked write (took " + stopMillis + "ms)");
        check(connection.isClosed(), "connection reports closed after stop");

        // Scenario 2 (real TCP): close unblocks a write stalled on a never-reading peer.
        ServerSocket server = blackHoleServer();
        Ts3Connection realConnection = new Ts3Connection(new Ts3Crypt(Ts3Identity.generate(0, 1)), true);
        realConnection.connect(java.net.InetAddress.getByName("127.0.0.1"), server.getLocalPort());
        Thread realSender = new Thread(() -> {
            for (int i = 0; i < 40_000 && !realConnection.isClosed(); i++) {
                try {
                    realConnection.addOutgoingPacket(new byte[400], PacketType.VOICE);
                } catch (Exception e) {
                    return;
                }
            }
        }, "real-voice-flood");
        realSender.setDaemon(true);
        realSender.start();
        Thread.sleep(200);
        long realStop = stopWithDuration(realConnection);
        realSender.join(2_000);
        check(realStop >= 0 && realStop < 2_000,
                "real-TCP stop() finishes within 2s despite a blocked write (took " + realStop + "ms)");
        check(!realSender.isAlive(), "real-TCP sender thread exits after close");

        // Scenario 3: concurrent send and close must not deadlock.
        ServerSocket server2 = blackHoleServer();
        Ts3Connection connection2 = new Ts3Connection(new Ts3Crypt(Ts3Identity.generate(0, 1)), true);
        connection2.connect(java.net.InetAddress.getByName("127.0.0.1"), server2.getLocalPort());
        Thread concurrentSender = new Thread(() -> {
            try {
                for (int i = 0; i < 40_000 && !connection2.isClosed(); i++) {
                    connection2.addOutgoingPacket(new byte[400], PacketType.VOICE);
                    connection2.addOutgoingPacket("clientupdate x=1".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            PacketType.COMMAND);
                }
            } catch (Exception e) {
                // closed mid-send is acceptable; a deadlock is not
            }
        }, "concurrent-sender");
        concurrentSender.setDaemon(true);
        concurrentSender.start();
        Thread.sleep(100);
        long stop2 = stopWithDuration(connection2);
        concurrentSender.join(2_000);
        check(stop2 >= 0 && stop2 < 2_000 && !concurrentSender.isAlive(),
                "concurrent send+close completes without deadlock (stop took " + stop2 + "ms)");

        // Scenario 4: unsupported inbound codecs are rejected at the boundary.
        Ts3VoiceClient client = new Ts3VoiceClient(Ts3Identity.generate(0, 1), "test", "", false);
        AtomicInteger delivered = new AtomicInteger();
        client.setVoiceListener(frame -> delivered.incrementAndGet());
        client.onVoicePacket(voicePacket(4, (short) 1, 7, new byte[]{9}));
        client.onVoicePacket(voicePacket(1, (short) 2, 8, new byte[]{9}));
        client.onVoicePacket(voicePacket(1, (short) 2, 9, new byte[]{9}));
        client.onVoicePacket(voicePacket(4, (short) 2, 10, new byte[]{9}));
        check(delivered.get() == 2,
                "only OPUS voice frames reach the listener (delivered=" + delivered.get() + ")");

        if (failures != 0) {
            throw new AssertionError(failures + " transport shutdown checks failed");
        }
        System.out.println("Transport shutdown regression: all checks passed");
    }

    static Ts3Packet voicePacket(int codec, short clid, int sequence, byte[] opus) {
        byte[] payload = new byte[5 + opus.length];
        payload[0] = (byte) ((sequence >> 8) & 0xFF);
        payload[1] = (byte) (sequence & 0xFF);
        payload[2] = (byte) ((clid >> 8) & 0xFF);
        payload[3] = (byte) (clid & 0xFF);
        payload[4] = (byte) codec;
        Ts3Packet packet = new Ts3Packet(true);
        packet.data = payload;
        return packet;
    }
}
