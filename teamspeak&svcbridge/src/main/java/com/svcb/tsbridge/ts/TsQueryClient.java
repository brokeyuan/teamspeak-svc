package com.svcb.tsbridge.ts;

import com.github.theholywaffle.teamspeak3.TS3Api;
import com.github.theholywaffle.teamspeak3.TS3Config;
import com.github.theholywaffle.teamspeak3.TS3Query;
import com.github.theholywaffle.teamspeak3.api.event.TS3EventType;
import com.github.theholywaffle.teamspeak3.api.event.TS3Listener;
import com.github.theholywaffle.teamspeak3.api.reconnect.ConnectionHandler;
import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.config.BridgeConfig;

import java.io.IOException;

/**
 * TeamSpeak ServerQuery 客户端封装。
 *
 * <p>管理 {@link TS3Query} + {@link TS3Api} 的连接生命周期：
 * 登录、选择虚拟服务器、设置昵称，并提供断线自动重连（指数退避）。</p>
 *
 * <p>线程安全：{@link #connect()}、{@link #disconnect()}、{@link #isConnected()}
 * 均为 synchronized；{@link #getApi()} 返回 volatile 字段，调用方需自行判空。</p>
 */
public class TsQueryClient {

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final String QUERY_NICKNAME = "TsBridge-Query";

    private final BridgeConfig config;

    private volatile TS3Query query;
    private volatile TS3Api api;
    private volatile boolean connected;
    private volatile String virtualServerUid;
    private Thread reconnectThread;
    /** 重连成功后的恢复回调（由 TsBridgeMod 设置，用于重建频道/监听器等）。 */
    private volatile Runnable onReconnected;

    public TsQueryClient(BridgeConfig config) {
        this.config = config;
    }

    /**
     * 设置重连成功回调。在重连线程成功恢复连接后调用一次。
     */
    public void setOnReconnected(Runnable callback) {
        this.onReconnected = callback;
    }

