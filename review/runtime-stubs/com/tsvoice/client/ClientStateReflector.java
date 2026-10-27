package com.tsvoice.client;
import java.util.*;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
public class ClientStateReflector {
 public static Map<UUID,String> active=new HashMap<>(), states=new HashMap<>();
 public static Map<UUID,String> activeGhosts(){return active;}
 public static boolean hasLocalState(UUID id){return states.containsKey(id);}
 public static void injectGhost(UUID id,String name,VoicechatClientApi api,UUID group){active.put(id,name);states.put(id,"injected");}
 public static void removeGhost(UUID id){active.remove(id);states.remove(id);}
 public static void updateUsernameCache(UUID id,String name){}
 public static Map<String,UUID> clientGroupsByName(){return Map.of();}
}