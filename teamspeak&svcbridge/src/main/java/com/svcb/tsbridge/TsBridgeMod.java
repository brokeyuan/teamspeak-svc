package com.svcb.tsbridge;

import com.svcb.tsbridge.config.BridgeConfig;
import com.svcb.tsbridge.config.PairingStore;
import com.svcb.tsbridge.ghost.TsGhostPresenceListener;
import com.svcb.tsbridge.audio.McAudioMixer;
import com.svcb.tsbridge.pairing.PairingManager;
import com.svcb.tsbridge.svc.McAudioInjector;
import com.svcb.tsbridge.ts.BridgeChannelManager;
import com.svcb.tsbridge.ts.TsQueryClient;
import com.svcb.tsbridge.transport.McToTsForwarder;
import com.svcb.tsbridge.transport.Ts3AudioBotTransport;
import com.svcb.tsbridge.transport.Ts3NativeTransport;
import com.svcb.tsbridge.transport.TsAudioTransport;
import com.svcb.tsbridge.ts.presence.TsPresenceUpdater;
import de.maxhenkel.voicechat.api.Group;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fabric 服务端入口。
 * onInitializeServer 中只注册事件回调，重 IO 放到 ServerLifecycleEvents.SERVER_STARTED。
 *
 * <p>v2.1 架构：</p>
 * <ul>
 *     <li><b>永久群组</b>：通过 SVC 公开 API {@code groupBuilder()} 创建持久群组
 *         （固定 UUID {@link #BRIDGE_GROUP_ID}），替代 v2 里"没有任何 target 的
 *         StaticAudioChannel"（B2）。</li>
 *     <li><b>编排器</b>：{@link #initializeTsComponents()} 幂等，既在
 *         SERVER_STARTED 首次执行，也在 ServerQuery 重连成功后重新执行（B7），
 *         恢复频道、事件监听、存在感同步等全部依赖组件。</li>
 *     <li><b>维护任务</b>：每 30 秒对账 Ghost 与 TS 频道真实成员、刷新 TS 音频
 *         targets、清理过期配对码（事件丢失自愈）。</li>
 * </ul>
 */
public class TsBridgeMod implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("tsbridge");
    public static final String MOD_ID = "tsbridge";

    /** 桥接群组固定 UUID（重启后复用同一群组）。 */
    public static final java.util.UUID BRIDGE_GROUP_ID =
            java.util.UUID.nameUUIDFromBytes("tsbridge-permanent".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    public static volatile net.minecraft.server.MinecraftServer mcServer;
    public static volatile BridgeConfig config;
    public static volatile PairingStore pairings;

    /** TeamSpeak ServerQuery 客户端（含断线重连）。 */
    public static volatile TsQueryClient tsQueryClient;
    /** 桥接频道管理器。 */
    public static volatile BridgeChannelManager bridgeChannelManager;

    /** MC↔TS 身份配对管理器。 */
    public static volatile PairingManager pairingManager;

    /** TS 音频传输层（原生 TS3 语音 / TS3AudioBot 回退）。 */
    public static volatile TsAudioTransport tsAudioTransport;
    /** MC→TS 异步转发器（mixer 不可用时的 v1 回退路径）。 */
    public static volatile McToTsForwarder forwarder;

    /** v2: 多人混音器（MC→TS 方向主路径）。 */
    public static volatile McAudioMixer mixer;

    /** v2: MC→TS 存在感同步器（bot 昵称 + 频道描述）。 */
    public static volatile TsPresenceUpdater presenceUpdater;

    /** v2.1: SVC 永久桥接群组（公开 API 创建，跨重启持久）。 */
    public static volatile Group bridgeGroup;
    /** v2.1: 桥接群组 ID（Ghost PlayerState.group 引用它）。 */
    public static volatile java.util.UUID bridgeGroupId;

    /** v0.3 真 1:1 模式标记（初始化时决定；运行期不变）。客户端管理见 {@link com.svcb.tsbridge.transport.PlayerVoiceClients}。 */
    public static volatile boolean perPlayerMode = false;

    /** 周期维护任务（Ghost 对账 / targets 刷新 / 配对码清理）。 */
    public static volatile ScheduledExecutorService maintenanceExecutor;

    /** SERVER_STOPPING 置位：阻止后台初始化线程与关闭流程竞态创建新组件。 */
    private static volatile boolean shutdownRequested = false;
    /** 后台初始化线程（SERVER_STARTED 触发；关闭时被中断并等待退出）。 */
    private static volatile Thread initThread;

    @Override
    public void onInitializeServer() {
        LOGGER.info("[tsbridge] Booting SVC <-> TeamSpeak bridge");

        // SERVER_STARTED：加载配置 + 配对数据 + 启动各组件（任一失败降级运行）。
        // 全部放在后台线程：ServerQuery 连接、TS 身份 PoW（可达数秒）、语音 UDP
        // 握手（超时 15s）都不允许阻塞服务器主线程的启动。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            mcServer = server;
            shutdownRequested = false;
            Thread t = new Thread(() -> {
                initializeRuntime();
                LOGGER.info("[tsbridge] Background initialization finished");
            }, "TsBridge-Init");
            t.setDaemon(true);
            t.start();
            initThread = t;
        });

        // SERVER_STOPPING：逆序关闭各组件，每个独立 try/catch
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            shutdownAll();
        });

        // 注册 /tsbridge 命令树
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        com.svcb.tsbridge.command.TsBridgeCommands.register(dispatcher)
        );

        // 信标 + 群组信息下发（teamspeak-svc 客户端 mod 配合组件）
        com.svcb.tsbridge.net.BeaconSender.init();
        // 可选 MC↔TS 身份声明：证明失败只降级为普通 TS Ghost，不影响语音
        com.svcb.tsbridge.identity.IdentityProofService.init();
    }

    /**
     * 加载配置与配对数据并启动全部组件（后台线程执行）。
     */
    private static void initializeRuntime() {
        // 1. 加载 config
        try {
            config = BridgeConfig.load();
            LOGGER.info("[tsbridge] Config loaded (debug={}, bridgeChannel='{}', tsHost={}:{})",
                    config.debug, config.bridgeChannelName, config.tsHost, config.tsQueryPort);
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Failed to load config: {}", e.getMessage(), e);
            config = new BridgeConfig();
        }

        // 2. 加载 pairings
        try {
            Path pairingsFile = FabricLoader.getInstance().getConfigDir()
                    .resolve("tsbridge").resolve("pairings.json");
            pairings = PairingStore.load(pairingsFile);
            LOGGER.info("[tsbridge] Pairings loaded ({} bound)", pairings.size());
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Failed to load pairings: {}", e.getMessage(), e);
            pairings = PairingStore.empty(FabricLoader.getInstance().getConfigDir()
                    .resolve("tsbridge").resolve("pairings.json"));
        }

        // 3. 启动 ServerQuery 及全部 TS 组件
        try {
            tsQueryClient = new TsQueryClient(config);
            tsQueryClient.setOnReconnected(TsBridgeMod::initializeTsComponents);
            tsQueryClient.connect();
            tsQueryClient.startReconnectLoop();
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Failed to connect TeamSpeak ServerQuery (will keep retrying in background): {}",
                    e.getMessage());
            if (tsQueryClient != null) {
                tsQueryClient.startReconnectLoop();
            }
        }

        initializeTsComponents();
        com.svcb.tsbridge.net.BeaconSender.startGroupSync();
        if (config.tcpTunnelPort > 0) {
            String tunnelSecret = System.getenv("TBRIDGE_TUNNEL_SECRET");
            if (tunnelSecret == null || tunnelSecret.length() < 32) {
                LOGGER.error("[tsbridge] TCP tunnel disabled: set TBRIDGE_TUNNEL_SECRET to a random value of at least 32 characters");
            } else {
                com.svcb.tsbridge.net.TcpTunnelServer.start(config.tcpTunnelPort,
                        config.tsHost, config.tsVoicePort, tunnelSecret, config.tcpTunnelBindAddress);
            }
        }
    }

    /**
     * 启动/恢复全部依赖 ServerQuery 与 SVC 的组件。幂等：
     * 可在 SERVER_STARTED 首次调用，也可在 ServerQuery 重连成功后再次调用（B7）。
     * 每个步骤独立 try/catch，失败降级运行。
     */
    public static synchronized void initializeTsComponents() {
        if (shutdownRequested) {
            return;
        }
        // group-sync 模式：仅 ServerQuery + TS 频道↔SVC 常驻群组同步，
        // 不启动任何音频/桥接组件（与客户端 teamspeak-svc mod 共存）
        if (config != null && "group-sync".equalsIgnoreCase(config.mode)) {
            LOGGER.info("[tsbridge] Running in group-sync mode (no audio components)");
            if (tsQueryClient != null && !tsQueryClient.isConnected()) {
                try {
                    tsQueryClient.connect();
                } catch (Exception e) {
                    LOGGER.warn("[tsbridge] ServerQuery not connected yet: {}", e.getMessage());
                }
            }
            com.svcb.tsbridge.ts.TsChannelGroupSync.syncNow();
            startMaintenanceExecutor();
            return;
        }
        // 1. 确保 SVC 永久群组（公开 API，重启复用同一 UUID）
        ensureBridgeGroup();

        // 2. ServerQuery（重连恢复场景下已连接则跳过）
        if (tsQueryClient != null && !tsQueryClient.isConnected()) {
            try {
                tsQueryClient.connect();
                LOGGER.info("[tsbridge] TeamSpeak ServerQuery connected to {}:{}",
                        config.tsHost, config.tsQueryPort);
            } catch (Exception e) {
                LOGGER.warn("[tsbridge] ServerQuery still not connected: {}", e.getMessage());
            }
        }

        boolean queryReady = tsQueryClient != null && tsQueryClient.isConnected();

        // 3. 桥接频道
        if (queryReady) {
            try {
                if (bridgeChannelManager == null) {
                    bridgeChannelManager = new BridgeChannelManager(tsQueryClient);
                }
                if (bridgeChannelManager.ensureBridgeChannel(config)) {
                    LOGGER.info("[tsbridge] Bridge channel '{}' ready (id={})",
                            config.bridgeChannelName, bridgeChannelManager.getBridgeChannelId());
                } else {
                    LOGGER.error("[tsbridge] Failed to ensure bridge channel '{}'",
                            config.bridgeChannelName);
                }
            } catch (Exception e) {
                LOGGER.error("[tsbridge] Error initializing BridgeChannelManager: {}", e.getMessage(), e);
            }
        }

        // 4. 配对管理器 + 文本事件监听
        if (queryReady && bridgeChannelManager != null && pairings != null) {
            try {
                if (pairingManager == null) {
                    pairingManager = new PairingManager(config, pairings, tsQueryClient, bridgeChannelManager);
                }
                pairingManager.registerTsEventListener();
                LOGGER.info("[tsbridge] Pairing manager ready");
            } catch (Exception e) {
                LOGGER.error("[tsbridge] Error initializing PairingManager: {}", e.getMessage(), e);
            }
        }

        // 5. Ghost Player 生命周期监听（join/moved/leave）
        if (queryReady) {
            try {
                if (tsQueryClient.registerEventListener(new TsGhostPresenceListener())) {
                    LOGGER.info("[tsbridge] Ghost Player TS event listener registered");
                }
                // 立即对账一次：补齐当前在桥接频道中的 TS 用户
                TsGhostPresenceListener.reconcile();
            } catch (Exception e) {
                LOGGER.error("[tsbridge] Failed to register TS presence listener: {}", e.getMessage(), e);
            }
        }

        // 6. MC→TS 存在感同步
        if (queryReady && bridgeChannelManager != null) {
            try {
                if (presenceUpdater == null) {
                    presenceUpdater = new TsPresenceUpdater(tsQueryClient, bridgeChannelManager, config);
                }
                presenceUpdater.updateNow();
                LOGGER.info("[tsbridge] TsPresenceUpdater ready");
            } catch (Exception e) {
                LOGGER.error("[tsbridge] Failed to create TsPresenceUpdater: {}", e.getMessage(), e);
            }
        }

        // 7. 音频传输：原生 TS3 语音客户端（默认开启，可用 nativeVoiceEnabled 关闭）
        //    → TS3AudioBot（回退，非官方功能）→ 无
        //    catch Throwable：Error（如缺依赖的 NoClassDefFoundError）也不允许击穿启动链。
        //    Ts3NativeTransport.start() 只做配置校验并立即返回（连接在后台线程重试），
        //    因此这里仅在网络配置错误时才走回退分支。
        if (tsAudioTransport == null) {
            if (config.nativeVoiceEnabled) {
                try {
                    tsAudioTransport = new Ts3NativeTransport(config,
                            FabricLoader.getInstance().getConfigDir()
                                    .resolve("tsbridge").resolve("identity.txt"),
                            () -> bridgeChannelManager != null
                                    ? bridgeChannelManager.getBridgeChannelId() : -1);
                    tsAudioTransport.start();
                    LOGGER.info("[tsbridge] Ts3NativeTransport starting (voice={}:{})",
                            config.tsHost, config.tsVoicePort);
                } catch (Throwable e) {
                    LOGGER.warn("[tsbridge] Ts3NativeTransport unavailable: {}", e.getMessage());
                    tsAudioTransport = null;
                }
            } else {
                LOGGER.info("[tsbridge] Ts3NativeTransport disabled by config (nativeVoiceEnabled=false)");
            }

            if (tsAudioTransport == null && config.botApiUrl != null && !config.botApiUrl.isBlank()) {
                try {
                    tsAudioTransport = new Ts3AudioBotTransport(config);
                    tsAudioTransport.start();
                    LOGGER.info("[tsbridge] Ts3AudioBotTransport started (botApiUrl={}) (fallback)",
                            config.botApiUrl);
                } catch (Throwable e) {
                    LOGGER.error("[tsbridge] Failed to start Ts3AudioBotTransport: {}", e.getMessage());
                    tsAudioTransport = null;
                }
            }

            if (tsAudioTransport == null) {
                LOGGER.warn("[tsbridge] Audio transport unavailable — bridge will run without audio");
            }
        } else if (tsAudioTransport instanceof Ts3NativeTransport nativeTransport) {
            // ServerQuery 重连后频道 ID 可能变化 → 重新加入桥接频道
            nativeTransport.joinBridgeChannel();
        }

        // 8. TS→MC 音频回调（Ghost UUID 统一由 GhostPlayerInjector.ghostUuidFor 派生）。
        //    真 1:1 模式下跳过 MC 玩家镜像客户端的帧，否则玩家会听到自己被注入回来的回声。
        if (tsAudioTransport != null) {
            tsAudioTransport.setOnTsAudioListener(frame -> {
                if (perPlayerMode
                        && com.svcb.tsbridge.transport.PlayerVoiceClients.isMcOwnedUid(frame.tsUniqueId())) {
                    return;
                }
                McAudioInjector.playTsAudio(
                        com.svcb.tsbridge.ghost.GhostPlayerInjector.ghostUuidFor(frame.tsUniqueId()),
                        frame.opusData());
            });
        }

        // 8.5 真 1:1 模式：每玩家专属 TS 语音客户端（MC→TS 直通，无混音）
        perPlayerMode = config != null && config.perPlayerClients;
        if (perPlayerMode) {
            try {
                com.svcb.tsbridge.transport.PlayerVoiceClients.init(() ->
                        bridgeChannelManager != null ? bridgeChannelManager.getBridgeChannelId() : -1);
                // 兜底：为在管理器就绪前已连入 SVC 的玩家补建镜像客户端
                for (var conn : com.svcb.tsbridge.svc.OnlinePlayerRegistry.all()) {
                    if (conn.getPlayer() != null
                            && conn.getPlayer().getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        com.svcb.tsbridge.transport.PlayerVoiceClients.onPlayerConnected(
                                sp.getUUID(), sp.getName().getString());
                    }
                }
                LOGGER.info("[tsbridge] Per-player TS voice clients enabled (1:1 mode)");
            } catch (Exception e) {
                LOGGER.error("[tsbridge] Failed to init per-player voice clients: {}", e.getMessage(), e);
                perPlayerMode = false;
            }
        }

        // 9. 混音器 + 输出连线（仅单 bot 混音模式；1:1 模式下每玩家直通，无需混音）
        if (!perPlayerMode) {
            try {
                if (mixer == null) {
                    mixer = new McAudioMixer();
                    mixer.start();
                    LOGGER.info("[tsbridge] McAudioMixer started (MC→TS group mix mode)");
                }
                if (tsAudioTransport != null) {
                    mixer.setOutputConsumer(opusData ->
                            tsAudioTransport.sendMcAudio(
                                    java.util.UUID.nameUUIDFromBytes("mixer-output".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                    opusData, System.currentTimeMillis()));
                }
            } catch (Exception e) {
                LOGGER.error("[tsbridge] Failed to start McAudioMixer: {}", e.getMessage(), e);
            }

            // 10. v1 回退转发器（仅混音模式）
            if (tsAudioTransport != null && forwarder == null) {
                try {
                    forwarder = new McToTsForwarder(tsAudioTransport);
                    forwarder.start();
                    LOGGER.info("[tsbridge] MC→TS forwarder started (fallback path)");
                } catch (Exception e) {
                    LOGGER.error("[tsbridge] Failed to start MC→TS forwarder: {}", e.getMessage(), e);
                }
            }
        }

        // 11. 周期维护任务（Ghost 对账 / targets / 配对码清理 / 群组补建）
        startMaintenanceExecutor();
    }

    /**
     * 判断 SVC 返回的 Group 是否是真实群组。
     *
     * <p>SVC 的 {@code VoicechatServerApiImpl.getGroup()} 在群组不存在时返回
     * 内部 group 为 null 的 {@code GroupImpl} 包装对象而非 Java null（上游实现缺陷），
     * 因此不能直接判 null —— 需检查其内部 group 字段（GroupImpl#getGroup）。
     * 反射失败（未来实现变更）时保守地按"存在"处理。</p>
     */
    private static boolean isRealGroup(Group group) {
        if (group == null) {
            return false;
        }
        try {
            return group.getClass().getMethod("getGroup").invoke(group) != null;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 确保 SVC 永久桥接群组存在（幂等）。SVC API 未就绪时静默跳过，
     * 由维护任务稍后重试。
     */
    private static void ensureBridgeGroup() {
        try {
            if (TsVoicechatPlugin.api == null) {
                LOGGER.debug("[tsbridge] SVC API not initialized yet, group creation deferred");
                return;
            }
            Group existing = TsVoicechatPlugin.api.getGroup(BRIDGE_GROUP_ID);
            if (bridgeGroup != null && isRealGroup(existing)) {
                bridgeGroupId = BRIDGE_GROUP_ID;
                return;
            }
            if (isRealGroup(existing)) {
                bridgeGroup = existing;
            } else {
                String name = config.bridgeGroupName != null && !config.bridgeGroupName.isBlank()
                        ? config.bridgeGroupName : "TeamSpeak互联";
                try {
                    bridgeGroup = TsVoicechatPlugin.api.groupBuilder()
                            .setId(BRIDGE_GROUP_ID)
                            .setName(name)
                            .setPersistent(true)
                            .setHidden(false)
                            .setType(Group.Type.NORMAL)
                            .build();
                } catch (IllegalStateException e) {
                    // 群组名不合法（超长/非法字符）时用安全默认名重试
                    LOGGER.warn("[tsbridge] Group name '{}' rejected ({}), retrying with 'TeamSpeak互联'",
                            name, e.getMessage());
                    bridgeGroup = TsVoicechatPlugin.api.groupBuilder()
                            .setId(BRIDGE_GROUP_ID)
                            .setName("TeamSpeak互联")
                            .setPersistent(true)
                            .setHidden(false)
                            .setType(Group.Type.NORMAL)
                            .build();
                }
                LOGGER.info("[tsbridge] Permanent bridge group '{}' created (id={})",
                        bridgeGroup.getName(), bridgeGroup.getId());
            }
            bridgeGroupId = bridgeGroup.getId();
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Failed to ensure bridge group: {}", e.getMessage(), e);
        }
    }

    /**
     * 启动周期维护任务（幂等）：每 30 秒
     * 对账 Ghost ↔ TS 频道成员、刷新 TS 音频通道 targets、清理过期配对码、
     * 在 SVC API 就绪后补建群组。
     */
    private static void startMaintenanceExecutor() {
        if (maintenanceExecutor != null) {
            return;
        }
        synchronized (TsBridgeMod.class) {
            if (maintenanceExecutor != null) {
                return;
            }
            maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TsBridge-Maintenance");
                t.setDaemon(true);
                return t;
            });
            maintenanceExecutor.scheduleWithFixedDelay(() -> {
                if (config != null && "group-sync".equalsIgnoreCase(config.mode)) {
                    try {
                        com.svcb.tsbridge.ts.TsChannelGroupSync.syncNow();
                    } catch (Exception e) {
                        LOGGER.debug("[tsbridge] Maintenance: group sync failed: {}", e.getMessage());
                    }
                    return;
                }
                try {
                    ensureBridgeGroup();
                } catch (Exception e) {
                    LOGGER.debug("[tsbridge] Maintenance: ensureBridgeGroup failed: {}", e.getMessage());
                }
                try {
                    // 频道可能被 TS 管理员删除或因临时创建而消失 → 周期性重建（幂等）
                    if (bridgeChannelManager != null && config != null) {
                        bridgeChannelManager.ensureBridgeChannel(config);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[tsbridge] Maintenance: ensureBridgeChannel failed: {}", e.getMessage());
                }
                try {
                    TsGhostPresenceListener.reconcile();
                } catch (Exception e) {
                    LOGGER.debug("[tsbridge] Maintenance: ghost reconcile failed: {}", e.getMessage());
                }
                try {
                    if (perPlayerMode) {
                        com.svcb.tsbridge.transport.PlayerVoiceClients.reconcile();
                    }
                } catch (Exception e) {
                    LOGGER.debug("[tsbridge] Maintenance: per-player reconcile failed: {}", e.getMessage());
                }
                try {
                    McAudioInjector.refreshTargets();
                } catch (Exception e) {
                    LOGGER.debug("[tsbridge] Maintenance: refreshTargets failed: {}", e.getMessage());
                }
                try {
                    if (pairingManager != null) {
                        pairingManager.cleanupExpired();
                    }
                } catch (Exception e) {
                    LOGGER.debug("[tsbridge] Maintenance: cleanupExpired failed: {}", e.getMessage());
                }
                try {
                    if (presenceUpdater != null && tsQueryClient != null && tsQueryClient.isConnected()) {
                        presenceUpdater.updateNow();
                    }
                } catch (Exception e) {
                    LOGGER.debug("[tsbridge] Maintenance: presence update failed: {}", e.getMessage());
                }
            }, 30, 30, TimeUnit.SECONDS);
            LOGGER.info("[tsbridge] Maintenance executor started (interval=30s)");
        }
    }

    /**
     * 逆序关闭全部组件（SERVER_STOPPING 调用）。
     */
    private static void shutdownAll() {
        // -1. 停止群组信息下发与 TCP 隧道
        try {
            com.svcb.tsbridge.net.BeaconSender.shutdown();
        } catch (Exception e) {
            LOGGER.debug("[tsbridge] Error stopping group beacon: {}", e.getMessage());
        }
        try {
            com.svcb.tsbridge.net.TcpTunnelServer.stop();
        } catch (Exception e) {
            LOGGER.debug("[tsbridge] Error stopping TCP tunnel: {}", e.getMessage());
        }

        // 0. 阻止后台初始化线程继续创建组件，并等它退出（可能正在连接/生成身份）
        shutdownRequested = true;
        Thread init = initThread;
        if (init != null && init.isAlive()) {
            init.interrupt();
            try {
                init.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 1. 停止维护任务
        ScheduledExecutorService me = maintenanceExecutor;
        maintenanceExecutor = null;
        if (me != null) {
            try {
                me.shutdownNow();
                LOGGER.info("[tsbridge] Maintenance executor stopped");
            } catch (Exception e) {
                LOGGER.error("[tsbridge] Error stopping maintenance executor: {}", e.getMessage(), e);
            }
        }

        // 2.5 停止所有玩家镜像客户端（真 1:1 模式）
        try {
            if (perPlayerMode) {
                com.svcb.tsbridge.transport.PlayerVoiceClients.shutdownAll();
                LOGGER.info("[tsbridge] Per-player TS voice clients shut down");
            }
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error shutting down per-player voice clients: {}", e.getMessage(), e);
        }

        // 3. 停止 mixer（停止混音，不再产生新的音频帧）
        try {
            if (mixer != null) {
                mixer.stop();
                LOGGER.info("[tsbridge] McAudioMixer stopped");
            }
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error stopping mixer: {}", e.getMessage(), e);
        }

        // 3.5 停止 forwarder（v1 回退转发器，仅混音模式存在）
        try {
            if (forwarder != null) {
                forwarder.stop();
                forwarder = null;
                LOGGER.info("[tsbridge] MC→TS forwarder stopped");
            }
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error stopping forwarder: {}", e.getMessage(), e);
        }

        // 4. 停止 TsPresenceUpdater
        try {
            if (presenceUpdater != null) {
                presenceUpdater.shutdown();
                LOGGER.info("[tsbridge] TsPresenceUpdater shut down");
            }
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error shutting down TsPresenceUpdater: {}", e.getMessage(), e);
        }

        // 5. 停止音频传输
        try {
            if (tsAudioTransport != null) {
                tsAudioTransport.stop();
                tsAudioTransport = null;
                LOGGER.info("[tsbridge] Audio transport stopped");
            }
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error stopping transport: {}", e.getMessage(), e);
        }

        // 6. 释放所有 Ghost（广播移除包）
        try {
            com.svcb.tsbridge.ghost.GhostPlayerInjector.shutdownAll();
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error shutting down GhostPlayerInjector: {}", e.getMessage(), e);
        }

        // 7. 释放所有 TS→MC 音频资源（AudioPlayer/Decoder/通道）
        try {
            McAudioInjector.shutdownAll();
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error shutting down McAudioInjector: {}", e.getMessage(), e);
        }

        // 8. 断开 ServerQuery
        try {
            if (tsQueryClient != null) {
                tsQueryClient.stopReconnectLoop();
                tsQueryClient.disconnect();
                LOGGER.info("[tsbridge] TeamSpeak ServerQuery disconnected");
            }
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Error disconnecting ServerQuery: {}", e.getMessage(), e);
        }

        // 9. 保存 pairings
        try {
            if (pairings != null) {
                pairings.shutdown();
                LOGGER.info("[tsbridge] Pairings saved on shutdown");
            }
        } catch (Exception e) {
            LOGGER.error("[tsbridge] Failed to save pairings on shutdown: {}", e.getMessage(), e);
        }

        // 10. 清理运行时引用（群组持久化在 SVC 侧，重启后按 UUID 复用）
        mixer = null;
        bridgeChannelManager = null;
        pairingManager = null;
        presenceUpdater = null;
        bridgeGroup = null;
        bridgeGroupId = null;
    }
}
