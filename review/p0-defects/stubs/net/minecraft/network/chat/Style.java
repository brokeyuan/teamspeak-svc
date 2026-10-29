package net.minecraft.network.chat;
public class Style {
 public static final Style EMPTY=new Style();
 public Style withColor(net.minecraft.ChatFormatting color){return this;}
 public Style withClickEvent(ClickEvent event){return this;}
 public Style withHoverEvent(HoverEvent event){return this;}
}