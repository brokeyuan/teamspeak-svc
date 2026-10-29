package com.tsvoice.client.ts3voice;
import java.util.*;
public class Ts3VoiceClient {
 public final java.util.List<String> sentCommands=new java.util.concurrent.CopyOnWriteArrayList<>();
 public volatile boolean failNextSend;
 public volatile com.tsvoice.client.Ts3FileTransfer.Result lastUploadResult;
 public final TsCommandTracker tracker=new TsCommandTracker(this);
 public int myChannel=1;
 public int joinCalls; public int lastJoinCid;
 public Map<Integer,String> channelNames=new java.util.concurrent.ConcurrentHashMap<>();
 public Map<Integer,Integer> channelParents=new java.util.concurrent.ConcurrentHashMap<>();
 public Ts3VoiceClient(Ts3Identity id,String nickname,String channel){}
 public TsCommandTracker commandTracker(){return tracker;}
 public void sendCommand(String cmd) throws Ts3VoiceException {
  if(failNextSend){throw new Ts3VoiceException("socket gone");}
  sentCommands.add(cmd);
 }
 public String getClientUid(){return "uid";}
 public String getVirtualServerUid(){return "fixture-server";}
 public boolean isConnected(){return true;}
 public static java.util.Map<String,String> parseParams(String row){
  java.util.Map<String,String> out=new java.util.HashMap<>();
  if(row==null){return out;}
  for(String token:row.trim().split("\s+")){
   int eq=token.indexOf('=');
   if(eq>0){out.put(token.substring(0,eq),token.substring(eq+1));}
  }
  return out;
 }
 public int getMyChannelId(){return myChannel;}
 public TsChannelSnapshot channelSnapshot(){
  TsChannelSnapshot snap=new TsChannelSnapshot("fixture-server");
  List<TsChannelSnapshot.Channel> list=new ArrayList<>();
  for(Map.Entry<Integer,String> e:channelNames.entrySet()){
   list.add(new TsChannelSnapshot.Channel(e.getKey(),channelParents.getOrDefault(e.getKey(),0),e.getValue(),false,0,1,10));
  }
  snap.replaceAll(list);return snap;
 }
 public Integer getChannelIdByName(String name){return null;}
 public Integer getSubChannelIdByName(String name,int parent){return null;}
 public void joinChannel(int cid,String channelPassword){joinCalls++;lastJoinCid=cid;}
 public void requestChannelList() throws Ts3VoiceException{}
 public void clearQueuedVoice(){}
}