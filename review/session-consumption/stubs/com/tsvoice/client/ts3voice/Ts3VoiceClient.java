package com.tsvoice.client.ts3voice;
import java.util.*;
public class Ts3VoiceClient {
 public static final int CODEC_OPUS_VOICE=4;
 public static final java.util.Deque<java.util.function.Consumer<Ts3VoiceClient>> connectScripts =
         new java.util.concurrent.ConcurrentLinkedDeque<>();
 public boolean connected;
 public int sent;
 public boolean throwOnSend;
 public boolean throwOnJoin;
 public String virtualServerUid="fixture-server";
 public java.util.function.LongSupplier epochSupplier=()->Long.MIN_VALUE;
 public int myChannel=1;
 public int joinCalls; public int lastJoinCid; public String lastJoinPassword;
 public int proofRequests; public long lastSendEpoch=Long.MIN_VALUE;
 public Map<Integer,String> channelNames=new java.util.concurrent.ConcurrentHashMap<>();
 public Map<Integer,Integer> channelParents=new java.util.concurrent.ConcurrentHashMap<>();
 public java.util.function.Consumer<Ts3VoiceClient> onConnect;
 public java.util.function.BiConsumer<String,String> textListener;
 public java.util.function.Consumer<TsVoiceIn> voiceListener;
 public java.util.function.IntConsumer membershipListener;
 public record TsVoiceIn(int senderClid,int codec,int sequence,byte[] opus){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,boolean tcp){onConnect=connectScripts.poll();}
 public void setServerPassword(String password){}
 public void setTextListener(java.util.function.BiConsumer<String,String> l){textListener=l;}
 public void setVoiceListener(java.util.function.Consumer<TsVoiceIn> l){voiceListener=l;}
 public void setChannelMembershipListener(java.util.function.IntConsumer l){membershipListener=l;}
 public void connect(java.net.InetAddress host,int port,long timeout) throws Exception {
  if(onConnect!=null){onConnect.accept(this);}
  connected=true;
 }
 public void disconnect(){connected=false;}
 public boolean isConnected(){return connected;}
 public int getMyClid(){return 1;}
 public int getMyChannelId(){return myChannel;}
 public com.tsvoice.client.ts3voice.TsChannelSnapshot channelSnapshot(){
  com.tsvoice.client.ts3voice.TsChannelSnapshot snap=new com.tsvoice.client.ts3voice.TsChannelSnapshot(virtualServerUid);
  List<com.tsvoice.client.ts3voice.TsChannelSnapshot.Channel> list=new ArrayList<>();
  for(Map.Entry<Integer,String> e:channelNames.entrySet()){
   list.add(new com.tsvoice.client.ts3voice.TsChannelSnapshot.Channel(e.getKey(),
       channelParents.getOrDefault(e.getKey(),0),e.getValue(),false,0,1,10));
  }
  snap.replaceAll(list);return snap;
 }
 public Integer getChannelIdByName(String name){
  Integer found=null;
  for(Map.Entry<Integer,String> e:channelNames.entrySet()){
   if(e.getValue().equalsIgnoreCase(name)){if(found!=null)return null;found=e.getKey();}
  }
  return found;
 }
 public Integer getSubChannelIdByName(String name,int parent){
  Integer found=null;
  for(Map.Entry<Integer,String> e:channelNames.entrySet()){
   if(e.getValue().equalsIgnoreCase(name)&&channelParents.getOrDefault(e.getKey(),0)==parent){if(found!=null)return null;found=e.getKey();}
  }
  return found;
 }
 public void joinChannel(int cid,String channelPassword){
  if(throwOnJoin){throw new RuntimeException("server rejected move");}
  joinCalls++;lastJoinCid=cid;lastJoinPassword=channelPassword;
 }
 public void createChannel(String name,int parent,String password){}
 public void findChannel(String name){}
 public void simulateSelfMove(int cid){
  int prev=myChannel;myChannel=cid;
  if(prev!=cid&&membershipListener!=null){membershipListener.accept(1);}
 }
 public void sendCommand(String cmd) throws Ts3VoiceException{}
 public void requestChannelList() throws Ts3VoiceException{}
 public void requestServerInfo() throws Ts3VoiceException{}
 public String getVirtualServerUid(){return virtualServerUid;}
 public String getClientUid(){return "uid";}
 public void publishMcUuid(java.util.UUID id){}
 public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier){epochSupplier=supplier;}
 public void clearQueuedVoice(){}
 public void sendVoice(byte[] opus,int codec){sendVoice(opus,codec,Long.MIN_VALUE);}
 public void sendVoice(byte[] opus,int codec,long routeEpoch){
  if(routeEpoch!=epochSupplier.getAsLong()){return;}
  lastSendEpoch=routeEpoch;
  if(throwOnSend){throw new RuntimeException("send failure");}
  sent++;
 }
 public String getUniqueIdOf(int clid){return "uid";}
 public String getNicknameOf(int clid){return "nick";}
 public java.util.UUID getMcUuidOf(int clid){return null;}
 public void sendChannelText(String message){}
}