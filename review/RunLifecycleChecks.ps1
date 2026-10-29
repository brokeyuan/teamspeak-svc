$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$stubs = @{
    'com/tsvoice/client/TeamspeakSvcClient.java' = @'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("review");
 public static de.maxhenkel.voicechat.api.VoicechatClientApi api;
 public static de.maxhenkel.voicechat.api.VoicechatClientApi clientApi(){return api;}
}
'@
    'com/tsvoice/client/ClientStateReflector.java' = @'
package com.tsvoice.client;
import java.util.*;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
public class ClientStateReflector {
 public static Map<UUID,String> active=new HashMap<>(), states=new HashMap<>();
 public static Map<UUID,String> activeGhosts(){return active;}
 public static boolean hasLocalState(UUID id){return states.containsKey(id);}
 public static void injectGhost(UUID id,String name,VoicechatClientApi api,UUID group){active.put(id,name);states.put(id,"injected");}
 public static void removeGhost(UUID id){active.remove(id);states.remove(id);}
 public static void updateUsernameCache(UUID id,String name){}
 public static Map<String,UUID> clientGroupsByName(){return Map.of();}
}
'@
    'com/tsvoice/client/GhostSkins.java' = @'
package com.tsvoice.client;
public class GhostSkins {public static void register(java.util.UUID a,java.util.UUID b,String n){} public static void unregister(java.util.UUID a){}}
'@
    'com/tsvoice/client/OnlinePlayers.java' = @'
package com.tsvoice.client;
public class OnlinePlayers {public static boolean isOnline(java.util.UUID id){return false;}}
'@
    'de/maxhenkel/voicechat/voice/client/ClientManager.java' = @'
package de.maxhenkel.voicechat.voice.client;
public class ClientManager {public static Object current; public static Object getClient(){return current;}}
'@
    'com/tsvoice/client/ts3voice/LifecycleAudit.java' = @'
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
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
    $path = Join-Path 'review/runtime-stubs' $item.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path (Get-Location) $path), $item.Value)
    $path
}
& javac -encoding UTF-8 -cp 'review/classes;review/lib/*' -d 'review/classes' @sources 'teamspeak-svc/src/main/java/com/tsvoice/client/TsAudioInjector.java'
if ($LASTEXITCODE -ne 0) {throw 'Compilation failed'}
& java -cp 'review/classes;review/lib/*' com.tsvoice.client.ts3voice.LifecycleAudit
if ($LASTEXITCODE -ne 0) {throw 'Probe failed'}
