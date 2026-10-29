package de.maxhenkel.voicechat.api;
public interface ClientVoicechatSocket {
 void open() throws Exception;
 RawUdpPacket read() throws Exception;
 void send(byte[] data, java.net.SocketAddress address) throws Exception;
 void close();
 boolean isClosed();
}
