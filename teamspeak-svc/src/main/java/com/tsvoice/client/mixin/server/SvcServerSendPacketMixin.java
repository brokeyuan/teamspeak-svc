package com.tsvoice.client.mixin.server;

import com.tsvoice.server.svc.SvcMcChannelAdapter;
import de.maxhenkel.voicechat.voice.common.NetworkMessage;
import de.maxhenkel.voicechat.voice.common.Packet;
import de.maxhenkel.voicechat.voice.server.Server;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SVC_MC_CHANNEL 服务端出站拦截（S2/P5）：SVC Server.sendPacketRaw HEAD 拦截，
 * 对声明走 MC 通道的玩家经 {@code NetworkMessage.writeServer} 保留 SVC 封装/
 * 密钥语义后走 MC 通道，其余（原生 UDP 玩家）完全不动 —— 按玩家路由，不是
 * 全服 transport 开关（与 SVC-Extra @6669d6f McChannelMixin 的关键差异之一）。
 *
 * <p>Mixin 默认关闭（defaultRequire=0 与本扩展 mixin 配置一致；拦截只在本
 * 扩展宣告了 MC_CHANNEL 的玩家上生效）。svc-extra 在场时适配层为空集，
 * 本 mixin 等效 no-op —— 不与 Extra 争用（Extra 也拦同一方法，全服开关下
 * 它会先 cancel；本扩展的 isMcChannelPlayer 空集即返回 false，双 cancel
 * 不会发生）。</p>
 */
@Mixin(value = Server.class, remap = false)
public class SvcServerSendPacketMixin {

    private static final Logger TSLOGGER = LoggerFactory.getLogger("teamspeak-svc");

    @Shadow(remap = false)
    @Final
    private MinecraftServer server;

    @Inject(method = "sendPacketRaw", at = @At("HEAD"), cancellable = true)
    private void tsvoice$onSendPacketRaw(Packet<?> packet,
                                         de.maxhenkel.voicechat.voice.server.ClientConnection connection,
                                         CallbackInfo ci) {
        try {
            UUID target = connection.getPlayerUUID();
            if (target == null || !SvcMcChannelAdapter.isMcChannelPlayer(target)) {
                return; // UDP 玩家 / 未宣告：SVC 原始路径不动
            }
            // writeServer 保留 SVC 加密/封装（与 Extra 的 wire 字节契约一致）
            byte[] fullPacket = new NetworkMessage(packet).writeServer(
                    (Server) (Object) this, connection);
            boolean handled = SvcMcChannelAdapter.onSendPacketRaw(target, fullPacket,
                    (java.util.UUID playerId, byte[] data) -> {
                        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                        if (player != null) {
                            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                                    player, new com.tsvoice.common.net.SvcMcChannelPayloads
                                            .SvcVoiceS2cPayload(data));
                        }
                    });
            if (handled) {
                ci.cancel(); // 已走 MC 通道：取消 SVC 原始 UDP 发送
            }
        } catch (Exception e) {
            // 拦截失败不吞语音：放行原始路径（SVC UDP 发送仍可用）
            TSLOGGER.debug("[teamspeak-svc] MC-channel send interception failed, falling back to UDP: {}",
                    e.getMessage());
        }
    }
}
