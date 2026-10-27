$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p0-defects'
$stubs = @{
    'net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking.java' = @'
package net.fabricmc.fabric.api.networking.v1;
public class ServerPlayNetworking {
 public static final java.util.Map<Object,java.util.function.BiConsumer<Object,Object>> RECEIVERS=new java.util.HashMap<>();
 public static final java.util.List<Object> SENT=new java.util.ArrayList<>();
 public static <T> void registerGlobalReceiver(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,java.util.function.BiConsumer<T,Context> handler){
  RECEIVERS.put(type,(java.util.function.BiConsumer<Object,Object>)(java.util.function.BiConsumer<?,?>)handler);
 }
 public static void send(net.minecraft.server.level.ServerPlayer player,Object payload){SENT.add(payload);}
 public record Context(){public net.minecraft.server.level.ServerPlayer player(){return net.minecraft.server.level.ServerPlayer.CURRENT;}}
}
'@
    'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.java' = @'
package net.fabricmc.fabric.api.networking.v1;
public class ServerPlayConnectionEvents {
 public static final Events DISCONNECT=new Events();
 public static final JoinEvents JOIN=new JoinEvents();
 public static class Events {
  private java.util.function.BiConsumer<net.minecraft.server.network.ServerPlayNetworkHandler,net.minecraft.server.MinecraftServer> handler;
  public void register(java.util.function.BiConsumer<net.minecraft.server.network.ServerPlayNetworkHandler,net.minecraft.server.MinecraftServer> h){handler=h;}
  public java.util.function.BiConsumer<net.minecraft.server.network.ServerPlayNetworkHandler,net.minecraft.server.MinecraftServer> handler(){return handler;}
 }
 public static class JoinEvents {
  private TriHandler handler;
  public void register(TriHandler h){handler=h;}
  public TriHandler handler(){return handler;}
 }
 public interface TriHandler { void accept(net.minecraft.server.network.ServerPlayNetworkHandler h,Object sender,net.minecraft.server.MinecraftServer server); }
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
 public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/p0-defects/issuing-config");}
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
    'net/minecraft/server/MinecraftServer.java' = @'
package net.minecraft.server;
public class MinecraftServer {
 public PlayerList getPlayerList(){return new PlayerList();}
 public static class PlayerList {
  public static java.util.List<net.minecraft.server.level.ServerPlayer> PLAYERS=java.util.List.of();
  public java.util.List<net.minecraft.server.level.ServerPlayer> getPlayers(){return PLAYERS;}
  public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id){
   return PLAYERS.stream().filter(p->p.getUUID().equals(id)).findFirst().orElse(null);
  }
 }
}
'@
    'net/minecraft/server/level/ServerPlayer.java' = @'
package net.minecraft.server.level;
public class ServerPlayer {
 public static ServerPlayer CURRENT;
 private final java.util.UUID uuid=java.util.UUID.randomUUID();
 public java.util.UUID getUUID(){return uuid;}
 public void sendSystemMessage(Object msg){}
}
'@
    'net/minecraft/server/network/ServerPlayNetworkHandler.java' = @'
package net.minecraft.server.network;
public class ServerPlayNetworkHandler {
 public net.minecraft.server.level.ServerPlayer player=net.minecraft.server.level.ServerPlayer.CURRENT;
}
'@
    'net/minecraft/network/chat/Component.java' = @'
package net.minecraft.network.chat;
public class Component {
 public static Component translatable(String key){return new Component();}
 public Component withStyle(Style style){return this;}
}
'@
    'net/minecraft/network/chat/Style.java' = @'
package net.minecraft.network.chat;
public class Style {
 public static final Style EMPTY=new Style();
 public Style withColor(net.minecraft.ChatFormatting color){return this;}
 public Style withClickEvent(ClickEvent event){return this;}
 public Style withHoverEvent(HoverEvent event){return this;}
}
'@
    'net/minecraft/network/chat/ClickEvent.java' = @'
package net.minecraft.network.chat;
public class ClickEvent {}
'@
    'net/minecraft/network/chat/HoverEvent.java' = @'
package net.minecraft.network.chat;
public class HoverEvent {}
'@
    'net/minecraft/ChatFormatting.java' = @'
package net.minecraft;
public enum ChatFormatting {AQUA}
'@
    'com/svcb/tsbridge/TsBridgeMod.java' = @'
package com.svcb.tsbridge;
public class TsBridgeMod {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("p0-issuing");
 public static com.svcb.tsbridge.ts.TsQueryClient tsQueryClient;
 public static net.minecraft.server.MinecraftServer mcServer;
}
'@
    'com/svcb/tsbridge/ts/TsQueryClient.java' = @'
package com.svcb.tsbridge.ts;
public class TsQueryClient {
 public String getVirtualServerUid(){return "fixture-server";}
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
$identity = @('teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/identity/IdentitySigningKeys.java',
    'teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/identity/IdentityProofService.java',
    'teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/net/identity/IdentityPayloads.java',
    "$base/IdentityIssuingRegression.java")
# The revision store is part of the fix; compile its regression only when present.
if (Test-Path 'teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/identity/IdentityRevisionStore.java') {
    $identity += 'teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/identity/IdentityRevisionStore.java'
    $identity += "$base/IdentityRevisionStoreRegression.java"
}
$identity = $identity | Where-Object { Test-Path $_ }
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @core @identity @sources
if ($LASTEXITCODE -ne 0) { throw 'Identity issuing regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.svcb.tsbridge.identity.IdentityIssuingRegression
if ($LASTEXITCODE -ne 0) { throw 'Identity issuing regression failed' }
& java -cp "$base/classes;review/lib/*" com.svcb.tsbridge.identity.IdentityRevisionStoreRegression
if ($LASTEXITCODE -ne 0) { throw 'Revision store regression failed' }
exit $LASTEXITCODE
