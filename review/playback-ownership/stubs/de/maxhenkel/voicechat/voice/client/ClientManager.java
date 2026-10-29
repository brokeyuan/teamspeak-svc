package de.maxhenkel.voicechat.voice.client;
public class ClientManager {
 public static Object current;
 public static ClientPlayerStateManager states=new ClientPlayerStateManager();
 public static Object getClient(){return current;}
 public static ClientPlayerStateManager getPlayerStateManager(){return states;}
 public static GroupManager getGroupManager(){return new GroupManager();}
 public static class GroupManager {public Iterable<Object> getGroups(){return java.util.List.of();}}
}