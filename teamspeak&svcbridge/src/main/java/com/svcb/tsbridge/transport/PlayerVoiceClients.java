package com.svcb.tsbridge.transport;

import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.ts3voice.Ts3Identity;
import com.svcb.tsbridge.ts3voice.Ts3VoiceClient;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * 真 1:1 模式：为每个在线 MC 玩家维护一个专属 TS 语音客户端。
 *
 * <ul>
 *     <li>玩家连入 SVC → 以玩家名作为昵称、为其专属身份（P-256 + PoW，
 *         持久化到 config/tsbridge/identities/&lt;uuid&gt;.txt，跨重启保持同一 TS uid）
 *         建立 TS 语音连接并加入桥接频道 —— TS 用户在频道里看到的就是该玩家本人；</li>
 *     <li>MC→TS 音频不再混音：玩家的 Opus 帧直接经其专属客户端透传发送
 *         （零编解码，SVC 帧本身就是 TS 兼容的 20ms Opus）；</li>
 *     <li>玩家离开 SVC → 断开并释放其 TS 客户端；</li>
 *     <li>断线/首次失败由每玩家独立线程按指数退避重连（与
 *         {@link Ts3NativeTransport} 相同的模式）；</li>
 *     <li>所有由本类创建的客户端的 TS uid 视为"MC 侧镜像客户端"：
 *         {@link #isMcOwnedUid} 供 Ghost 注入与 TS→MC 音频回调排除，
 *         否则玩家会听到自己被 bot 注入回来的回声、且镜像客户端会被误建 Ghost。</li>
 * </ul>
 */
public final class PlayerVoiceClients {

    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long ALIVE_POLL_MS = 500L;
    private static final long JOIN_RETRY_MS = 15_000L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    /** Per-player outbound frames: ≈1 second at 20ms; oldest frames drop under backpressure. */
    private static final int OUTBOUND_QUEUE_CAPACITY = 50;

    private static final Map<UUID, PlayerClient> players = new ConcurrentHashMap<>();
    /** 历史上为玩家生成过的全部 TS uid（含离线玩家）——排除回声/Ghost 的依据不受连接时序影响。 */
    private static final java.util.Set<String> knownMcUids = ConcurrentHashMap.newKeySet();
    private static volatile IntSupplier bridgeChannelIdSupplier;
    private static volatile boolean stopped = false;

    private PlayerVoiceClients() {
    }

    /**
     * 初始化（幂等）：预加载已持久化的玩家身份，提前登记"MC 侧 uid"，
     * 使 Ghost/回声排除在玩家首连之前就生效。
     */
    public static synchronized void init(IntSupplier channelIdSupplier) {
        bridgeChannelIdSupplier = channelIdSupplier;
        stopped = false;
        try {
            var dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("tsbridge").resolve("identities");
            if (java.nio.file.Files.isDirectory(dir)) {
                try (var files = java.nio.file.Files.list(dir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".txt")).forEach(p -> {
                        try {
                            Ts3Identity id = Ts3Identity.load(p);
                            if (id != null) {
                                knownMcUids.add(id.getClientUid());
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Failed to preload player identities: {}", e.getMessage());
        }
    }

    /**
     * 玩家连入 SVC 时调用：为其启动专属 TS 客户端管理线程（幂等）。
     */
    public static synchronized void onPlayerConnected(UUID mcUuid, String mcName) {
        if (mcUuid == null || mcName == null || stopped || bridgeChannelIdSupplier == null) {
            return;
        }
        players.computeIfAbsent(mcUuid, id -> {
            PlayerClient pc = new PlayerClient(mcUuid, mcName);
            Thread sender = new Thread(() -> outboundLoop(pc), "TsBridge-PVoice-Send-" + mcName);
            sender.setDaemon(true);
            pc.outboundThread = sender;
            sender.start();
            Thread t = new Thread(() -> runLoop(pc), "TsBridge-PVoice-" + mcName);
            t.setDaemon(true);
            pc.thread = t;
            t.start();
            TsBridgeMod.LOGGER.info("[tsbridge] Spawning dedicated TS voice client for MC player {}", mcName);
            return pc;
        });
    }

    /**
     * 玩家离开 SVC 时调用：断开其 TS 客户端并停止管理线程。
     */
    public static void onPlayerDisconnected(UUID mcUuid) {
        if (mcUuid == null) {
            return;
        }
        PlayerClient pc = players.remove(mcUuid);
        if (pc == null) {
            return;
        }
        synchronized (pc) {
            pc.online = false;
        }
        Thread t = pc.thread;
        if (t != null) {
            t.interrupt();
        }
        Thread sender = pc.outboundThread;
        if (sender != null) {
            sender.interrupt();
        }
        cleanup(pc);
        TsBridgeMod.LOGGER.info("[tsbridge] Dedicated TS voice client for {} released", pc.mcName);
    }

    /**
     * 周期对账：清理玩家实际已离线但事件丢失的残留条目（幂等，维护任务调用）。
     */
    public static void reconcile() {
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            if (com.svcb.tsbridge.svc.OnlinePlayerRegistry.get(uuid) == null) {
                onPlayerDisconnected(uuid);
            }
        }
    }

    /**
     * 判断该 TS uid 是否为本 mod 为 MC 玩家创建的镜像客户端
     * （用于 Ghost 注入与 TS→MC 音频回调排除，防止回声与幽灵误建）。
     */
    public static boolean isMcOwnedUid(String tsUniqueId) {
        if (tsUniqueId == null) {
            return false;
        }
        if (knownMcUids.contains(tsUniqueId)) {
            return true;
        }
        for (PlayerClient pc : players.values()) {
            if (tsUniqueId.equals(pc.tsUid)) {
                knownMcUids.add(tsUniqueId);
                return true;
            }
        }
        return false;
    }

    /**
     * 当前在线的镜像客户端数量（status 命令用）。
     */
    public static int connectedCount() {
        int n = 0;
        for (PlayerClient pc : players.values()) {
            Ts3VoiceClient c = pc.client;
            if (c != null && c.isConnected()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 将指定 MC 玩家的一帧 Opus 音频经其专属 TS 客户端透传发送。
     * 玩家客户端未连接（如 TS 不可达）时静默丢弃。
     */
    public static void sendToPlayer(UUID mcUuid, byte[] opusData) {
        if (mcUuid == null || opusData == null || opusData.length == 0 || stopped) {
            return;
        }
        PlayerClient pc = players.get(mcUuid);
        if (pc == null) {
            return;
        }
        Ts3VoiceClient client = pc.client;
        if (client == null || !client.isConnected()) {
            return;
        }
        // SVC callback only enqueues; encryption/UDP happens on the player's sender thread.
        if (!pc.outbound.offer(opusData)) {
            pc.outbound.poll();
            if (!pc.outbound.offer(opusData)) {
                pc.outboundDropped.incrementAndGet();
            }
        }
    }

    private static void outboundLoop(PlayerClient pc) {
        while (pc.online && !stopped) {
            byte[] opus;
            try {
                opus = pc.outbound.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (opus == null) {
                continue;
            }
            Ts3VoiceClient client = pc.client;
            if (client == null || !client.isConnected()) {
                continue;
            }
            try {
                client.sendVoice(opus, Ts3VoiceClient.CODEC_OPUS_VOICE);
            } catch (Exception e) {
                TsBridgeMod.LOGGER.debug("[tsbridge] sendVoice({}) failed: {}", pc.mcName, e.getMessage());
            }
        }
    }

    /**
     * 服务端关闭时释放全部镜像客户端。
     */
    public static synchronized void shutdownAll() {
        stopped = true;
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            onPlayerDisconnected(uuid);
        }
        players.clear();
        TsBridgeMod.LOGGER.info("[tsbridge] PlayerVoiceClients shut down (all per-player clients released)");
    }

    // ---- 每玩家连接循环 ----

    private static void runLoop(PlayerClient pc) {
        try {
            runWhileOnline(pc);
        } finally {
            cleanup(pc);
            players.remove(pc.mcUuid, pc);
        }
    }

    private static void runWhileOnline(PlayerClient pc) {
        long backoffMs = INITIAL_BACKOFF_MS;
        while (pc.online && !stopped) {
            try {
                connect(pc);
                backoffMs = INITIAL_BACKOFF_MS;
            } catch (Exception e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] TS voice client for {} failed: {}. Retrying in {}ms",
                        pc.mcName, e.getMessage(), backoffMs);
                cleanup(pc);
            }
            if (!pc.online || stopped) {
                return;
            }

            // 已连接：存活监测 + 加入桥接频道
            long lastJoinAttempt = 0;
            while (pc.online && !stopped) {
                Ts3VoiceClient client = pc.client;
                if (client == null || !client.isConnected()) {
                    break;
                }
                long now = System.currentTimeMillis();
                int bridgeCid = bridgeChannelIdSupplier.getAsInt();
                if (bridgeCid >= 0 && bridgeCid != client.getMyChannelId()
                        && now - lastJoinAttempt >= JOIN_RETRY_MS) {
                    lastJoinAttempt = now;
                    try {
                        client.joinChannel(bridgeCid);
                        TsBridgeMod.LOGGER.info("[tsbridge] {} voice client joined bridge channel (cid={})",
                                pc.mcName, bridgeCid);
                    } catch (Exception e) {
                        TsBridgeMod.LOGGER.warn("[tsbridge] {} failed to join bridge channel: {}",
                                pc.mcName, e.getMessage());
                    }
                }
                try {
                    Thread.sleep(ALIVE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!pc.online || stopped) {
                return;
            }

            cleanup(pc);
            TsBridgeMod.LOGGER.warn("[tsbridge] {} TS voice connection lost — reconnecting in {}ms",
                    pc.mcName, backoffMs);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
    }

    private static void connect(PlayerClient pc) throws Exception {
        if (!pc.online || stopped) {
            return;
        }
        Ts3Identity identity = pc.identity;
        if (identity == null) {
            Path path = identityPathFor(pc.mcUuid);
            identity = Ts3Identity.load(path);
            if (identity == null) {
                long t0 = System.currentTimeMillis();
                identity = Ts3Identity.generate(24, 8_000);
                identity.save(path);
                TsBridgeMod.LOGGER.info("[tsbridge] TS identity for {} generated in {}ms (uid={})",
                        pc.mcName, System.currentTimeMillis() - t0, identity.getClientUid());
            }
            pc.identity = identity;
        }
        pc.tsUid = identity.getClientUid();
        knownMcUids.add(pc.tsUid);

        Ts3VoiceClient client = new Ts3VoiceClient(identity, pc.mcName, "");
        try {
            client.connect(InetAddress.getByName(TsBridgeMod.config.tsHost),
                    TsBridgeMod.config.tsVoicePort, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            try {
                client.disconnect();
            } catch (Exception ignored) {
            }
            throw e;
        }
        synchronized (pc) {
            // 离线清理与成功提交使用同一把锁；未提交的连接必须由创建者关闭。
            if (!pc.online || stopped) {
                client.disconnect();
                return;
            }
            pc.client = client;
        }
        TsBridgeMod.LOGGER.info("[tsbridge] {} TS voice client connected (clid={}, uid={})",
                pc.mcName, client.getMyClid(), client.getMyUid());
    }

    private static void cleanup(PlayerClient pc) {
        Ts3VoiceClient old;
        pc.outbound.clear();
        synchronized (pc) {
            old = pc.client;
            pc.client = null;
        }
        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private static Path identityPathFor(UUID mcUuid) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("tsbridge").resolve("identities").resolve(mcUuid + ".txt");
    }

    /** 单个玩家的镜像客户端状态。 */
    private static final class PlayerClient {
        final UUID mcUuid;
        final String mcName;
        volatile Ts3Identity identity;
        volatile Ts3VoiceClient client;
        volatile String tsUid;
        volatile boolean online = true;
        volatile Thread thread;
        volatile Thread outboundThread;
        final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
        final AtomicLong outboundDropped = new AtomicLong(0);

        PlayerClient(UUID mcUuid, String mcName) {
            this.mcUuid = mcUuid;
            this.mcName = mcName;
        }
    }

    /** 供 status 命令展示的条目快照。 */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (PlayerClient pc : players.values()) {
            Ts3VoiceClient c = pc.client;
            out.add(pc.mcName + (c != null && c.isConnected() ? " (clid=" + c.getMyClid() + ")" : " (offline)"));
        }
        return out;
    }
}
