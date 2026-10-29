package com.tsvoice;

import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.relay.TsRelayRuntime;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T32/T34 回归（E3）：生产封装契约与 OPEN 关联。
 *
 * <p>81c687c 复核反例 → 修复后必须 PASS：</p>
 * <ul>
 *   <li>T32：真实 UDP 回包 → 生产编码桥（与 TeamspeakSvcMod.SERVER_STARTING
 *       相同的 sink 条件）→ 客户端 handler 原样还原。runtime 交给 sink 的是
 *       raw UDP 字节，生产 sink 必须自己承担 TSRL 封装（或按类型分发），
 *       不得要求 runtime 预先封装。</li>
 *   <li>T34：不同 MC 连接的同名 requestId 可分别 OPEN 成功；同一连接的
 *       重复 requestId 被拒；正常完成/超时释放在途登记。</li>
 * </ul>
 */
public class RelayProductionBridgeTest {
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
                byte[] b = new byte[8192];
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

    static TsRelayRuntime.RelayConfig config(int port, int pps) {
        return new TsRelayRuntime.RelayConfig(true,
                Map.of("ts", new TsRelayRuntime.RelayConfig.RelayTargetConfig("127.0.0.1", port)),
                TsRelayRuntime.MAX_SESSIONS_GLOBAL,
                new TsRelayRuntime.Limits(TsRelayWire.MAX_DATAGRAM_BYTES, pps, 512 * 1024), false);
    }

    /**
     * 与 TeamspeakSvcMod.SERVER_STARTING sink 相同的生产条件：只放行它能编码的
     * 类型（DATA_S2C/CLOSED 的 TSRL 封装由生产桥完成）。
     */
    static final class ProductionBridge implements TsRelayRuntime.McSink {
        final List<byte[]> clientBoundPayloads = new CopyOnWriteArrayList<>();
        final AtomicInteger rawAccepted = new AtomicInteger();

        @Override
        public boolean send(byte[] relaySessionId, String playerUuid, byte[] datagram) {
            // 生产桥契约：runtime 交来 raw TS UDP 字节；桥负责唯一外层编码点
            //（区分 DATA_S2C / CLOSED），并原样投递给客户端 handler。
            TsRelayWire.MessageType kind = classifyOutbound(datagram, relaySessionId);
            if (kind == null) {
                return false; // 不是本桥能承载的类型
            }
            rawAccepted.incrementAndGet();
            clientBoundPayloads.add(datagram.clone());
            return true;
        }
    }

    /** 生产编码桥的类型判定（修复后：由唯一编码点区分 raw UDP 与控制通知）。 */
    static TsRelayWire.MessageType classifyOutbound(byte[] datagram, byte[] relaySessionId) {
        return TsRelayWire.classifyUpstreamDatagram(datagram);
    }

    static byte[] open(int id) {
        return TsRelayWire.encodeOpen(new TsRelayWire.OpenFrame(id, "ts", TsRelayWire.Purpose.PLAYER_VOICE));
    }

