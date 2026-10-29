package net.minecraft.client;
public class Minecraft {
 public Player player=new Player();
 public static final java.util.List<Runnable> TASKS=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static final java.util.List<String> MESSAGES=new java.util.concurrent.CopyOnWriteArrayList<>();
 public static Minecraft getInstance(){return new Minecraft();}
 public void execute(Runnable r){TASKS.add(r);}
 public User getUser(){return new User();}
 public static class User{public String getName(){return "Test";}}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.randomUUID();}public void sendSystemMessage(Object msg){MESSAGES.add(msg instanceof net.minecraft.network.chat.Component c ? c.text : String.valueOf(msg));}}
}