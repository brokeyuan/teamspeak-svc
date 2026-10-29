package de.maxhenkel.voicechat.voice.common;
public class GroupSoundPacket extends SoundPacket {
 public GroupSoundPacket(java.util.UUID group,java.util.UUID sender,byte[] data,long sequence,String g){super(sender,data,sequence);}
}
