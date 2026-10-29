package de.maxhenkel.voicechat.voice.client;
public class ClientVoicechat {
 private final SoundManager soundManager = new SoundManager();
 public final java.util.Map<java.util.UUID,AudioChannel> audioChannels = new java.util.HashMap<>();
 public java.util.Map<java.util.UUID,AudioChannel> getAudioChannels(){return audioChannels;}
 public SoundManager getSoundManager(){return soundManager;}
 public void processSoundPacket(de.maxhenkel.voicechat.voice.common.SoundPacket packet){
  de.maxhenkel.voicechat.voice.client.ClientVoicechat.registered.add(packet);
 }
 public static final java.util.List<de.maxhenkel.voicechat.voice.common.SoundPacket> registered =
   new java.util.concurrent.CopyOnWriteArrayList<>();
}
