package com.tsvoice.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Extra 音频增强面板（降噪/AGC/AEC/混响/Ducking/Ducking 强度）。
 * 从 TS 配置界面的"音频增强"按钮进入；所有开关即点即存即热切换。
 */
public class AudioExtraScreen extends Screen {

    private Button denoiseBtn;
    private Button duckLevelBtn;
    private Screen parent;

    public AudioExtraScreen() {
        super(Component.translatable("tsvoice.gui.audio_title"));
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    private static boolean cfgBool(String field, boolean def) {
        try {
            Object v = ModConfig.class.getField(field).get(TeamspeakSvcClient.config());
            return v instanceof Boolean b ? b : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static void flipGlobal(String field) {
        try {
            java.lang.reflect.Field f = ModConfig.class.getField(field);
            f.set(TeamspeakSvcClient.config(), !(Boolean) f.get(TeamspeakSvcClient.config()));
            TeamspeakSvcClient.config().save();
            com.tsvoice.client.audio.TsAudio.refresh();
            com.tsvoice.client.audio.AudioPipeline.reconfigure();
            TsVoiceManager.onManualConfigChanged();
        } catch (Exception ignored) {
        }
    }

    private Component toggleLabel(String key, String field) {
        return Component.literal(Component.translatable(key).getString() + ": "
                + (cfgBool(field, false) ? "ON" : "OFF"));
    }

    private Button toggleBtn(String key, String field, int cx, int y) {
        Button[] ref = new Button[1];
        ref[0] = Button.builder(toggleLabel(key, field), b -> {
            flipGlobal(field);
            ref[0].setMessage(toggleLabel(key, field));
        }).bounds(cx - 100, y, 200, 20).build();
        return ref[0];
    }

    private static final com.tsvoice.client.audio.TsAudio.NoiseCancelMode[] DENOISE_ORDER = {
            com.tsvoice.client.audio.TsAudio.NoiseCancelMode.OFF,
            com.tsvoice.client.audio.TsAudio.NoiseCancelMode.WEBRTC_LOW,
            com.tsvoice.client.audio.TsAudio.NoiseCancelMode.WEBRTC_MEDIUM,
            com.tsvoice.client.audio.TsAudio.NoiseCancelMode.WEBRTC_HIGH,
            com.tsvoice.client.audio.TsAudio.NoiseCancelMode.WEBRTC_AGGRESSIVE,
            com.tsvoice.client.audio.TsAudio.NoiseCancelMode.NVIDIA_AI,
    };

    private static com.tsvoice.client.audio.TsAudio.NoiseCancelMode nextDenoise(
            com.tsvoice.client.audio.TsAudio.NoiseCancelMode current) {
        for (int i = 0; i < DENOISE_ORDER.length; i++) {
            if (DENOISE_ORDER[i] == current) {
                return DENOISE_ORDER[(i + 1) % DENOISE_ORDER.length];
            }
        }
        return com.tsvoice.client.audio.TsAudio.NoiseCancelMode.WEBRTC_MEDIUM;
    }

    private Component denoiseLabel() {
        String mode = TeamspeakSvcClient.config() != null ? TeamspeakSvcClient.config().denoiseMode : "OFF";
        String suffix = "NVIDIA_AI".equalsIgnoreCase(mode)
                ? (com.tsvoice.client.audio.AudioPipeline.isNvidiaAvailable() ? " (RTX)" : " (need Broadcast)")
                : "";
        return Component.literal(Component.translatable("tsvoice.gui.denoise").getString() + ": " + mode + suffix);
    }

    private static final float[] DUCK_LEVELS = {0.30f, 0.45f, 0.60f};

    private Component duckLevelLabel() {
        float lv = TeamspeakSvcClient.config() != null ? TeamspeakSvcClient.config().duckingLevel : 0.35f;
        return Component.literal(Component.translatable("tsvoice.gui.duck_level").getString() + ": "
                + Math.round(lv * 100) + "%");
    }

    private void cycleDuckLevel() {
        float cur = TeamspeakSvcClient.config() != null ? TeamspeakSvcClient.config().duckingLevel : 0.35f;
        float next = DUCK_LEVELS[0];
        for (int i = 0; i < DUCK_LEVELS.length; i++) {
            if (Math.abs(DUCK_LEVELS[i] - cur) < 0.01f) {
                next = DUCK_LEVELS[(i + 1) % DUCK_LEVELS.length];
                break;
            }
        }
        TeamspeakSvcClient.config().duckingLevel = next;
        TeamspeakSvcClient.config().save();
        com.tsvoice.client.audio.TsAudio.refresh();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 42;

        denoiseBtn = Button.builder(denoiseLabel(), b -> {
            var next = nextDenoise(com.tsvoice.client.audio.TsAudio.parseMode(
                    TeamspeakSvcClient.config().denoiseMode));
            TeamspeakSvcClient.config().denoiseMode = next.name();
            saveAndApply();
            denoiseBtn.setMessage(denoiseLabel());
        }).bounds(cx - 100, y, 200, 20).build();
        addRenderableWidget(denoiseBtn);
        y += 26;

        addRenderableWidget(toggleBtn("tsvoice.gui.echo", "echoCancelEnabled", cx, y));
        y += 26;
        addRenderableWidget(toggleBtn("tsvoice.gui.agc", "agcEnabled", cx, y));
        y += 26;
        addRenderableWidget(toggleBtn("tsvoice.gui.reverb", "reverbEnabled", cx, y));
        y += 26;
        addRenderableWidget(toggleBtn("tsvoice.gui.ducking", "duckingEnabled", cx, y));
        y += 26;
        duckLevelBtn = Button.builder(duckLevelLabel(), b -> {
            cycleDuckLevel();
            duckLevelBtn.setMessage(duckLevelLabel());
        }).bounds(cx - 100, y, 200, 20).build();
        addRenderableWidget(duckLevelBtn);
        y += 32;

        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.done"), b -> onClose())
                .bounds(cx - 100, y, 200, 20).build());
    }

    private void saveAndApply() {
        TeamspeakSvcClient.config().save();
        com.tsvoice.client.audio.TsAudio.refresh();
        com.tsvoice.client.audio.AudioPipeline.reconfigure();
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
