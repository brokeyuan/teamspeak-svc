package com.tsvoice.client;

import com.tsvoice.client.ts3voice.Ts3VoiceClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TS3 文件传输上传（最小实现，参照 TSLib TsBaseFunctions.FileTransfer）：
 * ftinitupload（voice 命令通道）→ 应答含 ftkey + port → TCP 直连，
 * 先发 ASCII ftkey 再发裸数据。用于头像上传（cid=0 根目录）。
 *
 * <p>需要服务器授予 {@code i_ft_file_upload_power}；被拒时返回 false（上层提示一次）。</p>
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

    private static final int FT_TIMEOUT_MS = 10_000;
    private static final java.util.concurrent.atomic.AtomicInteger FT_ID =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private Ts3FileTransfer() {
    }

    /**
     * 上传一个文件到 TS 服务器文件仓库根目录（cid=0）。
     *
     * @return detailed upload state; only CONFIRMED may be treated as permanent success
     */
    public static Result upload(Ts3VoiceClient client, String host, String fileName, byte[] data) {
        CountDownLatch reply = new CountDownLatch(1);
        final String[] ftKey = {null};
        final int[] port = {0};
        client.setFtReplyHandler(cmd -> {
            try {
                for (String row : cmd.split("\\|")) {
                    if (!row.contains("ftkey=")) {
                        continue;
                    }
                    Map<String, String> params = com.tsvoice.client.ts3voice.Ts3VoiceClient
                            .parseParams(row);
                    String key = params.get("ftkey");
                    String portStr = params.get("port");
                    if (key != null && portStr != null) {
                        ftKey[0] = key;
                        port[0] = Integer.parseInt(portStr);
                        reply.countDown();
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        });
        try {
            int ftId = FT_ID.updateAndGet(v -> v >= Integer.MAX_VALUE ? 2 : v + 1);
            client.sendCommand("ftinitupload clientftfid=" + ftId + " name=\\/" + fileName
                    + " cid=0 cpw= size=" + data.length + " overwrite=1 resume=0");
            if (!reply.await(FT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || ftKey[0] == null) {
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] ftinitupload no reply (permission i_ft_file_upload_power granted?)");
                return Result.UNKNOWN;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] File transfer init failed: {}", e.getMessage());
            return Result.FAILED;
        } finally {
            client.setFtReplyHandler(null);
        }

        // TCP 数据阶段：ASCII ftkey + 裸数据
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port[0]), FT_TIMEOUT_MS);
            socket.setSoTimeout(FT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(ftKey[0].getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.flush();
            // 服务器接收完毕后关闭连接；clean EOF is the best available confirmation.
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
