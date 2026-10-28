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