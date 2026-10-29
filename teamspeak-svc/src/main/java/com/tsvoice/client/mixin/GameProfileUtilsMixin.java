package com.tsvoice.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.tsvoice.client.GhostSkins;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.UUID;

/**
 * TS Ghost 皮肤注入：拦截 SVC 的 GameProfileUtils.getSkin(UUID)。
 * 命中本 mod 的 Ghost 集合时返回 跨服真实皮肤（可解析到 MC UUID 时）
 * 或内置 TS 品牌皮肤；未命中走原逻辑（require=0：SVC 缺失时整组跳过）。
 */
@Mixin(targets = "de.maxhenkel.voicechat.gui.GameProfileUtils", remap = false)
public abstract class GameProfileUtilsMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tsvoice$ghostSkin(UUID uuid, CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin skin = GhostSkins.skinFor(uuid);
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }
}
