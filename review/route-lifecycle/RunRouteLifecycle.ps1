$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base='review/route-lifecycle'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if(!(Test-Path 'review/lib/gson.jar')){
 $jar=Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
 Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$stubs=@{
'com/tsvoice/client/TeamspeakSvcClient.java'=@'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("route-lifecycle");
 public static ModConfig config=new ModConfig();
 public static ModConfig config(){return config;}
 public static de.maxhenkel.voicechat.api.VoicechatClientApi api;
 public static de.maxhenkel.voicechat.api.VoicechatClientApi clientApi(){return api;}
}
'@
'net/fabricmc/loader/api/FabricLoader.java'=@'
package net.fabricmc.loader.api;
public class FabricLoader {public static FabricLoader getInstance(){return new FabricLoader();} public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/route-lifecycle/config");}}
'@
'net/minecraft/client/Minecraft.java'=@'
package net.minecraft.client;
public class Minecraft {
 public Player player;private final Thread fakeThread=Thread.currentThread();public static Minecraft getInstance(){return new Minecraft();}public void execute(Runnable r){r.run();}public boolean isSameThread(){return Thread.currentThread()==fakeThread;}
 public User getUser(){return new User();}public static class User{public String getName(){return "Test";}}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.randomUUID();}public void sendSystemMessage(Object msg){}}
}
'@
'net/minecraft/network/chat/Component.java'=@'
package net.minecraft.network.chat;
public class Component {public static Component literal(String text){return new Component();} public static Component translatable(String key){return new Component();} public static Component translatable(String key,Object... args){return new Component();}}
'@
'com/tsvoice/client/net/BeaconPayload.java'=@'
package com.tsvoice.client.net;
public record BeaconPayload(String host,int port,String channel,String backendMode,int tunnelPort){}
'@
'com/tsvoice/client/TsAudioInjector.java'=@'
package com.tsvoice.client;
public class TsAudioInjector {
 public static int removeAllCalls;public static int onTsFrameCalls;
 private static final java.util.concurrent.atomic.AtomicLong INJECTED = new java.util.concurrent.atomic.AtomicLong();
 public static long getInjectedFrames(){return INJECTED.get();}
 public static void removeAll(){removeAllCalls++;}
 public static void reconcile(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void syncAllGhostVisibility(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void onTsFrame(String authority,String uid,String nick,java.util.UUID uuid,int seq,int codec,byte[] opus){onTsFrameCalls++;INJECTED.incrementAndGet();}
}
'@
'com/tsvoice/client/AvatarPipeline.java'=@'
package com.tsvoice.client;
public class AvatarPipeline {public static void uploadAvatarAsync(com.tsvoice.client.ts3voice.Ts3VoiceClient c,java.util.UUID id,String namespace,String host){}}
'@
'com/tsvoice/client/ClientIdentityService.java'=@'
package com.tsvoice.client;
public class ClientIdentityService {public static void beginProof(com.tsvoice.client.ts3voice.Ts3VoiceClient c,com.tsvoice.client.ts3voice.Ts3Identity id){}public static java.util.UUID getVerifiedMcUuid(String serverUid,String tsClientUid){return null;}public static void tsDisconnected(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}public static void clearVerified(){}}
'@
'com/tsvoice/client/OnlinePlayers.java'=@'
package com.tsvoice.client;
public class OnlinePlayers {public static boolean isOnline(java.util.UUID id){return false;}}
'@
'com/tsvoice/client/audio/AecReferenceMixer.java'=@'
package com.tsvoice.client.audio;
public class AecReferenceMixer{public static void clear(){}}
'@
'com/tsvoice/client/ts3voice/Ts3VoiceException.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3VoiceException extends Exception {
 public Ts3VoiceException(String message){super(message);}
 public Ts3VoiceException(String message,Throwable cause){super(message,cause);}
}
'@
'com/tsvoice/client/ts3voice/Ts3Identity.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3Identity {
 public static Ts3Identity load(java.nio.file.Path path){return new Ts3Identity();}
 public static Ts3Identity generate(int a,long b){return new Ts3Identity();}
 public void save(java.nio.file.Path path){}
 public String getClientUid(){return "uid";}
}
'@
'com/tsvoice/client/ts3voice/Ts3Crypt.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3Crypt {public Ts3Crypt(Ts3Identity id){}}
'@
'com/tsvoice/client/ts3voice/Ts3Connection.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3Connection {
 public Ts3Connection(Ts3Crypt crypt,com.tsvoice.client.ts3voice.TsTransport transport){}
 public Ts3Crypt crypt(){return new Ts3Crypt(null);}
 public void bindClient(Object c){}
 public void setOnDisconnected(Runnable r){}
}
'@
'com/tsvoice/client/ts3voice/TsTransport.java'=@'
package com.tsvoice.client.ts3voice;
import java.io.IOException;
public interface TsTransport extends AutoCloseable {
 void connect(java.net.InetAddress address,int port) throws IOException;
 void send(byte[] data) throws IOException;
 default void send(byte[] data, long routeEpoch) throws IOException {send(data);}
 byte[] receive(int maxLen) throws IOException;
 boolean isClosed();
 @Override void close();
}
'@
'com/tsvoice/common/net/TsRelayPayloads.java'=@'
package com.tsvoice.common.net;
public final class TsRelayPayloads {
 public record RelayOpenPayload(byte[] frame){public static final Type<RelayOpenPayload> TYPE=new Type<>("teamspeak-svc","relay_open_v1");public Type<RelayOpenPayload> type(){return TYPE;}}
 public record RelayOpenedPayload(byte[] frame){public static final Type<RelayOpenedPayload> TYPE=new Type<>("teamspeak-svc","relay_opened_v1");public Type<RelayOpenedPayload> type(){return TYPE;}}
 public record RelayDataC2sPayload(byte[] frame){public static final Type<RelayDataC2sPayload> TYPE=new Type<>("teamspeak-svc","relay_data_c2s_v1");public Type<RelayDataC2sPayload> type(){return TYPE;}}
 public record RelayDataS2cPayload(byte[] frame){public static final Type<RelayDataS2cPayload> TYPE=new Type<>("teamspeak-svc","relay_data_s2c_v1");public Type<RelayDataS2cPayload> type(){return TYPE;}}
  public record RelayRouteBarrierC2sPayload(byte[] frame){public static final Type<RelayRouteBarrierC2sPayload> TYPE=new Type<>("teamspeak-svc","relay_route_barrier_c2s_v1");public Type<RelayRouteBarrierC2sPayload> type(){return TYPE;}}public record RelayClosePayload(byte[] frame){public static final Type<RelayClosePayload> TYPE=new Type<>("teamspeak-svc","relay_close_v1");public Type<RelayClosePayload> type(){return TYPE;}}
 public record RelayClosedPayload(byte[] frame){public static final Type<RelayClosedPayload> TYPE=new Type<>("teamspeak-svc","relay_closed_v1");public Type<RelayClosedPayload> type(){return TYPE;}}
 public record RelayErrorPayload(byte[] frame){public static final Type<RelayErrorPayload> TYPE=new Type<>("teamspeak-svc","relay_error_v1");public Type<RelayErrorPayload> type(){return TYPE;}}
 public static final class Type<P> {
  private final String ns;private final String path;
  public Type(String ns,String path){this.ns=ns;this.path=path;}
  public String toString(){return ns+":"+path;}
  public boolean equals(Object o){return o instanceof Type<?> t && t.ns.equals(ns)&&t.path.equals(path);}
  public int hashCode(){return ns.hashCode()*31+path.hashCode();}
 }
 public static void register(){}
 public static boolean isRegistered(){return true;}
}
'@
'net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java'=@'
package net.fabricmc.fabric.api.client.networking.v1;
import java.util.function.BiConsumer;
public final class ClientPlayNetworking {
 public static <P> void registerGlobalReceiver(P type,BiConsumer<P,Context> handler){}
 public static boolean canSend(Object type){return true;}
 public static void send(Object payload){}
 public interface Context {net.minecraft.client.Minecraft client();}
}
'@
'com/tsvoice/client/ts3voice/Ts3VoiceClient.java'=@'
package com.tsvoice.client.ts3voice;
import java.util.*;
public class Ts3VoiceClient {
 public static final int CODEC_OPUS_VOICE=4;
 // Connect scripts: each new instance polls the next script and runs it from connect().
 public static final java.util.Deque<java.util.function.Consumer<Ts3VoiceClient>> connectScripts =
         new java.util.concurrent.ConcurrentLinkedDeque<>();
 public boolean connected;
 public int sent;
 public int droppedByEpoch;
 public long lastSendEpoch=Long.MIN_VALUE;
 public boolean throwOnSend;
 public boolean throwOnJoin;
 public java.util.function.LongSupplier epochSupplier=()->Long.MIN_VALUE;
 public int myChannel=1;
 public int joinCalls; public int lastJoinCid; public String lastJoinPassword;
 public Map<Integer,String> channelNames=new java.util.concurrent.ConcurrentHashMap<>();
 public Map<Integer,Integer> channelParents=new java.util.concurrent.ConcurrentHashMap<>();
 public java.util.function.Consumer<Ts3VoiceClient> onConnect;
 public java.util.function.BiConsumer<String,String> textListener;
 public java.util.function.Consumer<TsVoiceIn> voiceListener;
 public java.util.function.IntConsumer membershipListener;
 public record TsVoiceIn(int senderClid,int codec,int sequence,byte[] opus){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,boolean tcp){onConnect=connectScripts.poll();}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,com.tsvoice.client.ts3voice.Ts3Connection conn){onConnect=connectScripts.poll();}
 public void connect(long timeout) throws Exception{if(onConnect!=null){onConnect.accept(this);}connected=true;}
 public void setServerPassword(String password){}
 public void setTextListener(java.util.function.BiConsumer<String,String> l){textListener=l;}
 public void setVoiceListener(java.util.function.Consumer<TsVoiceIn> l){voiceListener=l;}
 public void setVoiceListenerForTestDispatch(TsVoiceIn frame){java.util.function.Consumer<TsVoiceIn> l=voiceListener;if(l!=null){l.accept(frame);}}
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
  com.tsvoice.client.ts3voice.TsChannelSnapshot snap=new com.tsvoice.client.ts3voice.TsChannelSnapshot("fixture-server");
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
 // N1/D05: moveChannelAsync mirrors the production surface. The returned future is
 // left UNCOMPLETED so tests decide the server's answer (success / error id / timeout);
 // flows that never complete it still confirm through the self-channel path.
 public record MoveResult(boolean succeeded,Integer errorCode,String errorMessage){}
 public static MoveResult moveOk(){return new MoveResult(true,null,null);}
 public static MoveResult moveError(int id,String msg){return new MoveResult(false,id,msg);}
 public java.util.concurrent.CompletableFuture<MoveResult> lastMoveFuture;
 public java.util.concurrent.CompletableFuture<MoveResult> moveChannelAsync(int cid,String channelPassword,long timeoutMillis) throws Ts3VoiceException{
  if(throwOnJoin){throw new RuntimeException("server rejected move");}
  joinCalls++;lastJoinCid=cid;lastJoinPassword=channelPassword;
  lastMoveFuture=new java.util.concurrent.CompletableFuture<>();
  return lastMoveFuture;
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
 public String getVirtualServerUid(){return "fixture-server";}
 public void publishMcUuid(java.util.UUID id){}
 public void sendVoice(byte[] opus,int codec){
  sendVoice(opus,codec,Long.MIN_VALUE);
 }
 public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier){epochSupplier=supplier;}
 public void clearQueuedVoice(){}
 public void sendVoice(byte[] opus,int codec,long routeEpoch){
  if(routeEpoch!=epochSupplier.getAsLong()){droppedByEpoch++;return;}
  lastSendEpoch=routeEpoch;
  if(throwOnSend){throw new RuntimeException("send failure");}
  sent++;
 }
 public String getUniqueIdOf(int clid){return "uid";}
 public String getNicknameOf(int clid){return "nick";}
 public java.util.UUID getMcUuidOf(int clid){return null;}
 public void sendChannelText(String message){}
}
'@
'com/tsvoice/client/net/GroupInfoPayload.java'=@'
package com.tsvoice.client.net;
public record GroupInfoPayload(java.util.UUID id,String name,String password,int typeOrd){}
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
    $path = Join-Path "$base/stubs" $item.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path (Get-Location) $path), $item.Value)
    $path
}
$client = 'teamspeak-svc/src/main/java/com/tsvoice/client'
$prod = @("$client/music/MusicBotLifecycle.java", "$client/TsVoiceManager.java", "$client/ts3voice/TsProtocolException.java", "$client/MicRedirector.java", "$client/audio/MicRouteGate.java",
    "$client/connection/VoiceState.java", "$client/connection/BackendCoordinator.java",
    "$client/connection/SelectionPolicy.java", "$client/connection/TsChannelRoute.java", "$client/connection/CapabilityDiscovery.java", "ts3voice-core/src/main/java/com/tsvoice/protocol/capability/CapabilityWire.java",
    "ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java",
    "teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelayRuntime.java",
    "teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelaySession.java",
    "$client/ts3voice/TsMcChannelTransport.java", "$client/connection/VoiceTransport.java",
    "$client/GroupChannelMapper.java", "$client/ts3voice/TsChannelSnapshot.java",
    "$client/config/TsBookmarkStore.java", "$client/config/EndpointCredentialStore.java",
    "$client/backend/SvcBackendAdapter.java",
    "$client/ModConfig.java", "$client/ServerConfigStore.java", "$client/ConfigFileUtil.java",
    "$client/frontend/SvcFrontendAdapter.java", "$client/frontend/FrontendCapabilities.java",
    "$client/frontend/FrontendLease.java", "$client/frontend/Svc2625FrontendAdapter.java",
    "$client/ClientStateReflector.java") |
    Where-Object { Test-Path $_ }
