package com.tsvoice.server.relay;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OPEN 异步建立器（G4）：把 DNS/socket 建立移出 MC 主线程。
 *
 * <p>四段拆分：请求校验（runtime.onOpen，主线程）→ 预留额度（pending 占位，
 * 可超时撤销）→ 异步建立（本执行器，有界 worker/待办）→ 原连接完成（结果
 * 送回 fabric 线程发送 OPENED/ERROR）。</p>
 *
 * <p>上界（计划缺省建议）：worker 4、待办 64。DNS 超时不强制停止底层解析 ——
 * 迟到结果关闭资源并丢弃，不通过不断创建线程绕过阻塞。关闭/reload 清 pending
 * 与占用。</p>
 */
public final class OpenEstablisher implements AutoCloseable {

    /** 建立完成回调（含超时后的迟到结果处理）。 */
    public interface Attempt {
        /** 迟到建立成功（超过客户端等待已报 ERROR）后的资源回收。 */
        void discard(TsRelaySession session);

        /** 完成回调（在建立线程执行；调用方负责线程安全地发送应答帧）。 */
        void complete(String connectionId, String playerUuid,
                      com.tsvoice.protocol.relay.TsRelayWire.OpenFrame openFrame,
                      TsRelaySession session, String failure);
    }

    public static final int MAX_WORKERS = 4;
    public static final int MAX_PENDING = 64;

    private final ExecutorService workers;
    /** 待办额度（有界）：submit 时占位、attempt 结束归还。 */
    private final java.util.concurrent.Semaphore pendingSlots =
            new java.util.concurrent.Semaphore(MAX_PENDING);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Attempt attempt;
    /** 同一 connectionId 的在途建立（重复 OPEN 拒绝；断连撤销）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int pendingCount;

    public OpenEstablisher(Attempt attempt) {
        this.attempt = attempt;
        this.workers = Executors.newFixedThreadPool(MAX_WORKERS, r -> {
            Thread t = new Thread(r, "tsvoice-relay-open");
            t.setDaemon(true);
            return t;
        });
    }

    /** 待办占用数（诊断/测试）。 */
    public int pendingCount() {
        return pendingCount;
    }

    /** 该连接是否已有在途建立（重复 OPEN 拒绝依据）。 */
    public boolean hasInFlight(String connectionId) {
        return inFlight.containsKey(connectionId);
    }

    /**
     * 提交异步建立。待办满或同连接重复 OPEN 返回 false（不无限提交线程）。
     *
     * @return 是否已受理
     */
    public boolean submit(String connectionId, String playerUuid,
                          com.tsvoice.protocol.relay.TsRelayWire.OpenFrame openFrame,
                          java.util.concurrent.Callable<TsRelaySession> establish) {
        if (closed.get()) {
            return false;
        }
        if (inFlight.putIfAbsent(connectionId, Boolean.TRUE) != null) {
            return false; // 同连接重复 OPEN：拒绝
        }
        if (!pendingSlots.tryAcquire()) {
            inFlight.remove(connectionId);
            return false; // 待办满：明确反馈，不排队不无限提交
        }
        pendingCount++;
        try {
            workers.submit(() -> {
                try {
                    TsRelaySession session = null;
                    String failure = null;
                    try {
                        session = establish.call();
                    } catch (Exception e) {
                        failure = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    if (closed.get()) {
                        if (session != null) {
                            attempt.discard(session); // 迟到结果：关闭资源并丢弃
                        }
                    } else {
                        attempt.complete(connectionId, playerUuid, openFrame, session, failure);
                    }
                } finally {
                    pendingCount--;
                    pendingSlots.release();
                    inFlight.remove(connectionId);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pendingCount--;
            pendingSlots.release();
            inFlight.remove(connectionId);
            return false;
        }
    }

    /** 断连/关闭：撤销该连接的在途建立（迟到结果将 discard）。 */
    public void revoke(String connectionId) {
        inFlight.remove(connectionId);
    }

    @Override
    public void close() {
        closed.set(true);
        workers.shutdownNow();
        inFlight.clear();
    }
}
