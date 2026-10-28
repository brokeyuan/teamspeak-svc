package de.maxhenkel.voicechat.voice.client;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
public class ClientVoicechat {
 private final SoundManager soundManager = new SoundManager();
 public SoundManager getSoundManager(){return soundManager;}
 public void processSoundPacket(SoundPacket packet) {}
}
