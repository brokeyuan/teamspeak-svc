package com.tsvoice.client.connection;

import com.tsvoice.protocol.capability.CapabilityWire;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 能力发现状态机（P4/T11 修订）：1.5 秒有界收集窗口 —— 从 {@link #begin} 起
 * 计时（<b>与是否收到公告无关</b>，无公告也会正常结束窗口）；响应绑定
 * contextEpoch + requestNonce；公告候选带 TTL，过期从快照剔除；授权绑定实际
 * 已接收的快照。不向任意端口探测；无公告意味着"未知/未声明"，不是"UDP 被封"。
 */
public final class CapabilityDiscovery {

    /** 有界收集窗口（1.5s）。 */
    public static final long COLLECT_WINDOW_NANOS = 1_500_000_000L;

    /** 已授权的推荐（玩家显式接受后的候选集合）。 */
    private volatile List<AnnouncedCandidate> authorized = List.of();
    /** 本窗口内实际接收的公告候选（授权弹窗的数据源；授权必须绑定它）。 */
    private volatile List<AnnouncedCandidate> received = List.of();
    private volatile long contextEpoch = -1;
    private volatile long windowStartNanos = Long.MIN_VALUE;
    /** 当前待校验的请求 nonce（每次 begin 刷新；响应必须回带）。 */
    private volatile long requestNonce = Long.MIN_VALUE;
    private volatile boolean begun = false;
    private final LongSupplier nanoClock;

    /** 一条绑定接收时刻与 TTL 的公告候选（expiresAtNanos 由接收时钟推导）。 */
    public record AnnouncedCandidate(CapabilityWire.Candidate candidate, long expiresAtNanos) {
    }

    public CapabilityDiscovery(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** MC 换服：公告与授权全部失效（显式书签不受影响，由协调器管理）。 */
    public synchronized void onContextChanged(long newContextEpoch) {
        this.contextEpoch = newContextEpoch;
        this.authorized = List.of();
        this.received = List.of();
        this.windowStartNanos = Long.MIN_VALUE;
        this.begun = false;
        this.requestNonce = Long.MIN_VALUE;
    }

    /**
     * 开始发现窗口并生成本轮请求 nonce（T11：无公告也有窗口；nonce 只防串会话/
     * 重放关联，不是服务器身份证明）。
     */
    public synchronized long begin() {
        begun = true;
        windowStartNanos = nanoClock.getAsLong();
        requestNonce = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        return requestNonce;
    }

    /** 当前请求 nonce（构造 hello/请求时读取）。 */
    public synchronized long currentRequestNonce() {
        return requestNonce;
    }

    /**
     * 收到一条公告（解码后传入）：context + nonce 校验失败即丢弃（T11）。
     * 候选按接收时刻 + TTL 记录过期点；TTL<=0 的公告不产生候选。
     */
    public synchronized boolean onAnnouncement(long announcementContextEpoch, long responseNonce,
                                               CapabilityWire.Capabilities capabilities) {
        if (!begun || announcementContextEpoch != contextEpoch || capabilities == null
                || responseNonce != requestNonce) {
            return false;
        }
        long now = nanoClock.getAsLong();
        if (windowStartNanos == Long.MIN_VALUE) {
            windowStartNanos = now;
        }
        // TTL 是 Capabilities 级字段；TTL<=0 的公告不产生可连接候选。
        if (capabilities.ttlSeconds() <= 0) {
            return false;
        }
        long expiresAt = now + capabilities.ttlSeconds() * 1_000_000_000L;
        java.util.List<AnnouncedCandidate> accepted = new java.util.ArrayList<>();
        for (CapabilityWire.Candidate candidate : capabilities.candidates()) {
            accepted.add(new AnnouncedCandidate(candidate, expiresAt));
        }
        received = List.copyOf(accepted);
        return !accepted.isEmpty();
    }

    /**
     * 玩家显式授权：候选必须绑定实际已接收的快照（T11：不接受外部任意列表），
     * 且在过期前；上下文改变后的确认被拒绝。
     */
    public synchronized boolean authorize(long contextEpochAtPrompt,
                                          List<AnnouncedCandidate> candidates) {
        if (!begun || contextEpochAtPrompt != contextEpoch) {
            return false; // 玩家回应时上下文已变：丢弃
        }
        long now = nanoClock.getAsLong();
        List<AnnouncedCandidate> live = candidates == null ? List.of()
                : candidates.stream().filter(c -> c.expiresAtNanos() > now).toList();
        // 授权必须绑定实际已接收的快照：外部任意/已过期列表不被接受。
        if (candidates != null) {
            for (AnnouncedCandidate c : candidates) {
                if (c == null || c.expiresAtNanos() <= now || !received.contains(c)) {
                    return false;
                }
            }
        }
        this.authorized = List.copyOf(live);
        return true;
    }

    /** 本窗口内实际接收、未过期的候选快照（授权弹窗数据源；未授权）。 */
    public List<AnnouncedCandidate> receivedCandidates() {
        long now = nanoClock.getAsLong();
        return received.stream().filter(c -> c.expiresAtNanos() > now).toList();
    }

    /** 收集窗口是否已超时（false = 仍在窗口内，可再等公告）。从未 begin 恒为 false。 */
    public boolean windowExpired() {
        long start = windowStartNanos;
        return begun && start != Long.MIN_VALUE
                && nanoClock.getAsLong() - start > COLLECT_WINDOW_NANOS;
    }

    /** 当前已授权且未过期的候选（快照；过期剔除，T11）。 */
    public List<AnnouncedCandidate> authorizedCandidates() {
        long now = nanoClock.getAsLong();
        return authorized.stream().filter(c -> c.expiresAtNanos() > now).toList();
    }

    /** 是否有已授权且未过期的 TS 推荐（选择策略输入）。 */
    public boolean hasAuthorizedTsRecommendation() {
        return authorizedCandidates().stream().anyMatch(c ->
                c.candidate().kind() == CapabilityWire.CandidateKind.TS_ENDPOINT);
    }

    /** 是否有已授权且未过期的兼容隧道（回退策略输入）。 */
    public boolean hasAuthorizedTunnel() {
        return authorizedCandidates().stream().anyMatch(c ->
                c.candidate().kind() == CapabilityWire.CandidateKind.TUNNEL);
    }
}
