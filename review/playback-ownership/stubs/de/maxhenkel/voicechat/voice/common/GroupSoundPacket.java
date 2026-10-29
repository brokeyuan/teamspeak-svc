package de.maxhenkel.voicechat.voice.common;
public class GroupSoundPacket extends SoundPacket {
 public final java.util.UUID channel;public final java.util.UUID sender;public final byte[] audio;public final long sequence;public final String category;
 public GroupSoundPacket(java.util.UUID channel,java.util.UUID sender,byte[] audio,long sequence,String category){this.channel=channel;this.sender=sender;this.audio=audio;this.sequence=sequence;this.category=category;}
 public java.util.UUID getChannelId(){return channel;}
}