//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.audio.rt;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
public record HitPoint(int round, Vec3 pos, double journey, double distance,
                       float absorption, float roughness, float hfGain, Direction face) {
    public double weight() {
        return (1 - absorption) / (journey * journey + 0.01);
    }
}
