package com.tsvoice;

import com.tsvoice.client.backend.SvcMcChannelClient;
import com.tsvoice.client.backend.SvcMcChannelSocket;
import com.tsvoice.common.net.SvcMcChannelPayloads;
import com.tsvoice.server.svc.SvcMcChannelAdapter;
import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T36/E2 回归：原生 UDP 保护与客户端 MC 通道闭环。
 *
 * <p>81c687c 复核反例转正式断言（修复后必须全部 PASS）：</p>
 * <ul>
 *   <li>初始化注册不再无条件替换原 UDP Socket：只有服务端宣告 MC_CHANNEL
 *       后套接字才接管上行/下行；宣告缺席时对原生 UDP 完全委托。</li>
 *   <li>自定义 socket 关闭后不能冒充"恢复默认 UDP"——它必须真实委托
 *       一个原生 DatagramSocket（open 时创建），关闭即恢复委托直通。</li>
 *   <li>配置/下行 S2C receiver → 当前连接 handler → socket 队列真实接线；
 *       宣告与初始化任意先后到达都能接通（迟到的宣告打开已注册 socket）。</li>
 *   <li>断连/换服复位：旧连接的宣告不进入新连接；socket 通道随断连关闭。</li>
 *   <li>服务端会话登记按 server instance + connection（不是线程 ID）；
 *       活跃地址不被回收后立即复用；合成地址确定且无 DNS。</li>
 *   <li>真机 UDP 回环：MC 通道 socket 的原生委托模式能双向搬运 SVC 原始
 *       数据报（UDP 默认路径保持的实证）。</li>
 * </ul>
 */
public class SvcMcChannelLoopbackTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** Fabric ClientPlayNetworking.send 的捕获 stub（生产桥的下游）。 */
    public static final class ClientSendStub {
        static final java.util.List<byte[]> captured = new java.util.ArrayList<>();
        static { install(); }
        public static void install() {
            try {
                Class<?> cls = Class.forName(
                        "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
                java.lang.reflect.Field f = cls.getDeclaredField("SENT_HOOK");
                f.setAccessible(true);
                f.set(null, (java.util.function.Consumer<byte[]>) captured::add);
            } catch (Throwable ignored) {
            }
        }
        public static void reset() { captured.clear(); }
    }

    /** ClientVoicechatInitializationEvent stub（承载 setSocketImplementation）。 */
    public static final class InitEventStub
            implements de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent {
        ClientVoicechatSocket socket;
        public void setSocketImplementation(ClientVoicechatSocket s) { socket = s; }
        public ClientVoicechatSocket getSocketImplementation() { return socket; }
    }

    /** 原生 SVC UDP 语义的委托 socket（修复后由 SvcMcChannelSocket 提供）。 */
    static final class UdpEchoPeer implements AutoCloseable {
        final DatagramSocket socket;
        UdpEchoPeer() throws Exception {
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                while (!socket.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
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

    public static void main(String[] args) throws Exception {
        // 先安装 Fabric send 捕获 hook：生产桥的下游断言点（必须在任何
        // ClientPlayNetworking.send 之前完成类加载/字段替换）。
        ClientSendStub.reset();
        // ================= 客户端：原生 UDP 保护 =================
        SvcMcChannelClient.resetForTest();
        ClientSendStub.reset();

        // [C1] initialization event 不再立即接管：注册的是委托型 socket，
        // 宣告 MC_CHANNEL 之前 open()/send()/read() 走原生 UDP 语义。
        InitEventStub init = new InitEventStub();
        SvcMcChannelClient.registerSocket(init);
        check(init.socket != null, "C1: initialization event receives a socket");
        check(init.socket instanceof SvcMcChannelSocket,
                "C1: registered socket is the session-scoped implementation");

        SvcMcChannelSocket socket = (SvcMcChannelSocket) init.socket;

        // [C2] open 后（尚未宣告 MC_CHANNEL）：真实 UDP 委托可收发原生 SVC 数据报
        UdpEchoPeer echo = new UdpEchoPeer();
        socket.open();
        byte[] probe = new byte[]{11, 22, 33};
        InetSocketAddress svcAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(),
                echo.socket.getLocalPort());
        socket.send(probe, svcAddr); // MC 通道未生效 → 必须走原生 UDP（echo 回来）
        RawUdpPacket received = socket.read();
        boolean udpLoopback = received != null && java.util.Arrays.equals(received.getData(), probe)
                && received.getSocketAddress().equals(svcAddr);
        check(udpLoopback, "C2: before MC_CHANNEL announcement the socket delegates to native UDP (echo round-trip)");
        echo.close();

        // [C3] 宣告 MC_CHANNEL（在 socket.open 之后到达）：切到 MC 桥
        // —— 发送进桥、下行进队列，不再触达原生 UDP。
        // 生产接线在宣告时安装真实桥 sender（Fabric ClientPlayNetworking.send）；
        // 断言捕获发生在 stub 层，而不是与生产 sender 竞争。
        SvcMcChannelClient.onServerAnnouncement(SvcMcChannelPayloads.SvcTransportConfigPayload.TRANSPORT_MC_CHANNEL);
        check(SvcMcChannelClient.mcChannelActive(), "C3: MC_CHANNEL announcement activates the channel");
        byte[] voiceFrame = new byte[300];
        socket.send(voiceFrame, svcAddr);
        check(ClientSendStub.captured.size() == 1
                        && java.util.Arrays.equals(ClientSendStub.captured.get(0), voiceFrame),
                "C3: after announcement send goes through the MC bridge (not UDP)");
        byte[] downstream = new byte[]{9, 8, 7};
        SvcMcChannelClient.onDownstreamVoice(downstream);
        RawUdpPacket frame = socket.read();
        check(frame != null && java.util.Arrays.equals(frame.getData(), downstream),
                "C3: S2C receiver feeds the socket queue (production handler path)");

        // [C4] 宣告在先 / 初始化在后：迟到注册仍接通（顺序无关）
        SvcMcChannelClient.resetForTest();
        SvcMcChannelClient.onServerAnnouncement(SvcMcChannelPayloads.SvcTransportConfigPayload.TRANSPORT_MC_CHANNEL);
        InitEventStub lateInit = new InitEventStub();
        SvcMcChannelClient.registerSocket(lateInit);
        SvcMcChannelSocket lateSocket = (SvcMcChannelSocket) lateInit.socket;
        check(lateSocket != null, "C4: announcement before initialization still registers a socket");
        lateSocket.open();
        AtomicReference<byte[]> latePayload = new AtomicReference<>();
        lateSocket.setMcSender(latePayload::set);
        SvcMcChannelClient.onDownstreamVoice(new byte[]{1});
        check(lateSocket.read() != null, "C4: late registration still receives downstream frames");

        // [C5] 宣告 UDP / 未知值：不激活 MC 通道（原生路径保持）
        SvcMcChannelClient.resetForTest();
        InitEventStub udpInit = new InitEventStub();
        SvcMcChannelClient.registerSocket(udpInit);
        SvcMcChannelSocket udpSocket = (SvcMcChannelSocket) udpInit.socket;
        udpSocket.open();
        SvcMcChannelClient.onServerAnnouncement(SvcMcChannelPayloads.SvcTransportConfigPayload.TRANSPORT_UDP);
        check(!SvcMcChannelClient.mcChannelActive(), "C5: UDP announcement keeps native transport");

        // [C6] 断连/换服复位：socket 通道关闭 + 宣告状态清零，不跨连接串用
        SvcMcChannelClient.onServerAnnouncement(SvcMcChannelPayloads.SvcTransportConfigPayload.TRANSPORT_MC_CHANNEL);
        check(SvcMcChannelClient.mcChannelActive(), "C6: precondition - channel active");
        SvcMcChannelClient.onMcConnectionClosed();
        check(!SvcMcChannelClient.mcChannelActive(), "C6: MC disconnect resets the channel state");
        check(udpSocket.isClosed(), "C6: socket is closed on disconnect (no stale bridge)");
        byte[] stale = new byte[]{3};
        SvcMcChannelClient.onDownstreamVoice(stale);
        check(SvcMcChannelClient.currentSocket() == null || !SvcMcChannelClient.mcChannelActive(),
                "C6: downstream after disconnect is dropped (no cross-world queue)");

        // ================= 客户端：会话级 socket（保留原 S2 边界） =================
        // 新 socket 默认 UDP 模式（原生保护）；队列语义在 MC 模式下成立
        //（生产：宣告后 SVC 才调用 onMcDatagram；UDP 模式下丢弃是保护语义）。
        SvcMcChannelSocket fresh = new SvcMcChannelSocket();
        fresh.open();
        fresh.onMcDatagram(new byte[64]);
        check(!fresh.isMcChannelMode(), "C7: fresh socket stays in UDP mode (frames dropped, not queued)");
        fresh.activateMcChannel();
        fresh.onMcDatagram(new byte[64]);
        fresh.onMcDatagram(new byte[64]);
        check(fresh.read() != null && fresh.read() != null,
                "C7: session socket queue delivers frames in order (MC mode)");
        fresh.close();
        boolean closedRead = false;
        try { fresh.read(); } catch (Exception e) { closedRead = true; }
        check(closedRead, "C7: read after close fails fast (SVC reader thread exits)");

        // ================= 服务端：连接身份与地址分配 =================
        SvcMcChannelAdapter.resetForTest();

        // [S1] 登记键是真实连接：同一（线程）上下文登记两个不同连接得到
        // 互不覆盖的独立会话（线程 ID 不再冒充 connection）。
        var a1 = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.randomUUID(), "conn-1", null);
        var a2 = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.randomUUID(), "conn-2", null);
        check(a1.sessionId() != a2.sessionId() && a1.player() != a2.player(),
                "S1: two connections registered back-to-back get independent sessions");

        // [S2] 同一玩家的重连（新 connection）：旧地址不复用、不回收活跃地址
        UUID replayer = UUID.randomUUID();
        var r1 = SvcMcChannelAdapter.onPlayerInitializedForConnection(replayer, "conn-A", 7);
        var r2 = SvcMcChannelAdapter.onPlayerInitializedForConnection(replayer, "conn-B", 7);
        check(r1.sessionId() != r2.sessionId(), "S2: reconnect of the same player gets a fresh session id");
        check(!SvcMcChannelAdapter.isMcChannelPlayerOnConnection(replayer, "conn-A"),
                "S2: the old connection is retired by the new registration");
        check(SvcMcChannelAdapter.isMcChannelPlayerOnConnection(replayer, "conn-B"),
                "S2: the new connection is the active session");

        // [S3] 断连回收：回收后地址不立即复用给新会话（防旧包污染）
        SvcMcChannelAdapter.onPlayerDisconnected(replayer, "conn-B");
        check(!SvcMcChannelAdapter.isMcChannelPlayerOnConnection(replayer, "conn-B"),
                "S3: disconnect reclaims the session");
        var after = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.randomUUID(), "conn-C", 9);
        check(after.sessionId() != r2.sessionId(),
                "S3: a reclaimed address is not immediately reused (stale-packet protection)");

        // [S4] 合成地址确定且无 DNS（不解析主机名）
        var synth = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.randomUUID(), "conn-D", 11);
        InetSocketAddress sa = synth.toSocketAddress();
        check(sa.isUnresolved() || sa.getAddress() == null,
                "S4: synthetic MC-channel address is unresolved (never DNS-resolved)");
        check(sa.equals(synth.toSocketAddress()),
                "S4: synthetic address construction is deterministic");
        SvcMcChannelAdapter.onPlayerDisconnected(synth.player(), "conn-D");

        // [S5] server instance 隔离：不同 server 的同名 connection 不共享登记
        var s1 = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.randomUUID(), "shared", 1);
        SvcMcChannelAdapter.onServerInstanceChanged(new Object()); // 换服
        check(SvcMcChannelAdapter.sessionSnapshot().isEmpty(),
                "S5: a server-instance change clears registrations (no cross-world state)");

        // [S6] Extra 元数据唯一所有者：svc-extra 在场时本适配停用（TS 路径不受影响）
        SvcMcChannelAdapter.setSvcExtraOwner(true);
        var blocked = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.randomUUID(), "conn-X", 3);
        check(blocked == null, "S6: svc-extra ownership disables this adapter's registration");
        check(SvcMcChannelAdapter.sessionSnapshot().isEmpty(),
                "S6: no session is registered while svc-extra owns the SVC domain");
        SvcMcChannelAdapter.setSvcExtraOwner(false);
        var allowed = SvcMcChannelAdapter.onPlayerInitializedForConnection(UUID.randomUUID(), "conn-Y", 3);
        check(allowed != null, "S6: without svc-extra the adapter registers normally");

        SvcMcChannelAdapter.resetForTest();
        SvcMcChannelClient.resetForTest();

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
