package de.maxhenkel.voicechat.voice.client;
public class ClientVoicechat {
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