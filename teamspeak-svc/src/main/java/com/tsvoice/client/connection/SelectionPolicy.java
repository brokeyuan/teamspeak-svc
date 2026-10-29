package com.tsvoice.client.connection;

import java.util.List;

/**
 * 纯函数选择策略（P4）：优先级为 显式 PINNED → 本服策略 → 已授权推荐 TS → SVC。
 * 不发网络、不读全局状态；输入全部显式传入。认证/权限类失败绝不通过换传输或
 * 换后端"绕过"（D04），传输回退只允许 NETWORK_TIMEOUT 且存在已授权兼容隧道。
 */
public final class SelectionPolicy {

    private static final VoiceState.BackendKind TS3 = VoiceState.BackendKind.TS3;
    private static final VoiceState.BackendKind SVC = VoiceState.BackendKind.SVC;

    private SelectionPolicy() {
    }

    /** 用户意图（D03/D1 语义）。PAUSED：用户主动断开 TS 后的明确暂停，不自动改选其他后端。 */
    public enum IntentKind { AUTO, PINNED_TS, PINNED_SVC, PAUSED, OFF }

    /** 传输策略：强制遵守，不悄悄把强制 TCP 改成 UDP。 */
    public enum TransportPolicy { AUTO, UDP_ONLY, TCP_ONLY }

    /** 失败类别：唯一枚举在 {@link VoiceState.FailureKind}；此处仅保留常量别名避免同义枚举漂移。 */
    public static final class FailureKind {
        public static final VoiceState.FailureKind NETWORK_TIMEOUT = VoiceState.FailureKind.NETWORK_TIMEOUT;
        public static final VoiceState.FailureKind DNS_FAILED = VoiceState.FailureKind.DNS_FAILED;
        public static final VoiceState.FailureKind AUTH_DENIED = VoiceState.FailureKind.AUTH_DENIED;
        public static final VoiceState.FailureKind CHANNEL_PASSWORD = VoiceState.FailureKind.CHANNEL_PASSWORD;
        public static final VoiceState.FailureKind PERMISSION_DENIED = VoiceState.FailureKind.PERMISSION_DENIED;
        public static final VoiceState.FailureKind BANNED = VoiceState.FailureKind.BANNED;
        public static final VoiceState.FailureKind SERVER_FULL = VoiceState.FailureKind.SERVER_FULL;
        public static final VoiceState.FailureKind UNSUPPORTED_PROTOCOL = VoiceState.FailureKind.UNSUPPORTED_PROTOCOL;
        public static final VoiceState.FailureKind FRONTEND_UNAVAILABLE = VoiceState.FailureKind.FRONTEND_UNAVAILABLE;
        public static final VoiceState.FailureKind CANCELLED = VoiceState.FailureKind.CANCELLED;
        public static final VoiceState.FailureKind INTERNAL = VoiceState.FailureKind.INTERNAL;

        private FailureKind() {
        }
    }

    /** 选择结果：目标后端与候选顺序（不可变）。 */
    public record Decision(VoiceState.BackendKind backend, List<Step> steps) {
    }

    /** 候选尝试步骤（顺序即回退顺序）。 */
    public record Step(VoiceState.BackendKind backend, boolean viaTunnel) {
    }

    /**
     * 解析候选顺序。pinned 意图不跨后端（PINNED_TS 只试 TS：UDP→已授权隧道）；
     * AUTO 按推荐与 SVC 兜底；PAUSED/OFF 永不自动连接。
     */
    public static Decision candidates(IntentKind intent, TransportPolicy transport,
                                      boolean authorizedTsRecommended,
                                      boolean authorizedTunnel) {
        return switch (intent) {
            case OFF, PAUSED -> new Decision(SVC, List.of());
            case PINNED_SVC -> new Decision(SVC, List.of(new Step(SVC, false)));
            case PINNED_TS -> new Decision(TS3, tsSteps(transport, authorizedTunnel));
            case AUTO -> {
                if (authorizedTsRecommended) {
                    yield new Decision(TS3, tsSteps(transport, authorizedTunnel));
                }
                yield new Decision(SVC, List.of(new Step(SVC, false)));
            }
        };
    }

    private static List<Step> tsSteps(TransportPolicy transport, boolean authorizedTunnel) {
        // 强制传输策略必须遵守；AUTO 允许 UDP 失败后试已授权隧道（仅 NETWORK_TIMEOUT）。
        return switch (transport) {
            case UDP_ONLY -> List.of(new Step(TS3, false));
            case TCP_ONLY -> authorizedTunnel
                    ? List.of(new Step(TS3, true))
                    : List.of();
            case AUTO -> authorizedTunnel
                    ? List.of(new Step(TS3, false), new Step(TS3, true))
                    : List.of(new Step(TS3, false));
        };
    }

    /**
     * 失败后是否允许下一步：仅传输内回退（UDP→隧道）在 NETWORK_TIMEOUT 且
     * 下一步确实是兼容隧道时允许；认证/权限/密码/ban/版本/前端错误一律停（等用户）。
     */
    public static boolean mayAdvanceAfter(VoiceState.FailureKind failure, Step failed, Step next) {
        if (failure != FailureKind.NETWORK_TIMEOUT) {
            return false;
        }
        return failed.backend() == TS3 && next != null
                && next.backend() == TS3 && next.viaTunnel() && !failed.viaTunnel();
    }

    /**
     * 重连退避（建议初值 2→4→…→60s，±20% jitter，单调时钟由调用方提供）。
     */
    public static long backoffMillis(int attempt, java.util.random.RandomGenerator random) {
        long base = Math.min(2_000L << Math.min(attempt, 5), 60_000L);
        long jitter = (long) (base * 0.2 * (random.nextDouble() * 2 - 1));
        return Math.max(1_000L, base + jitter);
    }
}
