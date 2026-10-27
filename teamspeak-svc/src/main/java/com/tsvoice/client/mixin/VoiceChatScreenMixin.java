// Ported button pattern from Simple Voice Chat's VoiceChatScreen (upstream is proprietary);
// injection logic and assets are our own.
package com.tsvoice.client.mixin;

import com.tsvoice.client.AudioExtraScreen;
import com.tsvoice.client.ConfigScreen;
import com.tsvoice.client.TsVoiceManager;
import de.maxhenkel.voicechat.gui.VoiceChatScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SVC 主界面（"语音通话"面板）注入两个按钮：
 * <ul>
 *     <li><b>Extra</b>（左）：音频增强配置（降噪/AEC/AGC/混响/Ducking）；</li>
 *     <li><b>TS</b>（右）：TeamSpeak 连接配置 + 连接状态查看。
 *         连接状态实时反映在图标上：已连接=正常墨色，不可用=图标变暗。</li>
 * </ul>
 * 按钮样式与 SVC 原生 ImageButton 一致（20x20 底 + 16x16 深色字形）。
 */
@Mixin(value = VoiceChatScreen.class, remap = false)
public abstract class VoiceChatScreenMixin {

    @Unique
    private static final Identifier TSVoice$TEX_TS =
            Identifier.fromNamespaceAndPath("teamspeak-svc", "icons/ts_button");
    @Unique
    private static final Identifier TSVoice$TEX_TS_DIM =
            Identifier.fromNamespaceAndPath("teamspeak-svc", "icons/ts_button_dim");
    @Unique
    private static final Identifier TSVoice$TEX_EXTRAS =
            Identifier.fromNamespaceAndPath("teamspeak-svc", "icons/audio_extras");

    @Unique
    private de.maxhenkel.voicechat.gui.widgets.ImageButton tsvoice$tsButton;
    @Unique
    private de.maxhenkel.voicechat.gui.widgets.ImageButton tsvoice$extraButton;
    @Unique
    private boolean tsvoice$lastDim = false;

    @Inject(method = "init", at = @At("TAIL"), remap = false, require = 0)
    private void tsvoice$addButtons(CallbackInfo ci) {
        VoiceChatScreen screen = (VoiceChatScreen) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        try {
            Class<?> base = screen.getClass().getSuperclass(); // VoiceChatScreenBase
            java.lang.reflect.Field leftF = base.getDeclaredField("guiLeft");
            java.lang.reflect.Field topF = base.getDeclaredField("guiTop");
            java.lang.reflect.Field ysizeF = base.getDeclaredField("ySize");
            leftF.setAccessible(true);
            topF.setAccessible(true);
            ysizeF.setAccessible(true);
            int guiLeft = leftF.getInt(screen);
            int guiTop = topF.getInt(screen);
            int ySize = ysizeF.getInt(screen);

            int rowY = guiTop + ySize - 6 - 20;
            // Extra 按钮（TS 左边）：音频增强配置
            tsvoice$extraButton = new de.maxhenkel.voicechat.gui.widgets.ImageButton(
                    guiLeft + 6 + 20 + 2 + 20 + 2 + 20 + 2, rowY,
                    TSVoice$TEX_EXTRAS,
                    b -> {
                        AudioExtraScreen extras = new AudioExtraScreen();
                        extras.setParent(screen);
                        mc.setScreenAndShow(extras);
                    });
            tsvoice$extraButton.setTooltip(Tooltip.create(
                    Component.translatable("tsvoice.button.extras")));
            // TS 按钮：连接配置
            tsvoice$tsButton = new de.maxhenkel.voicechat.gui.widgets.ImageButton(
                    guiLeft + 6 + 20 + 2 + 20 + 2 + 20 + 2 + 20 + 2, rowY,
                    TSVoice$TEX_TS,
                    b -> {
                        ConfigScreen config = new ConfigScreen();
                        config.setParent(screen);
                        mc.setScreenAndShow(config);
                    });
            tsvoice$tsButton.setTooltip(Tooltip.create(
                    Component.translatable("tsvoice.button.config")));
            // 通过 Screen.addRenderableWidget（protected）反射注册
            var addW = Screen.class.getDeclaredMethod("addRenderableWidget",
                    net.minecraft.client.gui.components.events.GuiEventListener.class);
            addW.setAccessible(true);
            addW.invoke(screen, tsvoice$extraButton);
            addW.invoke(screen, tsvoice$tsButton);
        } catch (Throwable e) {
            com.tsvoice.client.TeamspeakSvcClient.LOGGER.warn(
                    "[teamspeak-svc] Could not add TS buttons to voice chat screen: {}", e.toString());
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false, require = 0)
    private void tsvoice$updateButtonState(CallbackInfo ci) {
        if (tsvoice$tsButton == null) {
            return;
        }
        boolean dim = !TsVoiceManager.isSessionConnected();
        if (dim != tsvoice$lastDim) {
            tsvoice$lastDim = dim;
            try {
                // ImageButton.texture 是 protected —— 反射切换正常/暗态图标
                var texF = de.maxhenkel.voicechat.gui.widgets.ImageButton.class.getDeclaredField("texture");
                texF.setAccessible(true);
                texF.set(tsvoice$tsButton, dim ? TSVoice$TEX_TS_DIM : TSVoice$TEX_TS);
                tsvoice$tsButton.setTooltip(Tooltip.create(Component.translatable(dim
                        ? "tsvoice.button.config_dim" : "tsvoice.button.config")));
            } catch (Throwable e) {
                com.tsvoice.client.TeamspeakSvcClient.LOGGER.debug(
                        "[teamspeak-svc] button state update failed: {}", e.toString());
            }
        }
    }
}
