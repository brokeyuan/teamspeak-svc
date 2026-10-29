package com.tsvoice.client.ts3voice;

import com.tsvoice.client.Ts3FileTransferBridge;

import java.net.InetAddress;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小可用的 TS3 全功能语音客户端（源：TSLib TsFullClient 的核心流程移植）。
 *
 * <p>连接流程：init1 握手 → initiv 加密协商（旧/新协议）→ clientinit → 等待
 * 自身 cliententerview → 已连接。之后可收发语音包（OpusVoice/OpusMusic 编解码
 * 由调用方处理，协议层只搬运 Opus 载荷）与执行命令（clientmove / clientdisconnect 等）。</p>
 */
public final class Ts3VoiceClient {

    public static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("teamspeak-svc");

    /** TS3 Opus 编解码器 id。 */
    public static final int CODEC_OPUS_VOICE = 4;
    /** Opus Music（立体声/音乐模式）：入站放行，其声道/帧长兼容性由使用方实测。 */
    public static final int CODEC_OPUS_MUSIC = 5;

    /** 客户端版本常量（源：TSLib TsVersion.gen.cs VER_WIN_3_5_3）。 */
    private static final String CLIENT_VERSION = "3.5.3 [Build: 1587971024]";
    private static final String CLIENT_PLATFORM = "Windows";
    private static final String CLIENT_VERSION_SIGN =
            "Kvmj7qX6wJCPI5GVT71samfmhz/bvs7M+OTXWB/JWxdQbxDe17xda7dzUWLX7pjvdJTqZmbse1HBmTxThPKvAg==";

    /** 入站语音帧：发送者 clid、编解码器、TS 侧语音序号、opus 载荷。 */
    public record TsVoiceIn(int senderClid, int codec, int sequence, byte[] opus) {
    }

    private final Ts3Crypt crypt;
    private final Ts3Connection connection;
    private final String nickname;
    /** TCP 隧道传输（UDP 受限网络兜底）。 */
    private final boolean tcpTransport;
    /** 服务器/默认频道密码（clientinit client_server_password；进加密频道另用 cpw）。 */
    private volatile String serverPassword = "";
    private final String defaultChannel;
    private final AtomicReference<Consumer<TsVoiceIn>> voiceListener = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicReference<String> failReason = new AtomicReference<>();
    /** S0.7/R26：最近一次结构化协议拒绝（连接建立期间由服务器 error 行触发）。 */
    private final AtomicReference<TsProtocolException> protocolError = new AtomicReference<>();
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final AtomicInteger voiceCounter = new AtomicInteger(0);
    /** 已告警过的不支持入站 codec（每 codec 告警一次）。 */
    private final java.util.Set<Integer> unsupportedCodecs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Runnable onDisconnectedExternal;

    /** clid → 该客户端信息（uid/nick），来自 cliententerview / clientlist。 */
    private final Map<Integer, ClientEntry> clients = new ConcurrentHashMap<>();
    /** clid → 当前频道 cid（cliententerview ctid / notifyclientmoved 更新）。 */
    private final Map<Integer, Integer> channelByClid = new ConcurrentHashMap<>();
    /** clid → 该 TS 客户端广播的真实 MC UUID（client_description "tsvc-uuid:<uuid>"）。 */
    private final Map<Integer, java.util.UUID> mcUuidByClid = new ConcurrentHashMap<>();
    /** channelcreate 应答闭环：刚创建的频道名/父频道（收到裸 cid= 应答时补表）。 */
    private volatile String pendingCreateName;
    private volatile Integer pendingCreateParent;
    /** 命令关联器（P3/T05）：error 行分流到 return_code 等待方；无关联则走 handleError。 */
    volatile TsCommandTracker commandTracker;

    /** 本会话的命令关联器（异步命令确认入口；调用线程不得同步等待其 Future）。 */
    public TsCommandTracker commandTracker() {
        return commandTracker;
    }
    /** 频道快照（P3）：主键 cid 的完整频道视图；与 channels 名称映射并存。 */
    private final TsChannelSnapshot channelSnapshot = new TsChannelSnapshot(null);
    /** 名称只作属性：不同父频道允许重名，cid 才是频道身份。 */
    private final Map<Integer, ChannelEntry> channels = new ConcurrentHashMap<>();
    private record ChannelEntry(String name, int parentId) {
    }
    /** 我方 uid → clid。 */
    private volatile int myClid = -1;
    /** 我方当前所在频道（cliententerview / notifyclientmoved 维护）。 */
    private volatile int myChannelId = -1;
    /** 频道成员变化回调（任一客户端移动/进出时触发，参数=变化的 clid）。 */
    private volatile java.util.function.IntConsumer channelMembershipListener;
    private final String myUid;
    private volatile String virtualServerUid;

    /** 频道页用户列表条目（uid + 昵称；仅展示用途）。 */
    public record ClientEntry(String uniqueId, String nickname) {
    }

    /**
     * @param identity       已完成 PoW 的身份
     * @param nickname       TS 昵称
     * @param defaultChannel 进入的默认频道名（可为 null 走服务器默认频道）
     */
    public Ts3VoiceClient(Ts3Identity identity, String nickname, String defaultChannel) {
        this(identity, nickname, defaultChannel, false);
    }

