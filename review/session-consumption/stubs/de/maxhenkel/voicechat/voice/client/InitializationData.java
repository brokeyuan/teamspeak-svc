package de.maxhenkel.voicechat.voice.client;
/**
 * Real 2.6.25 requires a network SecretPacket to construct. The adapter passes
 * NULL because the real AudioChannel bytecode never reads the field back.
 */
public class InitializationData {
}
