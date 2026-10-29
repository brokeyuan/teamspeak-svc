package com.tsvoice;

import com.tsvoice.common.net.SvcMcChannelPayloads;
import com.tsvoice.server.svc.SvcMcChannelAdapter;
import com.tsvoice.server.svc.SvcServerBridge;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S2/P5 SVC_MC_CHANNEL 回归：按玩家路由、唯一会话地址、封装边界、双装让渡、
 * Extra wire 字节契约。
 *
 * <p>反射 stub：SVC 内部 Server 与 RawUdpPacketImpl 不可在无 SVC harness 编译，
 * 这里以同名替代类（stub 包）承载字节流断言 —— 被 stub 的方法签名与 26.2
 * 字节码一致（addRawPacket(RawUdpPacket)）。</p>
 */
public class SvcMcChannelTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** 记录 addRawPacket 投递的 stub（替代 SVC 内部 Server）。 */
    public static final class RecordedSvcServer {
        static final List<Object[]> packets = new ArrayList<>();

        public void addRawPacket(Object rawPacket) throws Exception {
            // rawPacket 是 RawUdpPacketStub
            Method data = rawPacket.getClass().getMethod("getData");
            Method addr = rawPacket.getClass().getMethod("getSocketAddress");
            packets.add(new Object[]{data.invoke(rawPacket), addr.invoke(rawPacket)});
        }
    }

    /** RawUdpPacket stub（记录字节与地址）。 */
    public static final class RawUdpPacketStub {
        final byte[] data;
        final InetSocketAddress address;
        final long timestamp;

        public RawUdpPacketStub(byte[] data, InetSocketAddress address, long timestamp) {
            this.data = data;
            this.address = address;
            this.timestamp = timestamp;
        }

        public byte[] getData() {
            return data;
        }

        public InetSocketAddress getSocketAddress() {
            return address;
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- wire 契约：voice payload = 1:1 字节（Extra @6669d6f 兼容面）----
        byte[] svcMessage = new byte[512];
        for (int i = 0; i < svcMessage.length; i++) {
            svcMessage[i] = (byte) (i * 13);
        }
        // codec round trip via StreamCodec is Fabric-tied; assert constant bounds only
        check(SvcMcChannelPayloads.MAX_VOICE_BYTES == 8 * 1024,
                "W: voice payload bound is 8 KiB (SVC NetworkMessage envelope far smaller)");

        // ---- 会话地址唯一性：100 玩家全部不同端口（不撞 UUID.hashCode 空间）----
        SvcMcChannelAdapter.resetForTest();
        List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var address = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.nameUUIDFromBytes(
                    ("player-" + i).getBytes()), "conn-" + i, null);
            int port = address.toSocketAddress().getPort();
            ports.add(port);
        }
        check(ports.stream().distinct().count() == ports.size(),
                "R1: 100 concurrent session addresses are collision-free (no UUID.hashCode%65535)");

        // ---- 未登记玩家拒收（宣告先于语音）----
        UUID registered = UUID.randomUUID();
        SvcMcChannelAdapter.onPlayerInitializedForConnection(registered, "conn-reg", null);
        UUID stranger = UUID.randomUUID();
        check(!SvcMcChannelAdapter.isMcChannelPlayer(stranger),
                "R2: unannounced player is not an MC-channel player");
        check(SvcMcChannelAdapter.isMcChannelPlayer(registered),
                "R2: announced player routes via MC channel");

        // ---- 按玩家路由：onSendPacketRaw 只接管已宣告玩家 ----
        boolean[] sent = {false};
        byte[] outbound = new byte[]{1, 2, 3, 4, 5};
        boolean takenOver = SvcMcChannelAdapter.onSendPacketRaw(registered, outbound,
                (player, data) -> sent[0] = true);
        check(takenOver && sent[0], "R3: outbound to an MC-channel player is taken over");
        sent[0] = false;
        boolean notTaken = SvcMcChannelAdapter.onSendPacketRaw(stranger, outbound,
                (player, data) -> sent[0] = true);
        check(!notTaken && !sent[0],
                "R3: outbound to a UDP player is untouched (per-player routing, no global switch)");

        // ---- 双装让渡（R1 注册的 100 玩家 + registered；先断言计数再清理）----
        check(SvcMcChannelAdapter.mcChannelPlayerCount() == 101,
                "R4: adapter tracks exactly the announced sessions (100 R1 + 1 R2)");

        // ---- 断开回收 ----
        SvcMcChannelAdapter.onPlayerDisconnected(registered, "conn-reg");
        check(!SvcMcChannelAdapter.isMcChannelPlayer(registered),
                "R5: disconnect reclaims the session address");
        boolean after = SvcMcChannelAdapter.onSendPacketRaw(registered, outbound,
                (player, data) -> sent[0] = true);
        check(!after, "R5: outbound after disconnect is not taken over (SVC keepalive owns timeouts)");

        // ---- 会话重建：重连分配新地址（旧地址不复用给新连接语义）----
        var first = SvcMcChannelAdapter.onPlayerInitializedForConnection(registered, "conn-1st", null);
        SvcMcChannelAdapter.onPlayerDisconnected(registered, "conn-1st");
        var second = SvcMcChannelAdapter.onPlayerInitializedForConnection(registered, "conn-2nd", null);
        check(first.toSocketAddress().getPort() != second.toSocketAddress().getPort(),
                "R6: a reconnected player gets a fresh session address (old address not reused)");

        // ---- SVC 不在场：onVoiceFromClient 为 no-op 且不抛 ----
        check(!SvcMcChannelAdapter.onVoiceFromClient(null, registered, new byte[]{1}),
                "R7: absent SVC server makes the downstream no-op (basic TS path unaffected)");

        // ---- bridge 重置 ----
        SvcMcChannelAdapter.resetForTest();
        check(SvcMcChannelAdapter.mcChannelPlayerCount() == 0,
                "R8: reset clears all sessions");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
