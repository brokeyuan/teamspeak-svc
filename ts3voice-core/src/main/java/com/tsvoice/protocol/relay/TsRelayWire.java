package com.tsvoice.protocol.relay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * TS 中继 wire 格式（S1.2/P6，纯编解码，零依赖）：
 *
 * <pre>
 * 共用帧头：magic "TSRL" (4) | version (1) | type (1) | payload...
 *
 * C2S OPEN:        requestId (4) | targetId 长度前缀字符串 | purpose (1)
 * S2C OPENED:      requestId (4) | relaySessionId (16 字节随机) | maxDatagram (4) |
 *                  maxDatagramsPerSecond (4) | maxBytesPerSecond (4)
 * 双向 DATA:       relaySessionId (16) | routeEpoch (8) | trafficClass (1) |
 *                  datagramBytes（长度前缀 ≤4096）
 * C2S ROUTE_BARRIER: relaySessionId (16) | nextRouteEpoch (8)
 * 双向 CLOSE/CLOSED: relaySessionId (16) | reason 长度前缀字符串
 * 双向 ERROR:      requestId (4，0=按会话) | relaySessionId (16，可全 0) |
 *                  stableErrorCode (2) | message 长度前缀字符串
 * </pre>
 *
 * 限制（工程保护初值，配置/协商取较小值）：外层帧 ≤16 KiB，分配前校验；
 * 单个 TS 数据报 ≤4096 字节，超长拒绝不截断。长度/计数字段全部严格验证，
 * 任何越界都是畸形输入 → 解码返回 null，绝不无界分配。未知 version 整帧拒绝；
 * 未知 type 返回 UNKNOWN 记录（接收方记日志后丢弃整帧）。
 *
 * <p>会话认领在服务端以真实 MC connection 代际 + player + relaySessionId 完成，
 * 客户端自报身份不进入认领判断（本 codec 不承载 player/连接代际字段）。</p>
 */
public final class TsRelayWire {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_FRAME_BYTES = 16 * 1024;
    public static final int MAX_DATAGRAM_BYTES = 4096;
    public static final int MAX_TARGET_ID_LENGTH = 64;
    public static final int MAX_REASON_LENGTH = 128;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 200;
    public static final int RELAY_SESSION_ID_BYTES = 16;

    static final byte[] MAGIC = {'T', 'S', 'R', 'L'};

    /** 中继消息类型（方向由注册层约束；codec 只编解码）。 */
    public enum MessageType {
        OPEN(1),
        OPENED(2),
        DATA_C2S(3),
        DATA_S2C(4),
        ROUTE_BARRIER(5),
        CLOSE(6),
        CLOSED(7),
        ERROR(8);

        final int wire;

        MessageType(int wire) {
            this.wire = wire;
        }

        static MessageType fromWire(int value) {
            for (MessageType type : values()) {
                if (type.wire == value) {
                    return type;
                }
            }
            return null;
        }
    }

    /** relay 能力发现帧（S1.2 兼容要求：独立 relay v1 发现，不动 capabilities v1）。 */
    public static final byte[] RELAY_CAPABILITY_MAGIC = {'T', 'S', 'R', 'C'};

    /** 中继用途（初版只启用 PLAYER_VOICE；MUSIC_BOT 需明确授权后再开放）。 */
    public enum Purpose {
        PLAYER_VOICE(1);

        final int wire;

        Purpose(int wire) {
            this.wire = wire;
        }

        static Purpose fromWire(int value) {
            for (Purpose p : values()) {
                if (p.wire == value) {
                    return p;
                }
            }
            return null;
        }
    }

