package com.tsvoice.client.ts3voice;
import com.tsvoice.client.*;
import java.util.*;
import java.lang.reflect.*;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.voice.client.ClientManager;
public class LifecycleAudit {
 public static void main(String[] args) throws Exception {
  UUID groupId=UUID.randomUUID(), playerId=UUID.randomUUID();
  Group group=(Group)Proxy.newProxyInstance(Group.class.getClassLoader(),new Class[]{Group.class},(p,m,a)->m.getName().equals("getId")?groupId:null);
  TeamspeakSvcClient.api=(VoicechatClientApi)Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),new Class[]{VoicechatClientApi.class},(p,m,a)->m.getName().equals("getGroup")?group:null);
  Method cached=TsAudioInjector.class.getDeclaredMethod("clientVoicechat");cached.setAccessible(true);
  Object first=new Object(), second=new Object();ClientManager.current=first;
  cached.invoke(null);TsAudioInjector.removeAll();ClientManager.current=second;
  System.out.println("after reconnect, cached SVC client is old instance -> "+(cached.invoke(null)==first));

  Ts3VoiceClient c=new Ts3VoiceClient(Ts3Identity.generate(0,1),"review","");
  c.onCommand("cliententerview clid=1 ctid=7 client_unique_identifier="+c.getMyUid()+" client_nickname=Me",false);
  c.onCommand("cliententerview clid=42 ctid=7 client_unique_identifier=remote client_nickname=Player",false);
  ClientStateReflector.states.put(playerId,"native");
  TsAudioInjector.onTsFrame("remote","Player",playerId,0,4,new byte[]{1});
  System.out.println("before visibility sync, real player state -> "+ClientStateReflector.states.get(playerId));
  TsAudioInjector.syncAllGhostVisibility(c);
  System.out.println("after visibility sync, real player state -> "+ClientStateReflector.states.get(playerId));
  TsAudioInjector.removeAll();
  System.out.println("after cleanup, real player state still present -> "+ClientStateReflector.states.containsKey(playerId));

  TeamspeakSvcClient.api=(VoicechatClientApi)Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),new Class[]{VoicechatClientApi.class},(p,m,a)->null);
  TsAudioInjector.onTsFrame("remote","Player",null,1,4,new byte[]{1});
  System.out.println("no-group TS ghost before sync -> "+ClientStateReflector.active.size());
  TsAudioInjector.syncAllGhostVisibility(c);
  System.out.println("no-group TS ghost after sync -> "+ClientStateReflector.active.size());
 }
}