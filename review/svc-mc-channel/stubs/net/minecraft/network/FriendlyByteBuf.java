package net.minecraft.network;
public class FriendlyByteBuf {
 public FriendlyByteBuf writeBytes(byte[] b){return this;}
 public FriendlyByteBuf readBytes(byte[] dst){return this;}
 public int readableBytes(){return 0;}
 public FriendlyByteBuf writeUtf(String s){return this;}
 public String readUtf(int max){return "";}
}
