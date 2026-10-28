package de.maxhenkel.voicechat.voice.client;
public class ClientManager {
 public static boolean present = true;
 private static final ClientVoicechat CLIENT = new ClientVoicechat();
 public static ClientVoicechat getClient(){return present ? CLIENT : null;}
}
