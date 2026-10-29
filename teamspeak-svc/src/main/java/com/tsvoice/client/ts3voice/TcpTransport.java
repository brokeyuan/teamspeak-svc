package com.tsvoice.client.ts3voice;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * TCP 隧道传输：与 tsbridge 的 TcpTunnelServer 配对。
 * 帧协议：4 字节大端长度 + 载荷（一个 TS3 UDP 包为一帧）。
 * 用于 UDP 受限网络（校园/公司网）；延迟抖动高于 UDP，定位为弱网兜底。
 */
public class TcpTransport implements TsTransport {

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;

    @Override
    public void connect(InetAddress address, int port) throws IOException {
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(address, port), 10_000);
        socket.setTcpNoDelay(true);
        // 略大于应用层 30s 超时，避免两个计时器同时竞争（由连接层给出明确日志）
        socket.setSoTimeout(0);
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        String secret = System.getenv("TBRIDGE_TUNNEL_SECRET");
        if (secret == null || secret.length() < 32) {
            close();
            throw new IOException("TCP tunnel requires TBRIDGE_TUNNEL_SECRET (at least 32 characters)");
        }
        var ticket = com.tsvoice.protocol.TunnelTicket.issue(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                System.currentTimeMillis() / 1000L, 60);
        out.write(ticket.encode());
        out.flush();
    }

    @Override
    public synchronized void send(byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    @Override
    public byte[] receive(int maxLen) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > Math.max(maxLen, 4096)) {
            throw new IOException("bad frame length " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    @Override
    public boolean isClosed() {
        return socket == null || socket.isClosed();
    }

    @Override
    public void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
