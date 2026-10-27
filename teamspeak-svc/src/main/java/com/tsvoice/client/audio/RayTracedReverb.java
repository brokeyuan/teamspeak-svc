//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.audio;
import com.tsvoice.client.audio.rt.BlockSoundProperty;
import com.tsvoice.client.audio.rt.HitPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.openal.EXTEfx.*;

/**
 * 光线追踪混响（接收侧环境空间感）。
 *
 * <p>线程模型：世界查询与参数聚合全部在客户端主线程、按 tick 预算分批完成
 * （{@link #tick()}）；OpenAL 对象的创建与参数写入只发生在 AL 上下文线程
 * （{@link #getAuxSlot()}，由 AlSpeakerMixin 调用）。两者通过 volatile
 * 参数与 {@code pendingApply} 标志解耦，不存在跨线程访问 ClientLevel。</p>
 */
public class RayTracedReverb {
    /** 主线程单批采样（两半球各 300 条）。 */
    private static final int RAYS_PER_HEMISPHERE = 300;
    private static final int RAYS_PER_TICK = 40;
    private static final int MAX_DIST = 64;
    private static final int MAX_BOUNCE = 5;

    private static final List<HitPoint> HIT_POINTS = new ArrayList<>();
    /** 渲染线程读取，主线程追加 → COW。 */
    public static final List<Ray> debugRays = new CopyOnWriteArrayList<>();

    /** AL 线程创建/应用；-1 = 未创建，0 = 创建失败（不重试）。 */
    private static int auxSlot = -1;
    private static int reverbEffect = -1;
    private static volatile boolean pendingApply = false;
    /** 请求在下次 AL 线程调用时删除 OpenAL 对象（删除必须有上下文）。 */
    private static volatile boolean deleteRequested = false;

    // 增量追踪状态（仅客户端主线程访问）
    private static boolean tracing = false;
    private static Vec3 batchEar = Vec3.ZERO;
    private static int nextRay = 0;
    private static List<Vector3d> batchRays = List.of();
    private static long lastUpdateMs = 0;

    public static volatile float density = 0.5f;
    public static volatile float diffusion = 0.5f;
    public static volatile float hfGain = 0.9f;
    public static volatile float rt60 = 1.5f;
    public static volatile float earlyRefGain = 0.05f;
    public static volatile float earlyRefDelay = 0.007f;
    public static volatile float lateRefGain = 0.5f;
    public static volatile float lateRefDelay = 0.01f;
    public static volatile float echoTime = 0.25f;
    public static volatile float echoDepth = 0.05f;
    private static volatile boolean inWater = false;
    private static volatile boolean inLava = false;

    /** 客户端主线程每 tick 调用。 */
    public static void tick() {
        if (!TsAudio.reverbEnabled) {
            tracing = false;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            tracing = false;
            return;
        }
        BlockPos eyePos = BlockPos.containing(mc.player.getEyePosition());
        FluidState fluid = mc.level.getBlockState(eyePos).getFluidState();
        inWater = !fluid.isEmpty() && fluid.getType().isSame(Fluids.WATER);
        inLava = !fluid.isEmpty() && fluid.getType().isSame(Fluids.LAVA);

        long now = System.currentTimeMillis();
        int interval = Math.max(5, Math.min(3000, TsAudio.reverbIntervalMs));
        if (!tracing) {
            if (now - lastUpdateMs < interval) {
                return;
            }
            startBatch(mc);
        }
        int budget = Math.min(RAYS_PER_TICK, batchRays.size() - nextRay);
        for (int i = 0; i < budget; i++) {
            traceRay(mc, batchEar, batchRays.get(nextRay++), 0x80ffffff);
        }
        if (nextRay >= batchRays.size()) {
            finishBatch();
            tracing = false;
            lastUpdateMs = System.currentTimeMillis();
        }
    }

    private static void startBatch(Minecraft mc) {
        HIT_POINTS.clear();
        debugRays.clear();
        batchEar = mc.player.getEyePosition();
        List<Vector3d> rays = new ArrayList<>(RAYS_PER_HEMISPHERE * 2);
        rays.addAll(generateFibonacciRays(RAYS_PER_HEMISPHERE, 0));
        rays.addAll(generateFibonacciRays(RAYS_PER_HEMISPHERE, 0.5));
        batchRays = rays;
        nextRay = 0;
        tracing = true;
    }

