package de.maxhenkel.voicechat.api;
public interface RawUdpPacket {
 byte[] getData();
 long getTimestamp();
 java.net.SocketAddress getSocketAddress();
}
