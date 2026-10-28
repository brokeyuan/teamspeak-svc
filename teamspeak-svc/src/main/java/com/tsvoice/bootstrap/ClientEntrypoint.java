package com.tsvoice.bootstrap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端薄入口（T14.2）：先做元数据级依赖检查（不加载 SVC/客户端类），
 * 通过后才进入现有客户端初始化 {@code TeamspeakSvcClient.onInitializeClient()}。
 * 缺 SVC 的客户端在此输出明确依赖原因并停用本扩展 —— 不出现 NoClassDefFoundError
 * 随机崩溃，也不伪装可用。
 */
public final class ClientEntrypoint implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return; // 防御：该入口只应被客户端环境调用
        }
        if (!RuntimeDependencies.clientReady()) {
            LOGGER.error("[teamspeak-svc] CLIENT DISABLED — {}", RuntimeDependencies.clientMissingReason());
            return;
        }
        if (RuntimeDependencies.legacyAnnouncerPresent()) {
            LOGGER.warn("[teamspeak-svc] legacy 'voice-capabilities' mod detected alongside the unified "
                    + "teamspeak-svc: remove it — capability announcements are built in (payload conflict).");
        }
        LOGGER.info("[teamspeak-svc] client entrypoint: dependencies ok (voicechat {}), delegating to client init",
                RuntimeDependencies.svcVersion());
        // 客户端初始化（SVC API 事件、麦克风改道、配置、命令）——保留上一轮顺序
        com.tsvoice.client.TeamspeakSvcClient.onInitializeClient();
    }
}
