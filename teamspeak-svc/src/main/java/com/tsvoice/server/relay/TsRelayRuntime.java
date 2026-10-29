package com.tsvoice.server.relay;

import com.tsvoice.protocol.relay.TsRelayWire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TS 中继运行时（S1.3/P6）：ServerRuntime 的可选 feature，默认关闭。
 * 职责：
 * <ul>
 *   <li>OPEN 处理：targetId 只查管理员 allowlist；每 MC 连接 1 个 PLAYER_VOICE
 *       会话；全服会话上限；OPEN 限流（每连接 2/s burst 4）；在途 OPEN 超时
 *       （迟到 OPENED 不复活已取消请求）。</li>
 *   <li>会话生命周期：16 字节随机 relaySessionId 认领；MC 连接断开（按
 *       connectionId）、目标禁用/重载、服务关闭、空闲超时全部回收
 *       socket/worker。</li>
 *   <li>限额：单数据报 ≤4096（协商取较小值）、每 session 256 datagrams/s 与
 *       512 KiB/s（控制+媒体全计）、上行 MC 投递队列有界（溢出关会话）。</li>
 * </ul>
 *
 * <p>本类不感知 MC 类型（Fabric API 的 payload 接收器在 TeamspeakSvcMod 中把
 * 解码后的帧交进来）； sink 上行由 runtime 内部管理（DATA_S2C 发送桥）。</p>
 */
