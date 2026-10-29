package com.tsvoice.client.ts3voice;
import java.io.IOException;
public final class TsMcChannelTransport implements TsTransport {
 public void connect(java.net.InetAddress address,int port){}
 public void send(byte[] data) throws IOException{throw new IOException("not wired");}
 public byte[] receive(int maxLen) throws IOException{throw new IOException("not wired");}
 public boolean isClosed(){return true;}
 public void close(){}
 public void attachRelaySession(byte[] id,int max) throws IOException{}
 public void setRelaySender(java.util.function.Consumer<byte[]> sender){}
 public void onRelayDatagram(byte[] datagram){}
 public long droppedReceiveFrames(){return 0;}
 public byte[] relaySessionId(){return null;}
 public void closeWithException(IOException reason){}
}