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

    /**
     * TS 书签（schema v3）：与 MC 服务器配置解绑的独立连接目标。
     * 凭据不保存在此 —— {@link EndpointCredentialStore} 按规范化 endpoint 隔离保存。
     * 手动书签优先于服务器公告（server-bridge 信标只能休眠"自动"路径，不能覆盖显式意图）。
     *
     * <p>v3 新增（T09/N4）：{@code nickname}（独立昵称，null=默认 "[MC] 玩家名"）、
     * {@code identityRef}（身份引用，保留扩展位，私钥绝不写入书签）、
     * {@code channelPolicy}（null/"follow"=服务器确认的频道可跟随；"locked"=锁定
     * 书签频道）。旧 v2 数据（缺字段）按默认值迁移，无需重写文件。</p>
     */
    public record Bookmark(String id, String label, String host, Integer port, String channel,
                           String transportPolicy, String credentialRef, boolean autoConnect,
                           String nickname, String identityRef, String channelPolicy) {
        /** v2 兼容构造器：旧调用方与旧 schema 字段按默认值补齐。 */
        public Bookmark(String id, String label, String host, Integer port, String channel,
                        String transportPolicy, String credentialRef, boolean autoConnect) {
            this(id, label, host, port, channel, transportPolicy, credentialRef, autoConnect,
                    null, null, null);
        }

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

        /** 独立昵称（未设置时返回 null，调用方用默认昵称）。 */
        public String effectiveNickname() {
            return nickname == null || nickname.isBlank() ? null : nickname.trim();
        }

        /** 频道策略：locked → LOCKED_TARGET；其余（null/follow/未知值）→ 默认跟随。 */
        public boolean lockedChannel() {
            return "locked".equalsIgnoreCase(channelPolicy);
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
                boolean legacySchema = false;
                for (Bookmark bookmark : parsed) {
                    try {
                        bookmarks.add(bookmark);
                        legacySchema |= bookmark.nickname() == null
                                && bookmark.identityRef() == null
                                && bookmark.channelPolicy() == null;
                    } catch (RuntimeException ignored) {
                        // 跳过损坏条目，其余书签仍可用
                    }
                }
                if (legacySchema) {
                    // v2 → v3 一次性迁移备份（保留旧文件原文；原子写且不覆盖已有备份）
                    Path backup = path.resolveSibling("bookmarks.v2-backup.json");
                    if (!Files.exists(backup)) {
                        try {
                            ConfigFileUtil.writePrivate(backup, Files.readString(path));
                            TeamspeakSvcClient.LOGGER.info(
                                    "[teamspeak-svc] Bookmarks migrated v2→v3 (backup: {})", backup.getFileName());
                        } catch (Exception e) {
                            TeamspeakSvcClient.LOGGER.warn(
                                    "[teamspeak-svc] v2 backup failed: {}", e.getMessage());
                        }
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
