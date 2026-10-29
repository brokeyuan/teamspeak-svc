package com.tsvoice.client;

import com.tsvoice.bootstrap.RuntimeDependencies;

/**
 * R26 版本谓词探针：直接调用生产 {@link RuntimeDependencies} 的语义版本解析，
 * 不经 FabricLoader（版本字符串由测试直接供给）。
 */
public final class R26VersionProbe {

    public static boolean supported(String friendly) {
        return RuntimeDependencies.versionSupported(friendly);
    }

    private R26VersionProbe() {
    }
}