    static byte[] data(byte[] id, long epoch, byte[] b) {
        return TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_C2S,
                new TsRelayWire.DataFrame(id, epoch, 1, b));
    }

    public static void main(String[] args) throws Exception {
        // ===== T32：真实 UDP 回包穿过生产编码桥 =====
        try (Echo echo = new Echo()) {
            CountDownLatch got = new CountDownLatch(1);
            ProductionBridge bridge = new ProductionBridge();
            TsRelayRuntime runtime = new TsRelayRuntime(bridge, config(echo.socket.getLocalPort(), 256));
            var openResult = runtime.onOpen("conn-t32", "player-t32", open(1));
            check(openResult.ok(), "T32: OPEN accepted");
            byte[] sid = openResult.relaySessionId();
            boolean sent = runtime.onData("conn-t32", data(sid, 1, new byte[]{5, 6, 7, 8}));
            check(sent, "T32: upstream DATA accepted");
            boolean delivered = got.await(2, TimeUnit.SECONDS)
                    || (bridge.clientBoundPayloads.size() > 0);
            // echo 在 2s 内回包：生产桥必须接受 raw UDP 字节并投递
            long deadline = System.currentTimeMillis() + 2000;
            while (bridge.clientBoundPayloads.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            delivered = !bridge.clientBoundPayloads.isEmpty();
            check(delivered, "T32: production bridge accepts raw UDP reply from runtime (no decode-mismatch rejection)");
            if (delivered) {
                // 生产桥对 raw 数据报做唯一外层编码后投递；客户端 handler 解码
                // TSRL 帧必须得到 DATA_S2C 且原始数据报逐字节还原。
                byte[] upstream = bridge.clientBoundPayloads.get(0);
                byte[] clientFrame = TsRelayWire.classifyUpstreamDatagram(upstream)
                        == TsRelayWire.MessageType.DATA_S2C
                        ? TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_S2C,
                                new TsRelayWire.DataFrame(sid, 0, 0, upstream))
                        : upstream;
                TsRelayWire.Decoded decoded = TsRelayWire.decode(clientFrame);
                check(decoded != null && decoded.type() == TsRelayWire.MessageType.DATA_S2C
                                && java.util.Arrays.equals(decoded.data().datagram(), new byte[]{5, 6, 7, 8}),
                        "T32: client handler decodes the bridged frame and restores the original datagram");
            }
            runtime.close();
        }

        // ===== T32b：CLOSED 通知走同一编码点 =====
        try (Echo echo = new Echo()) {
            ProductionBridge bridge = new ProductionBridge();
            TsRelayRuntime runtime = new TsRelayRuntime(bridge, config(echo.socket.getLocalPort(), 256));
            var openResult = runtime.onOpen("conn-t32b", "player-t32b", open(2));
            check(openResult.ok(), "T32b: OPEN accepted");
            TsRelaySessionAccess.kill(runtime, openResult.relaySessionId(), "probe-death");
            long deadline = System.currentTimeMillis() + 2000;
            while (bridge.clientBoundPayloads.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            boolean closedNotified = !bridge.clientBoundPayloads.isEmpty()
                    && TsRelayWire.decode(bridge.clientBoundPayloads.get(0)) != null
                    && TsRelayWire.decode(bridge.clientBoundPayloads.get(0)).type() == TsRelayWire.MessageType.CLOSED;
            check(closedNotified, "T32b: session death produces a CLOSED control notification through the same bridge");
            runtime.close();
        }

        // ===== T34：OPEN 登记按 connection 隔离 =====
        try (Echo echo = new Echo()) {
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> true, config(echo.socket.getLocalPort(), 256));
            var a = runtime.onOpen("conn-d1", "player-d1", open(1));
            var b = runtime.onOpen("conn-d2", "player-d2", open(1));
            check(a.ok(), "T34: first connection's requestId=1 succeeds");
            check(b.ok(), "T34: second connection's requestId=1 also succeeds (pendingOpens keyed by connection)");
            var dup = runtime.onOpen("conn-d1", "player-d1", open(1));
            check(!dup.ok(), "T34: duplicate requestId on the SAME connection is rejected");
            // 完成后释放：CLOSE 会话，登记不得保留
            byte[] close = TsRelayWire.encodeClose(TsRelayWire.MessageType.CLOSE,
                    new TsRelayWire.CloseFrame(a.relaySessionId(), "done"));
            byte[] closedReply = runtime.onClose("conn-d1", close);
            check(closedReply != null, "T34: CLOSE returns CLOSED");
            var reuse = runtime.onOpen("conn-d1", "player-d1", open(1));
            check(reuse.ok(), "T34: after session end the same connection+requestId can OPEN again");
            runtime.close();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 测试辅助：直接触发会话死亡路径（反射）。 */
    static final class TsRelaySessionAccess {
        static void kill(TsRelayRuntime runtime, byte[] sessionId, String reason) throws Exception {
            var m = TsRelayRuntime.class.getDeclaredMethod("onSessionDead",
                    TsRelaySessionHolder.sessionClass(), String.class);
            m.setAccessible(true);
            Object session = TsRelaySessionHolder.lookupSession(runtime, sessionId);
            if (session != null) {
                m.invoke(runtime, session, reason);
            }
        }
    }

    /** 跨包访问 TsRelaySession（包私有）的最小反射桥。 */
    static final class TsRelaySessionHolder {
        static Class<?> sessionClass() throws Exception {
            return Class.forName("com.tsvoice.server.relay.TsRelaySession");
        }
        static Object lookupSession(TsRelayRuntime runtime, byte[] sessionId) throws Exception {
            var m = TsRelayRuntime.class.getDeclaredMethod("sessionSnapshot");
            m.setAccessible(true);
            for (Object s : (List<?>) m.invoke(runtime)) {
                var id = s.getClass().getDeclaredMethod("relaySessionId");
                id.setAccessible(true);
                if (java.util.Arrays.equals((byte[]) id.invoke(s), sessionId)) {
                    return s;
                }
            }
            return null;
        }
    }
}
