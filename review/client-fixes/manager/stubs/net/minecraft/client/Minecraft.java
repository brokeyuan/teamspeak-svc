package net.minecraft.client;
public class Minecraft {
 public Player player;private final Thread fakeThread=Thread.currentThread();public static Minecraft getInstance(){return new Minecraft();}public void execute(Runnable r){r.run();}public boolean isSameThread(){return Thread.currentThread()==fakeThread;}
 public User getUser(){return new User();}public static class User{public String getName(){return "Test";}}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.randomUUID();}public void sendSystemMessage(Object msg){}}
}