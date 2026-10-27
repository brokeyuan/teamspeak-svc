package com.tsvoice.client;
public class GroupChannelMapper {
 public static boolean ready;
 public static void reset(){}
 public static void tick(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static boolean isReadyForVoice(com.tsvoice.client.ts3voice.Ts3VoiceClient c){return ready;}
 public record SessionInfo(String channelName){}
}