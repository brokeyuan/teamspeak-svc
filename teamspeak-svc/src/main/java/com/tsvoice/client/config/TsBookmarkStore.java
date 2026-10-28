package com.tsvoice.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tsvoice.client.ConfigFileUtil;
import com.tsvoice.client.TeamspeakSvcClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TS 书签（schema v2）：与 MC 服务器配置解绑的独立连接目标。
 * 凭据不保存在此 —— {@link EndpointCredentialStore} 按规范化 endpoint 隔离保存。
 * 手动书签优先于服务器公告（server-bridge 信标只能休眠"自动"路径，不能覆盖显式意图）。
 */
public final class TsBookmarkStore {

    /** transportPolicy: null / "udp" / "tcp"。credentialRef 由凭据存储解释。 */
    public record Bookmark(String id, String label, String host, Integer port, String channel,
                           String transportPolicy, String credentialRef, boolean autoConnect) {
        public Bookmark {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Bookmark id required");
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Bookmark host required");
            }
        }

        public int effectivePort() {
            return port != null ? port : 9987;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final CopyOnWriteArrayList<Bookmark> bookmarks = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded = false;

    private TsBookmarkStore() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc").resolve("bookmarks.json");
    }

    public static synchronized void load() {
        loaded = true;
        bookmarks.clear();
        try {
            Path path = file();
            if (!Files.exists(path)) {
                return;
            }
            List<Bookmark> parsed = GSON.fromJson(Files.readString(path),
                    new TypeToken<List<Bookmark>>() {
                    }.getType());
            if (parsed != null) {
                for (Bookmark bookmark : parsed) {
                    try {
                        bookmarks.add(bookmark);
                    } catch (RuntimeException ignored) {
                        // 跳过损坏条目，其余书签仍可用
                    }
                }
            }
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to load bookmarks: {}", e.getMessage());
            ConfigFileUtil.backupBroken(file());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            ConfigFileUtil.writePrivate(file(), GSON.toJson(new ArrayList<>(bookmarks)));
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to save bookmarks: {}", e.getMessage());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static List<Bookmark> list() {
        ensureLoaded();
        return List.copyOf(bookmarks);
    }

    public static Bookmark get(String id) {
        if (id == null) {
            return null;
        }
        ensureLoaded();
        for (Bookmark bookmark : bookmarks) {
            if (id.equals(bookmark.id())) {
                return bookmark;
            }
        }
        return null;
    }

    public static synchronized void put(Bookmark bookmark) {
        ensureLoaded();
        bookmarks.removeIf(existing -> existing.id().equals(bookmark.id()));
        bookmarks.add(bookmark);
        save();
    }

    public static synchronized boolean remove(String id) {
        ensureLoaded();
        boolean changed = bookmarks.removeIf(existing -> existing.id().equals(id));
        if (changed) {
            save();
        }
        return changed;
    }
}
