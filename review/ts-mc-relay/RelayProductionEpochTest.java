package com.tsvoice;

import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.relay.TsRelayRuntime;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * F2 回归：TS 中继生产发送边界。
 *
 * <ul>
 *   <li>F2a：帧携带入队时绑定的真实路由代际 —— 生产 sender 用非零 epoch 构帧，
 *       barrier 之下的旧帧在服务端被拒（生产 sender→runtime 链，不再 (session,0,0)）。</li>
 *   <li>F2b：barrier 单调推进，旧代帧拒、新代帧过、倒退拒。</li>
 *   <li>F2c：bytes/s 限额真实计费 —— 大帧组合超出协商字节预算后被拒，回填后恢复。</li>
 *   <li>F2d：pps 与 bytes 双桶独立生效。</li>
 * </ul>
 */
public class RelayProductionEpochTest {
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

    static TsRelayRuntime.RelayConfig config(int port, int pps, int bps) {
        return new TsRelayRuntime.RelayConfig(true,
                Map.of("ts", new TsRelayRuntime.RelayConfig.RelayTargetConfig("127.0.0.1", port)),
                TsRelayRuntime.MAX_SESSIONS_GLOBAL,
                new TsRelayRuntime.Limits(TsRelayWire.MAX_DATAGRAM_BYTES, pps, bps));
    }

    static byte[] open(int id) {
        return TsRelayWire.encodeOpen(new TsRelayWire.OpenFrame(id, "ts", TsRelayWire.Purpose.PLAYER_VOICE));
    }

    /** 生产 sender 形状：会话绑定 + 携带入队代际（与 TsVoiceManager 闭包一致）。 */
    static byte[] productionFrame(byte[] boundSessionId, long routeEpoch, byte[] datagram) {
        return TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_C2S,
                new TsRelayWire.DataFrame(boundSessionId, routeEpoch, 0, datagram));
    }

    static byte[] barrier(byte[] id, long epoch) {
        return TsRelayWire.encodeRouteBarrier(new TsRelayWire.RouteBarrierFrame(id, epoch));
    }

    public static void main(String[] args) throws Exception {
        // ===== F2a/F2b：生产帧代际 + barrier 执行 =====
        try (Echo echo = new Echo()) {
            List<byte[]> forwarded = new CopyOnWriteArrayList<>();
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> {
                forwarded.add(d.clone());
                return true;
            }, config(echo.socket.getLocalPort(), 4096, 512 * 1024));
            var o = runtime.onOpen("conn-f2", "player-f2", open(1));
            check(o.ok(), "F2a: OPEN accepted");
            byte[] sid = o.relaySessionId();
            // 生产 sender：确认频道后代际推进到 200，barrier 推给服务端
            check(runtime.onRouteBarrier("conn-f2", barrier(sid, 200)),
                    "F2b: production-style barrier accepted");
            check(!runtime.onData("conn-f2", productionFrame(sid, 199, new byte[]{1})),
                    "F2a: frame carrying the pre-barrier epoch (199) is rejected");
            check(runtime.onData("conn-f2", productionFrame(sid, 200, new byte[]{2})),
                    "F2a: frame carrying the barrier epoch (200) is forwarded");
            check(runtime.onData("conn-f2", productionFrame(sid, 201, new byte[]{3})),
                    "F2a: frame carrying a newer epoch (201) is forwarded");
            check(!runtime.onRouteBarrier("conn-f2", barrier(sid, 150)),
                    "F2b: barrier regression is rejected");
            check(runtime.onData("conn-f2", productionFrame(sid, 200, new byte[]{4})),
                    "F2b: rejected regression did not disturb the live barrier");
            runtime.close();
        }

        // ===== F2c：bytes/s 限额 =====
        try (Echo echo = new Echo()) {
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> true,
                    config(echo.socket.getLocalPort(), 4096, 4096)); // 4 KiB/s
            var o = runtime.onOpen("conn-b", "player-b", open(2));
            byte[] sid = o.relaySessionId();
            byte[] big = new byte[3000];
            boolean first = runtime.onData("conn-b", productionFrame(sid, 1, big));
            boolean second = runtime.onData("conn-b", productionFrame(sid, 1, big));
            // 预算 4096：3000 通过后剩 1096 —— 第二个 3000 帧超预算必须拒绝
            check(first, "F2c: first 3000-byte frame within the 4KiB/s budget is accepted");
            check(!second, "F2c: second frame exceeds the byte budget and is rejected");
            Thread.sleep(1100);
            boolean refill = runtime.onData("conn-b", productionFrame(sid, 1, big));
            check(refill, "F2c: after one second the byte budget refills");
            runtime.close();
        }

        // ===== F2d：pps 与 bytes 双桶独立 =====
        try (Echo echo = new Echo()) {
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> true,
                    config(echo.socket.getLocalPort(), 2, 512 * 1024)); // pps=2, bytes 宽裕
            var o = runtime.onOpen("conn-d", "player-d", open(3));
            byte[] sid = o.relaySessionId();
            byte[] small = new byte[10];
            boolean a = runtime.onData("conn-d", productionFrame(sid, 1, small));
            boolean b = runtime.onData("conn-d", productionFrame(sid, 1, small));
            boolean c = runtime.onData("conn-d", productionFrame(sid, 1, small));
            check(a && b && !c,
                    "F2d: pps bucket rejects the third datagram while the byte budget remains ample");
            runtime.close();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
