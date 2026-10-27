package com.tsvoice.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * B 方向：把 MC 皮肤脸作为 TS 头像上传。
 *
 * <p>流程：Mojang session API 按 UUID 取皮肤贴图 URL（正版账号；离线玩家跳过）
 * → 下载皮肤 PNG → 裁 8×8 脸（含帽子层）放大为 64×64 头像 PNG
 * → TS 文件传输上传（{@code /avatar_*.png}）→ {@code client_flag_avatar} 生效。</p>
 *
 * <p>每个身份只上传一次（成功后落 marker 文件）；TS 服务器需要
 * {@code i_ft_file_upload_power} 权限，缺失时聊天栏提示一次。</p>
 */
public final class AvatarPipeline {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AvatarPipeline() {
    }

    /** 连接成功后异步执行（voice 线程调用，立即返回）。 */
    public static void uploadAvatarAsync(Ts3VoiceClient client, UUID mcUuid,
                                         String fallbackNamespace, String fileTransferHost) {
        Thread t = new Thread(() -> {
            try {
                String namespace = waitForVirtualServerUid(client);
                if (namespace == null) {
                    namespace = fallbackNamespace == null || fallbackNamespace.isBlank()
                            ? "unknown" : fallbackNamespace;
                }
                Path marker = markerFile(namespace, mcUuid);
                if (Files.exists(marker)) {
                    return; // 该 TS namespace + MC 身份已上传过
                }
                byte[] skin = downloadSkin(mcUuid);
                if (skin == null) {
                    TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] No premium skin for {} — avatar skipped", mcUuid);
                    return;
                }
                byte[] face = renderFace(skin);
                String name = "avatar_tsvoice_" + Long.toHexString(mcUuid.getLeastSignificantBits() & 0xFFFFFFFFL)
                        + ".png";
                Ts3FileTransfer.Result result = Ts3FileTransfer.upload(client, fileTransferHost, name, face);
                if (result == Ts3FileTransfer.Result.CONFIRMED) {
                    client.sendCommand("clientupdate client_flag_avatar=" + name);
                    writeMarker(marker, name);
                    TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS avatar uploaded ({}, {} bytes)", name, face.length);
                } else {
                    TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] TS avatar upload not confirmed: {} ({})",
                            result, name);
                }
            } catch (Exception e) {
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Avatar upload failed: {}", e.getMessage());
            }
        }, "teamspeak-svc-avatar");
        t.setDaemon(true);
        t.start();
    }

    private static String waitForVirtualServerUid(Ts3VoiceClient client) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            String uid = client.getVirtualServerUid();
            if (uid != null && !uid.isBlank()) {
                return uid;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static void writeMarker(Path marker, String name) throws IOException {
        Files.createDirectories(marker.getParent());
        Path tmp = Files.createTempFile(marker.getParent(), marker.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, name, StandardCharsets.UTF_8);
            Files.move(tmp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- 皮肤获取（Mojang session API，正版账号） ----

    private static byte[] downloadSkin(UUID uuid) throws Exception {
        String body = get("https://sessionserver.mojang.com/session/minecraft/profile/"
                + uuid + "?unsigned=false");
        if (body == null) {
            return null;
        }
        String texturesB64 = extractValue(body, "\"value\" : \"", "\"value\":\"");
        if (texturesB64 == null) {
            return null;
        }
        String decoded = new String(Base64.getDecoder().decode(texturesB64), StandardCharsets.UTF_8);
        String url = extractValue(decoded, "\"url\" : \"", "\"url\":\"");
        if (url == null) {
            return null;
        }
        HttpResponse<byte[]> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    /** 从 JSON 里取 value（宽容两种空格形态；无需完整 JSON 解析）。 */
    private static String extractValue(String json, String patternA, String patternB) {
        for (String pattern : new String[]{patternA, patternB}) {
            int i = json.indexOf(pattern);
            if (i >= 0) {
                int start = i + pattern.length();
                int end = json.indexOf('"', start);
                if (end > start) {
                    return json.substring(start, end);
                }
            }
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "teamspeak-svc").GET().build(), HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    // ---- 脸部裁剪（8×8 脸 + 帽子层 → 64×64） ----

    static byte[] renderFace(byte[] skinPng) throws IOException {
        try (NativeImage skin = NativeImage.read(skinPng)) {
            if (skin.getWidth() < 64 || skin.getHeight() < 64) {
                throw new IOException("unsupported skin size " + skin.getWidth() + "x" + skin.getHeight());
            }
            int scale = 8; // 8×8 → 64×64
            try (NativeImage out = new NativeImage(64, 64, true)) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int face = skin.getPixel(8 + x, 8 + y);
                        int hat = skin.getPixel(40 + x, 8 + y);
                        int color = (hat >>> 24) != 0 ? hat : face; // 帽子层不透明时覆盖
                        for (int dy = 0; dy < scale; dy++) {
                            for (int dx = 0; dx < scale; dx++) {
                                out.setPixelABGR(x * scale + dx, y * scale + dy, color);
                            }
                        }
                    }
                }
                Path tmp = Files.createTempFile("tsvoice_face", ".png");
                try {
                    out.writeToFile(tmp);
                    return Files.readAllBytes(tmp);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        }
    }

    private static Path markerFile(String tsNamespace, UUID mcUuid) {
        String namespaceId;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(tsNamespace.getBytes(StandardCharsets.UTF_8));
            namespaceId = java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            namespaceId = "unknown";
        }
        return FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc")
                .resolve("avatar_" + namespaceId + "_" + mcUuid + ".done");
    }
}
