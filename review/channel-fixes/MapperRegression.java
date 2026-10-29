package com.tsvoice.client;
import com.tsvoice.client.net.GroupInfoPayload;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.*;
import java.lang.reflect.*;
import java.util.*;

public final class MapperRegression {
 static UUID groupId;static String groupName;static boolean password;static String groupType="NORMAL";static int failures;
 static void check(boolean ok,String s){System.out.println((ok?"PASS ":"FAIL ")+s);if(!ok)failures++;}
 static boolean ready(Ts3VoiceClient c){try{return (boolean)GroupChannelMapper.class.getMethod("isReadyForVoice",Ts3VoiceClient.class).invoke(null,c);}catch(Exception e){return true;}}
 static void select(UUID id,String name,boolean pass){groupId=id;groupName=name;password=pass;groupType="NORMAL";}
 public static void main(String[] args)throws Exception{
  // T02：许可判定在共用确认路由 —— 用生产 API 建立会话（legacy 群组映射模式）。
  var sessionToken=com.tsvoice.client.connection.BackendCoordinator.beginSession();
  com.tsvoice.client.connection.TsChannelRoute.beginSession(sessionToken,
      com.tsvoice.client.connection.VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING,null);
  TeamspeakSvcClient.api=(VoicechatClientApi)Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),new Class[]{VoicechatClientApi.class},(p,m,a)->m.getName().equals("getGroup")&&groupId!=null?(Group)Proxy.newProxyInstance(Group.class.getClassLoader(),new Class[]{Group.class},(p2,m2,a2)->switch(m2.getName()){case "getId"->groupId;case "getName"->groupName;case "hasPassword"->password;case "getType"->"OPEN".equals(groupType)?Group.Type.OPEN:"ISOLATED".equals(groupType)?Group.Type.ISOLATED:Group.Type.NORMAL;default->null;}):null);
  Ts3VoiceClient c=new Ts3VoiceClient();
  GroupChannelMapper.reset();select(UUID.randomUUID(),"Private",true);
  GroupChannelMapper.tick(c);
  try{Field f=GroupChannelMapper.class.getDeclaredField("groupChangedAt");f.setAccessible(true);f.setLong(null,0);}catch(NoSuchFieldException ignored){}
  GroupChannelMapper.tick(c);
  check(c.creates==0&&c.moves==0,"password unavailable never creates or joins public fallback after timeout");
  check(!ready(c),"password unavailable blocks voice");
  GroupChannelMapper.reset();select(UUID.randomUUID(),"Lobby",false);c.creates=0;c.moves=0;
  GroupChannelMapper.tick(c);
  check(c.moves==0&&c.creates==1,"wrong-parent global match is never reused");
  check(!ready(c),"unavailable group target blocks voice");
  c.children.put("Lobby",10);GroupChannelMapper.tick(c);
  check(!ready(c),"unconfirmed channel move blocks voice");
  c.current=10;GroupChannelMapper.tick(c);check(ready(c),"confirmed matching target permits voice");
  select(UUID.randomUUID(),"Another",false);check(!ready(c),"live group change blocks immediately before mapper tick");
  GroupChannelMapper.reset();select(null,null,false);c.current=10;c.lastPassword=null;
  GroupChannelMapper.tick(c);check("main-secret".equals(c.lastPassword),"main channel receives separate channel password");
  GroupChannelMapper.reset();UUID mirror=new UUID(0x7473766380018000L,0x8000000000000042L);
  select(mirror,"123456789012345678901234",false);c.creates=0;c.moves=0;
  GroupChannelMapper.tick(c);check(c.creates==0&&c.moves==0,"manual mirror waits for authoritative target info without beacon");
  GroupChannelMapper.onGroupInfo(new GroupInfoPayload(mirror,"123456789012345678901234X",null,0));
  c.children.put("123456789012345678901234X",12);GroupChannelMapper.tick(c);
  check(c.moves==1&&c.creates==0,"long-name mirror locates exact original TS target");
  GroupChannelMapper.reset();select(UUID.randomUUID(),"OpenLocal",false);groupType="OPEN";
  c.current=1;c.creates=0;c.moves=0;GroupChannelMapper.tick(c);
  check(c.moves==0&&c.creates==0&&ready(c),"local OPEN group stays in main channel");
  GroupChannelMapper.reset();select(mirror,"OpenMirror",false);groupType="OPEN";
  GroupChannelMapper.onGroupInfo(new GroupInfoPayload(mirror,"OpenMirrorTS",null,0));
  c.children.put("OpenMirrorTS",14);c.current=1;c.creates=0;c.moves=0;GroupChannelMapper.tick(c);
  check(c.moves==1&&c.creates==0,"OPEN mirror group still maps to TS child");
  c.current=14;GroupChannelMapper.tick(c);
  check(ready(c),"confirmed OPEN mirror target permits voice");
  if(failures>0)throw new AssertionError(failures+" mapping regressions failed");
 }
}
