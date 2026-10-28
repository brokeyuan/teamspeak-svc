package com.tsvoice.client.frontend;

import java.util.List;

/**
 * SVC 前端运行自检结果：关键 hook 缺失 → TS 必须禁用并明确告警；
 * 可选 hook 失败只禁用对应功能。结果用于 UI 与日志，不得静默降级。
 */
public record FrontendCapabilities(
        /** 关键：MicThread 可用（2.6.25 构造器支持 null connection：采集/PTT/激活照常，仅跳过发送）。 */
        boolean micThreadUsable,
        /** 关键：TS 播放注入管线可反射访问（processSoundPacket / GroupSoundPacket）。 */
        boolean playbackInjectionUsable,
        /** 可选：CLIENT_STOPPING 生命周期事件可注册（缺省时由 JVM shutdown 兜底）。 */
        boolean clientStoppingHookPresent,
        List<String> missingCriticalHooks,
        List<String> missingOptionalHooks) {

    /** TS 是否可用：任何关键 hook 缺失都禁用 TS（原版 SVC 在未接管时仍可用）。 */
    public boolean tsUsable() {
        return missingCriticalHooks.isEmpty();
    }
}
