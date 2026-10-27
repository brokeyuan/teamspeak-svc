//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.audio;

import com.tsvoice.client.mixin.SoundEngineAccessor;
import com.tsvoice.client.mixin.SoundManagerAccessor;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;

import java.util.EnumMap;
import java.util.Map;

/**
 * Ducking：有 TS 语音播放时压低游戏音量（不含 MASTER/VOICE）。
 *
 * <p>{@link #markActive()} 由音频播放线程调用；{@link #tick()} 必须在客户端主线程
 * 运行（与 vanilla SoundEngine 的 gainBySource 写者同线程）。时序：说话期间立即
 * 压暗，最后一帧后保持 600ms，再用 500ms 平滑恢复。</p>
 */
public class DuckingManager {
    private static volatile long lastAudioTime = 0;
    private static final long HOLD_MS = 600;
    private static final long RELEASE_MS = 500;
    private static final float SMOOTH = 0.15f;
    private static final float EPS = 0.001f;

    private static final Map<SoundSource, Float> originals = new EnumMap<>(SoundSource.class);
    private static final Map<SoundSource, Float> lastApplied = new EnumMap<>(SoundSource.class);
    private static boolean hasOriginals = false;
    private static float currentDuckLevel = 0f;

    /** 音频线程每播放一帧调用。 */
    public static void markActive() {
        lastAudioTime = System.currentTimeMillis();
    }

    /** 客户端主线程每 tick 调用。 */
    public static void tick() {
        float level = TsAudio.duckingLevel;
        if (!TsAudio.duckingEnabled || level <= 0f) {
            restoreAll();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        SoundEngine soundEngine = ((SoundManagerAccessor) mc.getSoundManager()).getSoundEngine();
        if (soundEngine == null) return;
        Object2FloatMap<SoundSource> gainMap = ((SoundEngineAccessor) soundEngine).gainBySource();
        if (gainMap == null) return;

        long elapsed = System.currentTimeMillis() - lastAudioTime;
        float target;
        if (elapsed < HOLD_MS) {
            target = level;
        } else {
            float p = Math.min(1f, (float) (elapsed - HOLD_MS) / RELEASE_MS);
            target = level * (1f - smoothstep(p));
        }

        if (target < EPS) {
            restoreAll(gainMap);
            return;
        }
        currentDuckLevel = lerp(currentDuckLevel, target, SMOOTH);
        if (currentDuckLevel < EPS) {
            currentDuckLevel = 0f;
            restoreAll(gainMap);
            return;
        }
        if (!hasOriginals) {
            snapshot(gainMap);
        }
        applyLevel(gainMap, currentDuckLevel);
    }

    private static void snapshot(Object2FloatMap<SoundSource> gainMap) {
        originals.clear();
        lastApplied.clear();
        for (SoundSource src : SoundSource.values()) {
            if (src == SoundSource.MASTER || src == SoundSource.VOICE) continue;
            float current = gainMap.getOrDefault(src, 1.0f);
            originals.put(src, current);
            lastApplied.put(src, current);
        }
        hasOriginals = true;
    }

    private static void applyLevel(Object2FloatMap<SoundSource> gainMap, float duckLevel) {
        for (var entry : originals.entrySet()) {
            SoundSource src = entry.getKey();
            float current = gainMap.getOrDefault(src, 1.0f);
            float prevApplied = lastApplied.getOrDefault(src, current);
            // 玩家在游戏内改了音量滑条 → 以当前值为新基准，避免每次 tick 被拉回
            if (Math.abs(current - prevApplied) > 0.01f) {
                entry.setValue(current);
            }
            float orig = entry.getValue();
            float target = Math.max(0f, orig * (1f - duckLevel));
            float newValue = current + (target - current) * SMOOTH;
            if (Math.abs(newValue - target) < EPS) {
                newValue = target;
            }
            gainMap.put(src, newValue);
            lastApplied.put(src, newValue);
        }
    }

    private static void restoreAll() {
        Minecraft mc = Minecraft.getInstance();
        SoundEngine soundEngine = ((SoundManagerAccessor) mc.getSoundManager()).getSoundEngine();
        if (soundEngine == null) return;
        Object2FloatMap<SoundSource> gainMap = ((SoundEngineAccessor) soundEngine).gainBySource();
        if (gainMap == null) return;
        restoreAll(gainMap);
    }

    private static void restoreAll(Object2FloatMap<SoundSource> gainMap) {
        if (!hasOriginals) return;

        boolean allRestored = true;
        for (var entry : originals.entrySet()) {
            SoundSource src = entry.getKey();
            float orig = entry.getValue();
            float current = gainMap.getOrDefault(src, 1.0f);
            if (Math.abs(current - orig) > 0.01f) {
                float newValue = current + (orig - current) * SMOOTH;
                gainMap.put(src, newValue);
                lastApplied.put(src, newValue);
                allRestored = false;
            } else {
                gainMap.put(src, orig);
                lastApplied.put(src, orig);
            }
        }

        if (allRestored) {
            originals.clear();
            lastApplied.clear();
            hasOriginals = false;
            currentDuckLevel = 0f;
        }
    }

    /** smoothstep（0→1），替代原先有误的三次贝塞尔求值。 */
    private static float smoothstep(float t) {
        float x = Math.max(0f, Math.min(1f, t));
        return x * x * (3f - 2f * x);
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * Math.min(1f, Math.max(0f, t));
    }
}
