package com.tsvoice.client.ts3voice;
import java.io.IOException;
public interface TsTransport extends AutoCloseable {
 void connect(java.net.InetAddress address,int port) throws IOException;
 void send(byte[] data) throws IOException;
 default void send(byte[] data, long routeEpoch) throws IOException {send(data);}
 byte[] receive(int maxLen) throws IOException;
 boolean isClosed();
 @Override void close();
}