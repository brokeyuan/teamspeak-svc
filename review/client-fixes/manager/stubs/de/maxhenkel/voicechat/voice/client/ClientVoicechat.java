package de.maxhenkel.voicechat.voice.client;
public class ClientVoicechat {
 public void processSoundPacket(de.maxhenkel.voicechat.voice.common.SoundPacket packet){
  de.maxhenkel.voicechat.voice.client.ClientVoicechat.registered.add(packet);
 }
 public static final java.util.List<de.maxhenkel.voicechat.voice.common.SoundPacket> registered =
   new java.util.concurrent.CopyOnWriteArrayList<>();
}
