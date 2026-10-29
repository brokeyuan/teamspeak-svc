package com.tsvoice.client.ts3voice;
public class Ts3VoiceClient {
 public String virtualServerUid="fixture-server";
 public boolean connected=true;
 public int proofRequests;
 public Ts3VoiceClient(Ts3Identity id,String nickname,String channel){}
 public String getVirtualServerUid(){return virtualServerUid;}
 public String getClientUid(){return "uid";}
 public boolean isConnected(){return connected;}
}
