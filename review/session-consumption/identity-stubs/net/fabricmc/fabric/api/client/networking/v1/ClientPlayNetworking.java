
package net.fabricmc.fabric.api.client.networking.v1;
public class ClientPlayNetworking {
 public static final java.util.Map<Object,java.util.function.BiConsumer<Object,Object>> RECEIVERS=new java.util.HashMap<>();
 public static <T> void registerGlobalReceiver(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,java.util.function.BiConsumer<T,Context> handler){
  RECEIVERS.put(type,(java.util.function.BiConsumer<Object,Object>)(java.util.function.BiConsumer<?,?>)handler);
 }
 public static final java.util.List<Object> SENT=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static void send(Object payload){SENT.add(payload);}
 public record Context(){public net.minecraft.client.Minecraft client(){return net.minecraft.client.Minecraft.getInstance();}}
}
