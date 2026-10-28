package com.tsvoice.client.ts3voice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Recording stub: captures listeners so tests can fire stale-session callbacks. */
public class Ts3VoiceClient {
    public static final int CODEC_OPUS_VOICE = 4;
    public static final List<Ts3VoiceClient> INSTANCES = new ArrayList<>();

    public String uidPrefix = "c";
    public boolean connected = true;
    public boolean disconnected;
    public int sent;
    public Consumer<TsVoiceIn> voiceListener;
    public TextListener textListener;
    public IntConsumer membershipListener;
    public Runnable onDisconnectedExternal;

    public record TsVoiceIn(int senderClid, int codec, int sequence, byte[] opus) {}

    public interface TextListener {
        void onText(String invokerName, String message);
    }

    public Ts3VoiceClient(Ts3Identity id, String nickname, String channel, boolean tcp) {
        INSTANCES.add(this);
    }

    public String identity() {
        return "client-" + uidPrefix;
    }

    public void setServerPassword(String password) {}
    public void setTextListener(TextListener listener) { this.textListener = listener; }
    public void setVoiceListener(Consumer<TsVoiceIn> listener) { this.voiceListener = listener; }
    public void setChannelMembershipListener(IntConsumer listener) { this.membershipListener = listener; }
    public void setOnDisconnected(Runnable callback) { this.onDisconnectedExternal = callback; }

    public void fireVoice(TsVoiceIn frame) { if (voiceListener != null) voiceListener.accept(frame); }
    public void fireText(String invoker, String message) { if (textListener != null) textListener.onText(invoker, message); }
    public void fireMembership(int clid) { if (membershipListener != null) membershipListener.accept(clid); }

    public void connect(java.net.InetAddress host, int port, long timeout) {}
    public void disconnect() { disconnected = true; connected = false; }
    public boolean isConnected() { return connected; }
    public int getMyClid() { return 1; }
    public int getMyChannelId() { return -1; }
    public com.tsvoice.client.ts3voice.TsChannelSnapshot channelSnapshot() {
        return new com.tsvoice.client.ts3voice.TsChannelSnapshot(null);
    }
    public void joinChannel(int cid, String channelPassword) {}
    public record MoveResult(boolean succeeded,Integer errorCode,String errorMessage){}
    public java.util.concurrent.CompletableFuture<MoveResult> moveChannelAsync(int cid,String channelPassword,long timeoutMillis){
        return java.util.concurrent.CompletableFuture.completedFuture(new MoveResult(true,null,null));
    }
    public void sendCommand(String cmd) throws Ts3VoiceException {}
    public void requestChannelList() throws Ts3VoiceException {}
    public void requestServerInfo() throws Ts3VoiceException {}
    public String getVirtualServerUid() { return "fixture-server"; }
    public void publishMcUuid(java.util.UUID id) {}
    public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier) {}
    public void clearQueuedVoice() {}
    public void sendVoice(byte[] opus, int codec) { sendVoice(opus, codec, Long.MIN_VALUE); }
    public void sendVoice(byte[] opus, int codec, long routeEpoch) { sent++; }
    public String getUniqueIdOf(int clid) { return uidPrefix + clid; }
    public String getNicknameOf(int clid) { return "nick-" + uidPrefix + clid; }
    public void sendChannelText(String message) {}
}
