package net.minecraft.server.level;
public class ServerPlayer {
 public static ServerPlayer CURRENT;
 private final java.util.UUID uuid=java.util.UUID.randomUUID();
 public java.util.UUID getUUID(){return uuid;}
 public void sendSystemMessage(Object msg){}
}