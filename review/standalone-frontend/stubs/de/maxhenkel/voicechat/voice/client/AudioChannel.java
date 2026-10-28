package de.maxhenkel.voicechat.voice.client;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
/**
 * Faithful stub of the real 2.6.25 AudioChannel surface: constructor stores all
 * four parameters (the real bytecode reads initializationData back ZERO times),
 * packets queue up, start() is Thread.start(), closeAndKill stops the channel.
 * The run loop stays alive until closed, mirroring a live playback channel.
 */
public class AudioChannel extends Thread {
 public final ClientVoicechat client;
 public final InitializationData initializationData;
 public final SoundManager soundManager;
 public final UUID uuid;
 public final Queue<SoundPacket> queue = new ConcurrentLinkedQueue<>();
 public volatile boolean closed;
 public static final CopyOnWriteArrayList<AudioChannel> startedChannels = new CopyOnWriteArrayList<>();
 public AudioChannel(ClientVoicechat client,InitializationData initializationData,SoundManager soundManager,UUID uuid){
  this.client=client;this.initializationData=initializationData;this.soundManager=soundManager;this.uuid=uuid;
  setDaemon(true); // real 2.6.25 bytecode: iconst_1; setDaemon — faithful
 }
 public void addToQueue(SoundPacket packet){queue.add(packet);}
 public boolean isClosed(){return closed;}
 public void closeAndKill(){closed=true;}
 public void run(){
  startedChannels.add(this);
  while(!closed){
   try{Thread.sleep(5);}catch(InterruptedException e){return;}
  }
 }
}
