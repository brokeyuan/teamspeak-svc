package com.tsvoice.server.relay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个被批准的中继会话（S1.3/P6）：绑定真实 MC 连接代际（connectionId）+
 * player UUID + 16 字节 relaySessionId，对应一个 connected UDP Socket
 * （回包只来自绑定目标，不能全服共用一个 UDP 源）。服务端只中继完整 TS
 * 数据报 —— 不解码、不混音、不持有玩家 TS 私钥；TS 的身份/密钥/频道权限/
 * 加密仍由客户端协议会话掌握。
 *
 * <p>资源边界：阻塞 receive 在专属 worker 线程（绝不占 MC 主线程）；socket
 * 级错误关闭会话并通知 runtime；{@link #close()} 幂等，关闭唤醒阻塞 receive。
 * 限流/计费与 MC 侧投递由 {@link TsRelayRuntime} 协调。</p>
 */
final class TsRelaySession {

    /** UDP 收包缓冲上限（TS3 包 ≤ ~1.3 KiB；留裕量并拒绝异常大包）。 */
    static final int MAX_UDP_RECEIVE_BYTES = 2048;

    /** 会话统计（诊断/限额复核）。 */
    final AtomicLong datagramsRelayed = new AtomicLong();
    final AtomicLong bytesRelayed = new AtomicLong();

    private final String playerUuid;
    private final String connectionId;
    private final String targetId;
    private final byte[] relaySessionId;
    private final TsRelayRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread worker;
    private volatile DatagramSocket socket;
    /** 会话最后活跃时刻（毫秒；空闲超时清理用）。 */
    private volatile long lastActiveMs = System.currentTimeMillis();

    TsRelaySession(String playerUuid, String connectionId, String targetId,
                   byte[] relaySessionId, TsRelayRuntime runtime) {
        this.playerUuid = playerUuid;
        this.connectionId = connectionId;
        this.targetId = targetId;
        this.relaySessionId = relaySessionId.clone();
        this.runtime = runtime;
        this.worker = new Thread(this::udpLoop, "tsvoice-relay-"
                + Integer.toHexString(relaySessionId[0] & 0xFF));
        this.worker.setDaemon(true);
    }

    String playerUuid() {
        return playerUuid;
    }

    String connectionId() {
        return connectionId;
    }

    String targetId() {
        return targetId;
    }

    byte[] relaySessionId() {
        return relaySessionId.clone();
    }

    long lastActiveMs() {
        return lastActiveMs;
    }

    boolean isClosed() {
        return closed.get();
    }

    /**
     * 建立到目标 endpoint 的 UDP 通道并启动中继线程。DNS/socket 失败抛出，
     * 由 runtime 回 ERROR（目标不可用）并回收会话。
     */
    void start(String host, int port) throws IOException {
        InetAddress resolved = InetAddress.getByName(host); // 服务端解析（客户端不做 DNS）
        DatagramSocket sock = new DatagramSocket();
        try {
            sock.connect(new InetSocketAddress(resolved, port));
        } catch (IOException e) {
            sock.close();
            throw e;
        }
        this.socket = sock;
        worker.start();
    }

    /** 下行：MC 通道 → UDP 目标（完整 TS 数据报原样发出；有界投递由 runtime 排队）。 */
    void sendToTarget(byte[] datagram) throws IOException {
        if (closed.get()) {
            throw new IOException("session closed");
        }
        DatagramSocket sock = socket;
        if (sock == null || sock.isClosed()) {
            throw new IOException("target socket closed");
        }
        lastActiveMs = System.currentTimeMillis();
        sock.send(new DatagramPacket(datagram, datagram.length));
        datagramsRelayed.incrementAndGet();
        bytesRelayed.addAndGet(datagram.length);
    }

    /** 上行循环：阻塞 receive → 经 runtime 有界投递给 MC 通道。 */
    private void udpLoop() {
        byte[] buffer = new byte[MAX_UDP_RECEIVE_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!closed.get()) {
            DatagramSocket sock = socket;
            if (sock == null || sock.isClosed()) {
                break;
            }
            try {
                sock.setSoTimeout(500);
                try {
                    sock.receive(packet);
                } catch (SocketTimeoutException e) {
                    runtime.sweepIdleSessions();
                    continue; // 周期性醒来检查 closed 与空闲清理
                }
                if (packet.getLength() <= 0 || packet.getLength() > MAX_UDP_RECEIVE_BYTES) {
                    continue;
                }
                byte[] datagram = new byte[packet.getLength()];
                System.arraycopy(buffer, 0, datagram, 0, packet.getLength());
                lastActiveMs = System.currentTimeMillis();
                datagramsRelayed.incrementAndGet();
                bytesRelayed.addAndGet(datagram.length);
                // 回包来源已由 connected socket 保证（只接受绑定目标）。
                runtime.deliverToMc(this, datagram);
            } catch (IOException e) {
                if (!closed.get()) {
                    close();
                    runtime.onSessionDead(this, "relay socket error: " + e.getMessage());
                    break;
                }
            }
        }
    }

    /** 幂等关闭：关 socket（唤醒阻塞 receive）；worker 随之退出。 */
    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        DatagramSocket sock = socket;
        if (sock != null && !sock.isClosed()) {
            sock.close();
        }
    }
}
