$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base='review/session-consumption'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if(!(Test-Path 'review/lib/gson.jar')){
 $jar=Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
 Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$stubs=@{
'com/tsvoice/client/TeamspeakSvcClient.java'=@'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("session-consumption");
 public static ModConfig config=new ModConfig();
 public static ModConfig config(){return config;}
 public static de.maxhenkel.voicechat.api.VoicechatClientApi api;
 public static de.maxhenkel.voicechat.api.VoicechatClientApi clientApi(){return api;}
}
'@
'net/fabricmc/loader/api/FabricLoader.java'=@'
package net.fabricmc.loader.api;
public class FabricLoader {public static FabricLoader getInstance(){return new FabricLoader();} public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/session-consumption/config");}}
'@
'net/minecraft/client/Minecraft.java'=@'
package net.minecraft.client;
public class Minecraft {
 public Player player=new Player();
 public static final java.util.List<Runnable> TASKS=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static final java.util.List<String> MESSAGES=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static Minecraft getInstance(){return new Minecraft();}
 public void execute(Runnable r){TASKS.add(r);}
 public User getUser(){return new User();}
 public static class User{public String getName(){return "Test";}}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.randomUUID();}public void sendSystemMessage(Object msg){MESSAGES.add(msg instanceof net.minecraft.network.chat.Component c ? c.text : String.valueOf(msg));}}
}
'@
'net/minecraft/network/chat/Component.java'=@'
package net.minecraft.network.chat;
public class Component {public final String text;public Component(){this.text="";}public Component(String t){this.text=t;}public static Component literal(String text){return new Component(text);} public static Component translatable(String key){return new Component(key);} public static Component translatable(String key,Object... args){return new Component(key);}public String getString(){return text;}
}
'@
'com/tsvoice/client/net/BeaconPayload.java'=@'
package com.tsvoice.client.net;
public record BeaconPayload(String host,int port,String channel,String backendMode,int tunnelPort){}
'@
'com/tsvoice/client/ClientStateReflector.java'=@'
package com.tsvoice.client;
public class ClientStateReflector {public static void refreshGroups(de.maxhenkel.voicechat.api.VoicechatClientApi a,de.maxhenkel.voicechat.api.Group g){}}
'@
'com/tsvoice/client/net/GroupInfoPayload.java'=@'
package com.tsvoice.client.net;
public record GroupInfoPayload(java.util.UUID id,String name,String password,int typeOrd){}
'@
'com/tsvoice/client/TsAudioInjector.java'=@'
package com.tsvoice.client;
public class TsAudioInjector {
 public static int removeAllCalls;public static int onTsFrameCalls;
 public static void removeAll(){removeAllCalls++;}
 public static void reconcile(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void syncAllGhostVisibility(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void onTsFrame(String authority,String uid,String nick,java.util.UUID uuid,int seq,int codec,byte[] opus){onTsFrameCalls++;}
}
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
'com/tsvoice/client/Ts3FileTransfer.java'=@'
package com.tsvoice.client;
public class Ts3FileTransfer {
 public enum Result { CONFIRMED, TRANSPORT_FINISHED, REMOTE_VERIFIED, FAILED, UNKNOWN }
 public static Ts3FileTransfer.Result upload(com.tsvoice.client.ts3voice.Ts3VoiceClient client,String host,String name,byte[] data){return Result.FAILED;}
}
'@
'com/mojang/blaze3d/platform/NativeImage.java'=@'
package com.mojang.blaze3d.platform;
public class NativeImage implements AutoCloseable {
 public static NativeImage read(byte[] png){return new NativeImage(64,64,true);}
 public NativeImage(int w,int h,boolean useAlpha){}
 public int getWidth(){return 64;} public int getHeight(){return 64;}
 public int getPixel(int x,int y){return 0;}
 public void setPixelABGR(int x,int y,int color){}
 public void writeToFile(java.nio.file.Path path){}
 public void close(){}
}
'@
'com/tsvoice/client/ts3voice/Ts3VoiceClient.java'=@'
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
 public record MoveResult(boolean succeeded,Integer errorCode,String errorMessage){}
 public java.util.concurrent.CompletableFuture<MoveResult> moveChannelAsync(int cid,String channelPassword,long timeoutMillis){
  joinCalls++;lastJoinCid=cid;lastJoinPassword=channelPassword;
  return java.util.concurrent.CompletableFuture.completedFuture(new MoveResult(true,null,null));
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
 public static java.util.Map<String,String> parseParams(String row){
  java.util.Map<String,String> out=new java.util.HashMap<>();
  if(row==null){return out;}
  for(String token:row.trim().split("\s+")){
   int eq=token.indexOf('=');
   if(eq>0){out.put(token.substring(0,eq),token.substring(eq+1));}
  }
  return out;
 }
 public final com.tsvoice.client.ts3voice.TsCommandTracker commandTrackerField=
     new com.tsvoice.client.ts3voice.TsCommandTracker(this);
 public com.tsvoice.client.ts3voice.TsCommandTracker commandTracker(){return commandTrackerField;}
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
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
    $path = Join-Path "$base/stubs" $item.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path (Get-Location) $path), $item.Value)
    $path
}
$client = 'teamspeak-svc/src/main/java/com/tsvoice/client'
$prod = @("$client/TsVoiceManager.java", "$client/ts3voice/TsProtocolException.java", "$client/MicRedirector.java", "$client/audio/MicRouteGate.java",
    "$client/connection/VoiceState.java", "$client/connection/BackendCoordinator.java",
    "$client/connection/SelectionPolicy.java", "$client/connection/TsChannelRoute.java", "$client/connection/CapabilityDiscovery.java", "ts3voice-core/src/main/java/com/tsvoice/protocol/capability/CapabilityWire.java",
    "$client/GroupChannelMapper.java", "$client/ts3voice/TsChannelSnapshot.java",
    "$client/ts3voice/TsCommandTracker.java",
    "$client/AvatarPipeline.java",
    "$client/frontend/SvcFrontendAdapter.java", "$client/frontend/FrontendCapabilities.java",
    "$client/frontend/FrontendLease.java", "$client/frontend/Svc2625FrontendAdapter.java",
    "$client/config/TsBookmarkStore.java", "$client/config/EndpointCredentialStore.java",
    "$client/backend/SvcBackendAdapter.java",
    "$client/ModConfig.java", "$client/ServerConfigStore.java", "$client/ConfigFileUtil.java") |
    Where-Object { Test-Path $_ }
