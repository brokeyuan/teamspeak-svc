package com.tsvoice.client.ts3voice;
import java.util.*;
public final class Ts3VoiceClient {
 public int current=1,creates=0,moves=0;public String lastPassword,lastCreated;public Map<String,Integer> children=new HashMap<>();
 public boolean isConnected(){return true;}
 public Integer getChannelIdByName(String n){return n.equals("Main")?1:99;}
 public Integer getSubChannelIdByName(String n,int p){return p==1?children.get(n):null;}
 public int getMyChannelId(){return current;}
 public com.tsvoice.client.ts3voice.TsChannelSnapshot channelSnapshot(){return new com.tsvoice.client.ts3voice.TsChannelSnapshot(null);}
 public void requestChannelList(){} public void findChannel(String n){}
 public void createChannel(String n,int p,String pass){creates++;lastPassword=pass;lastCreated=n;}
 public void joinChannel(int id,String pass){moves++;lastPassword=pass;}
 public record MoveResult(boolean succeeded,Integer errorCode,String errorMessage){}
 public java.util.concurrent.CompletableFuture<MoveResult> moveChannelAsync(int id,String pass,long timeoutMillis){
  moves++;lastPassword=pass;
  return java.util.concurrent.CompletableFuture.completedFuture(new MoveResult(true,null,null));
 }
 public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier){}
 public void clearQueuedVoice(){}
 public String getVirtualServerUid(){return "mapper-fixture";}
}