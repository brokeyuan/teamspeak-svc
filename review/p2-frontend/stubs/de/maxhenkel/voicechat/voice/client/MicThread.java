package de.maxhenkel.voicechat.voice.client;

import java.util.function.Consumer;

/** 2.6.25 ABI 模型：三参构造器（含 ClientVoicechatConnection，可为 null）。 */
public class MicThread extends Thread {
    public MicThread(ClientVoicechat client, ClientVoicechatConnection connection,
                     Consumer<de.maxhenkel.voicechat.voice.client.MicrophoneException> errorConsumer) {
    }

    public void close() {
    }

    public boolean isClosed() {
        return false;
    }
}
