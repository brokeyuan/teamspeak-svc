package de.maxhenkel.voicechat.voice.client;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
/**
 * N2/D01 faithful stub of the real 2.6.25 ClientVoicechat surface used here.
 * processSoundPacket replicates the real bytecode's offsets 0-7: getfield
 * connection, ifnonnull 8, return — a null connection silently drops the packet
 * and never creates an AudioChannel. The previous unconditional version of this
 * stub is what let F5/F6 fake a standalone playback that cannot exist.
 */
public class ClientVoicechat {
 public ClientVoicechatConnection connection;
 public final Map<UUID,AudioChannel> audioChannels = new HashMap<>();
 private final SoundManager soundManager = new SoundManager();
 public static final List<SoundPacket> processReceived = new CopyOnWriteArrayList<>();
 public static final AtomicLong droppedNullConnection = new AtomicLong();
 public SoundManager getSoundManager(){return soundManager;}
 public Map<UUID,AudioChannel> getAudioChannels(){return audioChannels;}
 public void processSoundPacket(SoundPacket packet){
  if(connection == null){droppedNullConnection.incrementAndGet(); return;}
  processReceived.add(packet);
 }
}
