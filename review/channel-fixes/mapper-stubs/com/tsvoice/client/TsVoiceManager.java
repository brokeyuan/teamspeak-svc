package com.tsvoice.client;
public class TsVoiceManager {
 public record SessionConfig(String channelPassword){}
 public static GroupChannelMapper.SessionInfo currentSession(){return new GroupChannelMapper.SessionInfo("Main");}
 public static SessionConfig currentSessionConfig(){return new SessionConfig("main-secret");}
 public static Object currentBeacon(){return null;}
}