    /** 清空 CPU 侧状态；OpenAL 对象在下次 AL 线程调用时删除。 */
    public static void disable() {
        tracing = false;
        HIT_POINTS.clear();
        debugRays.clear();
        pendingApply = false;
        deleteRequested = true;
    }

    public static synchronized void cleanup() {
        disable();
    }

    public static boolean isUnderwater() { return inWater || inLava; }
    public static boolean isInWater() { return inWater; }
    public static boolean isInLava() { return inLava; }

    /** 批次完成后的纯数学聚合（主线程）。 */
    private static void finishBatch() {
        if (HIT_POINTS.isEmpty()) {
            setDryEnvironment();
            return;
        }
        int n = HIT_POINTS.size();
        double journeySum = 0, distanceSum = 0, weightSum = 0;
        double roughnessW = 0, absorptionW = 0, hfW = 0;
        double earlyGS = 0, earlyDS = 0, earlyWS = 0;
        double lateGS = 0, lateDS = 0, lateWS = 0;
        float soundSpeed = inWater ? 1500f : 340f;
        for (HitPoint hp : HIT_POINTS) {
            double w = hp.weight();
            journeySum += hp.journey(); distanceSum += hp.distance();
            weightSum += w;
            roughnessW += hp.roughness() * w;
            absorptionW += hp.absorption() * w;
            hfW += hp.hfGain() * w;
            if (hp.journey() <= 17) {
                earlyGS += (1 - hp.absorption()) * w;
                earlyDS += hp.journey() * w; earlyWS += w;
            }
            if (hp.journey() > 17 && hp.journey() <= 34) {
                lateGS += (1 - hp.absorption()) * w;
                lateDS += hp.journey() * w; lateWS += w;
            }
        }
        if (weightSum == 0) return;
        double meanJourney = journeySum / n, meanDist = distanceSum / n;
        double meanAbs = absorptionW / weightSum, meanRough = roughnessW / weightSum;
        double hfAvg = hfW / weightSum;
        double sqDiff = 0;
        for (HitPoint hp : HIT_POINTS) sqDiff += (hp.journey() - meanJourney) * (hp.journey() - meanJourney);
        density = (float) Math.min(1, Math.sqrt(sqDiff / n) / (MAX_DIST / 2f) + (inWater ? 0.6f : 0));
        diffusion = (float) Math.min(1, meanRough);
        double airHf = Math.pow(0.99, meanJourney);
        hfGain = (float) Math.min(2, Math.max(0, Math.sqrt(airHf * hfAvg) * (inLava ? 0.35f : (inWater ? 0.15f : 1))));
        double collisionCount = -6 / Math.log10(1 - meanAbs + 1e-10);
        double openSpace = (inWater || inLava) ? 1
                : Math.min(1, (double) n / (RAYS_PER_HEMISPHERE * MAX_BOUNCE * 2));
        double r = collisionCount * meanDist / 340 * openSpace;
        rt60 = (float) Math.min(8, Math.max(0.1, (20 - 400 / (r + 20)) * (inLava ? 3.6f : (inWater ? 6.0f : 1))));
        if (earlyWS > 0) {
            // 湿声整体压低（约 -12dB）：保留空间感，避免人声被混响糊住
            earlyRefGain = (float) Math.min(1.5f, Math.max(0, 1.0 * Math.sqrt(earlyGS / n) * openSpace * ((inWater || inLava) ? 1.5f : 1)));
            earlyRefDelay = (float) (earlyDS / earlyWS / soundSpeed);
        }
        if (lateWS > 0) {
            lateRefGain = (float) Math.min(2.5f, Math.max(0, 5 * Math.sqrt(lateGS / n) * openSpace));
            lateRefDelay = (float) (lateDS / lateWS / soundSpeed) - earlyRefDelay;
        }
        echoTime = (float) Math.min(0.25, Math.max(0.075, 4 * meanDist / 340));
        echoDepth = Math.min(1, (float) (1.0 / (1.0 + meanRough)));
        pendingApply = true;
    }

    private static void setDryEnvironment() {
        density = 0f; diffusion = 0.5f; hfGain = 0.3f; rt60 = 0.1f;
        earlyRefGain = 0f; earlyRefDelay = 0.007f;
        lateRefGain = 0f; lateRefDelay = 0.01f;
        echoTime = 0.075f; echoDepth = 0f;
        pendingApply = true;
    }

