package com.tsvoice.client;

import com.tsvoice.client.config.TsBookmarkStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * TS 书签管理页（T09/N4）：列表 + 每条 连接/编辑/删除，底部 新建/完成。
 * 所有动作经生产入口（{@link TsVoiceManager#manualConnect(String)}、
 * {@link TsBookmarkStore}、{@link TsVoiceManager#onBookmarkDeleted(String)}），
 * 本屏不持有任何连接逻辑。删除"正在使用"的书签由管理器进入 PAUSED（不静默换目标）。
 *
 * <p>布局/可点击性等真实渲染验收按用户要求暂缓（E07）；本屏与 GuiFlowTest 共享
 * 同一动作入口，控制流由该回归覆盖。</p>
 */
public class TsBookmarksScreen extends Screen {

    private static final int ROW_HEIGHT = 26;
    private static final int MAX_ROWS = 6;

    private Screen parent;
    /** 滚动偏移（条目数）。 */
    private int scroll;

    public TsBookmarksScreen() {
        super(Component.translatable("tsvoice.gui.bookmarks.title"));
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 30;
        addRenderableWidget(makeLabel(cx - 100, 16, 200, this.title, 0xFFFFFF));
        List<TsBookmarkStore.Bookmark> bookmarks = TsBookmarkStore.list();
        int rows = Math.min(MAX_ROWS, bookmarks.size());
        for (int i = 0; i < rows; i++) {
            TsBookmarkStore.Bookmark bookmark = bookmarks.get(scroll + i);
            addRenderableWidget(makeLabel(cx - 200, y + 4, 170,
                    Component.literal(bookmark.label() + " (" + bookmark.host() + ":"
                            + bookmark.effectivePort() + ")"
                            + (bookmark.lockedChannel() ? " 🔒" : "")), 0xFFE0E0E0));
            addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.bookmarks.connect"), b ->
                    TsVoiceManager.manualConnect(bookmark.id())
            ).bounds(cx - 24, y, 52, 18).build());
            addRenderableWidget(Button.builder(Component.literal("✎"), b -> {
                TsBookmarkEditScreen edit = new TsBookmarkEditScreen(bookmark.id());
                edit.setParent(this);
                Minecraft.getInstance().setScreenAndShow(edit);
            }).bounds(cx + 32, y, 20, 18).build());
            addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                TsBookmarkStore.remove(bookmark.id());
                TsVoiceManager.onBookmarkDeleted(bookmark.id());
                Minecraft.getInstance().setScreenAndShow(new TsBookmarksScreen());
            }).bounds(cx + 56, y, 20, 18).build());
            y += ROW_HEIGHT;
        }
        if (bookmarks.isEmpty()) {
            addRenderableWidget(makeLabel(cx - 100, y + 8, 200,
                    Component.translatable("tsvoice.gui.bookmarks.empty"), 0xFFAAAAAA));
            y += 20;
        }
        y = Math.max(y + 8, this.height - 84);
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.bookmarks.new"), b -> {
            TsBookmarkEditScreen edit = new TsBookmarkEditScreen(null);
            edit.setParent(this);
            Minecraft.getInstance().setScreenAndShow(edit);
        }).bounds(cx - 100, y, 200, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.done"), b -> onClose())
                .bounds(cx - 100, y, 200, 20).build());
    }

    private net.minecraft.client.gui.components.StringWidget makeLabel(int x, int y, int w, Component text, int color) {
        var styled = text.copy().withStyle(st -> st.withColor(color));
        return new net.minecraft.client.gui.components.StringWidget(x, y, w, 10, styled, Minecraft.getInstance().font);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int total = TsBookmarkStore.list().size();
        int maxScroll = Math.max(0, total - MAX_ROWS);
        int next = scroll - (int) Math.signum(scrollY);
        if (next >= 0 && next <= maxScroll && next != scroll) {
            scroll = next;
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void rebuild() {
        Minecraft.getInstance().setScreenAndShow(new TsBookmarksScreen());
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
