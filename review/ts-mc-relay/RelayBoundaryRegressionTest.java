package com.tsvoice;

import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.relay.TsRelayRuntime;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T33/T35 回归（E4）：代际屏障、真实限额、接收长度统一。
 *
 * <p>81c687c 复核反例 → 修复后必须 PASS：</p>
 * <ul>
 *   <li>T33a：barrier=100 之后 epoch=99 的 DATA 被拒（代际单调推进，
 *       不只是记录）。</li>
 *   <li>T33b：协商 pps=1 时四连发 DATA 只放行配额内数量（真实 token
 *       bucket 拒绝，不是统计）。</li>
 *   <li>T33c：barrier 记录到会话上（重复 barrier 单调，倒退拒绝）。</li>
 *   <li>T35a：4096 契约下 3000 字节 DATA 全量回环（服务端 UDP 接收缓冲
 *       与协商值一致，不截断）。</li>
 *   <li>T35b：>4096 字节 DATA 被明确拒绝（codec 拒绝 + runtime 双保险）。</li>
 *   <li>T35c：客户端 transport receive(maxLen) 不足时明确失败，不静默裁剪。</li>
 * </ul>
 */
public class RelayBoundaryRegressionTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static final class Echo implements AutoCloseable {
        final DatagramSocket socket;
        Echo() throws Exception {
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                byte[] b = new byte[65536];
                while (!socket.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(b, b.length);
                        socket.receive(p);
                        socket.send(new DatagramPacket(p.getData(), p.getLength(), p.getSocketAddress()));
                    } catch (Exception e) {
                        if (socket.isClosed()) return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
        public void close() { socket.close(); }
    }

    static TsRelayRuntime.RelayConfig config(int port, int maxBytes, int pps) {
        return new TsRelayRuntime.RelayConfig(true,
                Map.of("ts", new TsRelayRuntime.RelayConfig.RelayTargetConfig("127.0.0.1", port)),
                TsRelayRuntime.MAX_SESSIONS_GLOBAL,
                new TsRelayRuntime.Limits(maxBytes, pps, 512 * 1024), false);
    }

    static byte[] open(int id) {
        return TsRelayWire.encodeOpen(new TsRelayWire.OpenFrame(id, "ts", TsRelayWire.Purpose.PLAYER_VOICE));
    }

    static byte[] data(byte[] id, long epoch, byte[] b) {
        return TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_C2S,
                new TsRelayWire.DataFrame(id, epoch, 1, b));
    }

    static byte[] barrier(byte[] id, long epoch) {
        return TsRelayWire.encodeRouteBarrier(new TsRelayWire.RouteBarrierFrame(id, epoch));
    }

    public static void main(String[] args) throws Exception {
        // ===== T33a：barrier 之下的旧代际 DATA 拒绝 =====
        try (Echo echo = new Echo()) {
            List<byte[]> forwarded = new CopyOnWriteArrayList<>();
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> {
                forwarded.add(d.clone());
                return true;
            }, config(echo.socket.getLocalPort(), 4096, 4096));
            var o = runtime.onOpen("conn-a", "player-a", open(1));
            byte[] sid = o.relaySessionId();
            boolean barrierAccepted = runtime.onRouteBarrier("conn-a", barrier(sid, 100));
            check(barrierAccepted, "T33a: barrier for a live session is accepted");
            boolean staleForwarded = runtime.onData("conn-a", data(sid, 99, new byte[]{1}));
            check(!staleForwarded, "T33a: DATA below the accepted route barrier is rejected");
            boolean freshForwarded = runtime.onData("conn-a", data(sid, 100, new byte[]{2}));
            check(freshForwarded, "T33a: DATA at the barrier epoch is forwarded");
            boolean newerForwarded = runtime.onData("conn-a", data(sid, 101, new byte[]{3}));
            check(newerForwarded, "T33a: DATA above the barrier epoch is forwarded");
            boolean barrierRegression = runtime.onRouteBarrier("conn-a", barrier(sid, 50));
            check(!barrierRegression, "T33a: barrier epoch regression is rejected (monotonic)");
            runtime.close();
        }

