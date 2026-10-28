package com.tsvoice.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * N3/D03：OFF（关闭全部语音）覆盖原生 SVC 下行。
 *
 * <p>目标：{@code de.maxhenkel.voicechat.voice.client.ClientVoicechat
 * #processSoundPacket}（2.6.25，javap 核对：方法内从 connection 判空起、经
 * audioChannels 表创建/排队到清理，全部在 HEAD 之后 —— 在 HEAD 取消即同时
 * 阻止新通道创建与既有通道继续接收排队帧）。</p>
 *
 * <p>用 {@code targets=} 字符串形式且处理器省略目标参数：编译期无需依赖完整
 * mod JAR 的内部类（与 {@code defaultRequire: 0} 的软失败策略一致 —— 目标类
 * 未装载/签名漂移时 mixin 静默跳过，原版行为不受影响）。门控读取协调器的
 * OFF 意图（{@link com.tsvoice.client.backend.SvcBackendAdapter
 * #isNativeReceiveSuppressed()}），与用户静音/音量设置无关；OFF 之外一律放行。
 * TS 侧收发与自有播放通道由管理器/OFF 会话停止另行覆盖，不在本 mixin 范围。</p>
 */
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.ClientVoicechat", remap = false)
public class ClientVoicechatMixin {

    @Inject(method = "processSoundPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private void teamspeakSvc$suppressNativeReceiveWhenOff(CallbackInfo ci) {
        if (com.tsvoice.client.backend.SvcBackendAdapter.isNativeReceiveSuppressed()) {
            ci.cancel();
        }
    }
}
