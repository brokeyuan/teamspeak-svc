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