    /**
     * 获取混响 aux slot（AL 上下文线程调用；懒创建并应用待生效参数）。
     * 返回 0 表示 EFX 不可用（AL_EFFECTSLOT_NULL）。
     */
    public static synchronized int getAuxSlot() {
        if (deleteRequested) {
            deleteRequested = false;
            if (reverbEffect != -1) { alDeleteEffects(reverbEffect); reverbEffect = -1; }
            if (auxSlot != -1) { alDeleteAuxiliaryEffectSlots(auxSlot); auxSlot = -1; }
        }
        if (auxSlot == -1) {
            auxSlot = alGenAuxiliaryEffectSlots();
            if (auxSlot == 0) {
                auxSlot = -1;
                return AL_EFFECTSLOT_NULL;
            }
            reverbEffect = alGenEffects();
            if (reverbEffect == 0) {
                alDeleteAuxiliaryEffectSlots(auxSlot);
                auxSlot = -1;
                reverbEffect = -1;
                return AL_EFFECTSLOT_NULL;
            }
            alEffecti(reverbEffect, AL_EFFECT_TYPE, AL_EFFECT_EAXREVERB);
            pendingApply = true;
        }
        if (pendingApply) {
            applyToEfx();
            pendingApply = false;
        }
        return auxSlot;
    }

    /** 仅 AL 上下文线程调用。 */
    private static void applyToEfx() {
        alEffectf(reverbEffect, AL_EAXREVERB_DENSITY, density);
        alEffectf(reverbEffect, AL_EAXREVERB_DIFFUSION, diffusion);
        alEffectf(reverbEffect, AL_EAXREVERB_GAIN, inLava ? 0.25f : (inWater ? 0.3f : 0.25f));
        alEffectf(reverbEffect, AL_EAXREVERB_GAINHF, hfGain);
        alEffectf(reverbEffect, AL_EAXREVERB_GAINLF, 1);
        alEffectf(reverbEffect, AL_EAXREVERB_DECAY_TIME, rt60);
        alEffectf(reverbEffect, AL_EAXREVERB_DECAY_HFRATIO, inLava ? 0.4f : (inWater ? 0.2f : hfGain));
        alEffectf(reverbEffect, AL_EAXREVERB_DECAY_LFRATIO, 1);
        alEffectf(reverbEffect, AL_EAXREVERB_REFLECTIONS_GAIN, earlyRefGain);
        alEffectf(reverbEffect, AL_EAXREVERB_REFLECTIONS_DELAY, earlyRefDelay);
        alEffectf(reverbEffect, AL_EAXREVERB_LATE_REVERB_GAIN, lateRefGain);
        alEffectf(reverbEffect, AL_EAXREVERB_LATE_REVERB_DELAY, lateRefDelay);
        alEffectf(reverbEffect, AL_EAXREVERB_ECHO_TIME, Float.isNaN(echoTime) ? 0.25f : echoTime);
        alEffectf(reverbEffect, AL_EAXREVERB_ECHO_DEPTH, Float.isNaN(echoDepth) ? 0.05f : echoDepth);
        alEffectf(reverbEffect, AL_EAXREVERB_MODULATION_TIME, 0.4f);
        alEffectf(reverbEffect, AL_EAXREVERB_MODULATION_DEPTH, 0.025f);
        alAuxiliaryEffectSloti(auxSlot, AL_EFFECTSLOT_EFFECT, reverbEffect);
    }