    public Ts3VoiceClient(Ts3Identity identity, String nickname, String defaultChannel, boolean tcp) {
        this(identity, nickname, defaultChannel,
                tcp ? new Ts3Connection(new Ts3Crypt(identity), true)
                        : new Ts3Connection(new Ts3Crypt(identity), false));
    }

    /**
     * 传输注入入口（S1.1/P6）：调用方提供已绑定 {@link TsTransport} 的
     * {@link Ts3Connection}（例如 MC 中继路径构造的连接）。握手/加密/序号
     * 实现完全复用既有 Ts3Connection 路径 —— 本类不感知传输差异，也不在这里
     * 做 DNS 解析（MC 通道的 endpoint 解析在服务端）。
     */
    public Ts3VoiceClient(Ts3Identity identity, String nickname, String defaultChannel,
                          Ts3Connection injectedConnection) {
        this.crypt = injectedConnection.crypt();
        this.tcpTransport = false; // 传输种类由注入对象决定；cookie 逻辑仅原生 UDP 需要
        this.connection = injectedConnection;
        this.commandTracker = new TsCommandTracker(this);
        this.nickname = nickname;
        this.defaultChannel = defaultChannel == null ? "" : defaultChannel;
        this.myUid = identity.getClientUid();
        connection.bindClient(this);
        connection.setOnDisconnected(this::onConnectionLost);
    }

    public void setVoiceListener(Consumer<TsVoiceIn> listener) {
        voiceListener.set(listener);
    }

    /** 连接断开（超时/被踢/网络错误）后的外部回调。 */
    public void setOnDisconnected(Runnable callback) {
        onDisconnectedExternal = callback;
    }

    /** 频道文字消息回调（invokerName, message）。 */
    public interface TextListener {
        void onText(String invokerName, String message);
    }

    private volatile TextListener textListener;

    public void setTextListener(TextListener listener) {
        this.textListener = listener;
    }

    /** 发送频道文字消息（targetmode=2）。 */
    public void sendChannelText(String message) throws Ts3VoiceException {
        if (myClid < 0) {
            throw new Ts3VoiceException("not connected (no clid)");
        }
        connection.addOutgoingPacket(
                ("sendtextmessage targetmode=2 msg=" + escape(message)).getBytes(StandardCharsets.UTF_8),
                PacketType.COMMAND);
    }

    public boolean isConnected() {
        return connected.get();
    }

    /** 是否曾成功完成 clientinit（自测用）。 */
    public boolean wasEverConnected() {
        return myClid >= 0;
    }

    /** 当前客户端表快照（频道页用户列表/自测用；clid → uid+昵称）。 */
    public Map<Integer, ClientEntry> clientsSnapshot() {
        return Map.copyOf(clients);
    }

    /** 兼容别名（自测用）。 */
    public Map<Integer, ClientEntry> debugClients() {
        return clientsSnapshot();
    }

    /** 直接发送一条命令（自测/管理用）。 */
    public void sendCommand(String command) throws Ts3VoiceException {
        connection.addOutgoingPacket(command.getBytes(StandardCharsets.UTF_8), PacketType.COMMAND);
    }

    public int getMyClid() {
        return myClid;
    }

    /** 我方当前所在频道 ID（-1 = 未知；供加入频道的状态判断）。 */
    public int getMyChannelId() {
        return myChannelId;
    }

    /** 注册频道成员变化监听（Ghost 可见性同步用）。 */
    public void setChannelMembershipListener(java.util.function.IntConsumer listener) {
        this.channelMembershipListener = listener;
    }

    /** 查询某 clid 当前所在频道。 */
    public int getChannelOf(int clid) {
        return channelByClid.getOrDefault(clid, -1);
    }

    /** 按 cid 反查频道名（无则 null）。 */
    public String getChannelNameByCid(int cid) {
        ChannelEntry entry = channels.get(cid);
        return entry == null ? null : entry.name();
    }

    /** 按 TS uid 反查 clid（无则 -1）。 */
    public int getClidOfUid(String tsUniqueId) {
        for (Map.Entry<Integer, ClientEntry> e : clients.entrySet()) {
            if (e.getValue().uniqueId().equals(tsUniqueId)) {
                return e.getKey();
            }
        }
        return -1;
    }

    public String getMyUid() {
        return myUid;
    }

    public String getVirtualServerUid() {
        return virtualServerUid;
    }

    public void requestServerInfo() throws Ts3VoiceException {
        connection.addOutgoingPacket("serverinfo".getBytes(StandardCharsets.UTF_8), PacketType.COMMAND);
    }

    public long getReceivedVoiceFrames() {
        return voiceCounter.get();
    }

    /** clid → TS 唯一 ID（用于把语音帧归属到具体 TS 用户）。 */
    public String getUniqueIdOf(int clid) {
        ClientEntry e = clients.get(clid);
        return e != null ? e.uniqueId() : null;
    }

    /** 频道映射快照（诊断用）。 */
    public Map<String, Integer> debugChannels() {
        Map<String, Integer> snapshot = new java.util.LinkedHashMap<>();
        channels.forEach((cid, entry) -> snapshot.put(entry.parentId() + "/" + entry.name() + "#" + cid, cid));
        return Map.copyOf(snapshot);
    }

    /** 设置服务器密码（须在 connect 前调用）。 */
    public void setServerPassword(String password) {
        this.serverPassword = password == null ? "" : password;
    }

