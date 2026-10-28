package com.tsvoice.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 能力公告 wire 格式（P4，纯编解码，零依赖）：
 *
 * <pre>
 * capabilities_v1 (S2C) / capabilities_hello_v1 (C2S) 共用帧头：
 *   magic "TSCAP" (4) | major (1) | minor (1) | requestNonce (8) |
 *   ttlSeconds (4) | serviceInstanceId 长度前缀字符串 |
 *   candidateCount (2) | 每个候选：
 *     kind (1: 1=TS_ENDPOINT 2=TUNNEL 3=SVC_MC_CHANNEL) |
 *     host 长度前缀字符串 | port (4) | extraCount (1) |
 *     每个 extra：2 字节 length + 原始字节（未知 minor 字段由消费方跳过）
 * </pre>
 *
 * 限制：帧 ≤16384 字节；候选 ≤8；host ≤253；TTL ≤60；未知 major 整帧拒绝。
 * 长度/端口/计数字段全部严格验证，任何越界都是畸形输入 → 解码失败，绝不无界分配。
 */
public final class CapabilityWire {

    public static final int MAX_FRAME_BYTES = 16 * 1024;
    public static final int MAX_CANDIDATES = 8;
    public static final int MAX_HOST_LENGTH = 253;
    public static final long MAX_TTL_SECONDS = 60;
    public static final int CURRENT_MAJOR = 1;
    public static final int CURRENT_MINOR = 0;
    static final byte[] MAGIC = {'T', 'S', 'C', 'A', 'P'};

    public enum CandidateKind {
        TS_ENDPOINT(1), TUNNEL(2), SVC_MC_CHANNEL(3);

        final int wire;

        CandidateKind(int wire) {
            this.wire = wire;
        }

