package com.mojang.blaze3d.platform;
public class NativeImage implements AutoCloseable {
 public static NativeImage read(byte[] png){return new NativeImage(64,64,true);}
 public NativeImage(int w,int h,boolean useAlpha){}
 public int getWidth(){return 64;} public int getHeight(){return 64;}
 public int getPixel(int x,int y){return 0;}
 public void setPixelABGR(int x,int y,int color){}
 public void writeToFile(java.nio.file.Path path){}
 public void close(){}
}