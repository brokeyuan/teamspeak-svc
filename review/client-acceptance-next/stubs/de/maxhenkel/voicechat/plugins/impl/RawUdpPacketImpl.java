package de.maxhenkel.voicechat.plugins.impl;
/** Stub matching the 2.6.x impl (public constructor; implements the API interface). */
public class RawUdpPacketImpl implements de.maxhenkel.voicechat.api.RawUdpPacket {
 private final byte[] data;
 private final java.net.SocketAddress address;
 private final long timestamp;
 public RawUdpPacketImpl(byte[] data, java.net.SocketAddress address, long timestamp) {
  this.data = data; this.address = address; this.timestamp = timestamp;
 }
 public byte[] getData() { return data; }
 public java.net.SocketAddress getSocketAddress() { return address; }
 public long getTimestamp() { return timestamp; }
}
