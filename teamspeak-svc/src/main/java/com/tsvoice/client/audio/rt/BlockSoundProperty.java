//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.audio.rt;
import net.minecraft.world.level.block.SoundType;
import java.util.IdentityHashMap;
import static net.minecraft.world.level.block.SoundType.*;
public class BlockSoundProperty {
    public record AcousticProps(float absorption, float roughness, float hfGain, float occlusion) {}
    private static final AcousticProps DEFAULT = new AcousticProps(0.1f, 0.4f, 0.8f, 0.5f);
    private static final IdentityHashMap<SoundType, AcousticProps> PROPS = new IdentityHashMap<>();
    static {
        put(STONE, 0.05f, 0.3f, 0.9f, 1.0f);
        put(WOOD, 0.15f, 0.6f, 0.7f, 0.8f);
        put(GRAVEL, 0.4f, 0.9f, 0.5f, 0.5f);
        put(GRASS, 0.5f, 0.8f, 0.4f, 0.3f);
        put(LILY_PAD, 0.5f, 0.8f, 0.4f, 0f);
        put(METAL, 0.02f, 0.2f, 0.95f, 1.2f);
        put(GLASS, 0.02f, 0.1f, 0.95f, 0.1f);
        put(WOOL, 0.7f, 0.7f, 0.2f, 0.8f);
        put(SAND, 0.5f, 0.8f, 0.3f, 0.4f);
        put(SNOW, 0.6f, 0.7f, 0.2f, 0.1f);
        put(POWDER_SNOW, 0.6f, 0.7f, 0.2f, 0.1f);
        put(CROP, 0.3f, 0.5f, 0.6f, 0f);
        put(STEM, 0.15f, 0.6f, 0.7f, 0.5f);
        put(VINE, 0.4f, 0.6f, 0.5f, 0f);
        put(NETHER_WART, 0.3f, 0.5f, 0.6f, 0.2f);
        put(ANVIL, 0.02f, 0.3f, 0.95f, 1.2f);
        put(SLIME_BLOCK, 0.3f, 0.4f, 0.6f, 0.5f);
        put(HONEY_BLOCK, 0.4f, 0.3f, 0.5f, 0.5f);
        put(NETHERITE_BLOCK, 0.02f, 0.1f, 0.98f, 1.5f);
        put(NETHER_GOLD_ORE, 0.02f, 0.2f, 0.95f, 1.0f);
        put(SOUL_SAND, 0.6f, 0.8f, 0.2f, 0.6f);
        put(SOUL_SOIL, 0.5f, 0.7f, 0.3f, 0.5f);
        put(BASALT, 0.05f, 0.4f, 0.85f, 1.0f);
        put(POLISHED_DEEPSLATE, 0.03f, 0.2f, 0.9f, 1.2f);
        put(CALCITE, 0.04f, 0.3f, 0.9f, 1.0f);
        put(DRIPSTONE_BLOCK, 0.06f, 0.5f, 0.85f, 0.8f);
        put(ROOTED_DIRT, 0.4f, 0.7f, 0.4f, 0.5f);
        put(MUD, 0.6f, 0.6f, 0.2f, 0.7f);
        put(MUD_BRICKS, 0.05f, 0.3f, 0.9f, 1.0f);
        put(MANGROVE_ROOTS, 0.3f, 0.6f, 0.5f, 0.3f);
        put(MUDDY_MANGROVE_ROOTS, 0.4f, 0.6f, 0.4f, 0.4f);
        put(PACKED_MUD, 0.3f, 0.5f, 0.6f, 0.8f);
        put(DECORATED_POT, 0.05f, 0.3f, 0.9f, 0.8f);
        put(BAMBOO, 0.2f, 0.5f, 0.7f, 0.1f);
        put(BAMBOO_WOOD, 0.15f, 0.6f, 0.7f, 0.8f);
        put(CHERRY_WOOD, 0.15f, 0.6f, 0.7f, 0.8f);
        put(CHERRY_SAPLING, 0.2f, 0.5f, 0.7f, 0f);
        put(CHISELED_BOOKSHELF, 0.2f, 0.5f, 0.7f, 0.6f);
        put(SUSPICIOUS_SAND, 0.4f, 0.7f, 0.4f, 0.4f);
        put(SUSPICIOUS_GRAVEL, 0.4f, 0.8f, 0.4f, 0.5f);
        put(COPPER, 0.02f, 0.2f, 0.95f, 1.2f);
        put(TUFF, 0.05f, 0.4f, 0.85f, 1.0f);
        put(DEEPSLATE, 0.05f, 0.4f, 0.85f, 1.2f);
        put(NETHERRACK, 0.1f, 0.5f, 0.8f, 0.6f);
        put(NETHER_BRICKS, 0.05f, 0.3f, 0.9f, 1.0f);
        put(WART_BLOCK, 0.3f, 0.5f, 0.6f, 0.5f);
        put(SCULK, 0.5f, 0.6f, 0.3f, 0.6f);
        put(SCULK_SENSOR, 0.4f, 0.5f, 0.4f, 0.3f);
        put(AMETHYST, 0.04f, 0.2f, 0.9f, 0.8f);
        put(COPPER_BULB, 0.02f, 0.2f, 0.95f, 1.0f);
        put(COPPER_GRATE, 0.05f, 0.3f, 0.9f, 0.3f);
    }
    private static void put(SoundType type, float a, float r, float h, float o) {
        PROPS.put(type, new AcousticProps(a, r, h, o));
    }
    public static AcousticProps get(SoundType type) {
        return PROPS.getOrDefault(type, DEFAULT);
    }
}
