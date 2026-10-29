package net.minecraft.resources;
public record Identifier(String namespace,String path) {
 public static Identifier fromNamespaceAndPath(String ns,String path){return new Identifier(ns,path);}
 public String toString(){return namespace+":"+path;}
}
