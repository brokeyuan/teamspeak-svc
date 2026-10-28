package com.tsvoice.bootstrap;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;

/**
 * 环境与依赖检查（T14.2）：基于 Loader 元数据（mod id / 版本 / 物理环境），
 * 不通过加载 SVC 类来探测其存在 —— 缺依赖时绝不触发目标类解析。
 */
public final class RuntimeDependencies {

    /** 客户端必需：SVC 本体（同时承载 voicechat API）。 */
    public static final String SVC_MOD_ID = "voicechat";
    /** 服务端公告能力不依赖 SVC；旧公告 mod 与本扩展冲突（M7 起并入）。 */
    public static final String LEGACY_ANNOUNCER_ID = "voice-capabilities";

    private RuntimeDependencies() {
    }

    /** 物理环境是否为客户端（含单人集成环境的客户端侧）。 */
    public static boolean isClientEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.CLIENT;
    }

    /** SVC 本体是否安装（仅元数据查询）。 */
    public static boolean svcModPresent() {
        return FabricLoader.getInstance().isModLoaded(SVC_MOD_ID);
    }

    /** SVC 版本（未安装为 null）。 */
    public static Version svcVersion() {
        return FabricLoader.getInstance().getModContainer(SVC_MOD_ID)
                .map(c -> c.getMetadata().getVersion())
                .orElse(null);
    }

    /** SVC API 版本是否满足客户端插件的最低要求（元数据比对，不加载类）。 */
    public static boolean svcApiCompatible() {
        Version version = svcVersion();
        if (version == null) {
            return false;
        }
        try {
            // 客户端插件面使用的 API 自 2.6.20 起稳定（上一轮基线 2.6.25）；
            // 2.6.x 兼容检查保持与历史约束一致（字符串解析，避免 Version API 形态差异）。
            return version.getFriendlyString().startsWith("2.6");
        } catch (Throwable t) {
            return true; // 非标准版本号：不因解析失败拒绝（真实兼容性由 SVC 入口验证）
        }
    }

    /** 客户端可启动性：SVC 在位且版本可接受。 */
    public static boolean clientReady() {
        return svcModPresent() && svcApiCompatible();
    }

    /** 客户端缺依赖时的明确原因（人类可读；不抛异常）。 */
    public static String clientMissingReason() {
        if (!svcModPresent()) {
            return "Simple Voice Chat (mod id '" + SVC_MOD_ID + "') is required on the client: "
                    + "this mod replaces SVC's voice backend with a TeamSpeak connection. "
                    + "Install Simple Voice Chat and restart the game.";
        }
        if (!svcApiCompatible()) {
            return "Simple Voice Chat version " + svcVersion() + " is not supported: "
                    + "a 2.6.x release is required.";
        }
        return "";
    }

    /** 旧公告 mod 是否仍在安装（M7 起与本扩展冲突，给出迁移诊断）。 */
    public static boolean legacyAnnouncerPresent() {
        return FabricLoader.getInstance().isModLoaded(LEGACY_ANNOUNCER_ID);
    }
}
