package com.svcb.tsbridge.ts3voice;

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
            org.slf4j.LoggerFactory.getLogger("tsbridge-voice");

    /** TS3 Opus 编解码器 id。 */
    public static final int CODEC_OPUS_VOICE = 4;
    public static final int CODEC_OPUS_MUSIC = 5;

    /** 客户端版本常量（源：TSLib TsVersion.gen.cs VER_WIN_3_5_3）。 */
    private static final String CLIENT_VERSION = "3.5.3 [Build: 1587971024]";
    private static final String CLIENT_PLATFORM = "Windows";
    private static final String CLIENT_VERSION_SIGN =
            "Kvmj7qX6wJCPI5GVT71samfmhz/bvs7M+OTXWB/JWxdQbxDe17xda7dzUWLX7pjvdJTqZmbse1HBmTxThPKvAg==";

    /** 入站语音帧：发送者 clid → opus 载荷。 */
    public record TsVoiceIn(int senderClid, int codec, byte[] opus) {
    }

    private final Ts3Crypt crypt;
    private final Ts3Connection connection;
    private final String nickname;
    private final String defaultChannel;
    private final AtomicReference<Consumer<TsVoiceIn>> voiceListener = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicReference<String> failReason = new AtomicReference<>();
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final AtomicInteger voiceCounter = new AtomicInteger(0);
    private volatile Runnable onDisconnectedExternal;

    /** clid → 该客户端信息（uid/nick），来自 cliententerview / clientlist。 */
    private final Map<Integer, ClientEntry> clients = new ConcurrentHashMap<>();
    /** 我方 uid → clid。 */
    private volatile int myClid = -1;
    /** 我方当前所在频道（cliententerview / notifyclientmoved 维护）。 */
    private volatile int myChannelId = -1;
    private final String myUid;

    private record ClientEntry(String uniqueId, String nickname) {
    }

    /**
     * @param identity       已完成 PoW 的身份
     * @param nickname       TS 昵称
     * @param defaultChannel 进入的默认频道名（可为 null 走服务器默认频道）
     */
    public Ts3VoiceClient(Ts3Identity identity, String nickname, String defaultChannel) {
        this.crypt = new Ts3Crypt(identity);
        this.connection = new Ts3Connection(crypt);
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

    public boolean isConnected() {
        return connected.get();
    }

    /** 是否曾成功完成 clientinit（自测用）。 */
    public boolean wasEverConnected() {
        return myClid >= 0;
    }

    /** 当前客户端表快照（自测用）。 */
    public Map<Integer, ClientEntry> debugClients() {
        return Map.copyOf(clients);
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

    public String getMyUid() {
        return myUid;
    }

    public long getReceivedVoiceFrames() {
        return voiceCounter.get();
    }

    /** clid → TS 唯一 ID（用于把语音帧归属到具体 TS 用户）。 */
    public String getUniqueIdOf(int clid) {
        ClientEntry e = clients.get(clid);
        return e != null ? e.uniqueId() : null;
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
            throw new Ts3VoiceException(failReason.get() == null ? "connect failed" : failReason.get());
        }
        connected.set(true);
    }

    /** 加入指定频道（clientmove）。 */
    public void joinChannel(int cid) throws Ts3VoiceException {
        if (myClid < 0) {
            throw new Ts3VoiceException("not connected (no clid)");
        }
        connection.addOutgoingPacket(
                ("clientmove clid=" + myClid + " cid=" + cid).getBytes(StandardCharsets.UTF_8),
                PacketType.COMMAND);
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
    public void sendVoice(byte[] opusData, int codec) throws Ts3VoiceException {
        if (!connected.get() || connection.isClosed()) {
            return;
        }
        byte[] payload = new byte[opusData.length + 3];
        payload[2] = (byte) codec;
        System.arraycopy(opusData, 0, payload, 3, opusData.length);
        // data[0..2) 的 packetId 由连接层覆写
        connection.addOutgoingPacket(payload, PacketType.VOICE);
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
        connection.stop();
    }

    // ---- 回调（连接层调用） ----

    void onConnectionLost() {
        if (connected.getAndSet(false)) {
            LOG.warn("[tsbridge-voice] Voice connection lost");
        }
        Runnable ext = onDisconnectedExternal;
        if (ext != null) {
            try {
                ext.run();
            } catch (Exception e) {
                LOG.debug("[tsbridge-voice] onDisconnected callback threw: {}", e.getMessage());
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
            System.out.println("[cmd] " + (command.length() > 200 ? command.substring(0, 200) + "..." : command));
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
            default -> {
                // 语音协议中命令应答不带命令名（如 clientlist 的回包直接以
                // "clid=.. cid=.." 开头）：含 client_unique_identifier 的行按客户端表解析。
                // 这也是 bot 重连后补齐"已在服务器上的用户"映射的关键路径。
                if (trimmed.contains("client_unique_identifier=")) {
                    handleClientRows(trimmed);
                }
                if (Ts3Connection.DEBUG) {
                    LOG.debug("[tsbridge-voice] notification: {}", trimmed.length() > 120
                            ? trimmed.substring(0, 120) + "..." : trimmed);
                }
            }
        }
    }

    /** 语音包入口：[pid 2][clid 2][codec 1][opus...]。 */
    void onVoicePacket(Ts3Packet packet) {
        if (packet.data == null || packet.data.length < 6) {
            return; // 结束标记 / 空帧
        }
        int clid = ((packet.data[2] & 0xFF) << 8) | (packet.data[3] & 0xFF);
        int codec = packet.data[4] & 0xFF;
        byte[] opus = new byte[packet.data.length - 5];
        System.arraycopy(packet.data, 5, opus, 0, opus.length);
        voiceCounter.incrementAndGet();
        Consumer<TsVoiceIn> listener = voiceListener.get();
        if (listener != null) {
            try {
                listener.accept(new TsVoiceIn(clid, codec, opus));
            } catch (Exception e) {
                LOG.debug("[tsbridge-voice] voice listener threw: {}", e.getMessage());
            }
        }
    }

    // ---- initiv / clientinit ----

    private void handleInitIvClassic(String command) {
        Map<String, String> params = parseParams(command);
        LOG.info("[tsbridge-voice] initiv (classic) received");
        String error = crypt.cryptoInit(params.get("alpha"), params.get("beta"), params.get("omega"));
        finishCryptoInit(error);
    }

    /** 新协议：license 链 + 临时 Ed25519 密钥 + clientek 证明。 */
    private void handleInitIv2(String command) {
        Map<String, String> params = parseParams(command);
        LOG.info("[tsbridge-voice] initivexpand2 (license-based) received");
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
        LOG.info("[tsbridge-voice] crypto init complete, sending clientinit");
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
                + " client_server_password="
                + " client_meta_data="
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
            if (myUid.equals(uid) && myClid < 0) {
                myClid = clid;
                connection.setClientNid(clid);
                String ctid = params.get("ctid");
                if (ctid != null) {
                    try {
                        myChannelId = Integer.parseInt(ctid);
                    } catch (NumberFormatException ignored) {
                    }
                }
                LOG.info("[tsbridge-voice] clientinit accepted (clid={}, uid={})", clid, uid);
                if (!failed.get()) {
                    connected.set(true);
                }
                connectLatch.countDown();
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
                if (clid == myClid) {
                    myChannelId = ctid;
                    LOG.debug("[tsbridge-voice] self moved to channel {}", ctid);
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
                if (clid == myClid) {
                    String reason = params.get("reasonmsg");
                    fail("removed from server" + (reason != null ? ": " + reason : ""));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void handleError(String command) {
        Map<String, String> params = parseParams(command);
        String id = params.get("id");
        if (id != null && !"0".equals(id)) {
            String msg = params.get("msg");
            String extra = params.get("extra_msg");
            // clientinit 后的首个 error 非 0 = 连接失败
            if (!connected.get() && myClid < 0) {
                fail("server error id=" + id + " msg=" + msg + (extra != null ? " extra=" + extra : ""));
            } else {
                LOG.warn("[tsbridge-voice] command error id={} msg={} extra={}", id, msg, extra);
            }
        }
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
    static Map<String, String> parseParams(String segment) {
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
            LOG.error("[tsbridge-voice] {}", reason);
        }
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
                    LOG.debug("[tsbridge-voice] onDisconnected callback threw: {}", e.getMessage());
                }
            }
        }
    }
}
