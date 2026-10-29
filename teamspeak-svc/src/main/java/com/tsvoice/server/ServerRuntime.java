package com.tsvoice.server;

import com.tsvoice.server.relay.TsRelayRuntime;
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
    private final TsRelayRuntime mcSinkAdapter;
    private volatile ServerConfig config;
    private volatile CapabilityAnnouncer announcer;
    private volatile CapabilityStatus status;
    private volatile boolean closed;
    /** TS 中继运行时（S1.3/P6）：默认关闭；sink 由主入口注入。 */
    private volatile TsRelayRuntime relayRuntime;

    private ServerRuntime(Path configDir, ServerConfig config, ServerConfig.LoadResult loadResult,
                          boolean dedicatedServer) {
        this(configDir, config, loadResult, dedicatedServer, null);
    }

    /** 完整构造（主入口注入 MC 上行 sink；测试可传 no-op sink）。 */
    ServerRuntime(Path configDir, ServerConfig config, ServerConfig.LoadResult loadResult,
                  boolean dedicatedServer, TsRelayRuntime.McSink relaySink) {
        this.configDir = configDir;
        this.dedicatedServer = dedicatedServer;
        this.config = config;
        this.mcSinkAdapter = relaySink != null ? new TsRelayRuntime(relaySink, relayConfigOf(config))
                : new TsRelayRuntime((id, player, datagram) -> false, relayConfigOf(config));
        this.relayRuntime = mcSinkAdapter;
        this.relayRuntime.applyConfig(relayConfigOf(config));
        this.announcer = new CapabilityAnnouncer(config, System::nanoTime);
        this.status = computeStatus(config, loadResult);
        LOGGER.info("[teamspeak-svc] server runtime started ({}) — {}", status.state(), status.reason());
    }

    /** ServerConfig.relay 节 → TsRelayRuntime.RelayConfig（缺节按默认关闭）。 */
    private static TsRelayRuntime.RelayConfig relayConfigOf(ServerConfig config) {
        if (config == null || config.relay == null || !config.relay.enabled) {
            return TsRelayRuntime.RelayConfig.disabled();
        }
        java.util.Map<String, TsRelayRuntime.RelayConfig.RelayTargetConfig> targets =
                new java.util.HashMap<>();
        if (config.relay.targetIds != null) {
            for (java.util.Map.Entry<String, ServerConfig.RelayTarget> e
                    : config.relay.targetIds.entrySet()) {
                if (e.getValue() != null && e.getKey() != null) {
                    targets.put(e.getKey().toLowerCase(java.util.Locale.ROOT),
                            new TsRelayRuntime.RelayConfig.RelayTargetConfig(
                                    e.getValue().host, e.getValue().port));
                }
            }
        }
        return new TsRelayRuntime.RelayConfig(true, java.util.Map.copyOf(targets),
                config.relay.maxSessions,
                new TsRelayRuntime.Limits(config.relay.maxDatagramBytes,
                        config.relay.maxDatagramsPerSecond, config.relay.maxBytesPerSecond),
                config.relay.musicBotEnabled);
    }

    /** 当前 TS 中继运行时（主入口 payload 接收/诊断；默认关闭时仍可安全调用）。 */
    public TsRelayRuntime relayRuntime() {
        return relayRuntime;
    }

    /**
     * 当前通道配置视图（S2/P5 主入口 JOIN 宣告用：svcMcChannelEnabled 决定
     * SVC 传输宣告；配置缺节时为默认关闭）。
     */
    public RelayView relayConfig() {
        ServerConfig current = config;
        boolean svcEnabled = current != null && current.relay != null && current.relay.svcMcChannelEnabled;
        return new RelayView(svcEnabled);
    }

    /** 通道配置视图（不可变快照）。 */
    public record RelayView(boolean svcMcChannelEnabled) {
    }

    /**
     * 启动运行时：读取/迁移配置并进入初始状态。
     *
     * @param dedicated 物理环境是否为 dedicated 服务器；集成（单人内嵌）服务器传
     *                  false，所有状态计算强制过 {@link #applyIntegratedServerGate}
     *                  语义（默认关闭，不受 reload/恢复路径绕过 —— R22）。
     */
    public static ServerRuntime start(Path configDir, boolean dedicated) {
        return start(configDir, dedicated, null);
    }

    /** 完整启动（S1.3：主入口注入 relay 上行 sink；测试传 null 用默认 no-op）。 */
    public static ServerRuntime start(Path configDir, boolean dedicated, TsRelayRuntime.McSink relaySink) {
        ServerConfig.LoadResult result = ServerConfig.load(configDir);
        if (!result.valid()) {
            ServerRuntime runtime = new ServerRuntime(configDir, new ServerConfig(), result,
                    dedicated, relaySink);
            // 损坏输入：公告停止（INVALID_CONFIG），不覆盖用户文件
            return runtime;
        }
        if (result.migratedFromLegacy()) {
            LOGGER.info("[teamspeak-svc] server config migrated: {}", result.source());
        }
        return new ServerRuntime(configDir, result.config(), result, dedicated, relaySink);
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
            // S1.3：无效配置 → relay 回到默认关闭（回收全部会话）
            TsRelayRuntime relay = this.relayRuntime;
            if (relay != null) {
                relay.applyConfig(relayConfigOf(this.config));
            }
            return new ReloadResult(false, result.problem());
        }
        this.config = result.config();
        this.announcer = new CapabilityAnnouncer(this.config, System::nanoTime);
        this.status = computeStatus(this.config, result);
        // S1.3：relay 配置随 reload 应用（目标变更不重定向已建立会话；总开关
        // 关闭时回收全部会话）
        TsRelayRuntime relay = this.relayRuntime;
        if (relay != null) {
            relay.applyConfig(relayConfigOf(this.config));
        }
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

    /** 幂等关闭：释放公告限流记录与中继会话；不触碰配置文件。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        announcer = null;
        TsRelayRuntime relay = relayRuntime;
        if (relay != null) {
            relay.close();
        }
        status = new CapabilityStatus(State.DISABLED, "runtime closed", 0, configDir);
    }
}
