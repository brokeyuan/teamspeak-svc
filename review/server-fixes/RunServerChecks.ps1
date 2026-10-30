$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$root = 'review/server-fixes'
New-Item -ItemType Directory -Path "$root/lib" -Force | Out-Null
# gson.jar is tracked in review/lib and therefore present in every CI
# checkout. Only fall back to a Gradle cache search when it is missing
# (GRADLE_USER_HOME first: CI runners keep caches outside USERPROFILE).
if (!(Test-Path 'review/lib/gson.jar')) {
    $cacheRoots = @()
    if ($env:GRADLE_USER_HOME) { $cacheRoots += "$env:GRADLE_USER_HOME/caches/modules-2/files-2.1/com.google.code.gson/gson" }
    $cacheRoots += "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson"
    $jar = $null
    foreach ($cacheRoot in $cacheRoots) {
        $jar = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($jar) { break }
    }
    if (-not $jar) { throw 'gson.jar not found in review/lib or any Gradle cache' }
    Copy-Item -LiteralPath $jar.FullName -Destination "$root/lib/gson.jar" -Force
}
$stubs = @{
'com/svcb/tsbridge/TsBridgeMod.java' = @'
package com.svcb.tsbridge;
import java.util.*;
public class TsBridgeMod {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("server-test");
 public static com.svcb.tsbridge.config.BridgeConfig config;
 public static UUID bridgeGroupId;
 public static boolean perPlayerMode;
 public static Mixer mixer=new Mixer(); public static Forwarder forwarder; public static Pairings pairings;
 public static Server mcServer;
 public static class Mixer {public int sent; public boolean isRunning(){return true;}public void enqueue(UUID id,byte[] b){sent++;}}
 public static class Forwarder {public int sent; public void enqueue(UUID id,byte[] b,long seq){sent++;}}
 public static class Pairings {public String getTsIdentity(UUID id){return null;}}
 public static class Server {public Players getPlayerList(){return new Players();}}
 public static class Players {public List<Object> getPlayers(){return List.of();}}
}
'@
'net/fabricmc/loader/api/FabricLoader.java' = @'
package net.fabricmc.loader.api;
public class FabricLoader {public static java.nio.file.Path path;public static FabricLoader getInstance(){return new FabricLoader();}public java.nio.file.Path getConfigDir(){return path;}}
'@
'com/svcb/tsbridge/svc/OnlinePlayerRegistry.java' = @'
package com.svcb.tsbridge.svc;
public class OnlinePlayerRegistry {public static Object get(java.util.UUID id){return null;}}
'@
'com/svcb/tsbridge/svc/McAudioInjector.java' = @'
package com.svcb.tsbridge.svc;
public class McAudioInjector {public static void stopTsAudio(java.util.UUID id){}}
'@
'com/svcb/tsbridge/ts3voice/Ts3Identity.java' = @'
package com.svcb.tsbridge.ts3voice;
public class Ts3Identity {public static Ts3Identity load(java.nio.file.Path p){return new Ts3Identity();}public static Ts3Identity generate(int l,long t){return new Ts3Identity();}public void save(java.nio.file.Path p){}public String getClientUid(){return "test-uid";}}
'@
'com/svcb/tsbridge/ts3voice/Ts3VoiceClient.java' = @'
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
'@
'com/github/theholywaffle/teamspeak3/TS3Config.java' = @'
package com.github.theholywaffle.teamspeak3;
public class TS3Config {public void setHost(String x){} public void setQueryPort(int x){}public void setEnableCommunicationsLogging(boolean x){}public void setConnectionHandler(com.github.theholywaffle.teamspeak3.api.reconnect.ConnectionHandler x){}}
'@
'com/github/theholywaffle/teamspeak3/TS3Query.java' = @'
package com.github.theholywaffle.teamspeak3;
public class TS3Query {public static String fail="";public static TS3Query last;public boolean closed;public TS3Query(TS3Config c){last=this;}public void connect(){if(fail.equals("connect"))throw new IllegalStateException("connect failure");}public TS3Api getApi(){return new TS3Api();}public void exit(){closed=true;}}
'@
'com/github/theholywaffle/teamspeak3/TS3Api.java' = @'
package com.github.theholywaffle.teamspeak3;
public class TS3Api {private void check(String s){if(TS3Query.fail.equals(s))throw new IllegalStateException(s+" failure");}public void login(String u,String p){check("login");}public void selectVirtualServerByPort(int p){check("select");}public void setNickname(String n){check("nickname");}public com.github.theholywaffle.teamspeak3.api.wrapper.VirtualServerInfo getServerInfo(){return new com.github.theholywaffle.teamspeak3.api.wrapper.VirtualServerInfo();}public void registerEvents(com.github.theholywaffle.teamspeak3.api.event.TS3EventType... x){}public void addTS3Listeners(com.github.theholywaffle.teamspeak3.api.event.TS3Listener... x){}}
'@
'com/github/theholywaffle/teamspeak3/api/wrapper/VirtualServerInfo.java' = @'
package com.github.theholywaffle.teamspeak3.api.wrapper;
public class VirtualServerInfo {public String getUniqueIdentifier(){return "fixture-server";}}
'@
'com/github/theholywaffle/teamspeak3/api/event/TS3EventType.java' = @'
package com.github.theholywaffle.teamspeak3.api.event;
public enum TS3EventType {SERVER,TEXT_SERVER,TEXT_CHANNEL,TEXT_PRIVATE}
'@
'com/github/theholywaffle/teamspeak3/api/event/TS3Listener.java' = @'
package com.github.theholywaffle.teamspeak3.api.event;
public interface TS3Listener {}
'@
'com/github/theholywaffle/teamspeak3/api/reconnect/ConnectionHandler.java' = @'
package com.github.theholywaffle.teamspeak3.api.reconnect;
public interface ConnectionHandler {void onConnect(com.github.theholywaffle.teamspeak3.TS3Query q);void onDisconnect(com.github.theholywaffle.teamspeak3.TS3Query q);}
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
 $path=Join-Path "$root/stubs" $item.Key
 New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
 [IO.File]::WriteAllText((Join-Path (Get-Location) $path),$item.Value)
 $path
}
$base='teamspeak&svcbridge/src/main/java/com/svcb/tsbridge'
$production=@("$base/net/TcpTunnelServer.java","$base/ts/TsQueryClient.java","$base/config/BridgeConfig.java","$base/transport/PlayerVoiceClients.java","$base/svc/McToTsDispatcher.java","$base/svc/BridgeMembershipService.java","$base/ghost/GhostPlayerInjector.java")
# The production sources under test (TcpTunnelServer etc.) reference
# com.tsvoice.protocol classes from ts3voice-core. This suite must stand
# alone on a clean checkout, so build the needed core sources itself
# instead of relying on a gradle run (or RunAll phase 1) having happened.
New-Item -ItemType Directory -Path 'ts3voice-core/build/classes/java/main' -Force | Out-Null
$coreSources = Get-ChildItem 'ts3voice-core/src/main/java' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -d 'ts3voice-core/build/classes/java/main' @coreSources
if ($LASTEXITCODE -ne 0) { throw 'ts3voice-core compilation for server regression failed' }
$cp="review/lib/*;$root/lib/*;ts3voice-core/build/classes/java/main"
& javac -encoding UTF-8 -cp $cp -d "$root/classes" @sources @production "$root/ServerRegression.java"
if($LASTEXITCODE -ne 0){throw 'Server regression compilation failed'}
& java -cp "$root/classes;$cp" ServerRegression
if($LASTEXITCODE -ne 0){throw 'Server regression failed'}
