package com.svcb.tsbridge.transport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.config.BridgeConfig;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 基于 TS3AudioBot JSON-RPC / WebSocket 的传输实现。<b>@Deprecated（v2.1）</b>。
 *
 * <p><b>诚实声明</b>：经查证 TS3AudioBot 官方 Web API（wiki/WebAPI + OpenAPI v3），
 * 其接口是命令导向的（播放 URL/文件），<b>不存在</b> PCM/Opus 音频注入端点，
 * 也不存在实时音频流订阅端点。因此本类假设的 {@code /pcm/send} 与
 * {@code /events/stream} 端点在官方版本上不存在，音频转发不可用。
 * 保留本类仅为了兼容运行了自定义/魔改 bot（提供等价端点）的部署，
 * 以及作为传输层接口的参考实现。生产部署请等待原生 TS3 语音协议实现完善。</p>
 *
 * <p>两条数据流（假设端点存在时）：</p>
 * <ul>
 *     <li><b>MC → TS（HTTP）</b>：{@link #sendMcAudio} 把 MC 玩家 Opus 帧 Base64 编码后
 *         POST 到 {@code <botApiUrl>/api/v3/bot/use/<botId>/pcm/send}，异步发送，
 *         失败仅记录日志。</li>
 *     <li><b>TS → MC（WebSocket）</b>：start 时异步连接
 *         {@code <botApiUrl 转 ws>/api/v3/bot/use/<botId>/events/stream}，
 *         发送 {@code {"action":"subscribe","event":"audio"}} 订阅音频事件；
 *         收到 {@code {"type":"audio",...}} 时解析为 {@link TsAudioFrame} 并回调 listener。</li>
 * </ul>
 *
 * <p>容错：</p>
 * <ul>
 *     <li>WebSocket 断线由 daemon 线程 5s 后重连；</li>
 *     <li>{@link #stop()} 设置 {@code stopped} 标志，阻止重连并幂等关闭；</li>
 *     <li>HTTP / WS 失败均不抛异常打断转发循环。</li>
 * </ul>
 */
@Deprecated
public class Ts3AudioBotTransport implements TsAudioTransport {

    private static final Gson GSON = new Gson();

    private final BridgeConfig config;
    private final String botId;
    private final HttpClient httpClient;
    private final AtomicLong sendCounter = new AtomicLong(0);
    /** 404 端点告警只打一次，避免刷屏。 */
    private final AtomicBoolean endpoint404Logged = new AtomicBoolean(false);

    private volatile WebSocketClient wsClient;
    private volatile boolean started = false;
    /** stop() 设置，用于阻止 onClose 触发的自动重连。 */
    private volatile boolean stopped = false;
    private volatile Consumer<TsAudioFrame> listener;

    public Ts3AudioBotTransport(BridgeConfig config) {
        this.config = config;
        this.botId = "0";
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void start() throws Exception {
        if (config.botApiUrl == null || config.botApiUrl.isBlank()) {
            throw new IllegalStateException("botApiUrl is not configured");
        }
        TsBridgeMod.LOGGER.warn("[tsbridge] Ts3AudioBotTransport is deprecated and NON-FUNCTIONAL against "
                + "official TS3AudioBot releases: the upstream web API has no PCM-injection or audio-stream "
                + "endpoints (this transport assumes custom endpoints). Audio will likely not work.");
        stopped = false;
        startWebSocket();
        started = true;
        TsBridgeMod.LOGGER.info("[tsbridge] Ts3AudioBotTransport started (botApiUrl='{}', botId='{}')",
                config.botApiUrl, botId);
    }

    @Override
    public void stop() {
        stopped = true;
        started = false;
        WebSocketClient ws = wsClient;
        if (ws != null) {
            try {
                ws.close();
            } catch (Exception e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Error closing TS3AudioBot WS: {}", e.getMessage());
            }
        }
        TsBridgeMod.LOGGER.info("[tsbridge] Ts3AudioBotTransport stopped");
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public void setOnTsAudioListener(Consumer<TsAudioFrame> listener) {
        this.listener = listener;
    }

    @Override
    public void sendMcAudio(UUID mcUuid, byte[] opusData, long sequence) {
        // 注：不检查 started（其反映 WS 连接状态），HTTP 推送在 WS 重连期间仍可独立工作。
        // 仅在 stop() 后跳过。
        if (stopped) {
            return;
        }
        if (mcUuid == null || opusData == null || opusData.length == 0) {
            return;
        }
        sendCounter.incrementAndGet();

        String opusB64 = Base64.getEncoder().encodeToString(opusData);
        JsonObject body = new JsonObject();
        body.addProperty("opusData", opusB64);
        body.addProperty("sequence", sequence);
        body.addProperty("sender", "mc-" + mcUuid);
        String bodyJson = GSON.toJson(body);
        String url = config.botApiUrl + "/api/v3/bot/use/" + botId + "/pcm/send";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        if (config.botApiToken != null && !config.botApiToken.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + config.botApiToken);
        }
        HttpRequest request = reqBuilder.build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, ex) -> {
                    if (ex != null) {
                        TsBridgeMod.LOGGER.warn("[tsbridge] TS3AudioBot PCM send failed (network): {}", ex.getMessage());
                        return;
                    }
                    int code = response.statusCode();
                    if (code == 200 || code == 204) {
                        if (TsBridgeMod.config != null && TsBridgeMod.config.debug) {
                            TsBridgeMod.LOGGER.debug("[tsbridge] TS3AudioBot PCM send ok (seq={})", sequence);
                        }
                    } else if (code == 404) {
                        if (endpoint404Logged.compareAndSet(false, true)) {
                            TsBridgeMod.LOGGER.warn("[tsbridge] TS3AudioBot endpoint not found (404 at {}), audio forwarding to TS may not work", url);
                        }
                    } else {
                        TsBridgeMod.LOGGER.warn("[tsbridge] TS3AudioBot PCM send returned HTTP {} (seq={})", code, sequence);
                    }
                });
    }

    /**
     * 创建并异步连接 WebSocket 订阅 TS3AudioBot 音频流。
     * 若 {@link #stopped} 为 true 则跳过（防止 stop 后的延迟重连）。
     */
    private void startWebSocket() {
        if (stopped) {
            return;
        }
        String wsUrl = config.botApiUrl
                .replaceFirst("^https://", "wss://")
                .replaceFirst("^http://", "ws://")
                + "/api/v3/bot/use/" + botId + "/events/stream";
        final String subscribeMsg = "{\"action\":\"subscribe\",\"event\":\"audio\"}";
        try {
            WebSocketClient client = new WebSocketClient(URI.create(wsUrl)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    TsBridgeMod.LOGGER.info("[tsbridge] TS3AudioBot WS connected: {}", wsUrl);
                    started = true;
                    try {
                        send(subscribeMsg);
                    } catch (Exception e) {
                        TsBridgeMod.LOGGER.warn("[tsbridge] Failed to send WS subscribe message: {}", e.getMessage());
                    }
                }

                @Override
                public void onMessage(String message) {
                    handleWsMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    TsBridgeMod.LOGGER.warn("[tsbridge] TS3AudioBot WS closed (code={}, reason='{}', remote={})",
                            code, reason, remote);
                    started = false;
                    if (!stopped) {
                        reconnectWs();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    TsBridgeMod.LOGGER.error("[tsbridge] TS3AudioBot WS error: {}", ex.getMessage(), ex);
                }
            };
            if (config.botApiToken != null && !config.botApiToken.isBlank()) {
                client.addHeader("Authorization", "Bearer " + config.botApiToken);
            }
            this.wsClient = client;
            client.connect();
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to start TS3AudioBot WS client (url={}): {}", wsUrl, e.getMessage(), e);
        }
    }

    /**
     * 解析 TS3AudioBot WS 推送的音频帧消息，回调 listener。
     * 期望格式：{@code {"type":"audio","tsUniqueId":"...","opusData":"<base64>","sequence":N,"timestampMs":N}}
     */
    private void handleWsMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Consumer<TsAudioFrame> cb = listener;
        if (cb == null) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            String type = obj.has("type") && !obj.get("type").isJsonNull()
                    ? obj.get("type").getAsString() : "";
            if (!"audio".equals(type)) {
                return;
            }
            String tsUniqueId = obj.has("tsUniqueId") && !obj.get("tsUniqueId").isJsonNull()
                    ? obj.get("tsUniqueId").getAsString() : "";
            String opusB64 = obj.has("opusData") && !obj.get("opusData").isJsonNull()
                    ? obj.get("opusData").getAsString() : "";
            long seq = obj.has("sequence") && !obj.get("sequence").isJsonNull()
                    ? obj.get("sequence").getAsLong() : 0L;
            long ts = obj.has("timestampMs") && !obj.get("timestampMs").isJsonNull()
                    ? obj.get("timestampMs").getAsLong() : System.currentTimeMillis();
            byte[] opusData;
            try {
                opusData = Base64.getDecoder().decode(opusB64);
            } catch (IllegalArgumentException e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Failed to decode opus base64 from TS frame: {}", e.getMessage());
                return;
            }
            TsAudioFrame frame = new TsAudioFrame(tsUniqueId, opusData, seq, ts);
            try {
                cb.accept(frame);
            } catch (Exception e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] TS audio listener threw: {}", e.getMessage(), e);
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to parse TS audio WS message: {}", e.getMessage());
        }
    }

    /**
     * daemon 线程，5s 后尝试重连 WebSocket。
     * 重连前再次检查 {@link #stopped}，避免 stop 期间的无谓重连。
     */
    private void reconnectWs() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopped) {
                return;
            }
            TsBridgeMod.LOGGER.info("[tsbridge] Reconnecting TS3AudioBot WS...");
            startWebSocket();
        }, "TsBridge-WsReconnect");
        t.setDaemon(true);
        t.start();
    }
}
