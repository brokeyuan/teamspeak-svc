package com.tsvoice.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * TS Ghost 皮肤注册表（A/C 两方向共用的渲染数据源）。
 *
 * <ul>
 *     <li><b>C 指定皮肤</b>：本 mod 内置 TS 品牌皮肤（资源
 *         {@code assets/teamspeak-svc/skin/ts_ghost.png}）注册为动态纹理，
 *         所有无跨服身份的 TS Ghost 默认使用；</li>
 *     <li><b>A 跨服真实皮肤</b>：能从 TS client_description 解析到对方真实
 *         MC UUID 时，经原版 {@code SkinManager.createLookup(GameProfile, false)}
 *         拉取+缓存其正版皮肤（官方管线，含异步加载与缓存）。</li>
 * </ul>
 *
 * <p>mixin（GameProfileUtilsMixin）按 UUID 查询本表；未注册的 UUID 走原逻辑。</p>
 */
public final class GhostSkins {

    private static final Identifier BUILTIN_TEXTURE_ID =
            Identifier.fromNamespaceAndPath("teamspeak-svc", "ts_ghost_skin");

    private static volatile PlayerSkin builtinSkin;
    /** ghostUuid → 皮肤供应器（跨服真实皮肤；原生 Supplier 由 SkinManager 创建）。 */
    private static final Map<UUID, Supplier<PlayerSkin>> crossServer = new ConcurrentHashMap<>();

    private GhostSkins() {
    }

    /**
     * 注册跨服玩家真实皮肤（键=该玩家的真实 MC UUID，同时也在 activeGhosts 中）。
     * 同服玩家与纯 TS 用户不走此方法（原生皮肤 / 内置 TS 皮）。
     */
    public static void register(UUID ghostUuid, UUID mcUuid, String fallbackName) {
        if (mcUuid == null) {
            return; // 仅内置皮肤：mixin 的 activeGhosts 兜底处理
        }
        Minecraft mc = Minecraft.getInstance();
        // SkinManager/TextureManager 只能在客户端主线程使用（调用方是 TS 网络线程）
        mc.execute(() -> {
            if (!ClientStateReflector.activeGhosts().containsKey(ghostUuid)) {
                return;
            }
            try {
                GameProfile profile = new GameProfile(mcUuid,
                        fallbackName != null ? fallbackName : mcUuid.toString());
                Supplier<PlayerSkin> lookup = mc.getSkinManager().createLookup(profile, false);
                crossServer.put(ghostUuid, lookup);
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] Cross-server skin registered for ghost {} (mc={})",
                        ghostUuid, mcUuid);
            } catch (Throwable t) {
                TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] Skin lookup create failed: {}", t.toString());
            }
        });
    }

    public static void unregister(UUID ghostUuid) {
        crossServer.remove(ghostUuid);
    }

    /** mixin 查询入口：非本 mod Ghost 或尚未就绪返回 null（走原逻辑/内置）。 */
    public static PlayerSkin skinFor(UUID uuid) {
        if (!ClientStateReflector.activeGhosts().containsKey(uuid)) {
            return null;
        }
        Supplier<PlayerSkin> lookup = crossServer.get(uuid);
        if (lookup != null) {
            try {
                PlayerSkin skin = lookup.get();
                if (skin != null && !isDefault(skin)) {
                    return skin;
                }
            } catch (Throwable ignored) {
            }
        }
        return builtin();
    }

    private static boolean isDefault(PlayerSkin skin) {
        // 皮肤未加载完成时 createLookup 返回默认皮肤：此时先用内置 TS 皮，
        // 后台加载完成后下次查询返回真实皮肤（与 DefaultPlayerSkin 全等比较判定）
        try {
            PlayerSkin def = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin();
            return skin == null || skin.body() == null || skin.body().equals(def.body());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 内置 TS 品牌皮肤（懒加载，注册一次动态纹理）。 */
    static synchronized PlayerSkin builtin() {
        if (builtinSkin != null) {
            return builtinSkin;
        }
        try {
            InputStream in = GhostSkins.class.getResourceAsStream(
                    "/assets/teamspeak-svc/skin/ts_ghost.png");
            if (in == null) {
                return null;
            }
            NativeImage image;
            try (in) {
                image = NativeImage.read(in);
            }
            TextureManager textures = Minecraft.getInstance().getTextureManager();
            DynamicTexture texture = new DynamicTexture(() -> "tsvoice_ghost_skin", image);
            textures.register(BUILTIN_TEXTURE_ID, texture);
            texture.upload();

            ClientAsset.Texture asset = new ClientAsset.Texture() {
                @Override
                public Identifier texturePath() {
                    return BUILTIN_TEXTURE_ID;
                }

                @Override
                public Identifier id() {
                    return BUILTIN_TEXTURE_ID;
                }
            };
            builtinSkin = PlayerSkin.insecure(asset, null, null, PlayerModelType.WIDE);
            TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Built-in TS ghost skin registered");
            return builtinSkin;
        } catch (Throwable t) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] Failed to load built-in TS skin: {}", t.toString());
            return null;
        }
    }
}
