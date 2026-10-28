package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TS3 文件传输上传（最小实现，参照 TSLib TsBaseFunctions.FileTransfer）：
 * ftinitupload（voice 命令通道）→ 应答含 ftkey + port → TCP 直连，
 * 先发 ASCII ftkey 再发裸数据。用于头像上传（cid=0 根目录）。
 *
 * <p>F10 修复：应答按 {@code clientftfid} 关联到发起请求的 Future —— 并发/
 * 迟到/错误响应不再串用单个 handler；EOF 仍只作为"运输结束"证据，只有
 * CONFIRMED 允许上层写成功 marker。请求在超时/错误/取消时释放注册。</p>
 *
 * <p>需要服务器授予 {@code i_ft_file_upload_power}；被拒时返回 FAILED（error 行）。</p>
 */
public final class Ts3FileTransfer {

    public enum Result {
        /** The bytes were written and the server closed the data socket cleanly. */
        CONFIRMED,
        /** No init reply, permission denied, or an inconclusive I/O timeout. */
        UNKNOWN,
        /** The upload was rejected or the transport failed while sending. */
        FAILED
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
    /** clientftfid → 在途请求（F10：会话内唯一 ID 关联，迟到响应被忽略）。 */
    private static final ConcurrentHashMap<Integer, UploadRequest> IN_FLIGHT = new ConcurrentHashMap<>();

    private Ts3FileTransfer() {
    }

    /** 处理一行含 ftkey= 的应答：按 clientftfid 关联；无匹配则忽略（迟到/串线）。 */
    static void onFtReply(String row) {
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
                        request = IN_FLIGHT.get(Integer.parseInt(idStr));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (request != null) {
                    // 关联命中：只有发起方消费自己的应答。
                    request.complete(key, Integer.parseInt(portStr));
                    IN_FLIGHT.remove(request.clientftfid, request);
                }
                // 无关联应答：不能确定归属，忽略（不喂给任何在途请求）。
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 上传一个文件到 TS 服务器文件仓库根目录（cid=0）。
     *
     * @return detailed upload state; only CONFIRMED may be treated as permanent success
     */
    public static Result upload(Ts3VoiceClient client, String host, String fileName, byte[] data) {
        int ftId;
        UploadRequest request;
        do {
            ftId = FT_ID.updateAndGet(v -> v >= 0xFFFF ? 2 : v + 1);
            request = new UploadRequest(ftId);
        } while (IN_FLIGHT.putIfAbsent(ftId, request) != null);

        try {
            client.sendCommand("ftinitupload clientftfid=" + ftId + " name=\\/" + fileName
                    + " cid=0 cpw= size=" + data.length + " overwrite=1 resume=0");
            String ftKey;
            Integer port;
            try {
                ftKey = request.ftKeyFuture.get(FT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                port = request.portFuture.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] ftinitupload no reply (permission i_ft_file_upload_power granted?)");
                return Result.UNKNOWN;
            }
            return transferData(host, ftKey, port, data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] File transfer init failed: {}", e.getMessage());
            return Result.FAILED;
        } finally {
            IN_FLIGHT.remove(ftId, request);
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
            // 服务器接收完毕后关闭连接；clean EOF is the best available confirmation
            // of transport completion — NOT of a valid, stored file (F10).
            int ignored = socket.getInputStream().read(new byte[64]);
            return ignored < 0 ? Result.CONFIRMED : Result.UNKNOWN;
        } catch (SocketTimeoutException e) {
            return Result.UNKNOWN;
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] File transfer failed: {}", e.getMessage());
            return Result.FAILED;
        }
    }
}
