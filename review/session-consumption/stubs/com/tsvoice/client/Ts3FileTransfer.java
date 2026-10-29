package com.tsvoice.client;
public class Ts3FileTransfer {
 public enum Result { CONFIRMED, TRANSPORT_FINISHED, REMOTE_VERIFIED, FAILED, UNKNOWN }
 public static Ts3FileTransfer.Result upload(com.tsvoice.client.ts3voice.Ts3VoiceClient client,String host,String name,byte[] data){return Result.FAILED;}
}