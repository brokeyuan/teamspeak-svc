package net.minecraft.network.chat;
public class Component {
 public static Component translatable(String key){return new Component();}
 public Component withStyle(Style style){return this;}
}