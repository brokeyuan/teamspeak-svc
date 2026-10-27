package com.tsvoice.client.ts3voice;

/**
 * TS3 语音协议包（源：TSLib Packet.cs）。
 * 线上布局：[MAC(8)][header][data]；C2S header 5B = pid(2BE)+clientId(2BE)+type|flags(1)，
 * S2C header 3B = pid(2BE)+type|flags(1)。
 */
public final class Ts3Packet {

    public final boolean fromServer;
    public final int headerLength;

    public byte[] raw;
    public byte[] header;
    public byte[] data;

    public int packetId;
    public long generationId;
    /** type 低 4 位 + flags 高 4 位 */
    public int typeFlagged;

    /** C2S 头部扩展：我方 clientId。 */
    public int clientNid;

    public Ts3Packet(boolean fromServer) {
        this.fromServer = fromServer;
        this.headerLength = fromServer ? 3 : 5;
    }

    /** 从发送数据构造出站包。 */
    public static Ts3Packet out(byte[] data, PacketType type, int packetId, long generationId, int clientNid) {
        Ts3Packet p = new Ts3Packet(false);
        p.raw = new byte[data.length + p.headerLength + Ts3Crypt.MAC_LEN];
        p.header = new byte[p.headerLength];
        p.data = data;
        p.typeFlagged = type.value & 0x0F;
        p.packetId = packetId;
        p.generationId = generationId;
        p.clientNid = clientNid;
        return p;
    }

    /** 解析原始入站字节（含 MAC）。 */
    public static Ts3Packet in(byte[] raw) {
        if (raw.length < 3 + Ts3Crypt.MAC_LEN) {
            return null;
        }
        Ts3Packet p = new Ts3Packet(true);
        p.raw = raw;
        p.header = new byte[p.headerLength];
        p.fromHeader();
        return p;
    }

    public void fromHeader() {
        packetId = ((raw[Ts3Crypt.MAC_LEN] & 0xFF) << 8) | (raw[Ts3Crypt.MAC_LEN + 1] & 0xFF);
        typeFlagged = raw[Ts3Crypt.MAC_LEN + 2] & 0xFF;
    }

    public void buildHeader() {
        buildHeader(header, 0);
    }

    /** 将包头写入指定缓冲（加密时以 AAD 形式使用）。 */
    public void buildHeader(byte[] into, int off) {
        into[off] = (byte) (packetId >>> 8);
        into[off + 1] = (byte) packetId;
        if (!fromServer) {
            into[off + 2] = (byte) (clientNid >>> 8);
            into[off + 3] = (byte) clientNid;
            into[off + 4] = (byte) typeFlagged;
        } else {
            into[off + 2] = (byte) typeFlagged;
        }
    }

    /** 包类型；未知类型（0x9-0xF）返回 null，由连接层丢弃。 */
    public PacketType packetType() {
        return PacketType.fromValue(typeFlagged & 0x0F);
    }

    public void setPacketType(PacketType t) {
        typeFlagged = (typeFlagged & 0xF0) | (t.value & 0x0F);
    }

    public boolean hasFlag(int flag) {
        return (typeFlagged & flag) != 0;
    }

    public void setFlag(int flag, boolean on) {
        if (on) {
            typeFlagged |= flag;
        } else {
            typeFlagged &= ~flag;
        }
    }

    public boolean fragmentedFlag() {
        return hasFlag(PacketType.FLAG_FRAGMENTED);
    }

    public boolean compressedFlag() {
        return hasFlag(PacketType.FLAG_COMPRESSED);
    }

    public boolean unencryptedFlag() {
        return hasFlag(PacketType.FLAG_UNENCRYPTED);
    }

    @Override
    public String toString() {
        PacketType t = packetType();
        return (t != null ? t : "UNKNOWN(" + (typeFlagged & 0x0F) + ")")
                + " id=" + packetId + " flags=" + Integer.toBinaryString(typeFlagged)
                + " len=" + (data == null ? 0 : data.length);
    }
}