    /** 稳定错误码（协议语义与传输/取消/内部异常分开；数值即 wire 值）。 */
    public static final int ERR_UNKNOWN = 0;
    public static final int ERR_UNSUPPORTED_VERSION = 1;
    public static final int ERR_UNKNOWN_TARGET = 2;
    public static final int ERR_TARGET_NOT_ALLOWED = 3;
    public static final int ERR_NOT_AUTHORIZED = 4;
    public static final int ERR_LIMIT_OPEN_RATE = 5;
    public static final int ERR_LIMIT_SESSIONS = 6;
    public static final int ERR_SESSION_NOT_FOUND = 7;
    public static final int ERR_DATAGRAM_TOO_LARGE = 8;
    public static final int ERR_TARGET_UNAVAILABLE = 9;
    public static final int ERR_MALFORMED = 10;
    public static final int ERR_SERVER_SHUTDOWN = 11;

    public record OpenFrame(int requestId, String targetId, Purpose purpose) {
    }

    public record OpenedFrame(int requestId, byte[] relaySessionId, int maxDatagramBytes,
                              int maxDatagramsPerSecond, int maxBytesPerSecond) {
    }

    public record DataFrame(byte[] relaySessionId, long routeEpoch, int trafficClass, byte[] datagram) {
    }

    public record RouteBarrierFrame(byte[] relaySessionId, long nextRouteEpoch) {
    }

    public record CloseFrame(byte[] relaySessionId, String reason) {
    }

    public record ErrorFrame(int requestId, byte[] relaySessionId, int stableErrorCode, String message) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private TsRelayWire() {
    }

    /** 生成 128 位随机 relaySessionId。 */
    public static byte[] newSessionId() {
        byte[] id = new byte[RELAY_SESSION_ID_BYTES];
        RANDOM.nextBytes(id);
        return id;
    }

    public static boolean isZeroSessionId(byte[] id) {
        if (id == null || id.length != RELAY_SESSION_ID_BYTES) {
            return false;
        }
        for (byte b : id) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    // ---- 编码 ----

    public static byte[] encodeOpen(OpenFrame frame) {
        byte[] target = frame.targetId() == null ? new byte[0]
                : frame.targetId().getBytes(StandardCharsets.UTF_8);
        if (target.length > MAX_TARGET_ID_LENGTH) {
            throw new IllegalArgumentException("Target id too long");
        }
        if (frame.purpose() == null) {
            throw new IllegalArgumentException("Purpose required");
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + 2 + 4 + 2 + target.length + 1);
        buffer.put(MAGIC).put((byte) PROTOCOL_VERSION).put((byte) MessageType.OPEN.wire);
        buffer.putInt(frame.requestId());
        putString(buffer, target);
        buffer.put((byte) frame.purpose().wire);
        return buffer.array();
    }

    public static byte[] encodeOpened(OpenedFrame frame) {
        requireSessionId(frame.relaySessionId());
        ByteBuffer buffer = ByteBuffer.allocate(
                MAGIC.length + 2 + 4 + RELAY_SESSION_ID_BYTES + 4 + 4 + 4);
        buffer.put(MAGIC).put((byte) PROTOCOL_VERSION).put((byte) MessageType.OPENED.wire);
        buffer.putInt(frame.requestId());
        buffer.put(frame.relaySessionId());
        buffer.putInt(frame.maxDatagramBytes());
        buffer.putInt(frame.maxDatagramsPerSecond());
        buffer.putInt(frame.maxBytesPerSecond());
        return buffer.array();
    }

    public static byte[] encodeData(MessageType direction, DataFrame frame) {
        if (direction != MessageType.DATA_C2S && direction != MessageType.DATA_S2C) {
            throw new IllegalArgumentException("DATA direction required");
        }
        requireSessionId(frame.relaySessionId());
        if (frame.datagram() == null || frame.datagram().length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException(
                    "Datagram must be 1.." + MAX_DATAGRAM_BYTES + " bytes");
        }
        if (frame.trafficClass() < 0 || frame.trafficClass() > 0xFF) {
            throw new IllegalArgumentException("Traffic class out of range");
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + 2 + RELAY_SESSION_ID_BYTES
                + 8 + 1 + 2 + frame.datagram().length);
        buffer.put(MAGIC).put((byte) PROTOCOL_VERSION).put((byte) direction.wire);
        buffer.put(frame.relaySessionId());
        buffer.putLong(frame.routeEpoch());
        buffer.put((byte) frame.trafficClass());
        putString(buffer, frame.datagram());
        return buffer.array();
    }

    public static byte[] encodeRouteBarrier(RouteBarrierFrame frame) {
        requireSessionId(frame.relaySessionId());
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + 2 + RELAY_SESSION_ID_BYTES + 8);
        buffer.put(MAGIC).put((byte) PROTOCOL_VERSION).put((byte) MessageType.ROUTE_BARRIER.wire);
        buffer.put(frame.relaySessionId());
        buffer.putLong(frame.nextRouteEpoch());
        return buffer.array();
    }