    /** 广播自己的 MC UUID（跨服皮肤互认：对端 mod 据此显示你的真实皮肤）。 */
    public void publishMcUuid(java.util.UUID mcUuid) throws Ts3VoiceException {
        connection.addOutgoingPacket(
                ("clientupdate client_description=tsvc-uuid:" + mcUuid).getBytes(StandardCharsets.UTF_8),
                PacketType.COMMAND);
    }

    /** 该 clid 广播的真实 MC UUID（无则 null）。 */
    public java.util.UUID getMcUuidOf(int clid) {
        return mcUuidByClid.get(clid);
    }

    /** clid → TS 昵称（Ghost 显示名用；客户端表）。 */
    public String getNicknameOf(int clid) {
        ClientEntry e = clients.get(clid);
        return e != null && e.nickname() != null && !e.nickname().isBlank() ? e.nickname() : null;
    }

    /** 是否已知该 TS uid（客户端表同步自 cliententerview / clientlist 应答）。 */
    public boolean hasUid(String tsUniqueId) {
        for (ClientEntry e : clients.values()) {
            if (e.uniqueId().equals(tsUniqueId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 连接并完成 clientinit（阻塞直到确认加入服务器或失败）。
     *
     * @param timeoutMs 超时（毫秒）
     */
    public void connect(InetAddress address, int port, long timeoutMs) throws Ts3VoiceException {
        try {
            connection.connect(address, port);
        } catch (Ts3VoiceException e) {
            throw e;
        }
        connectAfterTransport(timeoutMs);
    }

    /**
     * 经注入传输连接（S1.1/P6，MC 中继路径）：不做任何 DNS/地址解析 ——
     * 地址参数语义由注入的传输定义（MC 中继的 endpoint 解析与允许判断都在
     * 服务端）。此后的握手/加密与 {@link #connect(InetAddress, int, long)} 完全一致。
     */
    public void connect(long timeoutMs) throws Ts3VoiceException {
        try {
            connection.connect(null, 0);
        } catch (Ts3VoiceException e) {
            throw e;
        }
        connectAfterTransport(timeoutMs);
    }

    private void connectAfterTransport(long timeoutMs) throws Ts3VoiceException {
        try {
            if (!connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new Ts3VoiceException(failReason.get() != null
                        ? failReason.get()
                        : "connect timeout (no cliententerview within " + timeoutMs + "ms)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Ts3VoiceException("connect interrupted");
        }
        if (failed.get()) {
            // S0.7/R26：协议拒绝（服务器 error 行）以结构化异常抛出，保留数值 id；
            // 其余失败（超时/网络层）保持普通 Ts3VoiceException。
            TsProtocolException protocol = protocolError.get();
            if (protocol != null) {
                throw protocol;
            }
            throw new Ts3VoiceException(failReason.get() == null ? "connect failed" : failReason.get());
        }
        connected.set(true);
    }

    /** 加入指定频道（clientmove）。 */
    public void joinChannel(int cid) throws Ts3VoiceException {
        joinChannel(cid, null);
    }

    /** 加入指定频道（加密频道需提供密码 cpw；重复加入已在频道会被 770 拒绝，调用方先查 myChannelId）。 */
    public void joinChannel(int cid, String channelPassword) throws Ts3VoiceException {
        if (myClid < 0) {
            throw new Ts3VoiceException("not connected (no clid)");
        }
        connection.addOutgoingPacket(buildMoveCommand(cid, channelPassword)
                .getBytes(StandardCharsets.UTF_8), PacketType.COMMAND);
    }

    /** clientmove 命令行（含可选频道密码转义）。 */
    private String buildMoveCommand(int cid, String channelPassword) {
        String cmd = "clientmove clid=" + myClid + " cid=" + cid;
        if (channelPassword != null && !channelPassword.isBlank()) {
            cmd += " cpw=" + escape(channelPassword);
        }
        return cmd;
    }

    /**
     * 单条移动命令的最终结果（N1/D05）：成功应答只代表服务器接受了命令，
     * 不代表 self 频道已确认 —— 发送许可只能由频道确认流程发布。
     */
    public record MoveResult(boolean succeeded, Integer errorCode, String errorMessage) {
    }

    /**
     * 发起 clientmove 并经命令 tracker 关联结果（N1/D05）：返回的 Future 在服务器
     * 应答（成功/错误）、命令超时或会话取消时完成；异常保持原始类型（TimeoutException
     * / CancellationException / 发送失败）。结果按 return_code 绑定本次请求，
     * 旧请求的迟到回复不会路由到新请求。
     */
    public java.util.concurrent.CompletableFuture<MoveResult> moveChannelAsync(
            int cid, String channelPassword, long timeoutMillis) throws Ts3VoiceException {
        if (myClid < 0) {
            throw new Ts3VoiceException("not connected (no clid)");
        }
        String cmd = buildMoveCommand(cid, channelPassword);
        java.util.concurrent.CompletableFuture<MoveResult> out = new java.util.concurrent.CompletableFuture<>();
        commandTracker.executeAsync(cmd, timeoutMillis).whenComplete((result, error) -> {
            if (error != null) {
                out.completeExceptionally(error);
            } else {
                out.complete(new MoveResult(result.succeeded(), result.errorCode(), result.errorMessage()));
            }
        });
        return out;
    }

    /** 更新本客户端昵称（clientupdate，存在感同步用）。 */
    public void setNickname(String newNickname) throws Ts3VoiceException {
        if (myClid < 0) {
            throw new Ts3VoiceException("not connected (no clid)");
        }
        connection.addOutgoingPacket(
                ("clientupdate client_nickname=" + escape(newNickname)).getBytes(StandardCharsets.UTF_8),
                PacketType.COMMAND);
    }

    /** 发送一帧 Opus 语音（48kHz mono，20ms 帧）。 */
    /** 当前 TS 身份的 client UID（identity 派生）；未初始化时为 null。 */
    public String getClientUid() {
        return myUid;
    }

    public void sendVoice(byte[] opusData, int codec) throws Ts3VoiceException {
        sendVoice(opusData, codec, Long.MIN_VALUE);
    }

    /**
     * 发送一帧 Opus 语音并绑定路由代际（R08/T03）。无有效代际
     * （{@code Long.MIN_VALUE}）的帧在 writer 最终写出点被丢弃 —— fail-closed。
     */
    public void sendVoice(byte[] opusData, int codec, long routeEpoch) throws Ts3VoiceException {
        if (!connected.get() || connection.isClosed()) {
            return;
        }
        byte[] payload = new byte[opusData.length + 3];
        payload[2] = (byte) codec;
        System.arraycopy(opusData, 0, payload, 3, opusData.length);
        // data[0..2) 的 packetId 由连接层覆写
        connection.addOutgoingPacket(payload, PacketType.VOICE, routeEpoch);
    }

    /** writer 的当前有效路由代际来源（会话层接线协调器快照）。 */
    public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier) {
        connection.setVoiceRouteEpochSupplier(supplier);
    }

    /** 撤销许可后清空排队中的旧代际语音帧（writer 侧代际复核覆盖已取出帧）。 */
    public void clearQueuedVoice() {
        connection.clearQueuedVoice();
    }

    /** 断开连接（发送 clientdisconnect）。 */
    public void disconnect() {
        try {
            if (connected.get()) {
                connection.addOutgoingPacket(
                        "clientdisconnect reasonid=8 reasonmsg=bridge".getBytes(StandardCharsets.UTF_8),
                        PacketType.COMMAND);
            }
        } catch (Exception ignored) {
        }
        connected.set(false);
        // 会话关闭：立即取消全部在途命令 Future 与本 client 的 ft 登记（T05）。
        commandTracker.cancelSession();
        Ts3FileTransferBridge.cancelClient(this);
        connection.stop();
    }

    // ---- 回调（连接层调用） ----

    void onConnectionLost() {
        if (connected.getAndSet(false)) {
            LOG.warn("[teamspeak-svc] Voice connection lost");
        }
        Runnable ext = onDisconnectedExternal;
        if (ext != null) {
            try {
                ext.run();
            } catch (Exception e) {
                LOG.debug("[teamspeak-svc] onDisconnected callback threw: {}", e.getMessage());
            }
        }
        if (failed.compareAndSet(false, true)) {
            failReason.compareAndSet(null, "connection closed by peer/timeout");
        }
        connectLatch.countDown();
    }

    /**
     * 命令入口：initiv → 加密协商；其余为通知/应答，解析客户端表与错误。
     */
    void onCommand(String command, boolean lowPriority) {
        if (Ts3Connection.DEBUG) {
            System.out.println("[cmd] " + (command.length() > 1500 ? command.substring(0, 1500) + "...(len=" + command.length() + ")" : command));
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String cmdName = trimmed.split("\\s", 2)[0];
        switch (cmdName) {
            case "initiv", "initivexpand" -> handleInitIvClassic(trimmed);
            case "initivexpand2" -> handleInitIv2(trimmed);
            case "error" -> handleError(trimmed);
            case "notifycliententerview", "cliententerview", "notifyclientlist", "clientlist" ->
                    handleClientRows(trimmed);
            case "notifyclientleftview", "clientleftview" -> handleClientLeft(trimmed);
            case "notifyclientmoved", "clientmoved" -> handleClientMoved(trimmed);
            case "notifytextmessage" -> handleTextMessage(trimmed);
            case "notifychanneldeleted", "channeldeleted" -> handleChannelDeleted(trimmed);
            case "notifychannelcreated", "channelcreated", "notifychanneledited", "channeledited",
                    "notifychannelmoved", "channelmoved" -> handleChannelRows(trimmed);
            default -> {
                // 语音协议中命令应答不带命令名（如 clientlist 的回包直接以
                // "clid=.. cid=.." 开头）：含 client_unique_identifier 的行按客户端表解析。
                // 这也是 bot 重连后补齐"已在服务器上的用户"映射的关键路径。
                if (trimmed.contains("client_unique_identifier=")) {
                    handleClientRows(trimmed);
                }
                // channellist / channelcreate 应答：维护频道名 → cid 映射
                if (trimmed.contains("channel_name=") || trimmed.startsWith("cid=")) {
                    handleChannelRows(trimmed);
                }
                if (trimmed.contains("client_description=") || trimmed.startsWith("clid=")) {
                    for (String row : trimmed.split("\\|")) {
                        Map<String, String> params = parseParams(row);
                        String clidStr = params.get("clid");
                        String desc = params.get("client_description");
                        if (clidStr != null && desc != null) {
                            try {
                                int clid = Integer.parseInt(clidStr);
                                if (desc.startsWith("tsvc-uuid:")) {
                                    mcUuidByClid.put(clid, java.util.UUID.fromString(desc.substring("tsvc-uuid:".length())));
                                } else {
                                    mcUuidByClid.remove(clid);
                                }
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                }
                if (trimmed.contains("virtualserver_unique_identifier=")) {
                    for (String row : trimmed.split("\\|")) {
                        Map<String, String> params = parseParams(row);
                        String uid = params.get("virtualserver_unique_identifier");
                        if (uid != null && !uid.isBlank()) {
                            virtualServerUid = uid;
                            refreshSnapshotServerUid(uid);
                        }
                    }
                }
                if (trimmed.contains("ftkey=")) {
                    // F10：按 clientftfid 关联到发起请求；无关联则忽略（不喂给单 handler）。
                    Ts3FileTransferBridge.onFtReply(trimmed, this);
                }
                if (Ts3Connection.DEBUG) {
                    LOG.debug("[teamspeak-svc] notification: {}", trimmed.length() > 120
                            ? trimmed.substring(0, 120) + "..." : trimmed);
                }
            }
        }
    }

    /**
     * 频道数据解析：initserver / channellist / channelfind / notifychannelcreated
     * 的行（channel_name + cid + pid/cpid）→ 维护映射；仅含 cid 的行
     * （notifychanneldeleted / channelmove）→ 移除对应映射。
     */
    private void handleChannelRows(String command) {
        for (String row : command.split("\\|")) {
            Map<String, String> params = parseParams(row);
            String name = params.get("channel_name");
            String cidStr = params.get("cid");
            if (cidStr == null) {
                continue;
            }
            int cid;
            try {
                cid = Integer.parseInt(cidStr);
            } catch (NumberFormatException ignored) {
                continue;
            }
            try {
                ChannelEntry existing = channels.get(cid);
                String pidStr = params.containsKey("pid") ? params.get("pid") : params.get("cpid");
                int parent = pidStr != null ? Integer.parseInt(pidStr) : existing != null ? existing.parentId() : 0;
                if (name == null && existing != null) {
                    name = existing.name();
                }
                // 裸 cid= 是 channelcreate 的应答；移动通知不能误消费待创建名称。
                if (name == null && command.startsWith("cid=") && pendingCreateName != null) {
                    name = pendingCreateName;
                    parent = pendingCreateParent != null ? pendingCreateParent : parent;
                    pendingCreateName = null;
                    pendingCreateParent = null;
                }
                if (name != null) {
                    channels.put(cid, new ChannelEntry(name, parent));
                    // P3 频道快照：同步 upsert（password/order/clients 可选字段缺省 UNKNOWN）。
                    String passwordStr = params.get("password");
                    String orderStr = params.get("channel_order");
                    String clientsStr = params.containsKey("clients") ? params.get("clients")
                            : params.get("total_clients");
                    String maxStr = params.get("channel_maxclients");
                    channelSnapshot.upsert(new TsChannelSnapshot.Channel(cid, parent, name,
                            passwordStr != null && "1".equals(passwordStr),
                            orderStr != null ? safeParseInt(orderStr) : TsChannelSnapshot.UNKNOWN,
                            clientsStr != null ? safeParseInt(clientsStr) : TsChannelSnapshot.UNKNOWN,
                            maxStr != null ? safeParseInt(maxStr) : TsChannelSnapshot.UNKNOWN));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void handleChannelDeleted(String command) {
        for (String row : command.split("\\|")) {
            try {
                int cid = Integer.parseInt(parseParams(row).get("cid"));
                channels.remove(cid);
                channelSnapshot.remove(cid);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static int safeParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return TsChannelSnapshot.UNKNOWN;
        }
    }

    /** 频道快照（P3）：只读视图；serverUID 在握手确认后填充。 */
    public TsChannelSnapshot channelSnapshot() {
        return channelSnapshot;
    }

    /** 会话确认 serverUID 后刷新快照的命名空间。 */
    void refreshSnapshotServerUid(String serverUid) {
        if (serverUid != null && !serverUid.isBlank()) {
            channelSnapshot.refreshServerUid(serverUid);
        }
    }

    /** 请求频道列表（应答异步到达，稍后查询 {@link #getChannelIdByName}）。 */
    public void requestChannelList() throws Ts3VoiceException {
        connection.addOutgoingPacket("channellist".getBytes(StandardCharsets.UTF_8), PacketType.COMMAND);
    }

    /** 按名查询频道 ID（大小写不敏感；需先 {@link #requestChannelList()} 刷新）。 */
    public Integer getChannelIdByName(String name) {
        return findChannelId(name, null);
    }

    /**
     * 按名 + 父频道查询子频道 ID（大小写不敏感；用于群组子频道定位，
     * 避免命中其它层级下的同名频道）。
     */
    public Integer getSubChannelIdByName(String name, int parentCid) {
        return findChannelId(name, parentCid);
    }

    private Integer findChannelId(String name, Integer parent) {
        if (name == null) return null;
        Integer found = null;
        for (Map.Entry<Integer, ChannelEntry> row : channels.entrySet()) {
            ChannelEntry entry = row.getValue();
            if (entry.name().equalsIgnoreCase(name) && (parent == null || entry.parentId() == parent)) {
                if (found != null) return null; // 歧义不能靠迭代次序决定。
                found = row.getKey();
            }
        }
        return found;
    }

    /** channelfind 精准查询单个频道（应答极小，不会被压缩）。 */
    public void findChannel(String name) throws Ts3VoiceException {
        connection.addOutgoingPacket(
                ("channelfind pattern=" + escape(name)).getBytes(StandardCharsets.UTF_8),
                PacketType.COMMAND);
    }

    /**
     * 在指定父频道下创建临时子频道（最后一人离开自动删除；对应 SVC 群组的临时语义）。
     * temporary 权限（permid 86）是 TS3 普通客户端的默认授予项。
     */
    public void createChannel(String name, int parentCid) throws Ts3VoiceException {
        createChannel(name, parentCid, null);
    }

    /** 创建临时子频道（可带密码；最后一人离开自动删除 —— 与 SVC 临时群组一致）。 */
    public void createChannel(String name, int parentCid, String channelPassword) throws Ts3VoiceException {
        pendingCreateName = name;
        pendingCreateParent = parentCid;
        String cmd = "channelcreate channel_name=" + escape(name)
                + " cpid=" + parentCid
                + " channel_flag_temporary=1"
                + " channel_flag_semi_permanent=0"
                + " channel_flag_permanent=0";
        if (channelPassword != null && !channelPassword.isBlank()) {
            cmd += " channel_password=" + escape(channelPassword);
        }
        connection.addOutgoingPacket(cmd.getBytes(StandardCharsets.UTF_8), PacketType.COMMAND);
    }

    /** 语音包入口：[seq 2][clid 2][codec 1][opus...]。 */
    void onVoicePacket(Ts3Packet packet) {
        if (packet.data == null || packet.data.length < 5) {
            return; // 结束标记 / 空帧
        }
        int sequence = ((packet.data[0] & 0xFF) << 8) | (packet.data[1] & 0xFF);
        int clid = ((packet.data[2] & 0xFF) << 8) | (packet.data[3] & 0xFF);
        int codec = packet.data[4] & 0xFF;
        voiceCounter.incrementAndGet();
        // 入站边界拒绝不支持的 codec（F11）：每 codec 告警一次，丢弃该帧但
        // 不影响其他用户；Opus Music 的声道/帧长兼容性由使用方另行实测。
        if (codec != CODEC_OPUS_VOICE && codec != CODEC_OPUS_MUSIC) {
            if (unsupportedCodecs.add(codec)) {
                LOG.warn("[teamspeak-svc] dropping voice frames with unsupported codec {} (sender clid {})",
                        codec, clid);
            }
            return;
        }
        byte[] opus = new byte[packet.data.length - 5];
        System.arraycopy(packet.data, 5, opus, 0, opus.length);
        Consumer<TsVoiceIn> listener = voiceListener.get();
        if (listener != null) {
            try {
                listener.accept(new TsVoiceIn(clid, codec, sequence, opus));
            } catch (Exception e) {
                LOG.debug("[teamspeak-svc] voice listener threw: {}", e.getMessage());
            }
        }
    }

    // ---- initiv / clientinit ----

    private void handleInitIvClassic(String command) {
        Map<String, String> params = parseParams(command);
        LOG.info("[teamspeak-svc] initiv (classic) received");
        String error = crypt.cryptoInit(params.get("alpha"), params.get("beta"), params.get("omega"));
        finishCryptoInit(error);
    }

    /** 新协议：license 链 + 临时 Ed25519 密钥 + clientek 证明。 */
    private void handleInitIv2(String command) {
        Map<String, String> params = parseParams(command);
        LOG.info("[teamspeak-svc] initivexpand2 (license-based) received");
        if (Ts3Connection.DEBUG) {
            params.forEach((k2, v2) -> System.out.println("  param " + k2 + " len=" + v2.length() + " head=" + v2.substring(0, Math.min(24, v2.length()))));
        }
        byte[][] tmpKey = Ts3Crypt.generateTemporaryKey();
        String ek = java.util.Base64.getEncoder().encodeToString(tmpKey[0]);
        String proof;
        try {
            proof = signInitProof(tmpKey[0], params.get("beta"));
        } catch (Exception e) {
            fail("failed to sign clientek proof: " + e.getMessage());
            return;
        }
        try {
            connection.addOutgoingPacket(
                    ("clientek ek=" + ek + " proof=" + proof).getBytes(StandardCharsets.UTF_8),
                    PacketType.COMMAND);
        } catch (Ts3VoiceException e) {
            fail("failed to send clientek: " + e.getMessage());
            return;
        }
        String error = crypt.cryptoInit2(params.get("l"), params.get("omega"),
                params.get("proof"), params.get("beta"), tmpKey[1]);
        finishCryptoInit(error);
    }

    private void finishCryptoInit(String error) {
        if (error != null) {
            fail("crypto init failed: " + error);
            return;
        }
        connection.notifyInitComplete();
        LOG.info("[teamspeak-svc] crypto init complete, sending clientinit");
        try {
            sendClientInit();
        } catch (Ts3VoiceException e) {
            fail("failed to send clientinit: " + e.getMessage());
        }
    }

    /** proof = base64(ECDSA-P256(identity priv, ek_pub(32B) || beta_raw))，r||s 原始 64 字节。 */
    private String signInitProof(byte[] ekPub, String betaB64) throws Exception {
        byte[] beta = java.util.Base64.getDecoder().decode(betaB64);
        byte[] toSign = new byte[32 + 54];
        System.arraycopy(ekPub, 0, toSign, 0, 32);
        System.arraycopy(beta, 0, toSign, 32, 54);
        var signer = new org.bouncycastle.crypto.signers.ECDSASigner();
        var curve = org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1");
        var domain = new org.bouncycastle.crypto.params.ECDomainParameters(
                curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
        signer.init(true, new org.bouncycastle.crypto.params.ECPrivateKeyParameters(
                crypt.getIdentity().getPrivateKey(), domain));
        BigInteger[] sig = signer.generateSignature(Ts3Crypt.sha256(toSign));
        var der = new org.bouncycastle.asn1.DERSequence(new org.bouncycastle.asn1.ASN1Encodable[]{
                new org.bouncycastle.asn1.ASN1Integer(sig[0]),
                new org.bouncycastle.asn1.ASN1Integer(sig[1])});
        return java.util.Base64.getEncoder().encodeToString(der.getEncoded("DER"));
    }

    private void sendClientInit() throws Ts3VoiceException {
        String cmd = "clientinit"
                + " client_nickname=" + escape(nickname)
                + " client_version=" + escape(CLIENT_VERSION)
                + " client_platform=" + escape(CLIENT_PLATFORM)
                + " client_input_hardware=1"
                + " client_output_hardware=1"
                + " client_default_channel=" + escape(defaultChannel)
                + " client_default_channel_password="
                + " client_server_password=" + escape(serverPassword)
                + " client_version_sign=" + escape(CLIENT_VERSION_SIGN)
                + " client_key_offset=" + crypt.getIdentity().getValidKeyOffset()
                + " client_nickname_phonetic="
                + " client_default_token="
                + " hwid=";
        connection.addOutgoingPacket(cmd.getBytes(StandardCharsets.UTF_8), PacketType.COMMAND);
    }

    // ---- 通知解析 ----

    /** 解析含 client_unique_identifier 的行，维护客户端表；发现自身行则标记连接成功。 */
    private void handleClientRows(String command) {
        for (String row : command.split("\\|")) {
            Map<String, String> params = parseParams(row);
            String uid = params.get("client_unique_identifier");
            String clidStr = params.get("clid");
            if (uid == null || clidStr == null) {
                continue;
            }
            int clid;
            try {
                clid = Integer.parseInt(clidStr);
            } catch (NumberFormatException e) {
                continue;
            }
            clients.put(clid, new ClientEntry(uid, params.get("client_nickname")));
            String ctid = params.containsKey("ctid") ? params.get("ctid") : params.get("cid");
            if (ctid != null) {
                try {
                    int prev = channelByClid.getOrDefault(clid, -1);
                    int now = Integer.parseInt(ctid);
                    channelByClid.put(clid, now);
                    if (prev != now) {
                        java.util.function.IntConsumer l = channelMembershipListener;
                        if (l != null) {
                            l.accept(clid);
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            String desc = params.get("client_description");
            if (desc != null && desc.startsWith("tsvc-uuid:")) {
                try {
                    mcUuidByClid.put(clid, java.util.UUID.fromString(desc.substring("tsvc-uuid:".length())));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (myUid.equals(uid) && myClid < 0) {
                myClid = clid;
                connection.setClientNid(clid);
                if (channelByClid.containsKey(clid)) {
                    myChannelId = channelByClid.get(clid);
                }
                LOG.info("[teamspeak-svc] clientinit accepted (clid={}, uid={})", clid, uid);
                if (!failed.get()) {
                    connected.set(true);
                }
                connectLatch.countDown();
            }
        }
    }

    /** 频道文字消息通知 → 回调（过滤自身与 server 模式消息）。 */
    private void handleTextMessage(String command) {
        for (String row : command.split("\\|")) {
            Map<String, String> params = parseParams(row);
            String targetMode = params.get("targetmode");
            String invokerId = params.get("invokerid");
            String msg = params.get("msg");
            String name = params.get("invokername");
            if (!"2".equals(targetMode) || msg == null) {
                continue;
            }
            // 过滤自身回显
            try {
                if (invokerId != null && Integer.parseInt(invokerId) == myClid) {
                    continue;
                }
            } catch (NumberFormatException ignored) {
            }
            TextListener listener = textListener;
            if (listener != null && name != null) {
                try {
                    listener.onText(name, msg);
                } catch (Exception e) {
                    LOG.debug("[teamspeak-svc] text listener threw: {}", e.getMessage());
                }
            }
        }
    }

    /** notifyclientmoved：维护我方所在频道（被移出删除的频道时服务器也会推送）。 */
    private void handleClientMoved(String command) {
        for (String row : command.split("\\|")) {
            Map<String, String> params = parseParams(row);
            String clidStr = params.get("clid");
            String ctidStr = params.get("ctid");
            if (clidStr == null || ctidStr == null) {
                continue;
            }
            try {
                int clid = Integer.parseInt(clidStr);
                int ctid = Integer.parseInt(ctidStr);
                int prev = channelByClid.getOrDefault(clid, -1);
                channelByClid.put(clid, ctid);
                if (clid == myClid) {
                    myChannelId = ctid;
                    LOG.debug("[teamspeak-svc] self moved to channel {}", ctid);
                }
                if (prev != ctid) {
                    java.util.function.IntConsumer l = channelMembershipListener;
                    if (l != null) {
                        l.accept(clid);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void handleClientLeft(String command) {
        for (String row : command.split("\\|")) {
            Map<String, String> params = parseParams(row);
            String clidStr = params.get("clid");
            if (clidStr == null) {
                continue;
            }
            try {
                int clid = Integer.parseInt(clidStr);
                clients.remove(clid);
                mcUuidByClid.remove(clid);
                channelByClid.remove(clid);
                if (clid == myClid) {
                    String reason = params.get("reasonmsg");
                    fail("removed from server" + (reason != null ? ": " + reason : ""));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void handleError(String command) {
        // P3：带 return_code 的错误/成功优先路由给等待方；未关联的才走既有路径。
        if (command.contains("return_code=")) {
            TsCommandTracker tracker = commandTracker;
            if (tracker != null) {
                tracker.onError(command);
            }
            return;
        }
        Map<String, String> params = parseParams(command);
        String id = params.get("id");
        if (id != null && !"0".equals(id)) {
            String msg = params.get("msg");
            String extra = params.get("extra_msg");
            // clientinit 后的首个 error 非 0 = 连接失败
            if (!connected.get() && myClid < 0) {
                // S0.7/R26：结构化传递数值 id 与原始 msg token，分类层不再从
                // 拼接文本里猜语义（数值解释须依真实协议资料，本层不解释）。
                int numericId;
                try {
                    numericId = Integer.parseInt(id.trim());
                } catch (NumberFormatException e) {
                    numericId = -1;
                }
                failWithProtocolError(numericId, msg, extra);
            } else {
                LOG.warn("[teamspeak-svc] command error id={} msg={} extra={}", id, msg, extra);
            }
        }
    }

    /**
     * S0.7/R26：以结构化协议错误终止连接 —— failReason 记录人类可读文本，
     * 协议拒绝经 {@link TsProtocolException} 保留 id/msg，供上层分类。
     */
    private void failWithProtocolError(int id, String msg, String extra) {
        String reason = "server error id=" + id + " msg=" + msg + (extra != null ? " extra=" + extra : "");
        if (failed.compareAndSet(false, true)) {
            failReason.set(reason);
            protocolError.set(new TsProtocolException(reason, id, msg, extra));
            LOG.error("[teamspeak-svc] {}", reason);
        }
        commandTracker.cancelSession();
        Ts3FileTransferBridge.cancelClient(this);
        connectLatch.countDown();
    }

    /** 最近一次协议拒绝（结构化）；无则 null（普通失败/网络断开）。 */
    public TsProtocolException lastProtocolError() {
        return protocolError.get();
    }

    // ---- 工具 ----

    /** TS3 命令转义（\ → \\, / → \/, 空格 → \s, | → \p）。 */
    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '/' -> sb.append("\\/");
                case ' ' -> sb.append("\\s");
                case '|' -> sb.append("\\p");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\x%02X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 解析 key=value 对（处理反斜杠转义）。 */
    public static Map<String, String> parseParams(String segment) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        int i = 0;
        int n = segment.length();
        while (i < n) {
            // 跳过空白
            while (i < n && segment.charAt(i) == ' ') {
                i++;
            }
            int keyStart = i;
            while (i < n && segment.charAt(i) != '=' && segment.charAt(i) != ' ') {
                i++;
            }
            if (i >= n || segment.charAt(i) != '=') {
                // 无 '=' 的 token（命令名等）：跳过继续
                continue;
            }
            String key = segment.substring(keyStart, i);
            i++; // '='
            StringBuilder value = new StringBuilder();
            while (i < n) {
                char c = segment.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    char next = segment.charAt(i + 1);
                    switch (next) {
                        case '\\' -> value.append('\\');
                        case '/' -> value.append('/');
                        case 's' -> value.append(' ');
                        case 'p' -> value.append('|');
                        case 'a' -> value.append('\007');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'v' -> value.append('\013');
                        case 'x' -> {
                            if (i + 3 >= n) {
                                throw new IllegalArgumentException("Truncated hexadecimal escape");
                            }
                            try {
                                value.append((char) Integer.parseInt(segment.substring(i + 2, i + 4), 16));
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("Invalid hexadecimal escape", e);
                            }
                            i += 2;
                        }
                        default -> value.append(next);
                    }
                    i += 2;
                } else if (c == ' ' || c == '|') {
                    break;
                } else {
                    value.append(c);
                    i++;
                }
            }
            map.put(key, value.toString());
        }
        return map;
    }

    private void fail(String reason) {
        if (failed.compareAndSet(false, true)) {
            failReason.set(reason);
            LOG.error("[teamspeak-svc] {}", reason);
        }
        commandTracker.cancelSession();
        Ts3FileTransferBridge.cancelClient(this);
        // 已完成过 clientinit 的连接意外终止（被踢/被 ban/服务器错误）时，
        // 与网络层掉线同样通知外层，触发上层重连逻辑；连接建立过程中的失败
        // 由 connect() 的调用方处理，不通知（避免双重重连循环）。
        boolean wasConnected = connected.getAndSet(false);
        connection.stop();
        connectLatch.countDown();
        if (wasConnected) {
            Runnable ext = onDisconnectedExternal;
            if (ext != null) {
                try {
                    ext.run();
                } catch (Exception e) {
                    LOG.debug("[teamspeak-svc] onDisconnected callback threw: {}", e.getMessage());
                }
            }
        }
    }
}