    /** 客户端主线程：单条射线追踪（含反弹）。 */
    private static void traceRay(Minecraft mc, Vec3 earPos, Vector3d ray, int debugColor) {
        Vec3 pos = earPos;
        float journey = 0;
        for (int round = 0; round < MAX_BOUNCE; round++) {
            if (journey >= MAX_DIST) break;
            Vec3 to = pos.add(ray.x * MAX_DIST, ray.y * MAX_DIST, ray.z * MAX_DIST);
            var ctx = new ClipContext(pos, to, ClipContext.Block.COLLIDER,
                    (inWater || inLava) ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY, CollisionContext.empty());
            BlockHitResult hit = mc.level.clip(ctx);
            if (hit.getType() == HitResult.Type.MISS) break;
            float dist = (float) hit.getLocation().distanceTo(pos);
            if (dist == 0) break;
            journey += dist;
            BlockPos hitPos = hit.getBlockPos();
            BlockState block = mc.level.getBlockState(hitPos);
            FluidState fluid = block.getFluidState();
            float absorption, roughness, hf;
            if (!fluid.isEmpty()) {
                if (fluid.getType().isSame(Fluids.LAVA) || fluid.getType().isSame(Fluids.FLOWING_LAVA)) {
                    absorption = 0.5f; roughness = 0.9f; hf = 0.8f;
                } else {
                    absorption = 0.9f; roughness = 0.1f; hf = 0.05f;
                }
            } else {
                var props = BlockSoundProperty.get(block.getSoundType());
                absorption = props.absorption();
                roughness = props.roughness();
                hf = props.hfGain();
            }
            HIT_POINTS.add(new HitPoint(round, hit.getLocation(), journey, dist,
                    absorption, roughness, hf, hit.getDirection()));
            if (TsAudio.showRayTrace) {
                debugRays.add(new Ray(pos, hit.getLocation(), debugColor, 1f));
            }
            if (block.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                    || block.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                    || block.getBlock() instanceof net.minecraft.world.level.block.WallBlock
                    || block.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
                float bleed = 0.3f;
                Vec3 past = hit.getLocation().add(ray.x * bleed, ray.y * bleed, ray.z * bleed);
                journey += bleed;
                HIT_POINTS.add(new HitPoint(round, past, journey, bleed,
                        0.3f, roughness * 0.5f, hf * 0.7f, hit.getDirection()));
                pos = past;
                if (TsAudio.showRayTrace) {
                    debugRays.add(new Ray(hit.getLocation(), past, 0x40ffffff, 0.5f));
                }
                continue;
            }
            Vec3 hitPos3 = hit.getLocation();
            AABB segBounds = new AABB(pos, hitPos3).inflate(0.5);
            for (Entity e : mc.level.getEntities(null, segBounds)) {
                if (e instanceof Player && !e.equals(mc.player) && e.getBoundingBox().clip(pos, hitPos3).isPresent()) {
                    Vec3 entHit = e.getBoundingBox().clip(pos, hitPos3).get();
                    float eDist = (float) entHit.distanceTo(pos);
                    HIT_POINTS.add(new HitPoint(round, entHit, journey + eDist, eDist,
                            0.7f, 0.6f, 0.3f, hit.getDirection()));
                    if (TsAudio.showRayTrace) {
                        debugRays.add(new Ray(pos, entHit, debugColor, 1f));
                    }
                }
            }
            boolean hitFluid = !fluid.isEmpty();
            boolean earInFluid = inWater || inLava;
            boolean refract = false;
            if (hitFluid && !earInFluid) {
                refract = true; // air -> water/lava
            }
            if (!hitFluid && earInFluid && round > 1) {
                refract = true; // water/lava -> air（仅在内部反弹后）
            }
            if (refract && Math.random() < 0.3) {
                double squeeze = hitFluid ? 1.33 : 0.75;
                Vec3 refrEnd = hit.getLocation().add(ray.x * squeeze * 3, ray.y * squeeze * 3, ray.z * squeeze * 3);
                float refrDist = (float) refrEnd.distanceTo(hit.getLocation());
                float refrAbsorb = Math.min(0.98f, absorption + 0.5f);
                journey += refrDist;
                HIT_POINTS.add(new HitPoint(round + 1, refrEnd, journey, refrDist,
                        refrAbsorb, 0.8f, 0.05f, hit.getDirection()));
                if (TsAudio.showRayTrace) {
                    debugRays.add(new Ray(hit.getLocation(), refrEnd, debugColor, 0.4f));
                }
            }
            switch (hit.getDirection()) {
                case UP, DOWN -> ray.mul(1, -1, 1);
                case NORTH, SOUTH -> ray.mul(1, 1, -1);
                case EAST, WEST -> ray.mul(-1, 1, 1);
            }
            pos = hit.getLocation();
        }
    }

    private static List<Vector3d> generateFibonacciRays(int n, double offset) {
        double phi = Math.PI * (3 - Math.sqrt(5));
        var rays = new ArrayList<Vector3d>(n);
        for (int i = 0; i < n; i++) {
            double y = 1 - ((i + offset) / (float) (n - 1 + offset)) * 2;
            double r = Math.sqrt(1 - y * y);
            double theta = phi * (i + offset);
            rays.add(new Vector3d(Math.cos(theta) * r, y, Math.sin(theta) * r));
        }
        return rays;
    }

    public record Ray(Vec3 from, Vec3 to, int color, float width) {}
}
