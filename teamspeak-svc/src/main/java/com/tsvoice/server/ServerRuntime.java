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
    private volatile ServerConfig config;
    private volatile CapabilityAnnouncer announcer;
    private volatile CapabilityStatus status;
    private volatile boolean closed;

    private ServerRuntime(Path configDir, ServerConfig config, ServerConfig.LoadResult loadResult) {
        this.configDir = configDir;
        this.config = config;
        this.announcer = new CapabilityAnnouncer(config, System::nanoTime);
        this.status = statusFor(config, loadResult);
        LOGGER.info("[teamspeak-svc] server runtime started ({}) — {}", status.state(), status.reason());
    }

    /** 启动运行时：读取/迁移配置并进入初始状态。 */
    public static ServerRuntime start(Path configDir) {
        ServerConfig.LoadResult result = ServerConfig.load(configDir);
        if (!result.valid()) {
            ServerRuntime runtime = new ServerRuntime(configDir, new ServerConfig(), result);
            // 损坏输入：公告停止（INVALID_CONFIG），不覆盖用户文件
            return runtime;
        }
        if (result.migratedFromLegacy()) {
            LOGGER.info("[teamspeak-svc] server config migrated: {}", result.source());
        }
        return new ServerRuntime(configDir, result.config(), result);
    }

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
     * 集成服务器默认关闭服务端增强（M7-T14.5）：仅当配置显式
     * integratedServerEnabled=true 时保持配置状态，否则强制 DISABLED。
     * 由主入口在 SERVER_STARTING 时调用（仅非 dedicated）。
     */
    public void applyIntegratedServerGate() {
        if (closed) {
            return;
        }
        if (!config.integratedServerEnabled) {
            this.status = new CapabilityStatus(State.DISABLED,
                    "integrated server: server-side enhancements disabled by default "
                            + "(set integratedServerEnabled=true in server.json to enable)",
                    config.announceableTargets().size(), status.configFile());
        }
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
     */
    public ReloadResult reload() {
        if (closed) {
            return new ReloadResult(false, "runtime closed");
        }
        ServerConfig.LoadResult result = ServerConfig.load(configDir);
        if (!result.valid()) {
            this.config = new ServerConfig();
            this.announcer = new CapabilityAnnouncer(this.config, System::nanoTime);
            this.status = new CapabilityStatus(State.INVALID_CONFIG, result.problem(), 0, result.file());
            return new ReloadResult(false, result.problem());
        }
        this.config = result.config();
        this.announcer = new CapabilityAnnouncer(this.config, System::nanoTime);
        this.status = statusFor(this.config, result);
        LOGGER.info("[teamspeak-svc] server config reloaded: {} ({})", status.state(), status.reason());
        return new ReloadResult(true, status.reason());
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
