package com.tsvoice.client.ts3voice;
public class Ts3VoiceClient {
 public static final int CODEC_OPUS_VOICE=4;
 public boolean connected;
 public boolean throwOnSend;
 public int sent;
 public int connectedCalls;
 public Boolean[] connectedScript;
 public record TsVoiceIn(int senderClid,int codec,int sequence,byte[] opus){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,boolean tcp){}
 public void setServerPassword(String password){}
 public void setTextListener(java.util.function.BiConsumer<String,String> l){}
 public void setVoiceListener(java.util.function.Consumer<TsVoiceIn> l){}
 public void connect(java.net.InetAddress host,int port,long timeout){}
 public void disconnect(){}
 public boolean isConnected(){
  if(connectedScript!=null){return connectedScript[Math.min(connectedCalls++,connectedScript.length-1)];}
  return connected;
 }
 public int getMyClid(){return 1;}
 public int getMyChannelId(){return 1;}
 public com.tsvoice.client.ts3voice.TsChannelSnapshot channelSnapshot(){return new com.tsvoice.client.ts3voice.TsChannelSnapshot(null);}
 public void sendCommand(String cmd) throws Ts3VoiceException{}
 public void requestChannelList() throws Ts3VoiceException{}
 public void requestServerInfo() throws Ts3VoiceException{}
 public String getVirtualServerUid(){return "fixture-server";}
 public void joinChannel(int cid,String channelPassword){}
 public void setChannelMembershipListener(java.util.function.IntConsumer l){}
 public void publishMcUuid(java.util.UUID id){}
 public void sendVoice(byte[] opus,int codec){
  if(throwOnSend){throw new RuntimeException("send failure");}
  sent++;
 }
 public String getUniqueIdOf(int clid){return "uid";}
 public String getNicknameOf(int clid){return "name";}
 public java.util.UUID getMcUuidOf(int clid){return null;}
 public void sendChannelText(String message){}
}