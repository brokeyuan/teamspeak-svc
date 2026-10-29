package com.tsvoice.server;

import com.tsvoice.protocol.capability.CapabilityWire;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公告器（M7-T14.4）：从当前运行时的配置快照与当前连接处理 hello、构造公告。
 * 不执行 DNS/TS 连接/文件读取；hello 限流只影响公告响应。
 */
public final class CapabilityAnnouncer {

    /** 单连接限流：2 次/秒、突发 4（时钟可注入；只影响响应，不踢玩家）。 */
    static final double RATE_PER_SECOND = 2.0;
    static final double BURST = 4.0;

    private final ServerConfig config;
    private final TimeSource clock;
    /** 连接身份（玩家 UUID + 连接会话标识）→ 令牌桶；由 runtime 在退出时清理。 */
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** 可注入时钟（纳秒）。 */
    public interface TimeSource {
        long nanoTime();
    }

    private static final class TokenBucket {
        double tokens;
        long lastRefill;

        TokenBucket(long now) {
            tokens = BURST;
            lastRefill = now;
        }

        boolean tryConsume(double ratePerSecond, long now) {
            double refill = (now - lastRefill) / 1_000_000_000.0 * ratePerSecond;
            tokens = Math.min(BURST, tokens + refill);
            lastRefill = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public CapabilityAnnouncer(ServerConfig config, TimeSource clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * 处理一条 hello 帧：有界校验 → 解码 → 限流 → 构造响应帧。
     *
     * @param connectionId 连接身份（重连区分；非 player UUID）
     * @return 响应帧；null = 不回复（限流/关闭/无目标/畸形）
     */
    public byte[] onHello(String connectionId, byte[] helloFrame) {
        if (!config.capabilities.enabled || helloFrame == null
                || helloFrame.length > CapabilityWire.MAX_FRAME_BYTES) {
            return null;
        }
        CapabilityWire.Capabilities hello;
        try {
            hello = CapabilityWire.decode(helloFrame);
        } catch (Exception e) {
            return null; // 畸形 hello 静默丢弃
        }
        if (hello == null) {
            return null;
        }
        List<ServerConfig.Target> targets = config.announceableTargets();
        if (targets.isEmpty()) {
            return null; // 无目标不回复：客户端走既有选择规则
        }
        if (!buckets.computeIfAbsent(connectionId, id -> new TokenBucket(clock.nanoTime()))
                .tryConsume(RATE_PER_SECOND, clock.nanoTime())) {
            return null; // 限流：不回复本次 hello
        }
        List<CapabilityWire.Candidate> candidates = targets.stream()
                .map(t -> new CapabilityWire.Candidate(
                        CapabilityWire.CandidateKind.TS_ENDPOINT, t.host,
                        t.port == null ? 9987 : t.port, List.of()))
                .toList();
        CapabilityWire.Capabilities response = new CapabilityWire.Capabilities(
                hello.requestNonce(),
                CapabilityWire.CURRENT_MAJOR,
                CapabilityWire.CURRENT_MINOR,
                (int) Math.max(1L, config.capabilities.ttlSeconds),
                config.capabilities.serviceInstanceId,
                candidates);
        byte[] frame = CapabilityWire.encode(response);
        return frame.length <= CapabilityWire.MAX_FRAME_BYTES ? frame : null;
    }

    /** 连接退出：清理限流记录（runtime 在 DISCONNECT/停止时调用）。 */
    public void forgetConnection(String connectionId) {
        buckets.remove(connectionId);
    }

    /** 当前限流记录数（诊断/生命周期测试用）。 */
    public int trackedConnections() {
        return buckets.size();
    }

    /** 已处理但无需桶维护的边界保护：极老记录由 runtime 周期清理。 */
    public void retainAllRecent() {
        // 有界性：桶数以连接数为上界，连接退出即清理；无后台任务。
    }

    /** 供状态展示的目标数（不含未实现 kind）。 */
    public int configuredTargetCount() {
        return config.announceableTargets().size();
    }
}
