package de.maxhenkel.voicechat.voice.common;
public class PlayerState {
 public java.util.UUID id,group;public String name;public boolean disabled,disconnected;
 public PlayerState(java.util.UUID id,String name,boolean disabled,boolean disconnected){this.id=id;this.name=name;this.disabled=disabled;this.disconnected=disconnected;}
 public void setGroup(java.util.UUID group){this.group=group;}
}