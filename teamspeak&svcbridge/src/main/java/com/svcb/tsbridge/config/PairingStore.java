package com.svcb.tsbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.svcb.tsbridge.TsBridgeMod;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 线程安全的 MC UUID ↔ TS uniqueIdentifier 双向映射。
 * 持久化到 &lt;游戏根目录&gt;/config/tsbridge/pairings.json。
 *
 * <p>序列化格式：{@code {"<mcUuid>": "<tsUniqueId>", ...}}，仅持久化正向映射，
 * 反向索引在加载时重建。</p>
 */
public class PairingStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SERIALIZED_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Path file;
    private final Map<UUID, String> mcToTs = new HashMap<>();
    private final Map<String, UUID> tsToMc = new HashMap<>();

    /** 单线程异步保存执行器，daemon 线程不阻塞 JVM 退出。 */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tsbridge-pairings-saver");
        t.setDaemon(true);
        return t;
    });

    private PairingStore(Path file) {
        this.file = file;
    }

    /**
     * 创建一个空 store（不读取磁盘），仅用作异常情况下的兜底。
     */
    public static PairingStore empty(Path file) {
        return new PairingStore(file);
    }

    /**
     * 从文件加载 store；文件不存在则创建空 store（并确保父目录存在）。
     * 解析失败则返回空 store（不抛异常）。
     */
    public static PairingStore load(Path file) {
        PairingStore store = new PairingStore(file);
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, String> data = GSON.fromJson(json, SERIALIZED_TYPE);
                if (data != null) {
                    for (Map.Entry<String, String> e : data.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(e.getKey());
                            String tsId = e.getValue();
                            if (tsId == null) {
                                continue;
                            }
                            store.mcToTs.put(uuid, tsId);
                            store.tsToMc.put(tsId, uuid);
                        } catch (IllegalArgumentException ex) {
                            TsBridgeMod.LOGGER.warn("[tsbridge] Skipping invalid pairing entry: {} -> {}", e.getKey(), e.getValue());
                        }
                    }
                }
                TsBridgeMod.LOGGER.info("[tsbridge] Loaded {} pairing(s) from {}", store.mcToTs.size(), file);
            } catch (IOException | JsonSyntaxException e) {
                TsBridgeMod.LOGGER.error("[tsbridge] Failed to load pairings at {}, starting with empty store: {}", file, e.getMessage());
            }
        } else {
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException e) {
                TsBridgeMod.LOGGER.error("[tsbridge] Failed to create pairings directory {}: {}", file.getParent(), e.getMessage());
            }
            TsBridgeMod.LOGGER.info("[tsbridge] Pairings file not found, starting with empty store at {}", file);
        }
        return store;
    }

    /**
     * 建立双向绑定，并异步保存。若任一端已存在旧绑定，先移除旧绑定再建立新绑定。
     */
    public synchronized void bind(UUID mcUuid, String tsUniqueId) {
        String existingTs = mcToTs.remove(mcUuid);
        if (existingTs != null) {
            tsToMc.remove(existingTs);
        }
        UUID existingMc = tsToMc.remove(tsUniqueId);
        if (existingMc != null) {
            mcToTs.remove(existingMc);
        }
        mcToTs.put(mcUuid, tsUniqueId);
        tsToMc.put(tsUniqueId, mcUuid);
        saveAsync();
    }

    /**
     * 移除指定 MC UUID 的绑定。返回是否实际移除了绑定。
     */
    public synchronized boolean unbind(UUID mcUuid) {
        String tsId = mcToTs.remove(mcUuid);
        if (tsId == null) {
            return false;
        }
        tsToMc.remove(tsId);
        saveAsync();
        return true;
    }

    /**
     * 返回 MC UUID 对应的 TS uniqueId，未绑定返回 null。
     */
    public synchronized String getTsIdentity(UUID mcUuid) {
        return mcToTs.get(mcUuid);
    }

    /**
     * 返回 TS uniqueId 对应的 MC UUID，未绑定返回 null。
     */
    public synchronized UUID getMcUuid(String tsUniqueId) {
        return tsToMc.get(tsUniqueId);
    }

    /**
     * 返回已绑定数量。
     */
    public synchronized int size() {
        return mcToTs.size();
    }

    /**
     * 持久化到文件（仅写正向映射 mcToTs）。UTF-8 + PrettyPrinting。
     */
    public synchronized void save() {
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> e : mcToTs.entrySet()) {
            data.put(e.getKey().toString(), e.getValue());
        }
        try {
            Files.createDirectories(file.getParent());
            String json = GSON.toJson(data);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to save pairings at {}: {}", file, e.getMessage());
        }
    }

    /**
     * 关闭异步保存执行器并执行最后一次同步保存。应在 ServerStopping 回调中调用。
     */
    public void shutdown() {
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Pairing save executor did not terminate cleanly, forcing");
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveExecutor.shutdownNow();
        }
        save();
    }

    private void saveAsync() {
        saveExecutor.submit(this::save);
    }
}
