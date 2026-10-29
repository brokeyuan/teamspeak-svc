$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p0-defects'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if (!(Test-Path 'review/lib/gson.jar')) {
    $jar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$stubs = @{
    'net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java' = @'
package net.fabricmc.fabric.api.client.networking.v1;
public class ClientPlayNetworking {
 public static final java.util.Map<Object,java.util.function.BiConsumer<Object,Object>> RECEIVERS=new java.util.HashMap<>();
 public static <T> void registerGlobalReceiver(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,java.util.function.BiConsumer<T,Context> handler){
  RECEIVERS.put(type,(java.util.function.BiConsumer<Object,Object>)(java.util.function.BiConsumer<?,?>)handler);
 }
 public static void send(Object payload){}
 public record Context(){public net.minecraft.client.Minecraft client(){return net.minecraft.client.Minecraft.getInstance();}}
}
'@
    'net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java' = @'
package net.fabricmc.fabric.api.networking.v1;
public class PayloadTypeRegistry {
 public static Registry clientboundPlay(){return new Registry();}
 public static Registry serverboundPlay(){return new Registry();}
 public static class Registry {
  public <T> void register(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,
                           net.minecraft.network.codec.StreamCodec<?,T> codec){}
 }
}
'@
    'net/fabricmc/loader/api/FabricLoader.java' = @'
package net.fabricmc.loader.api;
public class FabricLoader {
 public static FabricLoader getInstance(){return new FabricLoader();}
 public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/p0-defects/config");}
}
'@
    'net/minecraft/client/Minecraft.java' = @'
package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();
 public Player player;
 public static Minecraft getInstance(){return INSTANCE;}
 public boolean isSameThread(){return true;}
 public void execute(Runnable action){action.run();}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.randomUUID();}}
}
'@
    'net/minecraft/network/FriendlyByteBuf.java' = @'
package net.minecraft.network;
public class FriendlyByteBuf {
 public FriendlyByteBuf writeUtf(String s){return this;}
 public String readUtf(){return "";}
 public FriendlyByteBuf writeLong(long v){return this;}
 public long readLong(){return 0;}
 public FriendlyByteBuf writeByteArray(byte[] b){return this;}
 public byte[] readByteArray(){return new byte[0];}
}
'@
    'net/minecraft/network/codec/StreamCodec.java' = @'
package net.minecraft.network.codec;
public interface StreamCodec<B,V> {
 void encode(B buf,V value);
 V decode(B buf);
}
'@
    'net/minecraft/network/protocol/common/custom/CustomPacketPayload.java' = @'
package net.minecraft.network.protocol.common.custom;
public interface CustomPacketPayload {
 Type<? extends CustomPacketPayload> type();
 record Type<T>(net.minecraft.resources.Identifier id){}
}
'@
    'net/minecraft/resources/Identifier.java' = @'
package net.minecraft.resources;
public record Identifier(String namespace,String path) {
 public static Identifier fromNamespaceAndPath(String namespace,String path){return new Identifier(namespace,path);}
}
'@
    'com/tsvoice/client/net/BeaconPayload.java' = @'
package com.tsvoice.client.net;
public record BeaconPayload(String host,int port,String channel,String backendMode,int tunnelPort){}
'@
    'com/tsvoice/client/TeamspeakSvcClient.java' = @'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("p0-identity");
}
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
    $path = Join-Path "$base/stubs" $item.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path (Get-Location) $path), $item.Value)
    $path
}
$core = Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol' -Filter '*.java' | Select-Object -ExpandProperty FullName
$core += Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol/relay' -Filter '*.java' | Select-Object -ExpandProperty FullName
$ts3voice = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @core @ts3voice @sources `
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ClientIdentityService.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/net/identity/IdentityPayloads.java' `
    "$base/ClientIdentityServiceRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Client identity regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.ClientIdentityServiceRegression
if ($LASTEXITCODE -ne 0) { throw 'Client identity regression failed' }

exit $LASTEXITCODE
