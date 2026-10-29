package com.tsvoice.client.ts3voice;
import java.io.IOException;
public interface TsTransport extends AutoCloseable {
 void connect(java.net.InetAddress address,int port) throws IOException;
 void send(byte[] data) throws IOException;
 byte[] receive(int maxLen) throws IOException;
 boolean isClosed();
 @Override void close();
}
