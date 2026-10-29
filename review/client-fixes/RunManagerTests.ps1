$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base='review/client-fixes/manager'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if(!(Test-Path 'review/lib/gson.jar')){
 $jar=Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
 Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$stubs=@{
'com/tsvoice/client/TeamspeakSvcClient.java'=@'
package com.tsvoice.client;
public class TeamspeakSvcClient {public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("review"); public static ModConfig config=new ModConfig(); public static ModConfig config(){return config;}}
'@
'net/fabricmc/loader/api/FabricLoader.java'=@'
package net.fabricmc.loader.api;
public class FabricLoader {public static FabricLoader getInstance(){return new FabricLoader();} public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/client-fixes/config");}}
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
public class Component {public static Component literal(String text){return new Component();}}
'@
'com/tsvoice/client/net/BeaconPayload.java'=@'
package com.tsvoice.client.net;
public record BeaconPayload(String host,int port,String channel,String backendMode,int tunnelPort){}
'@
'com/tsvoice/client/GroupChannelMapper.java'=@'
package com.tsvoice.client;
public class GroupChannelMapper {public static boolean ready;public static void reset(){} public static void tick(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}public static boolean isReadyForVoice(com.tsvoice.client.ts3voice.Ts3VoiceClient c){return ready;}public record SessionInfo(String channelName){}}
'@
'com/tsvoice/client/TsAudioInjector.java'=@'
package com.tsvoice.client;
public class TsAudioInjector {public static void removeAll(){}public static void reconcile(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}public static void syncAllGhostVisibility(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}public static void onTsFrame(String authority,String uid,String nick,java.util.UUID uuid,int seq,int codec,byte[] opus){}}
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
public class OnlinePlayers {public static java.util.UUID getUuidByName(String name){return null;}}
'@
'com/tsvoice/client/audio/AecReferenceMixer.java'=@'
package com.tsvoice.client.audio;
public class AecReferenceMixer{public static void clear(){}}
'@
'com/tsvoice/client/ts3voice/Ts3VoiceException.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3VoiceException extends Exception{
 public Ts3VoiceException(String message){super(message);}
 public Ts3VoiceException(String message,Throwable cause){super(message,cause);}
}
'@
'com/tsvoice/client/ts3voice/Ts3Identity.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3Identity{public static Ts3Identity load(java.nio.file.Path path){return new Ts3Identity();}public static Ts3Identity generate(int a,long b){return new Ts3Identity();}public void save(java.nio.file.Path path){}public String getClientUid(){return "uid";}}
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
  public record RelayRouteBarrierC2sPayload(byte[] frame){public static final Type<RelayRouteBarrierC2sPayload> TYPE=new Type<>("teamspeak-svc","relay_route_barrier_c2s_v1");public Type<RelayRouteBarrierC2sPayload> type(){return TYPE;}}
public record RelayClosePayload(byte[] frame){public static final Type<RelayClosePayload> TYPE=new Type<>("teamspeak-svc","relay_close_v1");public Type<RelayClosePayload> type(){return TYPE;}}
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
public class Ts3VoiceClient {
 public void setVoiceListenerForTestDispatch(TsVoiceIn frame){}

 public static final int CODEC_OPUS_VOICE=4;public int sent; public boolean connected; public record TsVoiceIn(int senderClid,int codec,int sequence,byte[] opus){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,boolean tcp){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,com.tsvoice.client.ts3voice.Ts3Connection conn){}
 public void connect(long timeout) throws Exception{connected=true;}
 public void setServerPassword(String password){}public void setTextListener(java.util.function.BiConsumer<String,String> l){}public void setVoiceListener(java.util.function.Consumer<TsVoiceIn> l){}
 public void connect(java.net.InetAddress host,int port,long timeout){}public void disconnect(){}public boolean isConnected(){return true;}
 public int getMyClid(){return 1;}public int getMyChannelId(){return 1;}public com.tsvoice.client.ts3voice.TsChannelSnapshot channelSnapshot(){return new com.tsvoice.client.ts3voice.TsChannelSnapshot(null);}public void joinChannel(int cid,String channelPassword){}public record MoveResult(boolean succeeded,Integer errorCode,String errorMessage){}public java.util.concurrent.CompletableFuture<MoveResult> moveChannelAsync(int cid,String channelPassword,long timeoutMillis){return java.util.concurrent.CompletableFuture.completedFuture(new MoveResult(true,null,null));}public void sendCommand(String cmd)throws Ts3VoiceException{}public void requestChannelList()throws Ts3VoiceException{}public void requestServerInfo()throws Ts3VoiceException{}public String getVirtualServerUid(){return "fixture-server";}
 public void setChannelMembershipListener(java.util.function.IntConsumer l){}public void publishMcUuid(java.util.UUID id){}
 public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier){}
 public void clearQueuedVoice(){}
 public void sendVoice(byte[] opus,int codec){sendVoice(opus,codec,Long.MIN_VALUE);}
 public void sendVoice(byte[] opus,int codec,long routeEpoch){sent++;}public String getUniqueIdOf(int clid){return "uid";}public String getNicknameOf(int clid){return "name";}public java.util.UUID getMcUuidOf(int clid){return null;}
 public void sendChannelText(String message){}
}
'@
}
$sources=foreach($item in $stubs.GetEnumerator()){
 $path=Join-Path "$base/stubs" $item.Key
 New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
 [IO.File]::WriteAllText((Join-Path (Get-Location) $path),$item.Value);$path
}
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @sources 'teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsProtocolException.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/SvcFrontendAdapter.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendCapabilities.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendLease.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/Svc2625FrontendAdapter.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/audio/MicRouteGate.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/TsChannelRoute.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/CapabilityDiscovery.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceTransport.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsMcChannelTransport.java' 'ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java' 'ts3voice-core/src/main/java/com/tsvoice/protocol/capability/CapabilityWire.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsChannelSnapshot.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/backend/SvcBackendAdapter.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/config/TsBookmarkStore.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/config/EndpointCredentialStore.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ModConfig.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ServerConfigStore.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicBotLifecycle.java' 'review/client-fixes/ManagerTests.java'
if($LASTEXITCODE -ne 0){throw 'Manager tests compilation failed'}
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.ManagerTests
if($LASTEXITCODE -ne 0){throw 'Manager tests failed'}
