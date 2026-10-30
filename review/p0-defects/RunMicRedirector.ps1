$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p0-defects'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if (!(Test-Path 'review/lib/gson.jar')) {
    $jar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$stubs = @{
 # P1-03/G1: this suite owns its ClientPlayNetworking contract. Other p0
 # suites (identity/issuing) overwrite this shared file with a bare variant
 # lacking canSend, which broke the TsVoiceManager harness compilation; regen
 # here keeps the mic regression independent of whatever ran before it.
    'net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java' = @'
package net.fabricmc.fabric.api.client.networking.v1;
public class ClientPlayNetworking {
 public static final java.util.Map<Object,java.util.function.BiConsumer<Object,Object>> RECEIVERS=new java.util.HashMap<>();
 /** Test-controlled allow-list for canSend (G1): set per payload type, cleared
  *  between cases. Default-deny so "channel absent" is actually exercised. */
 private static final java.util.Set<Object> ALLOWED_TYPES=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
 /** Payloads passed to send() (test assertion surface). */
 public static final java.util.List<Object> SENT=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
 public static void setCanSend(Object type,boolean allowed){if(allowed){ALLOWED_TYPES.add(type);}else{ALLOWED_TYPES.remove(type);}}
 public static void clearCanSend(){ALLOWED_TYPES.clear();}
 public static void clearSent(){SENT.clear();}
 public static boolean canSend(Object type){return ALLOWED_TYPES.contains(type);}
 public static <T> void registerGlobalReceiver(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,java.util.function.BiConsumer<T,Context> handler){
  RECEIVERS.put(type,(java.util.function.BiConsumer<Object,Object>)(java.util.function.BiConsumer<?,?>)handler);
 }
 public static void send(Object payload){SENT.add(payload);}
 public record Context(){public net.minecraft.client.Minecraft client(){return net.minecraft.client.Minecraft.getInstance();}}
}
'@

 'com/tsvoice/client/music/MusicBotLifecycle.java' = @'
package com.tsvoice.client.music;
/** Harness stub: the real MusicBotManager is compiled by gradle and the
 *  client-acceptance boundary suite; these suites only verify voice lifecycle. */
public class MusicBotLifecycle {
 public static void onPlayerVoiceLifecycleChange(String reason) {}
}
'@

    'com/tsvoice/client/TeamspeakSvcClient.java' = @'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("p0-mic");
 public static ModConfig config=new ModConfig();
 public static ModConfig config(){return config;}
 public static de.maxhenkel.voicechat.api.VoicechatClientApi api;
 public static de.maxhenkel.voicechat.api.VoicechatClientApi clientApi(){return api;}
}
'@
    'com/tsvoice/client/GroupChannelMapper.java' = @'
