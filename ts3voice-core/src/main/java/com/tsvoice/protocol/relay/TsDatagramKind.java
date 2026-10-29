package com.tsvoice.protocol.relay;

/**
 * TS 数据报类型识别（G2）：仅按可见包头分类，不解密、不重写 TS 数据，
 * 不宣称中继验证了 TS MAC。
 *
 * <p>线上布局：[MAC(8)][header][data]。C2S header 5B = pid(2)+clientId(2)+
 * type|flags(1) → type 字节下标 12；S2C header 3B = pid(2)+type|flags(1) →
 * 下标 10。类型取低 4 位（高 4 位为 flags，不参与类型判定）。</p>
 *
 * <ul>
 *   <li>MEDIA：VOICE(0x0)、VOICE_WHISPER(0x1) —— 受媒体代际屏障约束；</li>
 *   <li>CONTROL：COMMAND(0x2)、COMMAND_LOW(0x3)、PING(0x4)、PONG(0x5)、
 *       ACK(0x6)、ACK_LOW(0x7)、INIT1(0x8) —— 不受媒体屏障，但仍受
 *       来源/会话/大小/速率/额度约束；</li>
 *   <li>INVALID：短包、未知类型（0x9–0xF）—— 拒绝。</li>
 * </ul>
 *
 * <p>trafficClass 常量（DATA 帧外层字段）：0=旧版未分类（按包头识别，不全放行）、
 * 1=CONTROL、2=MEDIA。显式类别与包头不符、或未知类别 → 拒绝。</p>
 */
public final class TsDatagramKind {

    private TsDatagramKind() {
    }

    /** 中继方向（决定 type 字节在包内的下标）。 */
    public enum Direction { C2S, S2C }

    /** 分类结果。 */
    public enum Kind { CONTROL, MEDIA, INVALID }

    /** DATA 帧外层 trafficClass：旧版未分类。 */
    public static final int TRAFFIC_LEGACY = 0;
    /** DATA 帧外层 trafficClass：控制。 */
    public static final int TRAFFIC_CONTROL = 1;
    /** DATA 帧外层 trafficClass：媒体。 */
    public static final int TRAFFIC_MEDIA = 2;

    /** MAC 字节数（与 TS3Packet/Ts3Crypt 一致；core 零依赖故重复常量）。 */
    static final int MAC_LEN = 8;

    /**
     * 按可见包头分类一个 TS 数据报。
     *
     * @param datagram  完整 TS 数据报（含 MAC）
     * @param direction 中继方向（C2S=客户端→服务端，S2C=服务端→客户端）
     * @return CONTROL / MEDIA / INVALID（短包或未知类型）
     */
    public static Kind classify(byte[] datagram, Direction direction) {
        if (datagram == null || direction == null) {
            return Kind.INVALID;
        }
        int typeIndex = switch (direction) {
            case C2S -> MAC_LEN + 4; // 12
            case S2C -> MAC_LEN + 2; // 10
        };
        if (datagram.length < typeIndex + 1) {
            return Kind.INVALID;
        }
        int type = datagram[typeIndex] & 0x0F;
        return switch (type) {
            case 0x0, 0x1 -> Kind.MEDIA;
            case 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8 -> Kind.CONTROL;
            default -> Kind.INVALID;
        };
    }

    /**
     * 校验 DATA 帧外层 trafficClass 与包头分类的一致性。
     *
     * @return true = 该帧类别合法（0 按包头识别；1/2 必须与包头一致；其他拒绝）
     */
    public static boolean trafficClassConsistent(int trafficClass, Kind kind) {
        if (kind == Kind.INVALID) {
            return false;
        }
        return switch (trafficClass) {
            case TRAFFIC_LEGACY -> true; // 旧版未分类：按真实包头识别
            case TRAFFIC_CONTROL -> kind == Kind.CONTROL;
            case TRAFFIC_MEDIA -> kind == Kind.MEDIA;
            default -> false;
        };
    }
}
