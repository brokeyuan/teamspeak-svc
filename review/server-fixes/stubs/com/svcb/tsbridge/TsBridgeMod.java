package com.svcb.tsbridge;
import java.util.*;
public class TsBridgeMod {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("server-test");
 public static com.svcb.tsbridge.config.BridgeConfig config;
 public static UUID bridgeGroupId;
 public static boolean perPlayerMode;
 public static Mixer mixer=new Mixer(); public static Forwarder forwarder; public static Pairings pairings;
 public static Server mcServer;
 public static class Mixer {public int sent; public boolean isRunning(){return true;}public void enqueue(UUID id,byte[] b){sent++;}}
 public static class Forwarder {public int sent; public void enqueue(UUID id,byte[] b,long seq){sent++;}}
 public static class Pairings {public String getTsIdentity(UUID id){return null;}}
 public static class Server {public Players getPlayerList(){return new Players();}}
 public static class Players {public List<Object> getPlayers(){return List.of();}}
}