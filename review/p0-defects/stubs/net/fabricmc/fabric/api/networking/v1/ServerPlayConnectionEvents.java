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