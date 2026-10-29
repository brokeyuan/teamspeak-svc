package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();
 public static Minecraft getInstance(){return INSTANCE;}
 public boolean isSameThread(){return true;}
 public void execute(Runnable action){action.run();}
}