package com.tsvoice.client;

import com.tsvoice.client.connection.CapabilityDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 公告首次授权界面（S0.4/R23）：服务器经能力公告推荐的新 TS endpoint 必须由
 * 玩家在此显式接受/拒绝 —— 新服务器仅发 capabilities（无旧 beacon）也有正常
 * 授权入口。授权绑定当前 context、实际接收快照与能力种类：
 * <ul>
 *   <li>接受 → {@link TsVoiceManager#acceptAnnouncedCandidates}（可选记住 TS
 *       endpoint，与信标记住同一信任存储）；</li>
 *   <li>拒绝 → {@link TsVoiceManager#rejectAnnouncedCandidates}（本 context
 *       内不再打扰）；</li>
 *   <li>过期/换服 → 授权动作被状态机拒绝，界面显示真实状态，不制造假成功。</li>
 * </ul>
 *
 * <p>自动连接是否启用（autoConnect/信标）与信任是否存在是两个决定：本屏只
 * 处理信任；不开启任何自动连接。同地址的其他能力种类不因本屏批准而自动授权。</p>
 */
public final class CapabilityConsentScreen extends Screen {

    private final Screen parent = null;

    public CapabilityConsentScreen() {
        super(Component.translatable("tsvoice.consent.title"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 34;
        y = label(cx, y, Component.translatable("tsvoice.consent.title"), 0xFFFFFF55);
        y += 4;

        var pending = TsVoiceManager.pendingConsentCandidates();
        if (pending.isEmpty()) {
            y = label(cx, y, Component.translatable("tsvoice.consent.none"), 0xFFAAAAAA);
            addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.done"),
                    b -> close()).bounds(cx - 100, y + 8, 200, 20).build());
            return;
        }
        for (CapabilityDiscovery.AnnouncedCandidate candidate : pending) {
            String kind = switch (candidate.candidate().kind()) {
                case TS_ENDPOINT -> "TS";
                case TUNNEL -> "TUNNEL";
                case SVC_MC_CHANNEL -> "SVC";
            };
            y = label(cx, y, Component.literal("[" + kind + "] "
                            + candidate.candidate().host() + ":" + candidate.candidate().port()),
                    0xFFE0E0E0);
        }
        y = label(cx, y, Component.translatable("tsvoice.consent.warning"), 0xFFAAAAAA);
        y += 8;

        addRenderableWidget(Button.builder(Component.translatable("tsvoice.consent.accept"),
                b -> {
                    TsVoiceManager.acceptAnnouncedCandidates(false);
                    close();
                }).bounds(cx - 152, y, 148, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.consent.remember"),
                b -> {
                    TsVoiceManager.acceptAnnouncedCandidates(true);
                    close();
                }).bounds(cx + 4, y, 148, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.trust.deny"),
                b -> {
                    TsVoiceManager.rejectAnnouncedCandidates();
                    close();
                }).bounds(cx - 100, y + 24, 200, 20).build());
    }

    private int label(int cx, int y, net.minecraft.network.chat.Component text, int color) {
        var styled = text.copy().withStyle(style -> style.withColor(color));
        addRenderableWidget(new StringWidget(cx - 160, y, 320, 10, styled, Minecraft.getInstance().font));
        return y + 13;
    }

    private void close() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
