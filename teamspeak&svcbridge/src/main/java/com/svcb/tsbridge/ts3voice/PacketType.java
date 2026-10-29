package com.svcb.tsbridge.ts3voice;

/**
 * TS3 语音协议包类型与标志位（源：TSLib PacketType.cs）。
 */
public enum PacketType {
    VOICE(0x0),
    VOICE_WHISPER(0x1),
    COMMAND(0x2),
    COMMAND_LOW(0x3),
    PING(0x4),
    PONG(0x5),
    ACK(0x6),
    ACK_LOW(0x7),
    INIT1(0x8);

    public final int value;

    PacketType(int value) {
        this.value = value;
    }

    public static PacketType fromValue(int v) {
        return switch (v & 0x0F) {
            case 0x0 -> VOICE;
            case 0x1 -> VOICE_WHISPER;
            case 0x2 -> COMMAND;
            case 0x3 -> COMMAND_LOW;
            case 0x4 -> PING;
            case 0x5 -> PONG;
            case 0x6 -> ACK;
            case 0x7 -> ACK_LOW;
            case 0x8 -> INIT1;
            default -> null;
        };
    }

    // ---- 标志位（高 4 位） ----
    public static final int FLAG_FRAGMENTED = 0x10;
    public static final int FLAG_NEW_PROTOCOL = 0x20;
    public static final int FLAG_COMPRESSED = 0x40;
    public static final int FLAG_UNENCRYPTED = 0x80;
}
