package com.tsvoice.client;

import com.tsvoice.client.net.BeaconPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Explicit consent for a server-supplied TeamSpeak endpoint. A beacon is never
 * auto-connected and never receives credentials saved for another endpoint.
 */
public final class BeaconTrustScreen extends Screen {
    private final BeaconPayload payload;
    private final Screen parent = null;

    public BeaconTrustScreen(BeaconPayload payload) {
        super(Component.translatable("tsvoice.trust.title"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 34;
        y = label(cx, y, Component.translatable("tsvoice.trust.title"), 0xFFFFFF55);
        y += 4;
        y = label(cx, y, Component.translatable("tsvoice.trust.server")
                .append(Component.literal(" " + payload.host() + ":" + payload.port())), 0xFFE0E0E0);
        y = label(cx, y, Component.translatable("tsvoice.gui.channel")
                .append(Component.literal(" " + payload.channel())), 0xFFE0E0E0);
        y = label(cx, y, Component.translatable("tsvoice.trust.mode")
                .append(Component.literal(" " + payload.backendMode())), 0xFFE0E0E0);
        y = label(cx, y, Component.translatable("tsvoice.trust.warning"), 0xFFAAAAAA);
        y += 8;

        addRenderableWidget(Button.builder(Component.translatable("tsvoice.trust.once"), b -> {
            TsVoiceManager.authorizePendingBeacon(false, true);
            close();
        }).bounds(cx - 152, y, 148, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.trust.remember"), b -> {
            TsVoiceManager.authorizePendingBeacon(true, true);
            close();
        }).bounds(cx + 4, y, 148, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.trust.deny"), b -> {
            TsVoiceManager.authorizePendingBeacon(false, false);
            close();
        }).bounds(cx - 152, y + 24, 148, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.trust.disable"), b -> {
            ServerConfigStore.Entry entry = ServerConfigStore.get(currentAddress());
            entry.useServerBeacon = false;
            ServerConfigStore.save();
            TsVoiceManager.authorizePendingBeacon(false, false);
            close();
        }).bounds(cx + 4, y + 24, 148, 20).build());
    }

    private int label(int cx, int y, Component text, int color) {
        var styled = text.copy().withStyle(style -> style.withColor(color));
        addRenderableWidget(new StringWidget(cx - 160, y, 320, 10, styled, Minecraft.getInstance().font));
        return y + 13;
    }

    private void close() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private static String currentAddress() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "";
    }
}
