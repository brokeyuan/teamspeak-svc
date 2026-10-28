package net.minecraft.client;
public class Minecraft {
 public Player player=new Player();
 public static final java.util.List<Runnable> TASKS=new java.util.concurrent.CopyOnWriteArrayList<>();
 private static final Minecraft INSTANCE=new Minecraft();
 public static Minecraft getInstance(){return INSTANCE;}
 public void execute(Runnable r){TASKS.add(r);}
 public boolean isSameThread(){return true;}
 public static class Player{public java.util.UUID getUUID(){return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");}}
}
