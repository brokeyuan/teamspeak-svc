package com.tsvoice.client.ts3voice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TS 命令关联与结果路由（P3）：以 return_code（TS 协议标准参数）把应答/错误
 * 绑定到请求；服务器不支持 return_code 时不静默丢错 —— 回落"单会话串行队列"
 * （同时只有一个在途命令）。错误响应携带 return_code 时精确关联；无关联的
 * 错误作为会话级错误上报，绝不冒充其他请求的结果。
 */
public final class TsCommandTracker {

    /** 单个命令的完成来源：成功应答或错误 id!=0。 */
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

        Pending(int returnCode, long deadlineNanos) {
            this.returnCode = returnCode;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger returnCodes = new AtomicInteger(1);
    private final Ts3VoiceClient client;

    public TsCommandTracker(Ts3VoiceClient client) {
        this.client = client;
    }

    /** 下一个 return_code（会话内唯一）。 */
    public int nextReturnCode() {
        int value = returnCodes.getAndIncrement();
        return value > 0xFFFF ? returnCodes.updateAndGet(v -> v > 0xFFFF ? 1 : v + 1) : value;
    }

    /**
     * 执行命令并等待关联结果。return_code 附着在命令上；应答（error id=0/其他）
     * 经 {@link #onError(String)} 回来时按 return_code 路由。
     */
    public TsCommandResult execute(String command, long timeoutMillis) throws Exception {
        int code = nextReturnCode();
        Pending entry = new Pending(code, System.nanoTime() + timeoutMillis * 1_000_000L);
        pending.put(code, entry);
        try {
            client.sendCommand(command + " return_code=" + code);
        } catch (Exception e) {
            pending.remove(code);
            throw e;
        }
        while (true) {
            TsCommandResult result = entry.result.get();
            if (result != null) {
                return result;
            }
            if (System.nanoTime() > entry.deadlineNanos) {
                pending.remove(code);
                throw new TimeoutException("TS command timed out after " + timeoutMillis + "ms (return_code=" + code + ")");
            }
            Thread.sleep(10);
        }
    }

    /** 会话结束：清空全部在途（等待方将因 deadline 超时返回，不悬挂）。 */
    public void cancelSession() {
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
