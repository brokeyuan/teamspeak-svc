package com.tsvoice.client.connection;

/**
 * 明确的语音传输类型（S1.1/P6）：取代 boolean tcp 的隐式语义。
 *
 * <ul>
 *   <li>{@link #TS_UDP}：原生 TeamSpeak UDP（默认直连路径，行为不变）；</li>
 *   <li>{@link #TS_TUNNEL_V1}：旧 TCP 隧道（旧 {@code tcp=true} 只迁移到这一
 *       种，不自动变成 MC 通道）；</li>
 *   <li>{@link #TS_MC_CHANNEL}：经本扩展统一服务端中继的 per-session UDP
 *       （P6/S1）。需要服务端装 teamspeak-svc 且管理员 allowlist 目标 ——
 *       客户端不先解析 TS 主机名，DNS 与 endpoint 允许判断都在服务端。</li>
 * </ul>
 *
 * <p>{@link #AUTO} 只在选择层出现：按规则试下一种已授权传输。书签 schema 的
 * transportPolicy（null/"udp"/"tcp"）迁移映射：null→AUTO，"udp"→TS_UDP，
 * "tcp"→TS_TUNNEL_V1。</p>
 */
public enum VoiceTransport {
    AUTO,
    TS_UDP,
    TS_TUNNEL_V1,
    TS_MC_CHANNEL;

    /** 书签/配置字符串 → 传输类型（未知值回落 AUTO，与旧语义一致）。 */
    public static VoiceTransport fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "udp" -> TS_UDP;
            case "tcp", "tunnel" -> TS_TUNNEL_V1;
            case "mc_channel", "mc-channel", "relay" -> TS_MC_CHANNEL;
            default -> AUTO;
        };
    }

    /** 旧 boolean tcp（M7 语义）→ 传输类型。 */
    public static VoiceTransport fromLegacyTcp(boolean tcp) {
        return tcp ? TS_TUNNEL_V1 : TS_UDP;
    }
}
