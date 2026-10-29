
package net.minecraft.network;
public class FriendlyByteBuf {
 public FriendlyByteBuf writeUtf(String s){return this;}
 public String readUtf(){return "";}
 public FriendlyByteBuf writeLong(long v){return this;}
 public long readLong(){return 0;}
 public FriendlyByteBuf writeByteArray(byte[] b){return this;}
 public byte[] readByteArray(){return new byte[0];}
}
