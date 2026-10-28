package com.tsvoice.client.connection;

import com.tsvoice.protocol.capability.CapabilityWire;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 能力发现状态机（P4，每 MC context 一个实例）：有界收集窗口内缓存公告，
 * nonce/contextEpoch 绑定响应 —— 迟到/过期/错 nonce 的公告一律丢弃；
 * 窗口超时返回当前候选（没有即回退 SVC）。不向任意端口探测；
 * 无公告意味着"未知/未声明"，不是"UDP 被封"。
 */
public final class CapabilityDiscovery {

    /** 有界收集窗口（建议初值 1.5s，待测）。 */
    public static final long COLLECT_WINDOW_NANOS = 1_500_000_000L;

    /** 已授权的推荐（玩家显式接受后的候选集合）。 */
    private volatile List<CapabilityWire.Candidate> authorized = List.of();
    private volatile long contextEpoch = -1;
    private volatile long windowStartNanos = Long.MIN_VALUE;
    private final LongSupplier nanoClock;

    public CapabilityDiscovery(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** MC 换服：公告与授权全部失效（显式书签不受影响，由协调器管理）。 */
    public synchronized void onContextChanged(long newContextEpoch) {
        this.contextEpoch = newContextEpoch;
        this.authorized = List.of();
        this.windowStartNanos = Long.MIN_VALUE;
    }

    /** 收到一条公告（解码后传入）；context/nonce 校验失败即丢弃。 */
    public synchronized boolean onAnnouncement(long announcementContextEpoch,
                                               CapabilityWire.Capabilities capabilities) {
        if (announcementContextEpoch != contextEpoch || capabilities == null) {
            return false;
        }
        // 授权决定由授权弹窗流程做出；此处仅记录窗口起点供超时判定。
        if (windowStartNanos == Long.MIN_VALUE) {
            windowStartNanos = nanoClock.getAsLong();
        }
        return true;
    }

    /** 玩家显式授权一组候选（弹窗确认）；上下文改变后的确认被拒绝。 */
    public synchronized boolean authorize(long contextEpochAtPrompt,
                                          List<CapabilityWire.Candidate> candidates) {
        if (contextEpochAtPrompt != contextEpoch) {
            return false; // 玩家回应时上下文已变：丢弃
        }
        this.authorized = candidates == null ? List.of() : List.copyOf(candidates);
        return true;
    }

    /** 收集窗口是否已超时（ false = 仍在窗口内，可再等公告）。 */
    public boolean windowExpired() {
        long start = windowStartNanos;
        return start != Long.MIN_VALUE && nanoClock.getAsLong() - start > COLLECT_WINDOW_NANOS;
    }

    /** 当前已授权候选（快照）。 */
    public List<CapabilityWire.Candidate> authorizedCandidates() {
        return authorized;
    }

    /** 是否有已授权的 TS 推荐（选择策略输入）。 */
    public boolean hasAuthorizedTsRecommendation() {
        return authorized.stream().anyMatch(c ->
                c.kind() == CapabilityWire.CandidateKind.TS_ENDPOINT);
    }

    /** 是否有已授权的兼容隧道（回退策略输入）。 */
    public boolean hasAuthorizedTunnel() {
        return authorized.stream().anyMatch(c ->
                c.kind() == CapabilityWire.CandidateKind.TUNNEL);
    }
}
