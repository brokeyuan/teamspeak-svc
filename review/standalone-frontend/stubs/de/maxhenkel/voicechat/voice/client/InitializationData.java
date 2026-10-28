package de.maxhenkel.voicechat.voice.client;
/**
 * Real 2.6.25 requires (String, SecretPacket) — a network secret — to construct.
 * The adapter passes NULL instead because the real AudioChannel bytecode stores
 * the field and never reads it back (verified via javap: 0 getfield), so no
 * secret is needed and none is faked.
 */
public class InitializationData {
}
