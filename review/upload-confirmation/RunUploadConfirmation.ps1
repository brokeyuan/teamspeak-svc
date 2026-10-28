$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base='review/upload-confirmation'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if(!(Test-Path 'review/lib/gson.jar')){
 $jar=Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
 Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$stubs=@{
'com/tsvoice/client/TeamspeakSvcClient.java'=@'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("upload-confirmation");
}
'@
'net/fabricmc/loader/api/FabricLoader.java'=@'
package net.fabricmc.loader.api;
public class FabricLoader {public static FabricLoader getInstance(){return new FabricLoader();} public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/upload-confirmation/config");}}
'@
'com/tsvoice/client/TsVoiceManager.java'=@'
package com.tsvoice.client;
public class TsVoiceManager {
 public static com.tsvoice.client.ts3voice.Ts3VoiceClient currentClient;
 public static com.tsvoice.client.ts3voice.Ts3VoiceClient currentClient(){return currentClient;}
}
'@
'com/tsvoice/client/ts3voice/Ts3VoiceClient.java'=@'
package com.tsvoice.client.ts3voice;
import java.util.*;
public class Ts3VoiceClient {
 public final java.util.List<String> sentCommands=new java.util.concurrent.CopyOnWriteArrayList<>();
 public volatile boolean failNextSend;
 public volatile com.tsvoice.client.Ts3FileTransfer.Result lastUploadResult;
 public final TsCommandTracker tracker=new TsCommandTracker(this);
 public int myChannel=1;
 public int joinCalls; public int lastJoinCid;
 public Map<Integer,String> channelNames=new java.util.concurrent.ConcurrentHashMap<>();
 public Map<Integer,Integer> channelParents=new java.util.concurrent.ConcurrentHashMap<>();
 public Ts3VoiceClient(Ts3Identity id,String nickname,String channel){}
 public TsCommandTracker commandTracker(){return tracker;}
 public void sendCommand(String cmd) throws Ts3VoiceException {
  if(failNextSend){throw new Ts3VoiceException("socket gone");}
  sentCommands.add(cmd);
 }
 public String getClientUid(){return "uid";}
 public String getVirtualServerUid(){return "fixture-server";}
 public boolean isConnected(){return true;}
 public static java.util.Map<String,String> parseParams(String row){
  java.util.Map<String,String> out=new java.util.HashMap<>();
  if(row==null){return out;}
  for(String token:row.trim().split("\s+")){
   int eq=token.indexOf('=');
   if(eq>0){out.put(token.substring(0,eq),token.substring(eq+1));}
  }
  return out;
 }
 public int getMyChannelId(){return myChannel;}
 public TsChannelSnapshot channelSnapshot(){
  TsChannelSnapshot snap=new TsChannelSnapshot("fixture-server");
  List<TsChannelSnapshot.Channel> list=new ArrayList<>();
  for(Map.Entry<Integer,String> e:channelNames.entrySet()){
   list.add(new TsChannelSnapshot.Channel(e.getKey(),channelParents.getOrDefault(e.getKey(),0),e.getValue(),false,0,1,10));
  }
  snap.replaceAll(list);return snap;
 }
 public Integer getChannelIdByName(String name){return null;}
 public Integer getSubChannelIdByName(String name,int parent){return null;}
 public void joinChannel(int cid,String channelPassword){joinCalls++;lastJoinCid=cid;}
 public void requestChannelList() throws Ts3VoiceException{}
 public void clearQueuedVoice(){}
}
'@
'com/tsvoice/client/ts3voice/Ts3VoiceException.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3VoiceException extends Exception {
 public Ts3VoiceException(){}
 public Ts3VoiceException(String message){super(message);}
}
'@
'com/tsvoice/client/ts3voice/Ts3Identity.java'=@'
package com.tsvoice.client.ts3voice;
public class Ts3Identity {
 public static Ts3Identity load(java.nio.file.Path path){return new Ts3Identity();}
 public static Ts3Identity generate(int a,long b){return new Ts3Identity();}
 public void save(java.nio.file.Path path){}
 public String getClientUid(){return "uid";}
}
'@
'com/mojang/blaze3d/platform/NativeImage.java'=@'
package com.mojang.blaze3d.platform;
public class NativeImage implements AutoCloseable {
 public static NativeImage read(byte[] png){return new NativeImage(64,64,true);}
 public NativeImage(int w,int h,boolean useAlpha){}
 public int getWidth(){return 64;} public int getHeight(){return 64;}
 public int getPixel(int x,int y){return 0;}
 public void setPixelABGR(int x,int y,int color){}
 public void writeToFile(java.nio.file.Path path){}
 public void close(){}
}
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
    $path = Join-Path "$base/stubs" $item.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path (Get-Location) $path), $item.Value)
    $path
}
$client = 'teamspeak-svc/src/main/java/com/tsvoice/client'
$prod = @("$client/Ts3FileTransfer.java", "$client/Ts3FileTransferBridge.java", "$client/AvatarPipeline.java",
    "$client/connection/VoiceState.java", "$client/connection/BackendCoordinator.java",
    "$client/connection/SelectionPolicy.java",
    "$client/ts3voice/TsCommandTracker.java", "$client/ts3voice/TsChannelSnapshot.java") |
    Where-Object { Test-Path $_ }
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @prod @sources `
    "$base/CommandLifecycleTest.java" "$base/UploadConfirmationTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Upload confirmation regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.CommandLifecycleTest
if ($LASTEXITCODE -ne 0) { throw 'Command lifecycle regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.UploadConfirmationTest
if ($LASTEXITCODE -ne 0) { throw 'Upload confirmation regression failed' }
exit $LASTEXITCODE
