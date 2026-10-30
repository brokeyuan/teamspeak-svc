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