    public static byte[] encodeClose(MessageType direction, CloseFrame frame) {
        if (direction != MessageType.CLOSE && direction != MessageType.CLOSED) {
            throw new IllegalArgumentException("CLOSE direction required");
        }
        requireSessionId(frame.relaySessionId());
        byte[] reason = frame.reason() == null ? new byte[0]
                : frame.reason().getBytes(StandardCharsets.UTF_8);
        if (reason.length > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason too long");
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + 2 + RELAY_SESSION_ID_BYTES + 2 + reason.length);
        buffer.put(MAGIC).put((byte) PROTOCOL_VERSION).put((byte) direction.wire);
        buffer.put(frame.relaySessionId());
        putString(buffer, reason);
        return buffer.array();
    }

    public static byte[] encodeError(ErrorFrame frame) {
        byte[] message = frame.message() == null ? new byte[0]
                : frame.message().getBytes(StandardCharsets.UTF_8);
        if (message.length > MAX_ERROR_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Error message too long");
        }
        byte[] sessionId = frame.relaySessionId() == null ? new byte[RELAY_SESSION_ID_BYTES]
                : frame.relaySessionId();
        requireSessionId(sessionId);
        ByteBuffer buffer = ByteBuffer.allocate(
                MAGIC.length + 2 + 4 + RELAY_SESSION_ID_BYTES + 2 + 2 + message.length);
        buffer.put(MAGIC).put((byte) PROTOCOL_VERSION).put((byte) MessageType.ERROR.wire);
        buffer.putInt(frame.requestId());
        buffer.put(sessionId);
        buffer.putShort((short) frame.stableErrorCode());
        putString(buffer, message);
        return buffer.array();
    }

    // ---- 解码 ----

    /** 解码结果：type + 具体帧（按 type 转型）；畸形帧返回 null。 */
    public record Decoded(MessageType type, Object frame) {
        public OpenFrame open() {
            return (OpenFrame) frame;
        }

        public OpenedFrame opened() {
            return (OpenedFrame) frame;
        }

        public DataFrame data() {
            return (DataFrame) frame;
        }

        public RouteBarrierFrame routeBarrier() {
            return (RouteBarrierFrame) frame;
        }

        public CloseFrame close() {
            return (CloseFrame) frame;
        }

        public ErrorFrame error() {
            return (ErrorFrame) frame;
        }
    }

