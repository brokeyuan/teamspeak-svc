package com.tsvoice.client;

import com.tsvoice.client.config.EndpointCredentialStore;
import com.tsvoice.client.config.TsBookmarkStore;

import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * 书签编辑页（T09/N4）：新建/编辑一条书签。凭据（服务器密码/频道密码）只写入
 * {@link EndpointCredentialStore}（按 endpoint 隔离），身份私钥绝不入书签。
 * 保存经 {@link TsBookmarkStore#put}（原子持久化）；删除走
 * {@link TsVoiceManager#onBookmarkDeleted} 的保护路径。
 */
public class TsBookmarkEditScreen extends Screen {

    private final String bookmarkId; // null = 新建
    private Screen parent;

    private EditBox label;
    private EditBox host;
    private EditBox port;
    private EditBox channel;
    private EditBox nickname;
    private EditBox serverPassword;
    private EditBox channelPassword;
    private Button transportBtn;
    private Button policyBtn;
    private Button autoConnectBtn;

    private String transport;      // null / "udp" / "tcp"
    private boolean lockedPolicy;
    private boolean autoConnect;

    public TsBookmarkEditScreen(String bookmarkId) {
        super(Component.translatable(bookmarkId == null
                ? "tsvoice.gui.bookmarks.new" : "tsvoice.gui.bookmarks.edit"));
        this.bookmarkId = bookmarkId;
        TsBookmarkStore.Bookmark existing = bookmarkId == null ? null : TsBookmarkStore.get(bookmarkId);
        if (existing != null) {
            this.transport = existing.transportPolicy();
            this.lockedPolicy = existing.lockedChannel();
            this.autoConnect = existing.autoConnect();
        }
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    @Override
    protected void init() {
        Font font = Minecraft.getInstance().font;
        int cx = this.width / 2;
        int lh = 11;
        TsBookmarkStore.Bookmark existing = bookmarkId == null ? null : TsBookmarkStore.get(bookmarkId);
        // S0.5/R24：按书签自身的 ref 读取凭据（旧书签无 ref 时回落 endpoint），
        // 同 host:port 的两个书签不再共用同一条目。
        var credential = existing == null ? null
                : EndpointCredentialStore.getByRef(existing.credentialRef(), existing.host(), existing.port());
        int y = 28;

        label = new EditBox(font, cx - 100, y + lh, 200, 18, Component.translatable("tsvoice.gui.bookmarks.label"));
        label.setMaxLength(40);
        label.setValue(existing != null && existing.label() != null ? existing.label() : "");
        addRenderableWidget(makeLabel(cx - 100, y, 200, Component.translatable("tsvoice.gui.bookmarks.label"), 0xFFC8C8C8));
        y += lh + 20;

        host = new EditBox(font, cx - 100, y + lh, 130, 18, Component.translatable("tsvoice.gui.host"));
        host.setMaxLength(120);
        host.setValue(existing != null && existing.host() != null ? existing.host() : "");
        addRenderableWidget(makeLabel(cx - 100, y, 130, Component.translatable("tsvoice.gui.host"), 0xFFC8C8C8));
        port = new EditBox(font, cx + 34, y + lh, 66, 18, Component.translatable("tsvoice.gui.port"));
        port.setMaxLength(5);
        port.setValue(existing != null && existing.port() != null ? String.valueOf(existing.port()) : "9987");
        addRenderableWidget(makeLabel(cx + 34, y, 66, Component.translatable("tsvoice.gui.port"), 0xFFC8C8C8));
        y += lh + 20;

        channel = new EditBox(font, cx - 100, y + lh, 200, 18, Component.translatable("tsvoice.gui.channel"));
        channel.setMaxLength(40);
        channel.setValue(existing != null && existing.channel() != null ? existing.channel() : "Minecraft");
        addRenderableWidget(makeLabel(cx - 100, y, 200, Component.translatable("tsvoice.gui.channel"), 0xFFC8C8C8));
        y += lh + 20;

        nickname = new EditBox(font, cx - 100, y + lh, 200, 18, Component.translatable("tsvoice.gui.bookmarks.nickname"));
        nickname.setMaxLength(40);
        nickname.setValue(existing != null && existing.nickname() != null ? existing.nickname() : "");
        addRenderableWidget(makeLabel(cx - 100, y, 200, Component.translatable("tsvoice.gui.bookmarks.nickname"), 0xFFC8C8C8));
        y += lh + 20;

        serverPassword = new EditBox(font, cx - 100, y + lh, 98, 18, Component.translatable("tsvoice.gui.password"));
        serverPassword.setMaxLength(60);
        serverPassword.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        serverPassword.setValue(credential != null && credential.serverPassword() != null
                ? credential.serverPassword() : "");
        addRenderableWidget(makeLabel(cx - 100, y, 98, Component.translatable("tsvoice.gui.password"), 0xFFC8C8C8));
        channelPassword = new EditBox(font, cx + 2, y + lh, 98, 18,
                Component.translatable("tsvoice.gui.channel_password"));
        channelPassword.setMaxLength(60);
        channelPassword.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        channelPassword.setValue(credential != null && credential.channelPassword() != null
                ? credential.channelPassword() : "");
        addRenderableWidget(makeLabel(cx + 2, y, 98, Component.translatable("tsvoice.gui.channel_password"), 0xFFC8C8C8));
        y += lh + 22;

        transportBtn = Button.builder(transportLabel(), b -> {
            transport = transport == null ? "udp" : ("udp".equalsIgnoreCase(transport) ? "tcp" : null);
            transportBtn.setMessage(transportLabel());
        }).bounds(cx - 100, y, 98, 20).build();
        addRenderableWidget(transportBtn);
        policyBtn = Button.builder(policyLabel(), b -> {
            lockedPolicy = !lockedPolicy;
            policyBtn.setMessage(policyLabel());
        }).bounds(cx + 2, y, 98, 20).build();
        addRenderableWidget(policyBtn);
        y += 24;

        autoConnectBtn = Button.builder(autoConnectLabel(), b -> {
            autoConnect = !autoConnect;
            autoConnectBtn.setMessage(autoConnectLabel());
        }).bounds(cx - 100, y, 200, 20).build();
        addRenderableWidget(autoConnectBtn);
        y += 24;

        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.bookmarks.save"), b -> {
            save();
            onClose();
        }).bounds(cx - 100, y, 200, 20).build());
        y += 24;
        if (bookmarkId != null) {
            addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.bookmarks.delete"), b -> {
                TsBookmarkStore.remove(bookmarkId);
                TsVoiceManager.onBookmarkDeleted(bookmarkId);
                onClose();
            }).bounds(cx - 100, y, 200, 20).build());
        }

        addRenderableWidget(label);
        addRenderableWidget(host);
        addRenderableWidget(port);
        addRenderableWidget(channel);
        addRenderableWidget(nickname);
        addRenderableWidget(serverPassword);
        addRenderableWidget(channelPassword);
    }

    private void save() {
        String id = bookmarkId != null ? bookmarkId
                : "bm-" + Integer.toHexString((label.getValue() + host.getValue()).hashCode())
                + "-" + Long.toHexString(System.currentTimeMillis());
        String hostValue = host.getValue().trim();
        if (hostValue.isEmpty()) {
            return; // 主机必填（记录校验也会拒绝）
        }
        int portValue;
        try {
            portValue = Integer.parseInt(port.getValue().trim());
        } catch (NumberFormatException e) {
            portValue = 9987;
        }
        // S0.5/R24：credentialRef/identityRef —— 新书签分配独立 ref；编辑保留
        // 既有引用（绝不写 null 清空）。带 ref 的书签凭据存独立条目，同 host:port
        // 的两个书签互不串用；ref 为空的旧书签继续按 endpoint 读写（兼容）。
        TsBookmarkStore.Bookmark existingBookmark = bookmarkId == null ? null : TsBookmarkStore.get(bookmarkId);
        String credentialRef = existingBookmark != null ? existingBookmark.credentialRef() : null;
        String identityRef = existingBookmark != null ? existingBookmark.identityRef() : null;
        if (credentialRef == null || credentialRef.isBlank()) {
            credentialRef = "cred-" + id;
        }
        if (identityRef == null || identityRef.isBlank()) {
            // 首次为该书签建立身份引用：复制全局 identity.txt 为独立身份（不移动、
            // 不替换全局身份；其余书签/配置不受影响）。
            identityRef = copyGlobalIdentityFor(id);
        }
        TsBookmarkStore.Bookmark bookmark = new TsBookmarkStore.Bookmark(
                id,
                label.getValue().isBlank() ? hostValue : label.getValue().trim(),
                hostValue,
                portValue,
                channel.getValue().trim(),
                transport,
                credentialRef,
                autoConnect,
                nickname.getValue().isBlank() ? null : nickname.getValue().trim(),
                identityRef,
                lockedPolicy ? "locked" : null);
        TsBookmarkStore.put(bookmark);
        // 凭据按 ref 隔离存储；旧 endpoint 兼容条目仅在 ref 首次启用时复制，
        // 绝不删除既有 endpoint 条目（未迁移路径继续可用）。
        boolean refFresh = existingBookmark == null
                || credentialRef.equals("cred-" + id);
        if (refFresh) {
            EndpointCredentialStore.copyEndpointToRef(hostValue, portValue, credentialRef);
        }
        EndpointCredentialStore.setByRef(credentialRef,
                serverPassword.getValue(), channelPassword.getValue());
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Bookmark '{}' saved ({}:{}, policy={}, credRef={})",
                bookmark.label(), hostValue, portValue, lockedPolicy ? "locked" : "follow", credentialRef);
    }

    /**
     * 把全局 identity.txt 复制为书签独立身份 {@code identities/<key>.txt}（一次性）。
     * 全局身份不存在或已复制过时返回 null/既有 ref —— 全局身份绝不被改动，
     * 不能因迁移给所有用户自动换 TS 身份。
     */
    private String copyGlobalIdentityFor(String bookmarkId) {
        try {
            net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            java.nio.file.Path global = loader.getConfigDir()
                    .resolve("teamspeak-svc").resolve("identity.txt");
            if (!java.nio.file.Files.exists(global)) {
                return null; // 尚无全局身份：书签暂不带引用，连接时按需生成
            }
            String ref = "ident-" + bookmarkId;
            java.nio.file.Path target = EndpointCredentialStore.identityRefPath(ref);
            if (target == null) {
                return null;
            }
            if (java.nio.file.Files.exists(target)) {
                return ref; // 已复制过：幂等
            }
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.copy(global, target);
            // 身份文件含私钥：与 ConfigFileUtil 的私有权限约定一致
            try {
                java.nio.file.Files.setPosixFilePermissions(target,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows/非 POSIX 文件系统：目录级保护已由 config 目录承担
            }
            TeamspeakSvcClient.LOGGER.info(
                    "[teamspeak-svc] Bookmark '{}' identity reference created from global identity (copy; global untouched)",
                    bookmarkId);
            return ref;
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn(
                    "[teamspeak-svc] identity reference creation failed: {} (bookmark keeps global identity path)",
                    e.getMessage());
            return null;
        }
    }

    private net.minecraft.client.gui.components.StringWidget makeLabel(int x, int y, int w, Component text, int color) {
        var styled = text.copy().withStyle(st -> st.withColor(color));
        return new net.minecraft.client.gui.components.StringWidget(x, y, w, 10, styled, Minecraft.getInstance().font);
    }

    private Component transportLabel() {
        String name = transport == null ? Component.translatable("tsvoice.gui.auto").getString()
                : transport.toUpperCase();
        return Component.literal(Component.translatable("tsvoice.gui.transport").getString() + ": " + name);
    }

    private Component policyLabel() {
        return Component.literal(Component.translatable("tsvoice.gui.bookmarks.policy").getString() + ": "
                + Component.translatable(lockedPolicy
                ? "tsvoice.gui.bookmarks.policy_locked" : "tsvoice.gui.bookmarks.policy_follow").getString());
    }

    private Component autoConnectLabel() {
        return Component.literal(Component.translatable("tsvoice.gui.bookmarks.auto").getString() + ": "
                + (autoConnect ? "ON" : "OFF"));
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreenAndShow(parent);
        } else {
            super.onClose();
        }
    }
}
