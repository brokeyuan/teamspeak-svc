package com.tsvoice.client.ts3voice;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * TS3 包传输层抽象：UDP（原生）或 TCP 隧道（帧协议，见 {@link TcpTransport}）。
 * Ts3Connection 的全部收发经此接口，协议逻辑与传输无关。
 */
public interface TsTransport extends AutoCloseable {

    /** 建立传输（连接对端）。 */
    void connect(InetAddress address, int port) throws IOException;

    /** 发送一个 TS3 包（含 MAC+header 的完整字节）。 */
    void send(byte[] data) throws IOException;

    /**
     * 阻塞接收一个包（最长 maxLen 字节）。
     *
     * <p>实现以抛 {@link IOException}（SocketException / SocketTimeoutException / EOFException）
     * 表示传输关闭或对端断开；UDP/TCP 实现均不返回 null。</p>
     */
    byte[] receive(int maxLen) throws IOException;

    /** 传输是否已关闭。 */
    boolean isClosed();

    @Override
    void close();

    /** UDP 实现（包装 DatagramSocket，与 v0.4 行为一致）。 */
    class UdpTransport implements TsTransport {

        private DatagramSocket socket;

        @Override
        public void connect(InetAddress address, int port) throws IOException {
            socket = new DatagramSocket();
            socket.connect(address, port);
        }

        @Override
        public void send(byte[] data) throws IOException {
            socket.send(new DatagramPacket(data, data.length));
        }

        @Override
        public byte[] receive(int maxLen) throws IOException {
            byte[] buf = new byte[maxLen];
            DatagramPacket dp = new DatagramPacket(buf, maxLen);
            socket.receive(dp);
            if (dp.getLength() == maxLen) {
                return buf;
            }
            byte[] out = new byte[dp.getLength()];
            System.arraycopy(buf, 0, out, 0, dp.getLength());
            return out;
        }

        @Override
        public boolean isClosed() {
            return socket == null || socket.isClosed();
        }

        @Override
        public void close() {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
