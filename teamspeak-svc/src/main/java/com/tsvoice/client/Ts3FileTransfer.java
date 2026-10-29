package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TS3 文件传输上传（最小实现，参照 TSLib TsBaseFunctions.FileTransfer）：
 * ftinitupload（voice 命令通道）→ 应答含 ftkey + port → TCP 直连，
 * 先发 ASCII ftkey 再发裸数据。用于头像上传（cid=0 根目录）。
 *
 * <p>T05/R06 证据语义：EOF 只证明运输结束（{@link Result#TRANSPORT_FINISHED}），
 * 绝不当存储确认；远端读回验证（{@link Result#REMOTE_VERIFIED}）需要目标服务器
 * 实际支持的信息/哈希读回协议，未证实前不产出该状态；命令（client_flag_avatar）
 * 取得关联成功结果后由上层判定 AVATAR_APPLIED。登记按 来源 client+clientftfid
 * 隔离，ft 回复携带来源；会话关闭取消全部在途并拒绝迟到回复。</p>
 */
public final class Ts3FileTransfer {

    private static final Logger LOG = LoggerFactory.getLogger("teamspeak-svc-ft");

    public enum Result {
        /** 数据已写完且服务器干净关闭数据 Socket —— 仅运输结束证据。 */
        TRANSPORT_FINISHED,
        /** 预留：远端文件信息/哈希读回验证通过（目标协议证实前不产出）。 */
        REMOTE_VERIFIED,
        /** 失败：上传被拒或传输中失败。 */
        FAILED,
        /** 不确定：无 init 应答、权限拒绝或 I/O 超时；上层必须按未知处理。 */
        UNKNOWN
    }

    /** 登记键：来源 client 身份 + clientftfid（T05：跨会话/跨 client 不串线）。 */
    private static final class RequestKey {
        final Ts3VoiceClient client;
        final int clientftfid;

        RequestKey(Ts3VoiceClient client, int clientftfid) {
            this.client = client;
            this.clientftfid = clientftfid;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RequestKey other && other.clientftfid == clientftfid
                    && other.client == client;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(client) * 31 + clientftfid;
        }
    }

    /** 单个上传请求的关联状态：ftkey/port 由匹配 clientftfid 的应答填充。 */
    private static final class UploadRequest {
        final int clientftfid;
        final CompletableFuture<String> ftKeyFuture = new CompletableFuture<>();
        final CompletableFuture<Integer> portFuture = new CompletableFuture<>();

        UploadRequest(int clientftfid) {
            this.clientftfid = clientftfid;
        }

        boolean complete(String ftKey, int port) {
            boolean okKey = ftKeyFuture.complete(ftKey);
            boolean okPort = portFuture.complete(port);
            return okKey && okPort;
        }
    }

    private static final int FT_TIMEOUT_MS = 10_000;
    private static final AtomicInteger FT_ID = new AtomicInteger(1);
    /** (来源 client, clientftfid) → 在途请求（T05：来源隔离，迟到响应被忽略）。 */
    private static final ConcurrentHashMap<RequestKey, UploadRequest> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private Ts3FileTransfer() {
    }

    /** 处理一行含 ftkey= 的应答：按 (来源 client, clientftfid) 关联；无匹配则忽略。 */
    static void onFtReply(String row, Ts3VoiceClient source) {
        if (source == null) {
            return;
        }
        try {
            for (String part : row.split("\\|")) {
                if (!part.contains("ftkey=")) {
                    continue;
                }
                Map<String, String> params = Ts3VoiceClient.parseParams(part);
                String key = params.get("ftkey");
                String portStr = params.get("port");
                String idStr = params.get("clientftfid");
                if (key == null || portStr == null) {
                    continue;
                }
                UploadRequest request = null;
                if (idStr != null) {
                    try {
                        request = IN_FLIGHT.get(new RequestKey(source, Integer.parseInt(idStr)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (request != null) {
                    // 关联命中：只有发起方消费自己的应答。
                    request.complete(key, Integer.parseInt(portStr));
                    IN_FLIGHT.remove(new RequestKey(source, request.clientftfid), request);
                }
                // 无关联应答：不能确定归属，忽略（不喂给任何在途请求）。
            }
        } catch (Exception ignored) {
        }
    }

    /** 会话关闭（来源 client）：取消全部在途登记，等待方立即得到失败。 */
    static void cancelClient(Ts3VoiceClient source) {
        if (source == null) {
            return;
        }
        IN_FLIGHT.entrySet().removeIf(entry -> {
            if (entry.getKey().client == source) {
                entry.getValue().ftKeyFuture.completeExceptionally(
                        new java.util.concurrent.CancellationException("TS session closed"));
                entry.getValue().portFuture.completeExceptionally(
                        new java.util.concurrent.CancellationException("TS session closed"));
                return true;
            }
            return false;
        });
    }

    /**
     * 上传一个文件到 TS 服务器文件仓库根目录（cid=0）。
     *
     * @return detailed upload state; only transport-level completion is reported —
     *         the caller must confirm server-side acceptance separately
     */
    public static Result upload(Ts3VoiceClient client, String host, String fileName, byte[] data) {
        int ftId;
        RequestKey key;
        UploadRequest request;
        do {
            ftId = FT_ID.updateAndGet(v -> v >= 0xFFFF ? 2 : v + 1);
            key = new RequestKey(client, ftId);
            request = new UploadRequest(ftId);
        } while (IN_FLIGHT.putIfAbsent(key, request) != null);

        try {
            client.sendCommand("ftinitupload clientftfid=" + ftId + " name=\\/" + fileName
                    + " cid=0 cpw= size=" + data.length + " overwrite=1 resume=0");
            String ftKey;
            Integer port;
            try {
                ftKey = request.ftKeyFuture.get(FT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                port = request.portFuture.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                LOG.warn("[teamspeak-svc] ftinitupload no reply (permission i_ft_file_upload_power granted?)");
                return Result.UNKNOWN;
            }
            return transferData(host, ftKey, port, data);
        } catch (java.util.concurrent.CancellationException e) {
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            LOG.warn("[teamspeak-svc] File transfer init failed: {}", e.getMessage());
            return Result.FAILED;
        } finally {
            IN_FLIGHT.remove(key, request);
        }
    }

    private static Result transferData(String host, String ftKey, int port, byte[] data) {
        // TCP 数据阶段：ASCII ftkey + 裸数据
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), FT_TIMEOUT_MS);
            socket.setSoTimeout(FT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(ftKey.getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.flush();
            // T05/R06：clean EOF 只证明运输结束，绝不当存储成功确认。
            int ignored = socket.getInputStream().read(new byte[64]);
            return ignored < 0 ? Result.TRANSPORT_FINISHED : Result.UNKNOWN;
        } catch (SocketTimeoutException e) {
            return Result.UNKNOWN;
        } catch (Exception e) {
            LOG.warn("[teamspeak-svc] File transfer failed: {}", e.getMessage());
            return Result.FAILED;
        }
    }
}
