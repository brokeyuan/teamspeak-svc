package net.minecraft.client;
public class Minecraft {
 public Player player;
 public static Minecraft getInstance(){return new Minecraft();}
 public void execute(Runnable r){r.run();}
 public User getUser(){return new User();}
 public static class User{public String getName(){return "Test";}}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.randomUUID();}public void sendSystemMessage(Object msg){}}
}