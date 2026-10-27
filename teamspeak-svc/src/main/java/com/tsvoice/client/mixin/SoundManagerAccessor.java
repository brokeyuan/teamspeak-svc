//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
    @Accessor("soundEngine")
    SoundEngine getSoundEngine();
}
