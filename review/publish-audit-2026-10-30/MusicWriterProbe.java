package com.tsvoice.client;

import com.tsvoice.client.ts3voice.*;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立复核探针：复现 Bot 常量代际遇到真实 writer 默认许可的行为。 */
public final class MusicWriterProbe {
    static final class Transport implements TsTransport {
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger sent = new AtomicInteger();
        public void connect(InetAddress a, int p) {}
        public void send(byte[] b) { sent.incrementAndGet(); }
        public byte[] receive(int n) throws java.io.IOException {
            try { closed.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new java.io.IOException("closed");
        }
        public boolean isClosed() { return closed.getCount() == 0; }
        public void close() { closed.countDown(); }
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError("writer timeout");
    }
    public static void main(String[] args) throws Exception {
        Transport transport = new Transport();
        Ts3Connection connection = new Ts3Connection(new Ts3Crypt(Ts3Identity.generate(1, 500)), transport);
        connection.setClientNid(1);
        try {
            connection.connect(InetAddress.getLoopbackAddress(), 1);
            await(() -> transport.sent.get() > 0);
            long before = connection.droppedVoiceFrames();
            connection.addOutgoingPacket(new byte[]{0, 0, 5, 33, 33, 33}, PacketType.VOICE, 1L);
            await(() -> connection.droppedVoiceFrames() > before);
            System.out.println("REPRODUCED: bot epoch=1 rejected by real writer default supplier; dropped delta="
                    + (connection.droppedVoiceFrames() - before));
            // 对照只校验探针有效性，不代表本轮修复生产代码。
            connection.setVoiceRouteEpochSupplier(() -> 1L);
            int sent = transport.sent.get();
            connection.addOutgoingPacket(new byte[]{0, 0, 5, 44, 44, 44}, PacketType.VOICE, 1L);
            await(() -> transport.sent.get() > sent);
            System.out.println("CONTROL: explicitly bound supplier permits the same epoch");
        } finally { connection.stop(); }
    }
}
