package net.fabricmc.fabric.api.networking.v1;
public class PayloadTypeRegistry<T> {
 public static <T> PayloadTypeRegistry<T> clientboundPlay(){return new PayloadTypeRegistry<>();}
 public static <T> PayloadTypeRegistry<T> serverboundPlay(){return new PayloadTypeRegistry<>();}
 public PayloadTypeRegistry<T> register(Object type,Object codec){return this;}
}
