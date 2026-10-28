$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/client-fixes'
$stubs = @{
 'com/tsvoice/client/TeamspeakSvcClient.java' = @'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("review");
 public static de.maxhenkel.voicechat.api.VoicechatClientApi api;
 public static de.maxhenkel.voicechat.api.VoicechatClientApi clientApi(){return api;}
}
'@
 'com/tsvoice/client/GhostSkins.java' = @'
package com.tsvoice.client;
public class GhostSkins {public static void register(java.util.UUID a,java.util.UUID b,String n){} public static void unregister(java.util.UUID a){}}
'@
 'com/tsvoice/client/OnlinePlayers.java' = @'
package com.tsvoice.client;
public class OnlinePlayers {public static boolean isOnline(java.util.UUID id){return false;}}
'@
 'net/minecraft/client/Minecraft.java' = @'
package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();
 public static Minecraft getInstance(){return INSTANCE;}
 public boolean isSameThread(){return true;}
 public void execute(Runnable action){action.run();}
}
'@
 'de/maxhenkel/voicechat/voice/client/ClientManager.java' = @'
package de.maxhenkel.voicechat.voice.client;
public class ClientManager {
 public static Object current;
 public static ClientPlayerStateManager states=new ClientPlayerStateManager();
 public static Object getClient(){return current;}
 public static ClientPlayerStateManager getPlayerStateManager(){return states;}
 public static GroupManager getGroupManager(){return new GroupManager();}
 public static class GroupManager {public Iterable<Object> getGroups(){return java.util.List.of();}}
}
'@
 'de/maxhenkel/voicechat/voice/client/ClientPlayerStateManager.java' = @'
package de.maxhenkel.voicechat.voice.client;
public class ClientPlayerStateManager {
 public final java.util.Map<java.util.UUID,Object> states=new java.util.HashMap<>();
}
'@
 'de/maxhenkel/voicechat/voice/common/PlayerState.java' = @'
package de.maxhenkel.voicechat.voice.common;
public class PlayerState {
 public java.util.UUID id,group;public String name;public boolean disabled,disconnected;
 public PlayerState(java.util.UUID id,String name,boolean disabled,boolean disconnected){this.id=id;this.name=name;this.disabled=disabled;this.disconnected=disconnected;}
 public void setGroup(java.util.UUID group){this.group=group;}
}
'@
 'de/maxhenkel/voicechat/voice/common/SoundPacket.java' = @'
package de.maxhenkel.voicechat.voice.common;
public class SoundPacket {}
'@
 'de/maxhenkel/voicechat/voice/common/GroupSoundPacket.java' = @'
package de.maxhenkel.voicechat.voice.common;
public class GroupSoundPacket extends SoundPacket {
 public final java.util.UUID channel;public final java.util.UUID sender;public final byte[] audio;public final long sequence;public final String category;
 public GroupSoundPacket(java.util.UUID channel,java.util.UUID sender,byte[] audio,long sequence,String category){this.channel=channel;this.sender=sender;this.audio=audio;this.sequence=sequence;this.category=category;}
 public java.util.UUID getChannelId(){return channel;}
}
'@
 'de/maxhenkel/voicechat/voice/client/AudioChannel.java' = @'
package de.maxhenkel.voicechat.voice.client;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
// N2/D01 faithful playback-channel stub: Thread-based, stores all four ctor
// parameters, addToQueue queues, closeAndKill stops (daemon like the real class).
public class AudioChannel extends Thread {
 public final ClientVoicechat client;public final InitializationData initializationData;
 public final SoundManager soundManager;public final java.util.UUID uuid;
 public final java.util.Queue<SoundPacket> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();
 public volatile boolean closed;
 public AudioChannel(ClientVoicechat c,InitializationData i,SoundManager s,java.util.UUID u){
  client=c;initializationData=i;soundManager=s;uuid=u;setDaemon(true);}
 public void addToQueue(SoundPacket p){queue.add(p);}
 public boolean isClosed(){return closed;}
 public void closeAndKill(){closed=true;}
 public void run(){while(!closed){try{Thread.sleep(5);}catch(InterruptedException e){return;}}}
}
'@
 'de/maxhenkel/voicechat/voice/client/InitializationData.java' = @'
package de.maxhenkel.voicechat.voice.client;
// Real 2.6.25 needs a network secret to construct; the adapter passes null
// because the real AudioChannel bytecode never reads the field back.
public class InitializationData {}
'@
 'de/maxhenkel/voicechat/voice/client/SoundManager.java' = @'
package de.maxhenkel.voicechat.voice.client;
public class SoundManager {public boolean isClosed(){return false;}public boolean isClosing(){return false;}}
'@
 'de/maxhenkel/voicechat/voice/client/ClientVoicechat.java' = @'
package de.maxhenkel.voicechat.voice.client;
public class ClientVoicechat {
 private final SoundManager soundManager = new SoundManager();
 public SoundManager getSoundManager(){return soundManager;}
 public int frames;
 public final java.util.Map<java.util.UUID,AudioChannel> audioChannels=new java.util.HashMap<>();
 public void processSoundPacket(de.maxhenkel.voicechat.voice.common.SoundPacket packet){
  frames++;
  if(packet instanceof de.maxhenkel.voicechat.voice.common.GroupSoundPacket group){
   audioChannels.computeIfAbsent(group.getChannelId(),id->new AudioChannel());
  }
 }
 public java.util.Map<java.util.UUID,AudioChannel> getAudioChannels(){return audioChannels;}
 public static class AudioChannel {public boolean closed; public void closeAndKill(){closed=true;}}
}
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
 $path=Join-Path "$base/stubs" $item.Key
 New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
 [IO.File]::WriteAllText((Join-Path (Get-Location) $path),$item.Value)
 $path
}
$protocol = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
 Where-Object {$_.Name -notin @('Ts3VoiceSelfTest.java','Ts3ChannelTest.java')} | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*;ts3voice-core/build/classes/java/main' -d "$base/classes" @protocol @sources 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/TsPlaybackRegistry.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/SvcFrontendAdapter.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendCapabilities.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendLease.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/Svc2625FrontendAdapter.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ClientStateReflector.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/TsAudioInjector.java' "$base/ClientStateTests.java"
if($LASTEXITCODE -ne 0){throw 'Client test compilation failed'}
& java -cp "$base/classes;review/lib/*;ts3voice-core/build/classes/java/main" com.tsvoice.client.ts3voice.ClientStateTests
if($LASTEXITCODE -ne 0){throw 'Client regression failed'}
