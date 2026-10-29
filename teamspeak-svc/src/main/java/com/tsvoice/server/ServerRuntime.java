package com.tsvoice.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 每 MinecraftServer 一份的服务端运行时（M7-T14.5）：公告状态、限流记录与
 * 配置生命周期。不持客户端全局状态；开始/关闭幂等；重载先验证再提交。
 */
public final class ServerRuntime implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    /** 公告状态：只区分真实存在的四种。 */
    public enum State { DISABLED, INVALID_CONFIG, ACTIVE, FAILED }

    public record CapabilityStatus(State state, String reason, int configuredTargets, Path configFile) {
    }

    public record ReloadResult(boolean applied, String reason) {
    }

    private final Path configDir;
    /** 物理服务器环境（S0.3/R22）：构造时固定，reload/恢复等所有状态计算共用。 */
    private final boolean dedicatedServer;
    private volatile ServerConfig config;
    private volatile CapabilityAnnouncer announcer;
    private volatile CapabilityStatus status;
    private volatile boolean closed;

    private ServerRuntime(Path configDir, ServerConfig config, ServerConfig.LoadResult loadResult,
                          boolean dedicatedServer) {
        this.configDir = configDir;
        this.dedicatedServer = dedicatedServer;
        this.config = config;
        this.announcer = new CapabilityAnnouncer(config, System::nanoTime);
        this.status = computeStatus(config, loadResult);
        LOGGER.info("[teamspeak-svc] server runtime started ({}) — {}", status.state(), status.reason());
    }

    /**
     * 启动运行时：读取/迁移配置并进入初始状态。
     *
     * @param dedicated 物理环境是否为 dedicated 服务器；集成（单人内嵌）服务器传
     *                  false，所有状态计算强制过 {@link #applyIntegratedServerGate}
     *                  语义（默认关闭，不受 reload/恢复路径绕过 —— R22）。
     */
    public static ServerRuntime start(Path configDir, boolean dedicated) {
        ServerConfig.LoadResult result = ServerConfig.load(configDir);
        if (!result.valid()) {
            ServerRuntime runtime = new ServerRuntime(configDir, new ServerConfig(), result, dedicated);
            // 损坏输入：公告停止（INVALID_CONFIG），不覆盖用户文件
            return runtime;
        }
        if (result.migratedFromLegacy()) {
            LOGGER.info("[teamspeak-svc] server config migrated: {}", result.source());
        }
        return new ServerRuntime(configDir, result.config(), result, dedicated);
    }

    /** 兼容入口：环境按 dedicated 处理（旧测试面；生产主入口传真实环境）。 */
    public static ServerRuntime start(Path configDir) {
        return start(configDir, true);
    }

    /** 纯配置状态（不含集成门；S0.3/R22 起由 computeStatus 统一叠加环境约束）。 */
    private static CapabilityStatus statusFor(ServerConfig config, ServerConfig.LoadResult result) {
        if (result != null && !result.valid()) {
            return new CapabilityStatus(State.INVALID_CONFIG, result.problem(), 0, result.file());
        }
        int targets = config.announceableTargets().size();
        if (!config.capabilities.enabled) {
            return new CapabilityStatus(State.DISABLED,
                    "capabilities disabled by config", targets, result != null ? result.file() : null);
        }
        if (targets == 0) {
            return new CapabilityStatus(State.DISABLED,
                    "enabled but no valid targets configured", 0, result != null ? result.file() : null);
        }
        return new CapabilityStatus(State.ACTIVE,
                "announcing " + targets + " target(s)", targets, result != null ? result.file() : null);
    }

    /**
     * 统一状态计算（S0.3/R22）：先应用配置有效性 → 配置开关 → 目标数量 →
     * 集成服务器默认关闭门。启动、reload 与无效配置恢复共用这一个函数，
     * reload 不再绕过集成门。
     */
    private CapabilityStatus computeStatus(ServerConfig config, ServerConfig.LoadResult result) {
        CapabilityStatus base = statusFor(config, result);
        if (!dedicatedServer && !config.integratedServerEnabled) {
            return new CapabilityStatus(State.DISABLED,
                    "integrated server: server-side enhancements disabled by default "
                            + "(set integratedServerEnabled=true in server.json to enable)",
                    base.configuredTargets(), base.configFile());
        }
        return base;
    }

    public CapabilityStatus status() {
        return status;
    }

    /** 当前运行时是否回复公告（仅 ACTIVE 状态）。 */
    public boolean announcing() {
        return status.state() == State.ACTIVE;
    }

    /** hello 处理委托（由全局 receiver 按 player 所属 server 路由到这里）。 */
    public byte[] onHello(String connectionId, byte[] frame) {
        if (closed || !announcing()) {
            return null;
        }
        return announcer.onHello(connectionId, frame);
    }

    /** 连接退出清理（连接身份，非仅 player UUID）。 */
    public void forgetConnection(String connectionId) {
        if (!closed) {
            announcer.forgetConnection(connectionId);
        }
    }

    /**
     * 管理员重载：先读取/验证新快照，合法才提交；失败时停用不再可信的公告并
     * 保留文件内容。已连接客户端按既有 TTL/选择策略工作（服务端不强迫切换）。
     *
     * <p>S0.3/R22：重载与恢复路径同样经 computeStatus 的集成服务器门 ——
     * integratedServerEnabled=false 的集成服务器在任意多次 reload 后保持 DISABLED，
     * 显式开启/关闭按配置生效，dedicated 环境不受该门影响。</p>
     */
    public ReloadResult reload() {
        if (closed) {
            return new ReloadResult(false, "runtime closed");
        }
        ServerConfig.LoadResult result = ServerConfig.load(configDir);
        if (!result.valid()) {
            this.config = new ServerConfig();
            this.announcer = new CapabilityAnnouncer(this.config, System::nanoTime);
            // 无效配置恢复同样过集成门（旧配置里的开关已不可信）
            this.status = computeStatus(this.config, result);
            return new ReloadResult(false, result.problem());
        }
        this.config = result.config();
        this.announcer = new CapabilityAnnouncer(this.config, System::nanoTime);
        this.status = computeStatus(this.config, result);
        LOGGER.info("[teamspeak-svc] server config reloaded: {} ({})", status.state(), status.reason());
        return new ReloadResult(true, status.reason());
    }

    /**
     * 集成门声明入口（M7-T14.5 兼容保留）：S0.3 起环境在 {@link #start} 时以
     * dedicated 参数固定并参与所有状态计算；本方法保持为幂等无操作，仅防止
     * 旧调用点（TestspeakSvcMod 兼容路径/旧测试）编译断裂。重复调用不改变状态。
     */
    public void applyIntegratedServerGate() {
        // 环境已在 start(configDir, dedicated) 固定；状态计算统一走 computeStatus。
    }

    /** 幂等关闭：只释放自己的限流记录；不触碰配置文件。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        announcer = null;
        status = new CapabilityStatus(State.DISABLED, "runtime closed", 0, configDir);
    }
}
