package com.tsvoice.client.frontend;

import com.tsvoice.client.connection.VoiceState;

/**
 * SVC 前端适配边界：隔离 2.6.25 内部 API 依赖，提供 mic 所有权与自检。
 * 实现（{@link Svc2625FrontendAdapter}）只允许使用经 inspect 验证过的入口；
 * 版本敏感代码不得散落到其余代码路径。
 */
public interface SvcFrontendAdapter {

    /** 启动/重连时自检：探测本机 SVC 版本的关键与可选入口。 */
    FrontendCapabilities inspect();

    /**
     * 获取 mic 租约：任一时刻只有一个所有者。SVC 原生 mic 存在则借用；
     * 不存在则创建 null-connection 隔离 mic。关键 hook 缺失时返回 null
     * 并由调用方禁用 TS（FRONTEND_UNAVAILABLE）。
     */
    FrontendLease acquire(VoiceState.SessionToken token);

    /** 释放当前租约（若由本适配器发放）；幂等。 */
    void release(FrontendLease lease);
}