        static CandidateKind fromWire(int value) {
            for (CandidateKind kind : values()) {
                if (kind.wire == value) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** 一个候选（TS endpoint / 隧道 / MC_CHANNEL 通道）。附加字段原样承载。 */
    public record Candidate(CandidateKind kind, String host, int port, List<byte[]> unknownFields) {
        public Candidate {
            if (kind == null || host == null || host.isBlank()) {
                throw new IllegalArgumentException("Invalid candidate");
            }
        }
    }

    public record Capabilities(long requestNonce, int major, int minor, int ttlSeconds,
                               String serviceInstanceId, List<Candidate> candidates) {
    }

    private CapabilityWire() {
    }

    // ---- 编码 ----

    public static byte[] encode(Capabilities capabilities) {
        if (capabilities.major() != CURRENT_MAJOR) {
            throw new IllegalArgumentException("Unsupported major " + capabilities.major());
        }
        if (capabilities.ttlSeconds() < 0 || capabilities.ttlSeconds() > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("TTL out of range");
        }
        if (capabilities.candidates().size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Too many candidates");
        }
        byte[] serviceId = capabilities.serviceInstanceId() == null
                ? new byte[0] : capabilities.serviceInstanceId().getBytes(StandardCharsets.UTF_8);
        List<byte[]> candidateBodies = new ArrayList<>();
        for (Candidate candidate : capabilities.candidates()) {
            candidateBodies.add(encodeCandidate(candidate));
        }
        int size = MAGIC.length + 2 + 8 + 4 + 2 + serviceId.length + 2;
        for (byte[] body : candidateBodies) {
            size += body.length;
        }
        if (size > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame exceeds " + MAX_FRAME_BYTES);
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put(MAGIC);
        buffer.put((byte) capabilities.major());
        buffer.put((byte) capabilities.minor());
        buffer.putLong(capabilities.requestNonce());
        buffer.putInt(capabilities.ttlSeconds());
        putBytes(buffer, serviceId);
        buffer.putShort((short) capabilities.candidates().size());
        for (byte[] body : candidateBodies) {
            buffer.put(body);
        }
        return buffer.array();
    }

    private static byte[] encodeCandidate(Candidate candidate) {
        byte[] host = candidate.host().getBytes(StandardCharsets.UTF_8);
        if (host.length > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("Host too long");
        }
        if (candidate.port() < 0 || candidate.port() > 0xFFFF) {
            throw new IllegalArgumentException("Port out of range");
        }
        List<byte[]> extras = candidate.unknownFields() == null ? List.of() : candidate.unknownFields();
        if (extras.size() > 0xFF) {
            throw new IllegalArgumentException("Too many extra fields");
        }
        int size = 1 + 2 + host.length + 4 + 1;
        for (byte[] extra : extras) {
            size += 2 + extra.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) candidate.kind().wire);
        putBytes(buffer, host);
        buffer.putInt(candidate.port());
        buffer.put((byte) extras.size());
        for (byte[] extra : extras) {
            if (extra.length > 0xFFFF) {
                throw new IllegalArgumentException("Extra field too large");
            }
            buffer.putShort((short) extra.length);
            buffer.put(extra);
        }
        return buffer.array();
    }

    // ---- 解码 ----

    /** 解码；任何畸形输入返回 null（调用方必须丢弃整帧）。 */
    public static Capabilities decode(byte[] frame) {
        if (frame == null || frame.length < MAGIC.length + 2 + 8 + 4 + 2
                || frame.length > MAX_FRAME_BYTES) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            for (byte expected : MAGIC) {
                if (buffer.get() != expected) {
                    return null;
                }
            }
            int major = buffer.get() & 0xFF;
            int minor = buffer.get() & 0xFF;
            if (major != CURRENT_MAJOR) {
                return null; // 未知 major：整帧拒绝
            }
            long nonce = buffer.getLong();
            int ttl = buffer.getInt();
            if (ttl < 0 || ttl > MAX_TTL_SECONDS) {
                return null;
            }
            byte[] serviceId = getBytes(buffer);
            int candidateCount = buffer.getShort() & 0xFFFF;
            if (candidateCount > MAX_CANDIDATES) {
                return null;
            }
            List<Candidate> candidates = new ArrayList<>(candidateCount);
            for (int i = 0; i < candidateCount; i++) {
                Candidate candidate = decodeCandidate(buffer);
                if (candidate == null) {
                    return null;
                }
                candidates.add(candidate);
            }
            if (buffer.hasRemaining()) {
                return null; // 尾部垃圾：整帧拒绝
            }
            return new Capabilities(nonce, major, minor, ttl,
                    new String(serviceId, StandardCharsets.UTF_8), List.copyOf(candidates));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Candidate decodeCandidate(ByteBuffer buffer) {
        CandidateKind kind = CandidateKind.fromWire(buffer.get() & 0xFF);
        if (kind == null) {
            return null;
        }
        byte[] host = getBytes(buffer);
        if (host.length == 0 || host.length > MAX_HOST_LENGTH) {
            return null;
        }
        int port = buffer.getInt();
        if (port < 0 || port > 0xFFFF) {
            return null;
        }
        int extraCount = buffer.get() & 0xFF;
        List<byte[]> extras = new ArrayList<>(Math.min(extraCount, 16));
        for (int i = 0; i < extraCount; i++) {
            byte[] extra = getBytes(buffer);
            extras.add(extra);
        }
        return new Candidate(kind, new String(host, StandardCharsets.UTF_8), port,
                extras.isEmpty() ? List.of() : List.copyOf(extras));
    }

    private static void putBytes(ByteBuffer buffer, byte[] value) {
        if (value.length > 0xFFFF) {
            throw new IllegalArgumentException("Field too large");
        }
        buffer.putShort((short) value.length);
        buffer.put(value);
    }

    private static byte[] getBytes(ByteBuffer buffer) {
        int length = buffer.getShort() & 0xFFFF;
        if (length > buffer.remaining()) {
            throw new IllegalArgumentException("Truncated length-prefixed field");
        }
        byte[] out = new byte[length];
        buffer.get(out);
        return out;
    }
}