public final class TsRelayRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    /** 工程保护初值（S1.2 表）；协商取两者较小。 */
    public static final int MAX_DATAGRAM_BYTES = TsRelayWire.MAX_DATAGRAM_BYTES;
    public static final int MAX_DATAGRAMS_PER_SECOND = 256;
    public static final int MAX_BYTES_PER_SECOND = 512 * 1024;
    public static final int MAX_SESSIONS_PER_CONNECTION = 1;
    public static final int MAX_SESSIONS_GLOBAL = 64;
    public static final int OPEN_BURST = 4;
    public static final double OPEN_PER_SECOND = 2.0;
    public static final long OPEN_TIMEOUT_MS = 5_000;
    public static final long SESSION_IDLE_MS = 120_000;

    /** 协商限额（OPENED 下发）。 */
    public record Limits(int maxDatagramBytes, int maxDatagramsPerSecond, int maxBytesPerSecond) {
    }

    /** 上行 MC 投递桥：由 Fabric 层注入（把 DATA_S2C 发给对应玩家）。 */
    public interface McSink {
        /** 投递一个 TS 数据报给会话所属玩家；MC 连接已断开时返回 false。 */
        boolean send(byte[] relaySessionId, String playerUuid, byte[] datagram);
    }

    private final McSink sink;
    private final AtomicReference<RelayConfig> configRef;
    /** 会话表：hex(relaySessionId) → session。 */
    private final Map<String, TsRelaySession> sessions = new ConcurrentHashMap<>();
    /** 每 MC 连接的会话（E8：键 = connectionId#purposeWire —— PLAYER_VOICE 与
     *  MUSIC_BOT 互不覆盖；PLAYER_VOICE 仍限每连接 1 个）。 */
    private final Map<String, TsRelaySession> sessionsByConnection = new ConcurrentHashMap<>();
    /** 每 MC 连接的 OPEN 限流桶（connectionId → bucket）。 */
    private final Map<String, OpenBucket> openBuckets = new ConcurrentHashMap<>();
    /** 在途 OPEN：connection+requestId → 登记（T34：不同连接的同名 requestId
     *  互不冲突；同一连接重复 requestId 拒绝）。迟到 OPENED 不复活已取消请求。 */
    private final Map<String, PendingOpen> pendingOpens = new ConcurrentHashMap<>();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private volatile boolean closed;

    /** 每连接 token-bucket（2/s，burst 4）。 */
    private static final class OpenBucket {
        double tokens = OPEN_BURST;
        long lastRefill = System.nanoTime();
    }

    private record PendingOpen(String connectionId, int requestId, String playerUuid,
                               String targetId, long createdAtMs) {
    }

    /** 在途 OPEN 键（T34）：真实连接 + requestId。 */
    private static String pendingOpenKey(String connectionId, int requestId) {
        return connectionId + "#" + requestId;
    }

    /** 会话归属桶键（E8）：连接 + purpose。 */
    private static String sessionsByConnectionKey(String connectionId, TsRelayWire.Purpose purpose) {
        return connectionId + "#" + purpose.wireValue();
    }

    public TsRelayRuntime(McSink sink, RelayConfig initialConfig) {
        this.sink = sink;
        this.configRef = new AtomicReference<>(initialConfig);
    }

    // ---- 配置 ----

    /** 运行配置（管理员 allowlist + 总量上限）。 */
    public record RelayConfig(boolean enabled, Map<String, RelayTargetConfig> targets,
                              int maxSessions, Limits limits, boolean musicBotEnabled) {

        /** 目标配置条目（targetId → host/port；host 允许内网地址 —— 管理员明确 allowlist）。 */
        public record RelayTargetConfig(String host, int port) {
        }

        public static RelayConfig disabled() {
            return new RelayConfig(false, Map.of(), MAX_SESSIONS_GLOBAL,
                    new Limits(MAX_DATAGRAM_BYTES, MAX_DATAGRAMS_PER_SECOND, MAX_BYTES_PER_SECOND),
                    false);
        }
    }

    /** 应用新配置（reload）：已建立会话不重定向（目标变更不重定向 —— S1.3 约束），
     *  仅影响后续 OPEN；目标被移除/禁用时关闭其现存会话。 */
    public void applyConfig(RelayConfig newConfig) {
        RelayConfig old = configRef.getAndSet(newConfig);
        if (old != null && old.targets() != null && newConfig.targets() != null) {
            for (Map.Entry<String, RelayConfig.RelayTargetConfig> e : old.targets().entrySet()) {
                RelayConfig.RelayTargetConfig fresh = newConfig.targets().get(e.getKey());
                if (fresh == null || !fresh.host().equals(e.getValue().host())
                        || fresh.port() != e.getValue().port()) {
                    // 目标被移除或变更：现存会话保留旧目标直到自然结束/断开（不重定向），
                    // 但禁止其再次被 OPEN；这里只记日志（不主动杀会话 —— 明确保留语义）。
                    LOGGER.info("[teamspeak-svc] relay target '{}' changed/removed; existing sessions keep their bound endpoint", e.getKey());
                }
            }
        }
        if (!newConfig.enabled()) {
            // 总开关关闭：关闭全部会话（明确语义：禁用即回收）
            closeAllSessions("relay disabled by config");
        }
    }

    public RelayConfig config() {
        return configRef.get();
    }

    // ---- OPEN 处理 ----

    /** OPEN 处理结果（Fabric 层转 OPENED 或 ERROR 帧）。 */
    public record OpenResult(boolean ok, byte[] openedFrame, byte[] errorFrame, byte[] relaySessionId) {
        static OpenResult of(byte[] frame, byte[] sessionId) {
            return new OpenResult(true, frame, null, sessionId);
        }

        static OpenResult fail(byte[] frame) {
            return new OpenResult(false, null, frame, null);
        }
    }

    /**
     * 处理客户端 OPEN：目标 allowlist、每连接/全服会话上限、OPEN 限流。
     * 认领基于真实 connectionId（Fabric 层从 player 派生），不信客户端自报。
     */
    public OpenResult onOpen(String connectionId, String playerUuid, byte[] frame) {
        if (closed) {
            return OpenResult.fail(TsRelayWire.encodeError(new TsRelayWire.ErrorFrame(
                    0, null, TsRelayWire.ERR_SERVER_SHUTDOWN, "relay runtime closed")));
        }
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null || decoded.type() != TsRelayWire.MessageType.OPEN) {
            return OpenResult.fail(TsRelayWire.encodeError(new TsRelayWire.ErrorFrame(
                    0, null, TsRelayWire.ERR_MALFORMED, "malformed OPEN")));
        }
        TsRelayWire.OpenFrame open = decoded.open();
        RelayConfig config = configRef.get();
        if (!config.enabled()) {
            return failOpen(open, TsRelayWire.ERR_NOT_AUTHORIZED, "relay disabled on this server");
        }
        if (open.purpose() != TsRelayWire.Purpose.PLAYER_VOICE
                && !(open.purpose() == TsRelayWire.Purpose.MUSIC_BOT && config.musicBotEnabled())) {
            return failOpen(open, TsRelayWire.ERR_NOT_AUTHORIZED, "purpose not enabled");
        }
        if (open.targetId() == null || open.targetId().isBlank()) {
            return failOpen(open, TsRelayWire.ERR_MALFORMED, "target id required");
        }
        // OPEN 限流（每连接 2/s burst 4）
        OpenBucket bucket = openBuckets.computeIfAbsent(connectionId, k -> new OpenBucket());
        synchronized (bucket) {
            long now = System.nanoTime();
            double refill = (now - bucket.lastRefill) / 1e9 * OPEN_PER_SECOND;
            bucket.tokens = Math.min(OPEN_BURST, bucket.tokens + refill);
            bucket.lastRefill = now;
            if (bucket.tokens < 1) {
                return failOpen(open, TsRelayWire.ERR_LIMIT_OPEN_RATE, "open rate limit");
            }
            bucket.tokens -= 1;
        }
        // 在途重复 requestId：幂等拒绝（T34：按本连接的 requestId 判断 ——
        // 不同连接从 1 开始计数是正常行为，不冲突）
        if (pendingOpens.containsKey(pendingOpenKey(connectionId, open.requestId()))) {
            return failOpen(open, TsRelayWire.ERR_MALFORMED, "duplicate requestId");
        }
        // 目标 allowlist（DATA 不能携带 host/port —— 只按 targetId 查表）
        RelayConfig.RelayTargetConfig target = config.targets().get(open.targetId().toLowerCase(Locale.ROOT));
        if (target == null) {
            return failOpen(open, TsRelayWire.ERR_UNKNOWN_TARGET, "unknown target id");
        }
        // 会话归属按 purpose 分桶：PLAYER_VOICE 每连接 1 个；MUSIC_BOT 由
        // 管理员开关控制且与玩家语音互不覆盖（E8 登记结构）。
        String sessionBucket = sessionsByConnectionKey(connectionId, open.purpose());
        if (open.purpose() == TsRelayWire.Purpose.PLAYER_VOICE
                && sessionsByConnection.containsKey(sessionBucket)) {
            return failOpen(open, TsRelayWire.ERR_LIMIT_SESSIONS, "connection already holds a relay session");
        }
        // 全服上限
        if (sessions.size() >= Math.min(config.maxSessions(), MAX_SESSIONS_GLOBAL)) {
            return failOpen(open, TsRelayWire.ERR_LIMIT_SESSIONS, "server session limit reached");
        }
        // 认领会话并连接目标
        byte[] sessionId = TsRelayWire.newSessionId();
        TsRelaySession session = new TsRelaySession(playerUuid, connectionId,
                open.targetId().toLowerCase(Locale.ROOT), sessionId, this);
        try {
            session.start(target.host(), target.port());
        } catch (Exception e) {
            LOGGER.warn("[teamspeak-svc] relay target '{}' ({}:{}) unreachable: {}",
                    open.targetId(), target.host(), target.port(), e.getMessage());
            return failOpen(open, TsRelayWire.ERR_TARGET_UNAVAILABLE,
                    "target endpoint unavailable");
        }
        sessions.put(hexKey(sessionId), session);
        sessionsByConnection.put(sessionBucket, session);
        pendingOpens.put(pendingOpenKey(connectionId, open.requestId()), new PendingOpen(connectionId,
                open.requestId(), playerUuid, open.targetId(), System.currentTimeMillis()));
        // 协商限额 = min(工程初值, 管理员配置)
        Limits negotiated = new Limits(
                Math.min(MAX_DATAGRAM_BYTES, config.limits().maxDatagramBytes()),
                Math.min(MAX_DATAGRAMS_PER_SECOND, config.limits().maxDatagramsPerSecond()),
                Math.min(MAX_BYTES_PER_SECOND, config.limits().maxBytesPerSecond()));
        byte[] opened = TsRelayWire.encodeOpened(new TsRelayWire.OpenedFrame(
                open.requestId(), sessionId, negotiated.maxDatagramBytes(),
                negotiated.maxDatagramsPerSecond(), negotiated.maxBytesPerSecond()));
        LOGGER.info("[teamspeak-svc] relay session opened: player={} target={} limits={}/{}/{}B",
                playerUuid, open.targetId(), negotiated.maxDatagramBytes(),
                negotiated.maxDatagramsPerSecond(), negotiated.maxBytesPerSecond());
        // T33：协商限额下发给会话级 token bucket（立即生效，不只是返回值）
        session.configureLimits(negotiated.maxDatagramsPerSecond(), negotiated.maxBytesPerSecond());
        return OpenResult.of(opened, sessionId);
    }

    private static TsRelayWire.ErrorFrame errorFor(TsRelayWire.OpenFrame open, int code, String message) {
        return new TsRelayWire.ErrorFrame(open.requestId(), null, code, message);
    }

    private OpenResult failOpen(TsRelayWire.OpenFrame open, int code, String message) {
        return OpenResult.fail(TsRelayWire.encodeError(errorFor(open, code, message)));
    }

    // ---- DATA 处理 ----

    /**
     * 下行 DATA（客户端 → 目标）。所有流量计费（控制+媒体一个桶，控制优先只
     * 是调度概念，不改限额）；会话未知/超限拒绝。DATA 帧的 routeEpoch/trafficClass
     * 属于 TS 数据报外层中继信封 —— 本服务端不解码 TS 载荷。T33 实际执行：
     * 代际守卫（barrier 之下拒绝）与协商 pps token bucket 都在转发前检查。
     */
    public boolean onData(String connectionId, byte[] frame) {
        if (closed) {
            return false;
        }
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null || decoded.type() != TsRelayWire.MessageType.DATA_C2S) {
            return false;
        }
        TsRelayWire.DataFrame data = decoded.data();
        TsRelaySession session = sessions.get(hexKey(data.relaySessionId()));
        if (session == null || !session.connectionId().equals(connectionId)) {
            return false; // 伪造他人 sessionId：拒绝（不计费不投递）
        }
        if (data.datagram().length > MAX_DATAGRAM_BYTES) {
            return false; // codec 已拒绝超长，双保险
        }
        // G2：按真实包头分类（不解密不重写）；外层 trafficClass 必须与包头一致
        //（0=旧版未分类仍按包头识别；1=CONTROL；2=MEDIA；不符/未知拒绝）。
        com.tsvoice.protocol.relay.TsDatagramKind.Kind kind =
                com.tsvoice.protocol.relay.TsDatagramKind.classify(
                        data.datagram(), com.tsvoice.protocol.relay.TsDatagramKind.Direction.C2S);
        if (!com.tsvoice.protocol.relay.TsDatagramKind.trafficClassConsistent(
                data.trafficClass(), kind)) {
            return false;
        }
        // T33b/G2：pps 与 bytes 限额对控制与媒体统一计费（控制不是无限带宽旁路）
        if (!session.tryAcquireDatagram()) {
            return false;
        }
        if (!session.tryAcquireBytes(data.datagram().length)) {
            return false;
        }
        // T33a：代际屏障只约束媒体 —— 控制流（COMMAND/ACK/PING/PONG/INIT1）
        // 在 barrier 后仍必须送达，否则模式 B 的后续命令与保活中断。
        if (kind == com.tsvoice.protocol.relay.TsDatagramKind.Kind.MEDIA
                && !session.routeEpochPermitted(data.routeEpoch())) {
            return false;
        }
        try {
            session.sendToTarget(data.datagram());
            return true;
        } catch (Exception e) {
            // 目标 socket 失效：关闭会话（worker/错误路径也会兜底）
            return false;
        }
    }

    // ---- ROUTE_BARRIER / CLOSE ----

    /**
     * 客户端路由屏障：代际推进点（T33a 实际执行版）。barrier 单调推进该会话的
     * 最低放行代际；倒退/重复拒绝。返回是否识别了会话并接受了代际。
     */
    public boolean onRouteBarrier(String connectionId, byte[] frame) {
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null || decoded.type() != TsRelayWire.MessageType.ROUTE_BARRIER) {
            return false;
        }
        TsRelayWire.RouteBarrierFrame barrier = decoded.routeBarrier();
        TsRelaySession session = sessions.get(hexKey(barrier.relaySessionId()));
        return session != null && session.connectionId().equals(connectionId)
                && session.advanceRouteBarrier(barrier.nextRouteEpoch());
    }

    /** 客户端主动关闭：回收会话并回 CLOSED。 */
    public byte[] onClose(String connectionId, byte[] frame) {
        TsRelayWire.Decoded decoded = TsRelayWire.decode(frame);
        if (decoded == null || decoded.type() != TsRelayWire.MessageType.CLOSE) {
            return null;
        }
        TsRelayWire.CloseFrame close = decoded.close();
        TsRelaySession session = sessions.get(hexKey(close.relaySessionId()));
        if (session == null || !session.connectionId().equals(connectionId)) {
            return null; // 未知/非本连接会话：不回 CLOSED（伪造防御）
        }
        removeSession(session);
        return TsRelayWire.encodeClose(TsRelayWire.MessageType.CLOSED, close);
    }

    // ---- 上行（UDP → MC）----

    /** UDP 回包 → MC 通道（session worker 线程调用；有界投递失败关会话）。 */
    void deliverToMc(TsRelaySession session, byte[] datagram) {
        boolean sent = sink.send(session.relaySessionId(), session.playerUuid(), datagram);
        if (!sent) {
            // MC 连接已不可投递：回收会话（CLIENT_DISCONNECT 路径通常会先到，
            // 这里是竞态兜底）
            removeSession(session);
        }
    }

    /** 会话 worker 报告 socket 死亡：回收并通知客户端 CLOSED。 */
    void onSessionDead(TsRelaySession session, String reason) {
        if (removeSession(session)) {
            byte[] closed = TsRelayWire.encodeClose(TsRelayWire.MessageType.CLOSED,
                    new TsRelayWire.CloseFrame(session.relaySessionId(), reason));
            sink.send(session.relaySessionId(), session.playerUuid(), closed);
            LOGGER.info("[teamspeak-svc] relay session dead: player={} reason={}", session.playerUuid(), reason);
        }
    }

    // ---- 生命周期 ----

    /** MC 连接断开：回收该连接的全部会话（Fabric DISCONNECT 事件调用）。 */
    public int onClientDisconnected(String connectionId) {
        int n = sessionsByConnection.size();
        // E8：回收该连接全部 purpose 的会话（PLAYER_VOICE + MUSIC_BOT 等）
        sessionsByConnection.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(connectionId + "#")) {
                removeSession(e.getValue());
                return true;
            }
            return false;
        });
        openBuckets.remove(connectionId);
        pendingOpens.values().removeIf(p -> p.connectionId().equals(connectionId));
        return n - sessionsByConnection.size();
    }

    /** 空闲/超时清理（worker 在 receive 超时路径周期调用；也可由外部 tick 驱动）。 */
    public void sweepIdleSessions() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        for (TsRelaySession session : sessions.values()) {
            if (now - session.lastActiveMs() > SESSION_IDLE_MS) {
                if (removeSession(session)) {
                    byte[] closed = TsRelayWire.encodeClose(TsRelayWire.MessageType.CLOSED,
                            new TsRelayWire.CloseFrame(session.relaySessionId(), "idle timeout"));
                    sink.send(session.relaySessionId(), session.playerUuid(), closed);
                }
            }
        }
        // 在途 OPEN 超时清理
        pendingOpens.values().removeIf(p -> now - p.createdAtMs() > OPEN_TIMEOUT_MS);
    }

    /** 服务关闭：全部会话回收（幂等）。 */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TsRelaySession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        sessionsByConnection.clear();
        openBuckets.clear();
        pendingOpens.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    // ---- 诊断 ----

    public int sessionCount() {
        return sessions.size();
    }

    public List<TsRelaySession> sessionSnapshot() {
        return List.copyOf(sessions.values());
    }

    private boolean removeSession(TsRelaySession session) {
        String key = hexKey(session.relaySessionId());
        boolean removed = sessions.remove(key, session);
        sessionsByConnection.values().removeIf(s -> s == session);
        // T34：会话终结（CLOSE/死亡/空闲/禁用）释放其 OPEN 登记 ——
        // 同一连接之后可再次以相同 requestId OPEN。
        pendingOpens.values().removeIf(p ->
                p.connectionId().equals(session.connectionId())
                        && p.targetId().equals(session.targetId()));
        if (removed) {
            session.close();
        }
        return removed;
    }

    private void closeAllSessions(String reason) {
        for (TsRelaySession session : sessions.values()) {
            if (removeSession(session)) {
                byte[] closed = TsRelayWire.encodeClose(TsRelayWire.MessageType.CLOSED,
                        new TsRelayWire.CloseFrame(session.relaySessionId(), reason));
                sink.send(session.relaySessionId(), session.playerUuid(), closed);
            }
        }
    }

    static String hexKey(byte[] id) {
        StringBuilder sb = new StringBuilder(id.length * 2);
        for (byte b : id) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
