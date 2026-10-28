package com.tsvoice.client.ts3voice;
public class Ts3VoiceClient {
 public static final int CODEC_OPUS_VOICE=4;
 // Connect scripts: each new instance polls the next script and runs it from connect().
 public static final java.util.Deque<java.util.function.Consumer<Ts3VoiceClient>> connectScripts =
         new java.util.concurrent.ConcurrentLinkedDeque<>();
 public boolean connected;
 public int sent;
 public boolean throwOnSend;
 public java.util.function.Consumer<Ts3VoiceClient> onConnect;
 public java.util.function.BiConsumer<String,String> textListener;
 public java.util.function.Consumer<TsVoiceIn> voiceListener;
 public java.util.function.IntConsumer membershipListener;
 public record TsVoiceIn(int senderClid,int codec,int sequence,byte[] opus){}
 public Ts3VoiceClient(Ts3Identity id,String n,String channel,boolean tcp){onConnect=connectScripts.poll();}
 public void setServerPassword(String password){}
 public void setTextListener(java.util.function.BiConsumer<String,String> l){textListener=l;}
 public void setVoiceListener(java.util.function.Consumer<TsVoiceIn> l){voiceListener=l;}
 public void setChannelMembershipListener(java.util.function.IntConsumer l){membershipListener=l;}
 public void connect(java.net.InetAddress host,int port,long timeout) throws Exception {
  if(onConnect!=null){onConnect.accept(this);}
  connected=true;
 }
 public void disconnect(){connected=false;}
 public boolean isConnected(){return connected;}
 public int getMyClid(){return 1;}
 public int getMyChannelId(){return 1;}
 public void sendCommand(String cmd) throws Ts3VoiceException{}
 public void requestChannelList() throws Ts3VoiceException{}
 public void requestServerInfo() throws Ts3VoiceException{}
 public String getVirtualServerUid(){return "fixture-server";}
 public void publishMcUuid(java.util.UUID id){}
 public void sendVoice(byte[] opus,int codec){
  if(throwOnSend){throw new RuntimeException("send failure");}
  sent++;
 }
 public String getUniqueIdOf(int clid){return "uid";}
 public String getNicknameOf(int clid){return "nick";}
 public java.util.UUID getMcUuidOf(int clid){return null;}
 public void sendChannelText(String message){}
}