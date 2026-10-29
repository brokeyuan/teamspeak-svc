package com.tsvoice.client.ts3voice;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TS 命令关联与结果路由（P3/T05）：以 return_code（TS 协议标准参数）把应答/错误
 * 绑定到请求；每个 {@link Ts3VoiceClient} 持有一个实例。错误响应携带 return_code
 * 时精确关联；无关联的错误作为会话级错误上报，绝不冒充其他请求的结果。
 *
 * <p>异步入口 {@link #executeAsync(String, long)}：不阻塞任何调用线程（MC/UI/协议
 * 读取线程禁止同步等待）；超时由共享看护器释放登记并以超时异常完成；会话关闭
 * （{@link #cancelSession()}）立即以取消完成全部在途 Future。在途的 return_code
 * 不复用。</p>
 */
public final class TsCommandTracker {

    /** 单个命令的完成来源：成功应答（error id=0）或错误（id!=0，msg 附带）。 */
    public static final class TsCommandResult {
        final Integer errorCode;
        final String errorMessage;

        TsCommandResult(Integer errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public boolean succeeded() {
            return errorCode == null;
        }

        public Integer errorCode() {
            return errorCode;
        }

        public String errorMessage() {
            return errorMessage;
        }
    }

    private static final class Pending {
        final int returnCode;
        final long deadlineNanos;
        final AtomicReference<TsCommandResult> result = new AtomicReference<>(null);
        final CompletableFuture<TsCommandResult> future = new CompletableFuture<>();

        Pending(int returnCode, long deadlineNanos) {
            this.returnCode = returnCode;
            this.deadlineNanos = deadlineNanos;
        }
    }

    /** 超时看护：共享单线程守护调度器，只做登记释放与 Future 完成。 */
    private static final ScheduledExecutorService TIMEOUT_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "teamspeak-svc-command-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger returnCodes = new AtomicInteger(1);
    private final Ts3VoiceClient client;

    public TsCommandTracker(Ts3VoiceClient client) {
        this.client = client;
    }

    /** 下一个 return_code（会话内唯一；跳过仍在途的值，不复用）。 */
    int nextReturnCode() {
        int value = returnCodes.getAndIncrement();
        while (value > 0xFFFF || pending.containsKey(value)) {
            value = returnCodes.getAndIncrement();
        }
        return value;
    }

    /**
     * 异步执行命令（T05）：附着 return_code 入队，应答/错误经 {@link #onError(String)}
     * 完成 Future；超时由看护器释放登记并异常完成；{@link #cancelSession()} 立即取消。
     */
    public CompletableFuture<TsCommandResult> executeAsync(String command, long timeoutMillis) {
        int code = nextReturnCode();
        Pending entry = new Pending(code, System.nanoTime() + timeoutMillis * 1_000_000L);
        pending.put(code, entry);
        try {
            client.sendCommand(command + " return_code=" + code);
        } catch (Exception e) {
            pending.remove(code);
            entry.future.completeExceptionally(e);
            return entry.future;
        }
        TIMEOUT_WATCHDOG.schedule(() -> {
            if (pending.remove(code, entry)) {
                entry.future.completeExceptionally(new TimeoutException(
                        "TS command timed out after " + timeoutMillis + "ms (return_code=" + code + ")"));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        return entry.future;
    }

    /**
     * 阻塞执行（兼容旧调用/测试专用）：生产路径（MC/UI/协议读取线程）禁止使用，
     * 必须走 {@link #executeAsync(String, long)}。
     */
    public TsCommandResult execute(String command, long timeoutMillis) throws Exception {
        try {
            return executeAsync(command, timeoutMillis).get(timeoutMillis + 1_000L, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** 会话结束：立即以取消完成全部在途 Future（不悬挂等待方）。 */
    public void cancelSession() {
        pending.values().forEach(p ->
                p.future.completeExceptionally(new java.util.concurrent.CancellationException("TS session closed")));
        pending.clear();
    }

    // ---- 接线（Ts3VoiceClient 在收到 error 行时调用） ----

    /** 处理一行 error 应答（id=0 表示成功）；按 return_code 关联或按会话级处理。 */
    public void onError(String errorRow) {
        Map<String, String> params = Ts3VoiceClient.parseParams(errorRow);
        String idStr = params.get("id");
        if (idStr == null) {
            return;
        }
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return;
        }
        int code = -1;
        String rc = params.get("return_code");
        if (rc != null) {
            try {
                code = Integer.parseInt(rc);
            } catch (NumberFormatException ignored) {
            }
        }
        TsCommandResult result = new TsCommandResult(id == 0 ? null : id,
                id == 0 ? null : params.get("msg"));
        if (code > 0) {
            Pending entry = pending.remove(code);
            if (entry != null) {
                entry.result.set(result);
                entry.future.complete(result);
                return;
            }
        }
        // 无关联错误：仅会话级日志（client.handleError 已有告警路径），不冒充请求结果。
    }

    /** 在途数（测试/诊断）。 */
    public int inFlight() {
        return pending.size();
    }
}
