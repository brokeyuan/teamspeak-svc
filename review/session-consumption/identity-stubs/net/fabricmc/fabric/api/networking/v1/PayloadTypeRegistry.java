
package net.fabricmc.fabric.api.networking.v1;
public class PayloadTypeRegistry {
 public static Registry clientboundPlay(){return new Registry();}
 public static Registry serverboundPlay(){return new Registry();}
 public static class Registry {
  public <T> void register(net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type,
                           net.minecraft.network.codec.StreamCodec<?,T> codec){}
 }
}
