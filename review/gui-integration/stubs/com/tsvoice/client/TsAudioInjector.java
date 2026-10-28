package com.tsvoice.client;
public class TsAudioInjector {
 public static int removeAllCalls;public static int onTsFrameCalls;
 public static void removeAll(){removeAllCalls++;}
 public static void reconcile(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void syncAllGhostVisibility(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void onTsFrame(String authority,String uid,String nick,java.util.UUID uuid,int seq,int codec,byte[] opus){onTsFrameCalls++;}
}