$svcClientStubs = Get-ChildItem -LiteralPath "$base/stubs/de" -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @prod @sources $svcClientStubs `
    "$base/RouteLifecycleTest.java" "$base/BookmarkRouteTest.java" "$base/QueuedVoiceRouteTest.java" `
    "$base/RouteMoveLifecycleTest.java" "$base/OffGateTest.java" "$base/SingleBackendListenTest.java" "$base/S0FixRegressionTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Route lifecycle regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.RouteLifecycleTest
if ($LASTEXITCODE -ne 0) { throw 'Route lifecycle regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.BookmarkRouteTest
if ($LASTEXITCODE -ne 0) { throw 'Bookmark route regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.QueuedVoiceRouteTest
if ($LASTEXITCODE -ne 0) { throw 'Queued voice route regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.connection.RouteMoveLifecycleTest
if ($LASTEXITCODE -ne 0) { throw 'Route move lifecycle regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.OffGateTest
if ($LASTEXITCODE -ne 0) { throw 'Off gate regression failed' }
# E5: single-backend listen default (confirmed product rule)
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.SingleBackendListenTest
if ($LASTEXITCODE -ne 0) { throw 'single-backend listen regression failed' }
# S0 (post-m7 review): R21 epoch monotonicity + session-bound writer permit,
# R27 malformed announcement boundary, R23 explicit consent flow.
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.S0FixRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'S0 fix regression failed' }
# Connection-level linearization: REAL Ts3Connection/Ts3Crypt/Ts3Packet/TsTransport
# against a controllable blocking transport; ts3voice-core protocol sources via
# -sourcepath, client callback surface stubbed (connection layer only).
# N1: the connection compile also carries the production route coordinator so the
# epoch-revocation regression consumes real BackendCoordinator route state.
$connClasses = "$base/conn-classes"
New-Item -ItemType Directory -Path $connClasses -Force | Out-Null
& javac -encoding UTF-8 -cp 'review/lib/*' -sourcepath 'ts3voice-core/src/main/java' -d $connClasses `
    "$client/ts3voice/Ts3Connection.java" "$client/ts3voice/Ts3Crypt.java" "$client/ts3voice/Ts3Packet.java" `
    "$client/ts3voice/TsTransport.java" "$client/ts3voice/TcpTransport.java" "$client/ts3voice/Ts3Identity.java" `
    "$client/ts3voice/Ed25519Math.java" "$client/ts3voice/PacketType.java" `
    "$client/ts3voice/Ts3VoiceException.java" "$client/ConfigFileUtil.java" `
    "$client/connection/VoiceState.java" "$client/connection/SelectionPolicy.java" "$client/connection/BackendCoordinator.java" `
    "$base/conn-stubs/com/tsvoice/client/ts3voice/Ts3VoiceClient.java" `
    "$base/ConnectionLinearizationTest.java" "$base/RouteEpochRevocationTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Connection linearization compilation failed' }
& java -cp "$connClasses;review/lib/*" com.tsvoice.client.ConnectionLinearizationTest
if ($LASTEXITCODE -ne 0) { throw 'Connection linearization regression failed' }
& java -cp "$connClasses;review/lib/*" com.tsvoice.client.RouteEpochRevocationTest
if ($LASTEXITCODE -ne 0) { throw 'Route epoch revocation regression failed' }
exit $LASTEXITCODE
