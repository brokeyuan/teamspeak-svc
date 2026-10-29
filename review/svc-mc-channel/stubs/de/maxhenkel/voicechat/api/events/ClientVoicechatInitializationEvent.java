package de.maxhenkel.voicechat.api.events;
public interface ClientVoicechatInitializationEvent {
 void setSocketImplementation(de.maxhenkel.voicechat.api.ClientVoicechatSocket socket);
 de.maxhenkel.voicechat.api.ClientVoicechatSocket getSocketImplementation();
}
