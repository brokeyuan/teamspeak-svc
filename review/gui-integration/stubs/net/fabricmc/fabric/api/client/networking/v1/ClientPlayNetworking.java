package net.fabricmc.fabric.api.client.networking.v1;
import java.util.function.BiConsumer;
public final class ClientPlayNetworking {
 public static <P> void registerGlobalReceiver(P type,BiConsumer<P,Context> handler){}
 public static boolean canSend(Object type){return true;}
 public static void send(Object payload){}
 public interface Context {net.minecraft.client.Minecraft client();}
}