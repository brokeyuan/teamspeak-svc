package com.tsvoice.client.ts3voice;
public class Ts3Connection {
 public Ts3Connection(Ts3Crypt crypt,com.tsvoice.client.ts3voice.TsTransport transport){}
 public Ts3Crypt crypt(){return new Ts3Crypt(null);}
 public void bindClient(Object c){}
 public void setOnDisconnected(Runnable r){}
}