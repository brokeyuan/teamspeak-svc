package de.maxhenkel.voicechat.voice.client;
public class MicThread extends Thread {
 public MicThread(ClientVoicechat client, ClientVoicechatConnection connection, java.util.function.Consumer<?> onerror){}
 public void close(){closed=true;}
 public static volatile boolean closed;
}
