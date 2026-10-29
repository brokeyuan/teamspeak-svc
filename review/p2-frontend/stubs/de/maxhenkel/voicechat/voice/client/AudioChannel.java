package de.maxhenkel.voicechat.voice.client;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
/**
 * Faithful stub of the real 2.6.25 AudioChannel surface used by the adapter:
 * constructor stores all four parameters, addToQueue queues, start() is
 * Thread.start() (daemon, like the real bytecode), isClosed/closeAndKill manage
 * the lifecycle. The run loop stays alive until closed.
 */
public class AudioChannel extends Thread {
 public final ClientVoicechat client;
 public final InitializationData initializationData;
 public final SoundManager soundManager;
 public final UUID uuid;
 public final Queue<SoundPacket> queue = new ConcurrentLinkedQueue<>();
 public volatile boolean closed;
 public AudioChannel(ClientVoicechat client,InitializationData initializationData,SoundManager soundManager,UUID uuid){
  this.client=client;this.initializationData=initializationData;this.soundManager=soundManager;this.uuid=uuid;
  setDaemon(true);
 }
 public void addToQueue(SoundPacket packet){queue.add(packet);}
 public boolean isClosed(){return closed;}
 public void closeAndKill(){closed=true;}
 public void run(){while(!closed){try{Thread.sleep(5);}catch(InterruptedException e){return;}}}
}
