package com.tsvoice.client.music;

/**
 * 玩家语音生命周期 → 本机音乐 Bot 的转发点（F5）。
 *
 * <p>独立小类：语音协调器不必静态引用 BotManager 的连接构造面。生产转发走
 * 惰性类加载（BotManager 注册其处理逻辑）；回归 harness 只编译本类时为
 * no-op（BotManager 缺席 = 无 Bot 可停，语义一致）。</p>
 */
public final class MusicBotLifecycle {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("teamspeak-svc");

    private MusicBotLifecycle() {
    }

    /** 断开 TS / 切 SVC / OFF / 退出游戏：停<b>本机</b> Bot（产品默认）。 */
    public static void onPlayerVoiceLifecycleChange(String reason) {
        try {
            // 惰性解析生产实现（无编译期依赖；MusicBotManager 不在类路径时
            // 即无可停 Bot，静默返回）
            Class.forName("com.tsvoice.client.music.MusicBotManager", true,
                    MusicBotLifecycle.class.getClassLoader())
                    .getMethod("onPlayerVoiceLifecycleChange", String.class)
                    .invoke(null, reason);
        } catch (ClassNotFoundException ignored) {
            // harness/精简环境：无音乐 Bot 实现
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("[teamspeak-svc] music bot lifecycle forward failed: {}", e.getMessage());
        }
    }
}
