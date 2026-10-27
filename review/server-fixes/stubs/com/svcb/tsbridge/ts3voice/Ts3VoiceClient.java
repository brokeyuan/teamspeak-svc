package com.svcb.tsbridge.ts3voice;
public class Ts3VoiceClient {
 public static final int CODEC_OPUS_VOICE=4;
 public static java.util.concurrent.CountDownLatch entered,release;
 public static Ts3VoiceClient last;public boolean disconnected;
 public Ts3VoiceClient(Ts3Identity id,String n,String c){last=this;}
 public void connect(java.net.InetAddress a,int p,long t){entered.countDown();boolean done=false;while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}}
 public boolean isConnected(){return !disconnected;}public void disconnect(){disconnected=true;}
 public void sendVoice(byte[] data,int codec){} public void joinChannel(int c){}public int getMyChannelId(){return -1;}public int getMyClid(){return 1;}public String getMyUid(){return "test-uid";}
}