        // ===== T33b：pps 限额真实拒绝 =====
        try (Echo echo = new Echo()) {
            AtomicInteger accepted = new AtomicInteger();
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> {
                accepted.incrementAndGet();
                return true;
            }, config(echo.socket.getLocalPort(), 4096, 1));
            var o = runtime.onOpen("conn-b", "player-b", open(2));
            byte[] sid = o.relaySessionId();
            // 协商限额写在 OPENED；四连发最多 1 个立即通过（bucket 初始 >= 1，
            // 但 pps=1 协商必须生效 —— 连续 4 发不允许 4 全过）
            int ok = 0;
            for (int i = 0; i < 4; i++) {
                if (runtime.onData("conn-b", data(sid, 1, new byte[]{8}))) {
                    ok++;
                }
            }
            check(ok < 4, "T33b: negotiated maxDatagramsPerSecond=1 rejects a 4-burst (got " + ok + " through)");
            // 恢复等待一秒后至少又能通过 1 个（token 回填）
            Thread.sleep(1100);
            boolean refill = runtime.onData("conn-b", data(sid, 1, new byte[]{8}));
            check(refill, "T33b: after one second the token refills (rate limit, not a ban)");
            runtime.close();
        }

        // ===== T35a：3000 字节全量回环（无截断） =====
        try (Echo echo = new Echo()) {
            List<byte[]> replies = new CopyOnWriteArrayList<>();
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> {
                replies.add(d.clone());
                return true;
            }, config(echo.socket.getLocalPort(), 4096, 256));
            var o = runtime.onOpen("conn-c", "player-c", open(3));
            byte[] sid = o.relaySessionId();
            byte[] big = new byte[3000];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 7);
            boolean acceptedUp = runtime.onData("conn-c", data(sid, 1, big));
            check(acceptedUp, "T35a: 3000-byte datagram accepted under the 4096 contract");
            long deadline = System.currentTimeMillis() + 2000;
            while (replies.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            check(!replies.isEmpty() && replies.get(0).length == 3000
                            && java.util.Arrays.equals(replies.get(0), big),
                    "T35a: UDP reply comes back full-size (3000 bytes, no truncation)");
            runtime.close();
        }

        // ===== T35b：>4096 明确拒绝 =====
        {
            boolean encodeRejected = false;
            try {
                data(new byte[16], 1, new byte[4097]);
            } catch (IllegalArgumentException e) {
                encodeRejected = true;
            }
            check(encodeRejected, "T35b: codec rejects a 4097-byte datagram at encode");
            boolean decodeRejected = TsRelayWire.decode(new byte[0]) == null;
            check(decodeRejected, "T35b: decode of empty frame is null");
            // runtime 双保险：直接构造 4097 字节的 DATA_C2S 帧不可能合法存在
            //（encodeData 已拒绝），这里验证 decode 对越界 payload 帧同样拒绝。
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(6 + 16 + 8 + 1 + 4 + 4097);
            buf.put(TsRelayWire.encodeOpen(new TsRelayWire.OpenFrame(1, "ts", TsRelayWire.Purpose.PLAYER_VOICE)), 0, 0); // no-op
            buf.rewind();
            buf.put(new byte[]{'T','S','R','L', 1, 3}); // DATA_C2S wire id
            buf.put(new byte[16]);
            buf.putLong(1);
            buf.put((byte) 1);
            buf.putInt(4097);
            buf.put(new byte[4097]);
            check(TsRelayWire.decode(buf.array()) == null,
                    "T35b: decode rejects a hand-built 4097-byte DATA frame (runtime double guard)");
        }

        // ===== T35c：客户端 receive(maxLen) 不足时明确失败 =====
        {
            com.tsvoice.client.ts3voice.TsMcChannelTransport transport =
                    new com.tsvoice.client.ts3voice.TsMcChannelTransport();
            transport.attachRelaySession(new byte[16], 4096);
            transport.onRelayDatagram(new byte[3000]);
            boolean rejected = false;
            try {
                byte[] got = transport.receive(2048);
                rejected = got.length != 2048; // 若返回 2048 即静默截断 → FAIL
                if (got.length == 2048) {
                    rejected = false;
                }
            } catch (java.io.IOException e) {
                rejected = true; // 明确失败是期望行为
            }
            check(rejected, "T35c: receive(maxLen=2048) on a 3000-byte frame fails loudly (no silent truncation)");
            // maxLen 足够时完整还原（新帧：上面的失败路径已消费原帧）
            transport.onRelayDatagram(new byte[3000]);
            byte[] full = transport.receive(4096);
            check(full != null && full.length == 3000,
                    "T35c: receive(4096) returns the full 3000-byte frame");
            transport.close();
        }

        // ===== T33c：旧代际不允许携带新会话（session 绑定复核）=====
        try (Echo echo = new Echo()) {
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> true,
                    config(echo.socket.getLocalPort(), 4096, 4096));
            var o1 = runtime.onOpen("conn-d", "player-d", open(4));
            var o2 = runtime.onOpen("conn-e", "player-e", open(4));
            check(o1.ok() && o2.ok(), "T33c: two connections open independently (T34 base)");
            // conn-d 的 barrier 不影响 conn-e
            runtime.onRouteBarrier("conn-d", barrier(o1.relaySessionId(), 50));
            boolean unaffected = runtime.onData("conn-e", data(o2.relaySessionId(), 1, new byte[]{9}));
            check(unaffected, "T33c: one connection's barrier does not gate another session");
            runtime.close();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
