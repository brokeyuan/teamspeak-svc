package com.svcb.tsbridge;

import com.svcb.tsbridge.ghost.GhostPlayerInjector;
import com.svcb.tsbridge.svc.McAudioInjector;
import com.svcb.tsbridge.svc.McToTsDispatcher;
import com.svcb.tsbridge.svc.OnlinePlayerRegistry;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;

/**
 * Simple Voice Chat 插件入口。
 * 通过 fabric.mod.json 的 entrypoints.voicechat 由 SVC 自动加载。
 *
 * <p>注册的事件：</p>
 * <ul>
 *     <li>{@link MicrophonePacketEvent} → {@link McToTsDispatcher#onMicrophonePacket}</li>
 *     <li>{@link PlayerConnectedEvent} → 在线表注册 + 补发 Ghost 状态 + 刷新 TS 音频 targets</li>
 *     <li>{@link PlayerDisconnectedEvent} → 在线表注销 + 刷新 TS 音频 targets</li>
 * </ul>
 */
public class TsVoicechatPlugin implements VoicechatPlugin {

    public static volatile VoicechatServerApi api;

    @Override
    public String getPluginId() {
        return "svctsbridge";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        api = (VoicechatServerApi) voicechatApi;
        TsBridgeMod.LOGGER.info("[tsbridge] SVC plugin initialized");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, McToTsDispatcher::onMicrophonePacket);
        registration.registerEvent(PlayerConnectedEvent.class, event -> {
            // 连接可能为 null（SVC 在关闭阶段也会触发该事件）
            var conn = event.getConnection();
            if (conn == null || conn.getPlayer() == null) {
                return;
            }
            OnlinePlayerRegistry.onPlayerConnected(conn);

            // v2.1: 把当前所有 Ghost 状态补发给新玩家（SVC 初始同步不含 mod 注入的 Ghost）
            Object raw = conn.getPlayer().getPlayer();
            if (raw instanceof net.minecraft.server.level.ServerPlayer mcPlayer) {
                GhostPlayerInjector.sendAllStatesTo(mcPlayer);
                if (TsBridgeMod.presenceUpdater != null) {
                    TsBridgeMod.presenceUpdater.onPlayerJoined(mcPlayer.getName().getString());
                }
                // 真 1:1 模式：为该玩家生成专属 TS 语音客户端
                if (TsBridgeMod.perPlayerMode) {
                    com.svcb.tsbridge.transport.PlayerVoiceClients.onPlayerConnected(
                            conn.getPlayer().getUuid(), mcPlayer.getName().getString());
                }
            }
            // 新玩家进入桥接群组后才能听到 TS 音频 —— 立即刷新通道 targets
            McAudioInjector.refreshTargets();
        });
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            // v2: 提前获取名称（注销后拿不到了）
            String name = null;
            var conn = OnlinePlayerRegistry.get(event.getPlayerUuid());
            if (conn != null && conn.getPlayer() != null) {
                Object raw = conn.getPlayer().getPlayer();
                if (raw instanceof net.minecraft.server.level.ServerPlayer mcPlayer) {
                    name = mcPlayer.getName().getString();
                }
            }
            OnlinePlayerRegistry.onPlayerDisconnected(event.getPlayerUuid());
            // 真 1:1 模式：释放该玩家的专属 TS 语音客户端
            if (TsBridgeMod.perPlayerMode) {
                com.svcb.tsbridge.transport.PlayerVoiceClients.onPlayerDisconnected(event.getPlayerUuid());
            }
            if (name != null && TsBridgeMod.presenceUpdater != null) {
                TsBridgeMod.presenceUpdater.onPlayerLeft(name);
            }
            McAudioInjector.refreshTargets();
        });
        TsBridgeMod.LOGGER.info("[tsbridge] SVC events registered (MicrophonePacket / PlayerConnected / PlayerDisconnected + presence)");
    }
}
