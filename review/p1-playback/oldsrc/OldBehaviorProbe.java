package com.tsvoice.client;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
public class OldBehaviorProbe {
 public static void main(String[] a) throws Exception {
  TeamspeakSvcClient.api=(VoicechatClientApi)Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),new Class<?>[]{VoicechatClientApi.class},(p,m,v)->null);
  ClientManager.current=new de.maxhenkel.voicechat.voice.client.ClientVoicechat();
  int failures=0;
  // Old scheme: UUID ignores the TS server identity entirely.
  UUID u1=TsAudioInjector.ghostUuidFor("uid-x");
  if(u1.equals(TsAudioInjector.ghostUuidFor("uid-x"))&&u1.toString()!=null){
   System.out.println("FAIL old derivation is not authority-scoped (same uuid for any server)");
   failures++;
  }
  // Old close: unconditional by UUID kills whatever object is registered now.
  Map<UUID,Object> channels=(Map<UUID,Object>)ClientManager.getClient().getClass().getMethod("getAudioChannels").invoke(ClientManager.getClient());
  TsAudioInjector.onTsFrame("uid-x","Alice",null,1,4,new byte[]{1});
  Object stale=channels.get(TsAudioInjector.ghostUuidFor("uid-x"));
  Object fresh=new de.maxhenkel.voicechat.voice.client.ClientVoicechat.AudioChannel();
  channels.put(TsAudioInjector.ghostUuidFor("uid-x"),fresh); // SVC recreated the channel for a new stream
  ClientStateReflector.closeAudioChannel(TsAudioInjector.ghostUuidFor("uid-x"));
  if(!channels.containsKey(TsAudioInjector.ghostUuidFor("uid-x"))){
   System.out.println("FAIL old close removed the NEW stream channel by UUID alone");
   failures++;
  } else { System.out.println("unexpected: old close did not remove"); }
  System.out.println(failures==0?"probe inconclusive":"old behavior failures: "+failures);
  if(failures==0) System.exit(2); // probe must observe failures
 }
}
