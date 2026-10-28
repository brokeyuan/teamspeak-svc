package com.tsvoice.client;
import com.tsvoice.client.ts3voice.*;
import com.tsvoice.client.net.BeaconPayload;

public class ManagerTests {
 static int failures;
 static void check(boolean ok,String name){System.out.println((ok?"PASS ":"FAIL ")+name);if(!ok)failures++;}
 static boolean setChannelPassword(Object config,String value){try{config.getClass().getField("channelPassword").set(config,value);return true;}catch(Exception e){return false;}}
 static String channelPassword(Object config){try{return (String)config.getClass().getMethod("channelPassword").invoke(config);}catch(Exception e){return null;}}
 public static void main(String[] args)throws Exception{
  ModConfig global=TeamspeakSvcClient.config;global.tsHost="global";global.serverPassword=" server secret ";
  setChannelPassword(global," channel secret ");
  TsVoiceManager.onServerChanged("test-mc");
  Object config=TsVoiceManager.resolveSessionConfig();
  check(" server secret ".equals(((TsVoiceManager.SessionConfig)config).password()),"server password preserves exact spaces");
  check(" channel secret ".equals(channelPassword(config)),"global channel password resolves independently");
  ServerConfigStore.Entry entry=ServerConfigStore.get("test-mc");entry.host="manual";
  entry.serverPassword="server2";setChannelPassword(entry,"channel2");
  config=TsVoiceManager.resolveSessionConfig();
  check("server2".equals(((TsVoiceManager.SessionConfig)config).password()) && "channel2".equals(channelPassword(config)),"per-server passwords override both defaults independently");
  entry.serverPassword=null;setChannelPassword(entry,null);
  TsVoiceManager.onBeacon(new BeaconPayload("beacon",9987,"Main","direct",0));
  config=TsVoiceManager.resolveSessionConfig();
  check(config==null || "manual".equals(((TsVoiceManager.SessionConfig)config).source()),"untrusted beacon never activates the beacon route");
  entry.serverPassword="";setChannelPassword(entry,"");
  config=TsVoiceManager.resolveSessionConfig();
  check(((TsVoiceManager.SessionConfig)config).password()==null && channelPassword(config)==null,"explicit empty per-server passwords clear inherited defaults");

  TsVoiceManager.onServerChanged("trust-once");
  boolean originalAutoConnect=global.autoConnect;global.autoConnect=false;
  try {
  ServerConfigStore.Entry trustEntry=ServerConfigStore.get("trust-once");
  trustEntry.trustedBeaconHost=null;trustEntry.trustedBeaconPort=null;ServerConfigStore.save();
  TsVoiceManager.onBeacon(new BeaconPayload("beacon-a",9987,"Trust","direct",0));
  check(TsVoiceManager.pendingBeacon()!=null && TsVoiceManager.resolveSessionConfig()==null,"untrusted beacon waits for explicit approval");
  TsVoiceManager.authorizePendingBeacon(false,true);
  config=TsVoiceManager.resolveSessionConfig();
  check(config!=null && "beacon-a".equals(((TsVoiceManager.SessionConfig)config).host()) && ((TsVoiceManager.SessionConfig)config).password()==null,"one-time approval uses beacon without credentials");
  TsVoiceManager.onServerChanged("trust-remember");
  trustEntry=ServerConfigStore.get("trust-remember");trustEntry.trustedBeaconHost=null;trustEntry.trustedBeaconPort=null;ServerConfigStore.save();
  TsVoiceManager.onBeacon(new BeaconPayload("beacon-b",9987,"Remember","direct",0));
  TsVoiceManager.authorizePendingBeacon(true,true);
  check(TsVoiceManager.resolveSessionConfig()!=null,"remembered endpoint is approved");
  TsVoiceManager.onServerChanged("trust-remember");
  TsVoiceManager.onBeacon(new BeaconPayload("beacon-b",9987,"Remember","direct",0));
  check(TsVoiceManager.pendingBeacon()==null && TsVoiceManager.resolveSessionConfig()!=null,"remembered endpoint auto-trusts same host and port");
  TsVoiceManager.onBeacon(new BeaconPayload("beacon-c",9987,"Changed","direct",0));
  check(TsVoiceManager.pendingBeacon()!=null && TsVoiceManager.resolveSessionConfig()==null,"changed beacon endpoint requires renewed approval");
  TsVoiceManager.authorizePendingBeacon(false,false);
  check(TsVoiceManager.resolveSessionConfig()==null,"denied beacon does not connect");
  } finally {global.autoConnect=originalAutoConnect;}

  Ts3VoiceClient c=new Ts3VoiceClient(new Ts3Identity(),"Test","",false);
  var field=TsVoiceManager.class.getDeclaredField("client");field.setAccessible(true);field.set(null,c);
  // T01 契约：sendMic 只在 TS 被选中时接管 —— 经生产入口提交选择（不直改内部状态）。
  ServerConfigStore.get("manager-sendmic").host="127.0.0.1";
  TsVoiceManager.onServerChanged("manager-sendmic");
  check(com.tsvoice.client.audio.MicRouteGate.tsSelected(),"sendMic precondition: TS route committed via activation");
  GroupChannelMapper.ready=false;
  check(TsVoiceManager.sendMic(new byte[]{1}) && c.sent==0,"unconfirmed channel consumes microphone frame without TS send or SVC fallback");
  GroupChannelMapper.ready=true;
  check(TsVoiceManager.sendMic(new byte[]{1}) && c.sent==1,"confirmed channel forwards one microphone frame");
  if(failures!=0)throw new AssertionError(failures+" manager checks failed");
 }
}
