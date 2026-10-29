//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.mixin;
import com.tsvoice.client.audio.AecReferenceMixer;
import com.tsvoice.client.audio.TsAudio;
import com.tsvoice.client.audio.DuckingManager;
import com.tsvoice.client.audio.RayTracedReverb;
import de.maxhenkel.voicechat.voice.client.speaker.ALSpeakerBase;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTEfx;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SVC OpenAL 播放侧增强（音频线程）：
 * <ul>
 *     <li>Ducking 活跃标记、AEC 远端参考帧入队；</li>
 *     <li>可选混响 aux send 与水下滤波（纯 DSP；TS 音频为等响度，不做位置/距离/多普勒处理）；</li>
 *     <li>低延迟：TS 会话期间压扬声器缓冲。</li>
 * </ul>
 */
@Mixin(ALSpeakerBase.class)
public class AlSpeakerMixin {
    @Shadow
    @Mutable
    protected int source;

    @Shadow
    protected UUID audioChannelId;

    /** 水下滤波状态（channel id → {低通状态, 回授}）。 */
    @Unique
    private static final ConcurrentHashMap<UUID, float[]> WATER_STATE = new ConcurrentHashMap<>();

    @Inject(method = "openSync", at = @At("TAIL"), remap = false)
    private void onOpenSync(CallbackInfo ci) {
        AL11.alSpeedOfSound(340.0f);
        applyReverbFx();
    }

    /**
     * 低延迟：TS 会话期间把 SVC 扬声器预填充/维持的缓冲包数压到 2（约 40ms）。
     * TS 客户端没有 SVC 默认的 5 包（100ms）播放缓冲，这是双方可感延迟差的主要来源。
     */
    @Inject(method = "getBufferSize", at = @At("RETURN"), cancellable = true, remap = false)
    private void tsvoice$lowLatencyBuffer(CallbackInfoReturnable<Integer> cir) {
        if (TsAudio.lowLatencyMode && com.tsvoice.client.TsVoiceManager.isSessionConnected()) {
            int original = cir.getReturnValue();
            if (original > 2) {
                cir.setReturnValue(2);
            }
        }
    }

    @Inject(method = "writeSync", at = @At("HEAD"), remap = false)
    private void onWriteSync(short[] audio, float volume, Vec3 pos,
                             String whisper, float distance, CallbackInfo ci) {
        DuckingManager.markActive();
        if (TsAudio.echoCancelEnabled) {
            AecReferenceMixer.push(audio);
        }
        applyReverbFx();
        if (TsAudio.reverbEnabled) {
            applyUnderwaterFilter(audio);
        }
    }

    @Inject(method = "closeSync", at = @At("HEAD"), remap = false)
    private void onCloseSync(CallbackInfo ci) {
        if (audioChannelId != null) {
            WATER_STATE.remove(audioChannelId);
        }
    }

    @Unique
    private void applyReverbFx() {
        if (TsAudio.reverbEnabled) {
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    RayTracedReverb.getAuxSlot(), 0, EXTEfx.AL_FILTER_NULL);
        } else {
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
        }
    }

    @Unique
    private void applyUnderwaterFilter(short[] audio) {
        if (audioChannelId == null || !RayTracedReverb.isUnderwater()) {
            if (audioChannelId != null) {
                WATER_STATE.remove(audioChannelId);
            }
            return;
        }
        if (audio == null || audio.length == 0) return;
        float alpha = RayTracedReverb.isInLava() ? 0.095f : 0.025f;
        float a1 = 1f - alpha;
        float[] s = WATER_STATE.computeIfAbsent(audioChannelId, _ -> new float[2]);
        for (int i = 0; i < audio.length; i++) {
            float x = audio[i] / 32768f;
            x -= s[1] * 0.9995f;
            s[1] = x;
            float y = alpha * x + a1 * s[0];
            s[0] = y;
            if (y > 1f) y = 1f - (y - 1f) / (y + 1f);
            else if (y < -1f) y = -1f - (y + 1f) / (y - 1f);
            audio[i] = (short) (y * 23170f);
        }
    }
}
