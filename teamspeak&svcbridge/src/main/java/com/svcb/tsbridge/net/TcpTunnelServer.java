package com.svcb.tsbridge.net;

import com.svcb.tsbridge.TsBridgeMod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP-over-TCP 隧道服务（teamspeak-svc 客户端 `transport: "tcp"` 的对端）。
 *
 * <p>用途：UDP 受限网络（校园/公司网）——客户端经 TCP 帧协议连本服务，
 * 每帧还原为一个 TS3 UDP 包转发到本机 TS 语音端口；TS 回包按来源 socket
 * 路由回对应 TCP 连接。帧协议：4 字节大端长度 + 载荷。</p>
 *
 * <p>资源模型：每 TCP 连接 = 1 读线程 + 1 UDP 回包线程 + 1 独立 UDP socket
 * （TS 以隧道机为源看到多路 UDP，互不串扰）。断开时 try/finally 全回收。</p>
 */
public final class TcpTunnelServer {

    private static volatile ServerSocket serverSocket;
    private static volatile Thread acceptThread;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    /** 受类锁保护，stop 必须关闭监听器及全部已接受的会话。 */
    private static final int MAX_SESSIONS = 32;
    private static final Set<TunnelSession> sessions = new HashSet<>();
    private static final com.tsvoice.protocol.TunnelTicketStore usedTickets =
            new com.tsvoice.protocol.TunnelTicketStore();

    private TcpTunnelServer() {
    }

    /** 启动隧道（幂等）。 */
    public static synchronized void start(int port, String tsHost, int tsVoicePort, String secret) {
        start(port, tsHost, tsVoicePort, secret, "127.0.0.1");
    }

    public static synchronized void start(int port, String tsHost, int tsVoicePort, String secret, String bindAddress) {
        if (running.get()) {
            return;
        }
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("TCP tunnel requires a secret of at least 32 characters");
        }
        usedTickets.clear();
        try {
            ServerSocket listener = new ServerSocket();
            listener.bind(new java.net.InetSocketAddress(
                    InetAddress.getByName(bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress), port));
            serverSocket = listener;
            running.set(true);
            acceptThread = new Thread(() -> acceptLoop(listener, tsHost, tsVoicePort, secret), "TsBridge-Tunnel-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            TsBridgeMod.LOGGER.info("[tsbridge] TCP tunnel started (tcp={} -> udp {}:{})",
                    port, tsHost, tsVoicePort);
        } catch (IOException e) {
            serverSocket = null;
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to start TCP tunnel on {}: {}", port, e.getMessage());
        }
    }

    public static synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket = null;
        acceptThread = null;
        for (TunnelSession session : sessions) {
            session.close();
        }
        sessions.clear();
        TsBridgeMod.LOGGER.info("[tsbridge] TCP tunnel stopped");
    }

    public static boolean isRunning() {
        return running.get();
    }

    private static void acceptLoop(ServerSocket listener, String tsHost, int tsVoicePort, String secret) {
        while (running.get() && serverSocket == listener) {
            Socket client = null;
            try {
                client = listener.accept();
                client.setTcpNoDelay(true);
                if (sessions.size() >= MAX_SESSIONS) {
                    client.close();
                    continue;
                }
                TunnelSession session = new TunnelSession(client);
                synchronized (TcpTunnelServer.class) {
                    // accept 可能刚返回时 stop/restart 已发生；旧监听器不能向新一代提交连接。
                    if (!running.get() || serverSocket != listener) {
                        session.close();
                        return;
                    }
                    sessions.add(session);
                    Thread t = new Thread(() -> serve(session, tsHost, tsVoicePort, secret), "TsBridge-Tunnel-Conn");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (client != null) {
                    try { client.close(); } catch (IOException ignored) {}
                }
                if (running.get() && serverSocket == listener) {
                    TsBridgeMod.LOGGER.debug("[tsbridge] Tunnel accept error: {}", e.getMessage());
                }
                return;
            }
        }
    }

    /** 单连接服务：TCP 帧 ↔ 独立 UDP socket。 */
    private static void serve(TunnelSession session, String tsHost, int tsVoicePort, String secret) {
        Socket tcp = session.tcp;
        DatagramSocket udp = session.udp;
        try {
            tcp.setSoTimeout(30_000);
            InetAddress tsAddr = InetAddress.getByName(tsHost);
            udp.connect(tsAddr, tsVoicePort);
            DataInputStream in = new DataInputStream(tcp.getInputStream());
            DataOutputStream out = new DataOutputStream(tcp.getOutputStream());
            byte[] ticketBytes = in.readNBytes(65);
            com.tsvoice.protocol.TunnelTicket ticket = com.tsvoice.protocol.TunnelTicket.decode(ticketBytes);
            long nowSeconds = System.currentTimeMillis() / 1000L;
            if (!usedTickets.verifyAndConsume(ticket,
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), nowSeconds)) {
                throw new IOException("Tunnel authentication failed");
            }
            // Only unauthenticated handshakes are time-limited. After authentication,
            // receive-only TS users may have no upstream TCP packets for long periods.
            tcp.setSoTimeout(0);

            // TS→客户端：独立线程从 UDP 收回包写回 TCP
            DatagramSocket udpRef = udp;
            DataOutputStream outRef = out;
            Thread pump = new Thread(() -> {
                byte[] buf = new byte[4096];
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                try {
                    while (!tcp.isClosed() && !udpRef.isClosed()) {
                        udpRef.receive(dp);
                        synchronized (outRef) {
                            outRef.writeInt(dp.getLength());
                            outRef.write(buf, 0, dp.getLength());
                            outRef.flush();
                        }
                    }
                } catch (Exception ignored) {
                    // 对端断开/超时：线程自然结束
                } finally {
                    // UDP 回包或 TCP 写失败时，同样唤醒阻塞的 TCP 读线程。
                    session.close();
                }
            }, "TsBridge-Tunnel-Uplink");
            pump.setDaemon(true);
            pump.start();

            // 客户端→TS：本线程读帧发 UDP
            while (!tcp.isClosed()) {
                int len = in.readInt();
                if (len < 0 || len > 4096) {
                    throw new IOException("bad frame length " + len);
                }
                byte[] frame = new byte[len];
                in.readFully(frame);
                udp.send(new DatagramPacket(frame, len, tsAddr, tsVoicePort));
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Tunnel connection ended: {}", e.getMessage());
        } finally {
            session.close();
            synchronized (TcpTunnelServer.class) {
                sessions.remove(session);
            }
        }
    }

    private static final class TunnelSession {
        final Socket tcp;
        final DatagramSocket udp;

        TunnelSession(Socket tcp) throws IOException {
            this.tcp = tcp;
            this.udp = new DatagramSocket();
        }

        void close() {
            udp.close();
            try { tcp.close(); } catch (IOException ignored) {}
        }
    }
}
