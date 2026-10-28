package com.tsvoice.client.frontend;

import com.tsvoice.client.connection.VoiceState;

/**
 * mic 租约：前端单一所有权的凭据。同一时刻最多一个活跃租约；SVC 原生连接
 * 断开只关闭租约自己拥有的资源（借用的原生 mic 归还给 SVC 生命周期），
 * 协调器随后可重新申请。close 幂等。
 */
public final class FrontendLease implements AutoCloseable {

    /** mic 资源句柄：真实实现包装 SVC MicThread（原生或 null-connection 隔离）。 */
    public interface MicHandle extends AutoCloseable {
        boolean isAlive();

        @Override
        void close();
    }

    private final Svc2625FrontendAdapter owner;
    private final VoiceState.SessionToken token;
    private final MicHandle mic;
    private final boolean borrowedFromNativeSvc;
    private volatile boolean closed;

    FrontendLease(Svc2625FrontendAdapter owner, VoiceState.SessionToken token,
                  MicHandle mic, boolean borrowedFromNativeSvc) {
        this.owner = owner;
        this.token = token;
        this.mic = mic;
        this.borrowedFromNativeSvc = borrowedFromNativeSvc;
    }

    public VoiceState.SessionToken token() {
        return token;
    }

    public boolean isBorrowedFromNativeSvc() {
        return borrowedFromNativeSvc;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean micAlive() {
        return !closed && mic != null && mic.isAlive();
    }

    /**
     * SVC 原生连接断开：只关闭租约自己拥有的 mic（借用的原生 mic 交还 SVC
     * 生命周期，不自作主张关闭）；租约随后失效，协调器可重新申请。
     */
    void onSvcDisconnected() {
        if (closed) {
            return;
        }
        if (!borrowedFromNativeSvc && mic != null) {
            try {
                mic.close();
            } catch (Exception ignored) {
            }
        }
        closed = true;
        owner.onLeaseClosed(this);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (mic != null) {
            try {
                mic.close();
            } catch (Exception ignored) {
            }
        }
        owner.onLeaseClosed(this);
    }
}