    /**
     * 上行外层数据报分类（T32/E3 生产桥的唯一定型点）：runtime 的 UDP 回包是
     * <b>原始 TS 数据报</b>，生产桥在投递前用本方法判定该字节是已封装的 TSRL
     * 控制通知（CLOSED/ERROR —— runtime 控制路径直接给出 TSRL 帧）还是原始
     * TS UDP 数据。返回：
     * <ul>
     *   <li>{@code DATA_S2C}：字节不是 TSRL 帧 —— 按 raw TS 数据报处理，桥
     *       必须在此做唯一外层编码（包 DATA_S2C 后投递）；</li>
     *   <li>{@code CLOSED} / {@code ERROR}：字节已是 TSRL 控制帧，桥原样投递；</li>
     *   <li>{@code null}：无法判定（既非 TSRL 帧也无会话可归属）—— 桥拒绝。</li>
     * </ul>
     * 解析只做前缀/类型判定（前 6 字节），不要求整帧重解码。
     */
    public static MessageType classifyUpstreamDatagram(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length < MAGIC.length + 2) {
            return MessageType.DATA_S2C; // 短于 TSRL 帧头：必然是原始 TS 数据报
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return MessageType.DATA_S2C; // 非 TSRL 前缀：原始 TS 数据报
            }
        }
        if ((bytes[MAGIC.length] & 0xFF) != PROTOCOL_VERSION) {
            return null;
        }
        MessageType type = MessageType.fromWire(bytes[MAGIC.length + 1] & 0xFF);
        // 只有服务端 → 客户端方向的控制帧允许原样透传
        return (type == MessageType.CLOSED || type == MessageType.ERROR) ? type : null;
    }

    /** 解码；任何畸形输入返回 null（调用方丢弃整帧并按策略反馈 ERROR）。 */
    public static Decoded decode(byte[] frame) {
        if (frame == null || frame.length < MAGIC.length + 2 || frame.length > MAX_FRAME_BYTES) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            for (byte expected : MAGIC) {
                if (buffer.get() != expected) {
                    return null;
                }
            }
            int version = buffer.get() & 0xFF;
            if (version != PROTOCOL_VERSION) {
                return null; // 未知版本整帧拒绝
            }
            MessageType type = MessageType.fromWire(buffer.get() & 0xFF);
            if (type == null) {
                return null;
            }
            switch (type) {
                case OPEN: {
                    int requestId = buffer.getInt();
                    String targetId = new String(getBytes(buffer, MAX_TARGET_ID_LENGTH),
                            StandardCharsets.UTF_8);
                    Purpose purpose = Purpose.fromWire(buffer.get() & 0xFF);
                    if (purpose == null || buffer.hasRemaining()) {
                        return null;
                    }
                    return new Decoded(type, new OpenFrame(requestId, targetId, purpose));
                }
                case OPENED: {
                    int requestId = buffer.getInt();
                    byte[] sessionId = new byte[RELAY_SESSION_ID_BYTES];
                    buffer.get(sessionId);
                    int maxDatagram = buffer.getInt();
                    int maxDatagramsPerSecond = buffer.getInt();
                    int maxBytesPerSecond = buffer.getInt();
                    if (buffer.hasRemaining() || maxDatagram <= 0 || maxDatagram > MAX_DATAGRAM_BYTES
                            || maxDatagramsPerSecond <= 0 || maxBytesPerSecond <= 0) {
                        return null;
                    }
                    return new Decoded(type, new OpenedFrame(requestId, sessionId,
                            maxDatagram, maxDatagramsPerSecond, maxBytesPerSecond));
                }
                case DATA_C2S, DATA_S2C: {
                    byte[] sessionId = new byte[RELAY_SESSION_ID_BYTES];
                    buffer.get(sessionId);
                    long routeEpoch = buffer.getLong();
                    int trafficClass = buffer.get() & 0xFF;
                    byte[] datagram = getBytes(buffer, MAX_DATAGRAM_BYTES);
                    if (buffer.hasRemaining() || datagram.length == 0) {
                        return null;
                    }
                    return new Decoded(type, new DataFrame(sessionId, routeEpoch, trafficClass, datagram));
                }
                case ROUTE_BARRIER: {
                    byte[] sessionId = new byte[RELAY_SESSION_ID_BYTES];
                    buffer.get(sessionId);
                    long nextRouteEpoch = buffer.getLong();
                    if (buffer.hasRemaining()) {
                        return null;
                    }
                    return new Decoded(type, new RouteBarrierFrame(sessionId, nextRouteEpoch));
                }
                case CLOSE, CLOSED: {
                    byte[] sessionId = new byte[RELAY_SESSION_ID_BYTES];
                    buffer.get(sessionId);
                    String reason = new String(getBytes(buffer, MAX_REASON_LENGTH),
                            StandardCharsets.UTF_8);
                    if (buffer.hasRemaining()) {
                        return null;
                    }
                    return new Decoded(type, new CloseFrame(sessionId, reason));
                }
                case ERROR: {
                    int requestId = buffer.getInt();
                    byte[] sessionId = new byte[RELAY_SESSION_ID_BYTES];
                    buffer.get(sessionId);
                    int code = buffer.getShort() & 0xFFFF;
                    String message = new String(getBytes(buffer, MAX_ERROR_MESSAGE_LENGTH),
                            StandardCharsets.UTF_8);
                    if (buffer.hasRemaining()) {
                        return null;
                    }
                    return new Decoded(type, new ErrorFrame(requestId, sessionId, code, message));
                }
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- relay 能力发现帧（独立于 capabilities v1）----

    /**
     * 编码 relay 能力帧（服务端 → 客户端，单方向）：指示本服务器支持
     * TS_MC_CHANNEL 中继与 purpose 列表。纯固定字段；不支持时服务器不发送。
     */
    public static byte[] encodeRelayCapability(List<Purpose> purposes) {
        if (purposes == null || purposes.isEmpty() || purposes.size() > 8) {
            throw new IllegalArgumentException("Relay capability needs 1..8 purposes");
        }
        List<Integer> wires = new ArrayList<>();
        for (Purpose purpose : purposes) {
            if (purpose != Purpose.PLAYER_VOICE) {
                throw new IllegalArgumentException("Purpose not enabled in this protocol version");
            }
            wires.add(purpose.wire);
        }
        ByteBuffer buffer = ByteBuffer.allocate(RELAY_CAPABILITY_MAGIC.length + 1 + 1 + wires.size());
        buffer.put(RELAY_CAPABILITY_MAGIC).put((byte) PROTOCOL_VERSION);
        buffer.put((byte) wires.size());
        for (int wire : wires) {
            buffer.put((byte) wire);
        }
        return buffer.array();
    }

    /** 解码 relay 能力帧；畸形/未知版本返回 null。 */
    public static List<Purpose> decodeRelayCapability(byte[] frame) {
        if (frame == null || frame.length != RELAY_CAPABILITY_MAGIC.length + 1 + 1 + 1) {
            return null;
        }
        for (int i = 0; i < RELAY_CAPABILITY_MAGIC.length; i++) {
            if (frame[i] != RELAY_CAPABILITY_MAGIC[i]) {
                return null;
            }
        }
        if ((frame[4] & 0xFF) != PROTOCOL_VERSION) {
            return null;
        }
        int count = frame[5] & 0xFF;
        if (count != 1) {
            return null; // 当前版本只声明 PLAYER_VOICE
        }
        Purpose purpose = Purpose.fromWire(frame[6] & 0xFF);
        return purpose == null ? null : List.of(purpose);
    }

    private static void requireSessionId(byte[] id) {
        if (id == null || id.length != RELAY_SESSION_ID_BYTES) {
            throw new IllegalArgumentException("relaySessionId must be 16 bytes");
        }
    }

    private static void putString(ByteBuffer buffer, byte[] value) {
        if (value.length > 0xFFFF) {
            throw new IllegalArgumentException("Field too large");
        }
        buffer.putShort((short) value.length);
        buffer.put(value);
    }

    private static byte[] getBytes(ByteBuffer buffer, int max) {
        int length = buffer.getShort() & 0xFFFF;
        if (length > max || length > buffer.remaining()) {
            throw new IllegalArgumentException("Length-prefixed field out of bounds");
        }
        byte[] out = new byte[length];
        buffer.get(out);
        return out;
    }
}