package com.tsvoice.client;
public class GroupChannelMapper {
 public static boolean ready;
 public static void reset(){}
 public static void tick(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static boolean isReadyForVoice(com.tsvoice.client.ts3voice.Ts3VoiceClient c){return ready;}
 public record SessionInfo(String channelName){}
}
'@
    'com/tsvoice/client/TsAudioInjector.java' = @'
package com.tsvoice.client;
public class TsAudioInjector {
 public static void removeAll(){}
 public static void reconcile(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void syncAllGhostVisibility(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void onTsFrame(String authority,String uid,String nick,java.util.UUID uuid,int seq,int codec,byte[] opus){}
}
'@
    'com/tsvoice/client/ClientIdentityService.java' = @'
package com.tsvoice.client;
public class ClientIdentityService {
 public static void beginProof(com.tsvoice.client.ts3voice.Ts3VoiceClient c,com.tsvoice.client.ts3voice.Ts3Identity id){}
 public static java.util.UUID getVerifiedMcUuid(String serverUid,String tsClientUid){return null;}
 public static void tsDisconnected(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void clearVerified(){}
}
'@
    'com/tsvoice/client/AvatarPipeline.java' = @'
package com.tsvoice.client;
public class AvatarPipeline {
 public static void uploadAvatarAsync(com.tsvoice.client.ts3voice.Ts3VoiceClient c,java.util.UUID id,String namespace,String host){}
}
'@
    'com/tsvoice/client/OnlinePlayers.java' = @'
package com.tsvoice.client;
public class OnlinePlayers {public static boolean isOnline(java.util.UUID id){return false;}}
'@
    'com/tsvoice/client/audio/AecReferenceMixer.java' = @'
package com.tsvoice.client.audio;
public class AecReferenceMixer {public static void clear(){}}
'@
    'com/tsvoice/client/net/BeaconPayload.java' = @'
package com.tsvoice.client.net;
public record BeaconPayload(String host,int port,String channel,String backendMode,int tunnelPort){}
'@
    'com/tsvoice/client/ts3voice/Ts3Identity.java' = @'
package com.tsvoice.client.ts3voice;
public class Ts3Identity {
 public static Ts3Identity load(java.nio.file.Path path){return new Ts3Identity();}
 public static Ts3Identity generate(int a,long b){return new Ts3Identity();}
 public void save(java.nio.file.Path path){}
 public String getClientUid(){return "uid";}
}
'@
    'com/tsvoice/client/ts3voice/Ts3VoiceException.java' = @'
package com.tsvoice.client.ts3voice;
public class Ts3VoiceException extends Exception {
 public Ts3VoiceException(String message){super(message);}
 public Ts3VoiceException(String message,Throwable cause){super(message,cause);}
}
'@
    'com/tsvoice/client/ts3voice/Ts3VoiceClient.java' = @'
package com.tsvoice.client.ts3voice;
public class Ts3VoiceClient {
 public void setVoiceListenerForTestDispatch(TsVoiceIn frame){}

 public static final int CODEC_OPUS_VOICE=4;
 public boolean connected;
 public boolean throwOnSend;
 public int sent;
 public int connectedCalls;
 public Boolean[] connectedScript;
 public record TsVoiceIn(int senderClid,int codec,int sequence,byte[] opus){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,boolean tcp){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,com.tsvoice.client.ts3voice.Ts3Connection conn){}
 public void connect(long timeout) throws Exception{connected=true;}
 public void setServerPassword(String password){}
 public void setTextListener(java.util.function.BiConsumer<String,String> l){}
 public void setVoiceListener(java.util.function.Consumer<TsVoiceIn> l){}
 public void connect(java.net.InetAddress host,int port,long timeout){}
 public void disconnect(){}
 public boolean isConnected(){
  if(connectedScript!=null){return connectedScript[Math.min(connectedCalls++,connectedScript.length-1)];}
  return connected;
 }
 public int getMyClid(){return 1;}
 public int getMyChannelId(){return 1;}
 public com.tsvoice.client.ts3voice.TsChannelSnapshot channelSnapshot(){return new com.tsvoice.client.ts3voice.TsChannelSnapshot(null);}
 public void sendCommand(String cmd) throws Ts3VoiceException{}
 public void requestChannelList() throws Ts3VoiceException{}
 public void requestServerInfo() throws Ts3VoiceException{}
 public String getVirtualServerUid(){return "fixture-server";}
 public void joinChannel(int cid,String channelPassword){}
 public record MoveResult(boolean succeeded,Integer errorCode,String errorMessage){}
 public java.util.concurrent.CompletableFuture<MoveResult> moveChannelAsync(int cid,String channelPassword,long timeoutMillis){
  return java.util.concurrent.CompletableFuture.completedFuture(new MoveResult(true,null,null));
 }
 public void setChannelMembershipListener(java.util.function.IntConsumer l){}
 public void publishMcUuid(java.util.UUID id){}
 public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier){}
 public void clearQueuedVoice(){}
 public void sendVoice(byte[] opus,int codec){sendVoice(opus,codec,Long.MIN_VALUE);}
 public void sendVoice(byte[] opus,int codec,long routeEpoch){
  if(throwOnSend){throw new RuntimeException("send failure");}
  sent++;
 }
 public String getUniqueIdOf(int clid){return "uid";}
 public String getNicknameOf(int clid){return "name";}
 public java.util.UUID getMcUuidOf(int clid){return null;}
 public void sendChannelText(String message){}
}
'@
    'net/minecraft/client/Minecraft.java' = @'
package net.minecraft.client;
public class Minecraft {
 public Player player;
 public static Minecraft getInstance(){return new Minecraft();}
 public void execute(Runnable r){r.run();}
 public User getUser(){return new User();}
 public static class User{public String getName(){return "Test";}}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.randomUUID();}public void sendSystemMessage(Object msg){}}
}
'@
    'net/minecraft/network/chat/Component.java' = @'
package net.minecraft.network.chat;
public class Component {public static Component literal(String text){return new Component();}}
'@
    'net/fabricmc/loader/api/FabricLoader.java' = @'
package net.fabricmc.loader.api;
public class FabricLoader {
 public static FabricLoader getInstance(){return new FabricLoader();}
 public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/p0-defects/config");}
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
# MicRouteGate is part of the fix; tolerate its absence when proving the old code fails.
$prod = @("$client/TsVoiceManager.java", "$client/ts3voice/TsProtocolException.java", "$client/MicRedirector.java", "$client/frontend/SvcFrontendAdapter.java", "$client/frontend/FrontendCapabilities.java", "$client/frontend/FrontendLease.java", "$client/frontend/Svc2625FrontendAdapter.java", "$client/audio/MicRouteGate.java", "$client/connection/VoiceState.java", "$client/connection/BackendCoordinator.java", "$client/connection/SelectionPolicy.java", "$client/connection/TsChannelRoute.java", "$client/connection/CapabilityDiscovery.java", "$client/connection/VoiceTransport.java", "$client/ts3voice/TsMcChannelTransport.java", "ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java", "ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsDatagramKind.java", "$client/ts3voice/TsChannelSnapshot.java", "ts3voice-core/src/main/java/com/tsvoice/protocol/capability/CapabilityWire.java",
    "$client/config/TsBookmarkStore.java", "$client/config/EndpointCredentialStore.java", "$client/backend/SvcBackendAdapter.java",
    "$client/ModConfig.java", "$client/ServerConfigStore.java", "$client/ConfigFileUtil.java") | Where-Object { Test-Path $_ }
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @prod @sources "$base/stubs/com/tsvoice/client/ts3voice/Ts3Crypt.java"    "$base/stubs/com/tsvoice/client/ts3voice/Ts3Connection.java"    "$base/stubs/com/tsvoice/client/ts3voice/TsTransport.java"    "$base/stubs/com/tsvoice/common/net/TsRelayPayloads.java"    "$base/stubs/net/minecraft/network/protocol/common/custom/CustomPacketPayload.java" "$base/stubs/net/minecraft/resources/Identifier.java" "$base/stubs/net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java" "$base/MicRedirectorRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Mic redirect regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.MicRedirectorRegression
if ($LASTEXITCODE -ne 0) { throw 'Mic redirect regression failed' }
