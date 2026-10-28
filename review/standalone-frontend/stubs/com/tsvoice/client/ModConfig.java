package com.tsvoice.client;
public class ModConfig {
 public boolean autoConnect;
 public boolean chatBridgeEnabled=true;
 public String transport="udp";
 public String tsHost;
 public int tsVoicePort=9987;
 public String channelName;
 public String serverPassword;
 public String channelPassword;
 public boolean isConfigured(){return tsHost!=null&&!tsHost.isBlank();}
}