$svcClientStubs = Get-ChildItem -LiteralPath "$base/stubs/de" -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*' -sourcepath 'ts3voice-core/src/main/java' -d "$base/classes" @prod @sources $svcClientStubs "$base/SessionConsumptionTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Session consumption regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.SessionConsumptionTest
if ($LASTEXITCODE -ne 0) { throw 'Session consumption regression failed' }

# Identity consumption: REAL ClientIdentityService + real Ts3Identity + core
# identity protocol against recorded networking stubs.
$idClasses = "$base/identity-classes"
New-Item -ItemType Directory -Path $idClasses -Force | Out-Null
& javac -encoding UTF-8 -cp 'review/lib/*' -sourcepath 'ts3voice-core/src/main/java' -d $idClasses `
    "$client/ClientIdentityService.java" "$client/ts3voice/Ts3Identity.java" `
    "$client/ts3voice/Ts3VoiceException.java" "$client/ConfigFileUtil.java" `
    "$client/net/identity/IdentityPayloads.java" `
    "$client/connection/VoiceState.java" "$client/connection/BackendCoordinator.java" `
    "$client/connection/SelectionPolicy.java" `
    "$base/identity-stubs/com/tsvoice/client/TeamspeakSvcClient.java" `
    "$base/identity-stubs/com/tsvoice/client/ts3voice/Ts3VoiceClient.java" `
    "$base/identity-stubs/net/minecraft/client/Minecraft.java" `
    "$base/identity-stubs/net/fabricmc/loader/api/FabricLoader.java" `
    "$base/identity-stubs/net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java" `
    "$base/identity-stubs/net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java" `
    "$base/identity-stubs/net/minecraft/network/FriendlyByteBuf.java" `
    "$base/identity-stubs/net/minecraft/network/codec/StreamCodec.java" `
    "$base/identity-stubs/net/minecraft/network/protocol/common/custom/CustomPacketPayload.java" `
    "$base/identity-stubs/net/minecraft/resources/Identifier.java" `
    "$base/identity-stubs/com/tsvoice/client/net/BeaconPayload.java" `
    "$base/IdentityConsumptionTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Identity consumption compilation failed' }
& java -cp "$idClasses;review/lib/*" com.tsvoice.client.IdentityConsumptionTest
if ($LASTEXITCODE -ne 0) { throw 'Identity consumption regression failed' }
exit $LASTEXITCODE
