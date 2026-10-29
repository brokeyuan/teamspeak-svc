package com.tsvoice.compat.svc;

import com.tsvoice.bootstrap.RuntimeDependencies;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * T31/E1：SVC 目标 Mixin 的条件应用插件。
 *
 * <p>通用列表中的 {@code server.SvcServerSendPacketMixin} 目标是 SVC 内部类
 * {@code de.maxhenkel.voicechat.voice.server.Server}。SVC 不在场（无 voicechat
 * mod）时应用它会因目标类解析失败污染整个 mixin 配置 —— 公共入口、公告、
 * TS relay 都可能被拖垮。本插件按"voicechat mod 是否在位"放行该 mixin；
 * 客户端列表天然只在客户端环境应用（Loader 环境隔离），不在插件内重复判断。</p>
 *
 * <p>不使用 defaultRequire=0 替代可选目标隔离：require 只控制注入点计数，
 * 不阻止缺失目标类导致的配置级失败。</p>
 */
public final class TsVoiceMixinPlugin implements IMixinConfigPlugin {

    /** 仅当 SVC 在位时应用的通用（common）mixin：目标是 SVC 内部类。 */
    private static final String SVC_SERVER_SEND_MIXIN = "server.SvcServerSendPacketMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // mixinClassName 以配置 package 前缀到达（com.tsvoice.client.mixin.…）
        if (mixinClassName == null) {
            return false;
        }
        if (mixinClassName.endsWith("." + SVC_SERVER_SEND_MIXIN)) {
            // 公共 mixin 目标是 SVC 内部 Server：SVC 不在位时整个跳过（不解析目标类）。
            return RuntimeDependencies.svcModPresent();
        }
        // 其余（client 列表）保持默认应用；环境隔离由 Loader 的 client 作用域完成。
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
