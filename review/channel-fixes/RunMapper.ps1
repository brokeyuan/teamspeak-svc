$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$stubs = @{
'com/tsvoice/client/TeamspeakSvcClient.java' = @'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("test");
 public static de.maxhenkel.voicechat.api.VoicechatClientApi api;
 public static de.maxhenkel.voicechat.api.VoicechatClientApi clientApi(){return api;}
}
'@
'com/tsvoice/client/TsVoiceManager.java' = @'
package com.tsvoice.client;
public class TsVoiceManager {
 public record SessionConfig(String channelPassword){}
 public static GroupChannelMapper.SessionInfo currentSession(){return new GroupChannelMapper.SessionInfo("Main");}
 public static SessionConfig currentSessionConfig(){return new SessionConfig("main-secret");}
 public static Object currentBeacon(){return null;}
 public static boolean hasExplicitBookmarkIntent(){return false;}
}
'@
'com/tsvoice/client/ClientStateReflector.java' = @'
package com.tsvoice.client;
public class ClientStateReflector {public static void refreshGroups(de.maxhenkel.voicechat.api.VoicechatClientApi a,de.maxhenkel.voicechat.api.Group g){}}
'@
'com/tsvoice/client/net/GroupInfoPayload.java' = @'
package com.tsvoice.client.net;
public record GroupInfoPayload(java.util.UUID id,String name,String password,int typeOrd){}
'@
'com/tsvoice/client/ts3voice/Ts3VoiceClient.java' = @'
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
 public String getVirtualServerUid(){return "mapper-fixture";}
}
'@
}
$sources=foreach($s in $stubs.GetEnumerator()){$path=Join-Path 'review/channel-fixes/mapper-stubs' $s.Key;New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null;[IO.File]::WriteAllText((Join-Path (Get-Location) $path),$s.Value);$path}
& javac -encoding UTF-8 -cp 'review/lib/*' -d 'review/channel-fixes/mapper-classes' @sources 'teamspeak-svc/src/main/java/com/tsvoice/client/GroupChannelMapper.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/TsChannelRoute.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsChannelSnapshot.java' 'review/channel-fixes/MapperRegression.java'
if($LASTEXITCODE -ne 0){throw 'Mapper compilation failed'}
& java -cp 'review/channel-fixes/mapper-classes;review/lib/*' com.tsvoice.client.MapperRegression
if($LASTEXITCODE -ne 0){throw 'Mapper regression failed'}
