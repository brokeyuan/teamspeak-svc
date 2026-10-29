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

    /** 客户端插件面使用的 API 自 2.6.20 起稳定；上游兼容目标为 2.6.x（基线 2.6.25）。 */
    private static final int MIN_MAJOR = 2;
    private static final int MIN_MINOR = 6;
    private static final int MIN_PATCH = 20;

    /**
     * 解析 "major.minor(.patch)" 语义版本（S0.7/R26）：解析失败返回 null，由
     * 调用方拒绝 —— 不再用 startsWith("2.6") 这类字符串前缀把 2.6.1/2.60.x
     * 误判为兼容。
     */
    static int[] parseSemanticVersion(String friendly) {
        if (friendly == null || friendly.isBlank()) {
            return null;
        }
        // 剥离常见构建后缀（+build.1 / -beta 等）
        String core = friendly.split("[+\\-]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (out[i] < 0) {
                return null;
            }
        }
        return out;
    }

    static boolean semanticVersionAtLeast(int[] v, int minMajor, int minMinor, int minPatch) {
        if (v == null || v.length < 2) {
            return false;
        }
        if (v[0] != minMajor) {
            return v[0] > minMajor;
        }
        int minor = v[1];
        if (minor != minMinor) {
            return minor > minMinor;
        }
        int patch = v.length >= 3 ? v[2] : 0;
        return patch >= minPatch;
    }

    /**
     * SVC 版本约束（S0.7/R26）：有工程证据（基线 2.6.25、API 面 2.6.20 起）的
     * 支持范围是 2.6.20 ≤ v &lt; 2.7.0 —— 同一 2.6 小版本系列内、不低于 2.6.20。
     * 2.6.1（过旧）、2.60.x（另一小版本系列）、2.7.0（未验证的新系列）均不接受；
     * 语义范围不得宽于有工程证据的支持范围。新系列须重做 ABI 核对后再放宽。
     */
    public static boolean versionSupported(String friendly) {
        int[] v = parseSemanticVersion(friendly);
        if (v == null || v.length < 3) {
            return false;
        }
        return v[0] == MIN_MAJOR && v[1] == MIN_MINOR && v[2] >= MIN_PATCH;
    }

    /**
     * SVC API 版本是否满足客户端插件的最低要求（元数据比对，不加载类）。
     * 解析失败/非语义版本号一律拒绝 —— 真实兼容性由具体 ABI 检查判定，
     * 不因字符串解析失败放行整个 2.6.x 系列。
     */
    public static boolean svcApiCompatible() {
        Version version = svcVersion();
        if (version == null) {
            return false;
        }
        try {
            return versionSupported(version.getFriendlyString());
        } catch (Throwable t) {
            return false;
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
                    + "version 2.6." + MIN_PATCH + " or newer (2.6.x) is required.";
        }
        return "";
    }

    /** 旧公告 mod 是否仍在安装（M7 起与本扩展冲突，给出迁移诊断）。 */
    public static boolean legacyAnnouncerPresent() {
        return FabricLoader.getInstance().isModLoaded(LEGACY_ANNOUNCER_ID);
    }

    /** svc-extra（S2/P5 双装检测）：在场时本扩展让出 SVC MC 通道域。 */
    public static final String SVC_EXTRA_ID = "svc-extra";

    public static boolean svcExtraPresent() {
        return FabricLoader.getInstance().isModLoaded(SVC_EXTRA_ID);
    }
}
