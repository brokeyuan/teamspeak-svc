//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
package com.tsvoice.client.mixin;

import com.tsvoice.client.audio.AudioPipeline;
import com.tsvoice.client.audio.TsAudio;
import de.maxhenkel.voicechat.voice.client.MicrophoneProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MicrophoneProcessor.class)
public class MicProcessorMixin {
    @Inject(method = "useDenoiser", at = @At("HEAD"), cancellable = true, remap = false)
    private void overrideUseDenoiser(CallbackInfoReturnable<Boolean> cir) {
        // WebRTC 模式且 native 真实可用时才接管（禁用 SVC 原生降噪）；
        // native 不可用时回退 SVC 原生，保证始终有降噪
        if (com.tsvoice.client.audio.AudioPipeline.isNsActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "preprocess", at = @At("HEAD"), remap = false)
    private void onPreprocess(short[] audio, CallbackInfo ci) {
        if (TsAudio.denoiseMode == TsAudio.NoiseCancelMode.OFF && !TsAudio.echoCancelEnabled && !TsAudio.agcEnabled) return;
        AudioPipeline.processShort(audio);
    }
}