    /**
     * 建立 ServerQuery 连接。
     *
     * <p>流程：创建 {@link TS3Config} → 连接 → login → selectVirtualServerByPort → setNickname。
     * 已连接时直接返回（no-op）。配置错误抛 {@link IllegalStateException}，
     * 连接 / 登录失败抛 {@link IOException}，失败时自动调用 {@link #disconnect()} 清理资源。</p>
     */
    public synchronized void connect() throws IOException {
        if (connected) {
            return;
        }
        // 清理上次连接残留（如断线后遗留的 query 对象）
        disconnect();

        // 配置校验
        if (config.tsHost == null || config.tsHost.isEmpty()) {
            throw new IllegalStateException("ServerQuery host is empty - please configure config/tsbridge.json");
        }
        if (config.queryPassword == null || config.queryPassword.isEmpty()) {
            throw new IllegalStateException("ServerQuery password is empty - please configure config/tsbridge.json");
        }

        TS3Query newQuery = null;
        boolean committed = false;
        try {
            TS3Config tsConfig = new TS3Config();
            tsConfig.setHost(config.tsHost);
            tsConfig.setQueryPort(config.tsQueryPort);
            // The current query library logs complete commands, including login credentials.
            tsConfig.setEnableCommunicationsLogging(false);
            tsConfig.setConnectionHandler(new TsConnectionHandler());

            newQuery = new TS3Query(tsConfig);
            newQuery.connect();
            TS3Api newApi = newQuery.getApi();
            newApi.login(config.queryUsername, config.queryPassword);
            newApi.selectVirtualServerByPort(config.tsServerPort);
            newApi.setNickname(QUERY_NICKNAME);
            this.virtualServerUid = newApi.getServerInfo().getUniqueIdentifier();

            this.query = newQuery;
            this.api = newApi;
            this.connected = true;
            committed = true;
            TsBridgeMod.LOGGER.info("[tsbridge] Connected to TeamSpeak ServerQuery at {}:{} (virtual server port {})",
                    config.tsHost, config.tsQueryPort, config.tsServerPort);
        } catch (Exception e) {
            throw new IOException("Failed to connect to TeamSpeak ServerQuery at "
                    + config.tsHost + ":" + config.tsQueryPort + ": " + e.getMessage(), e);
        } finally {
            // 登录/选服失败时 newQuery 尚未提交给字段，disconnect() 无法回收它。
            if (!committed && newQuery != null) {
                try {
                    newQuery.exit();
                } catch (Exception e) {
                    TsBridgeMod.LOGGER.debug("[tsbridge] Failed to close uncommitted ServerQuery: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 断开连接并释放资源。可重复调用，每次调用都是安全的。
     *
     * <p>标记 connected=false，调用 {@link TS3Query#exit()}（try/catch 包裹），
     * 置空 query/api 引用。</p>
     */
    public synchronized void disconnect() {
        connected = false;
        virtualServerUid = null;
        TS3Query q = query;
        query = null;
        api = null;
        if (q != null) {
            try {
                q.exit();
            } catch (Exception e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Error while disconnecting ServerQuery: {}", e.getMessage());
            }
        }
    }

    /**
     * 返回当前是否处于已连接状态。
     */
    public synchronized boolean isConnected() {
        return connected;
    }

    /**
     * 返回当前 {@link TS3Api} 实例，可能为 null（未连接或已断开）。
     * 调用方需自行判空与异常处理。
     */
    public String getVirtualServerUid() {
        return virtualServerUid;
    }

    public TS3Api getApi() {
        return api;
    }

    /**
     * 启动断线重连守护线程 "TsBridge-Reconnect"。
     *
     * <p>当 {@code connected=false} 时按指数退避（1s → 2s → 4s → ... 最大 60s）
     * 尝试 {@link #connect()}；成功后重置退避；失败记录 WARN 日志并继续重试；
     * 线程被 interrupt 时优雅退出。已存在存活重连线程时为 no-op。</p>
     */
    public void startReconnectLoop() {
        if (reconnectThread != null && reconnectThread.isAlive()) {
            return;
        }
        reconnectThread = new Thread(() -> {
            long backoffMs = INITIAL_BACKOFF_MS;
            while (!Thread.currentThread().isInterrupted()) {
                if (connected) {
                    try {
                        Thread.sleep(INITIAL_BACKOFF_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                try {
                    connect();
                    backoffMs = INITIAL_BACKOFF_MS;
                    TsBridgeMod.LOGGER.info("[tsbridge] ServerQuery reconnected successfully");
                    // 通知上层恢复依赖 ServerQuery 的组件（频道/监听器/存在感同步等）
                    Runnable callback = onReconnected;
                    if (callback != null) {
                        try {
                            callback.run();
                        } catch (Exception ce) {
                            TsBridgeMod.LOGGER.error("[tsbridge] Reconnect recovery callback failed: {}", ce.getMessage(), ce);
                        }
                    }
                } catch (Exception e) {
                    TsBridgeMod.LOGGER.warn("[tsbridge] ServerQuery reconnect failed: {}. Next retry in {}ms.",
                            e.getMessage(), backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                }
            }
            TsBridgeMod.LOGGER.info("[tsbridge] ServerQuery reconnect loop terminated");
        }, "TsBridge-Reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * 停止重连线程（interrupt）。不会主动断开当前连接。
     */
    public void stopReconnectLoop() {
        Thread t = reconnectThread;
        if (t != null) {
            t.interrupt();
            reconnectThread = null;
        }
    }

    /**
     * 注册事件监听器（{@link TS3EventType#SERVER}（客户端加入/离开/移动）
     * 与 {@link TS3EventType#TEXT_SERVER} / {@link TS3EventType#TEXT_CHANNEL} /
     * {@link TS3EventType#TEXT_PRIVATE}（文本消息与 !pair 命令））。
     *
     * <p>注意：TS3 服务器只推送已注册的事件类型 —— 缺少 SERVER 时
     * {@code notifycliententerview/leftview/moved} 完全不会到达，
     * Ghost Player 的事件驱动生命周期随之失效（只剩周期对账）。</p>
     *
     * <p>同时调用 {@link TS3Api#registerEvents(TS3EventType...)} 与
     * {@link TS3Api#addTS3Listeners(TS3Listener...)}。失败（未连接或 API 异常）返回 false。</p>
     */
    public boolean registerEventListener(TS3Listener listener) {
        TS3Api currentApi = api;
        if (currentApi == null || listener == null) {
            return false;
        }
        try {
            currentApi.registerEvents(TS3EventType.SERVER,
                    TS3EventType.TEXT_SERVER, TS3EventType.TEXT_CHANNEL, TS3EventType.TEXT_PRIVATE);
            currentApi.addTS3Listeners(listener);
            return true;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.error("[tsbridge] Failed to register TS3 event listener: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 库内置 ConnectionHandler：仅用于在连接断开时把 {@code connected} 置 false，
     * 触发 {@link #startReconnectLoop()} 的重连逻辑。
     */
    private final class TsConnectionHandler implements ConnectionHandler {
        @Override
        public void onConnect(TS3Query ts3Query) {
            // 连接成功由 connect() 方法处理
        }

        @Override
        public void onDisconnect(TS3Query ts3Query) {
            // 失败或已被替换连接的迟到回调不能使新连接离线。
            if (query == ts3Query) {
                TsBridgeMod.LOGGER.warn("[tsbridge] TeamSpeak ServerQuery connection lost, will attempt to reconnect");
                connected = false;
            }
        }
    }
}
