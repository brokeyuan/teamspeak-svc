package com.tsvoice.compat.svc;

import com.tsvoice.bootstrap.RuntimeDependencies;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SVC 自定义入口门面（M7-T14.3）：只被 SVC 实际消费 voicechat 自定义入口时
 * 实例化 —— SVC 不存在时本类不会被装载，公共入口/服务端配置/诊断都不触碰
 * 插件 API 类型。
 *
 * <p>物理客户端：SVC 在位时延迟创建既有客户端插件委托
 * （{@link com.tsvoice.client.VoicechatPluginImpl}），客户端事件只注册一次；
 * dedicated server：返回稳定插件 ID 并保持无客户端事件（不伪造媒体功能）。
 * 本类依赖 SVC 公共 API 属计划允许面；common/main/server 不引用它。</p>
 */
public class VoicechatPluginEntrypoint implements VoicechatPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    /** 客户端委托（仅物理客户端且依赖检查通过后创建）。 */
    private VoicechatPlugin clientDelegate;
    private boolean delegateInitialized;

    @Override
    public String getPluginId() {
        return "teamspeak-svc";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (RuntimeDependencies.isClientEnvironment() && RuntimeDependencies.clientReady()) {
            ensureClientDelegate();
            if (clientDelegate != null) {
                clientDelegate.initialize(api);
                return;
            }
        }
        LOGGER.info("[teamspeak-svc] SVC plugin facade on {}: stable id only, no client events registered",
                RuntimeDependencies.isClientEnvironment() ? "client (deps unmet)" : "dedicated server");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        if (RuntimeDependencies.isClientEnvironment() && RuntimeDependencies.clientReady()) {
            ensureClientDelegate();
            if (clientDelegate != null) {
                clientDelegate.registerEvents(registration);
                return;
            }
        }
        // 服务端/依赖缺失：不注册任何客户端事件（M7 边界）
    }

    /** 延迟委托：只有在物理客户端且依赖就绪时才解析客户端插件类。 */
    private void ensureClientDelegate() {
        if (delegateInitialized) {
            return;
        }
        delegateInitialized = true;
        try {
            clientDelegate = new com.tsvoice.client.VoicechatPluginImpl();
        } catch (Throwable t) {
            LOGGER.error("[teamspeak-svc] client voicechat plugin delegation failed: {}", t.toString());
            clientDelegate = null;
        }
    }
}
