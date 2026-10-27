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