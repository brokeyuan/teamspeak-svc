package com.tsvoice;

import com.tsvoice.protocol.relay.TsDatagramKind;
import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.relay.TsRelayRuntime;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * G2/P1-02 回归：中继媒体屏障与控制流量隔离（真实 TS 包头 fixture）。
 *
 * <ul>
 *   <li>barrier=200 后：COMMAND/ACK/PING/PONG 正常送达（媒体屏障不误挡控制）；</li>
 *   <li>epoch199 媒体拒绝、epoch200 媒体通过、epoch0 媒体拒绝（不放行旧帧）；</li>
 *   <li>伪装 CONTROL 的媒体（tc=1 但包头是 VOICE）拒绝；类别不符拒绝；</li>
 *   <li>旧 session 控制拒绝；控制超额仍拒绝（不是无限带宽旁路）；</li>
 *   <li>tc=0 旧版未分类按包头识别；未知类型/短包 INVALID 拒绝。</li>
 * </ul>
 *
 * <p>fixture 用真实 {@link Ts3Packet} 生成（MAC 置零 —— 中继不验证 MAC，
 * 但包头布局/类型字节真实）。</p>
 */
public class RelayControlMediaBarrierTest {
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
                new TsRelayRuntime.Limits(TsRelayWire.MAX_DATAGRAM_BYTES, pps, bps), false);
    }

    static byte[] open(int id) {
        return TsRelayWire.encodeOpen(new TsRelayWire.OpenFrame(id, "ts", TsRelayWire.Purpose.PLAYER_VOICE));
    }

    /**
     * 真实 TS 包头 fixture。布局与生产 {@code Ts3Packet.out + buildHeader}
     * 一致（javap 核对）：[MAC 8 字节][C2S header 5B = pid(2BE)+clientId(2BE)+
     * type|flags(1)][payload]；MAC 置零 —— 中继不验证 MAC，但类型字节位于
     * 真实下标 12。
     */
    static byte[] tsPacket(int typeValue, byte[] payload) {
        byte[] raw = new byte[8 + 5 + payload.length];
        raw[8] = 0; raw[9] = 1;               // pid=1
        raw[10] = 0; raw[11] = 1;             // clientId=1
        raw[12] = (byte) (typeValue & 0x0F);  // type|flags
        System.arraycopy(payload, 0, raw, 13, payload.length);
        return raw;
    }
    static final int T_VOICE = 0x0, T_VOICE_WHISPER = 0x1, T_COMMAND = 0x2,
            T_PING = 0x4, T_PONG = 0x5, T_ACK = 0x6;

    static byte[] dataFrame(byte[] id, long epoch, int tc, byte[] datagram) {
        return TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_C2S,
                new TsRelayWire.DataFrame(id, epoch, tc, datagram));
    }

    static byte[] barrier(byte[] id, long epoch) {
        return TsRelayWire.encodeRouteBarrier(new TsRelayWire.RouteBarrierFrame(id, epoch));
    }

    public static void main(String[] args) throws Exception {
        try (Echo echo = new Echo()) {
            List<byte[]> forwarded = new CopyOnWriteArrayList<>();
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> {
                forwarded.add(d.clone());
                return true;
            }, config(echo.socket.getLocalPort(), 4096, 1024 * 1024));
            var o = runtime.onOpen("conn-g2", "player-g2", open(1));
            check(o.ok(), "G2: OPEN accepted");
            byte[] sid = o.relaySessionId();

            byte[] command = tsPacket(T_COMMAND, new byte[]{1, 2, 3});
            byte[] ack = tsPacket(T_ACK, new byte[]{0, 5});
            byte[] ping = tsPacket(T_PING, new byte[]{0, 0});
            byte[] pong = tsPacket(T_PONG, new byte[]{0, 1});
            byte[] voice = tsPacket(T_VOICE, new byte[64]);
            byte[] whisper = tsPacket(T_VOICE_WHISPER, new byte[64]);

            // barrier 前控制与媒体都通过（基线）
            check(runtime.onData("conn-g2", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_CONTROL, command)),
                    "G2: pre-barrier COMMAND forwarded");
            check(runtime.onData("conn-g2", dataFrame(sid, 200, TsDatagramKind.TRAFFIC_MEDIA, voice)),
                    "G2: pre-barrier media at 200 forwarded");
            check(runtime.onRouteBarrier("conn-g2", barrier(sid, 200)),
                    "G2: barrier 200 accepted");

            // barrier 后：控制全通
            forwarded.clear();
            String[] names = {"COMMAND", "ACK", "PING", "PONG"};
            byte[][] controls = {command, ack, ping, pong};
            for (int i = 0; i < controls.length; i++) {
                check(runtime.onData("conn-g2",
                        dataFrame(sid, 0, TsDatagramKind.TRAFFIC_CONTROL, controls[i])),
                        "G2: control " + names[i] + " accepted to the UDP path");
                Thread.sleep(10); // 回环 UDP 突发可能丢包：逐包间隔让 echo 跟上
            }
            // onData=true 仅表示已发往 UDP 目标；回包经 session worker 异步送达
            // sink —— 轮询等待 4 条到齐（上限 2s）。
            long sinkDeadline = System.currentTimeMillis() + 2000;
            while (forwarded.size() < 4 && System.currentTimeMillis() < sinkDeadline) {
                Thread.sleep(20);
            }
            check(forwarded.size() == 4,
                    "G2: after barrier COMMAND/ACK/PING/PONG all forwarded (got " + forwarded.size() + ")");

            // barrier 后：媒体代际门
            check(!runtime.onData("conn-g2", dataFrame(sid, 199, TsDatagramKind.TRAFFIC_MEDIA, voice)),
                    "G2: epoch 199 media rejected after barrier");
            check(runtime.onData("conn-g2", dataFrame(sid, 200, TsDatagramKind.TRAFFIC_MEDIA, voice)),
                    "G2: epoch 200 media forwarded after barrier");
            check(runtime.onData("conn-g2", dataFrame(sid, 201, TsDatagramKind.TRAFFIC_MEDIA, voice)),
                    "G2: epoch 201 media forwarded after barrier");
            check(!runtime.onData("conn-g2", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_MEDIA, voice)),
                    "G2: epoch 0 media rejected (no epoch0 bypass)");

            // VOICE_WHISPER 是媒体：同样受屏障约束
            check(!runtime.onData("conn-g2", dataFrame(sid, 199, TsDatagramKind.TRAFFIC_MEDIA, whisper)),
                    "G2: VOICE_WHISPER below barrier rejected (media, not control path)");
            check(runtime.onData("conn-g2", dataFrame(sid, 200, TsDatagramKind.TRAFFIC_MEDIA, whisper)),
                    "G2: VOICE_WHISPER at barrier epoch forwarded");

            // 伪装与类别一致性
            check(!runtime.onData("conn-g2", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_CONTROL, voice)),
                    "G2: media disguised as CONTROL (tc=1, VOICE header) rejected");
            check(!runtime.onData("conn-g2", dataFrame(sid, 0, 7, command)),
                    "G2: unknown trafficClass rejected");

            // tc=0 旧版未分类：按包头识别
            check(runtime.onData("conn-g2", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_LEGACY, command)),
                    "G2: legacy tc=0 control classified by header and forwarded");
            check(!runtime.onData("conn-g2", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_LEGACY, voice)),
                    "G2: legacy tc=0 media still hits the barrier (no blanket allow)");

            // INVALID：短包、未知类型
            check(!runtime.onData("conn-g2", dataFrame(sid, 200, TsDatagramKind.TRAFFIC_MEDIA, new byte[]{1})),
                    "G2: short packet (below header) rejected as INVALID");
            byte[] unknown = tsPacket(T_COMMAND, new byte[]{1});
            unknown[12] = (byte) ((unknown[12] & 0xF0) | 0x0B); // 未知类型 0xB
            check(TsDatagramKind.classify(unknown, TsDatagramKind.Direction.C2S)
                    == TsDatagramKind.Kind.INVALID,
                    "G2: unknown type 0xB classifies INVALID");
            check(!runtime.onData("conn-g2", dataFrame(sid, 200, TsDatagramKind.TRAFFIC_LEGACY, unknown)),
                    "G2: unknown type datagram rejected by runtime");

            // 旧 session 控制拒绝
            check(!runtime.onData("conn-other", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_CONTROL, command)),
                    "G2: control on a foreign connection rejected (no bypass via control path)");

            // 控制超额仍拒绝（速率约束，不是无限带宽旁路）
            runtime.close();
        }
        try (Echo echo = new Echo()) {
            AtomicIntegerOk ok = new AtomicIntegerOk();
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> {
                ok.n++;
                return true;
            }, config(echo.socket.getLocalPort(), 2, 1024 * 1024)); // pps=2
            var o = runtime.onOpen("conn-rate", "player-rate", open(3));
            byte[] sid = o.relaySessionId();
            byte[] command = tsPacket(T_COMMAND, new byte[]{1});
            boolean a = runtime.onData("conn-rate", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_CONTROL, command));
            boolean b = runtime.onData("conn-rate", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_CONTROL, command));
            boolean c = runtime.onData("conn-rate", dataFrame(sid, 0, TsDatagramKind.TRAFFIC_CONTROL, command));
            check(a && b && !c, "G2: control over rate limit is still rejected (third datagram)");
            runtime.close();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static final class AtomicIntegerOk {
        int n;
    }
}
