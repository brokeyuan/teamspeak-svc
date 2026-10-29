package com.svcb.tsbridge.transport;

import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.config.BridgeConfig;
import com.svcb.tsbridge.ts3voice.Ts3Identity;
import com.svcb.tsbridge.ts3voice.Ts3VoiceClient;

import java.net.InetAddress;
import java.util.UUID;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 原生 TS3 语音客户端传输实现（完整协议移植自 TSLib/TS3AudioBot）。
 *
 * <ul>
 *     <li>init1 握手（含 RSA 谜题）→ initivexpand2 加密协商（license 链 +
 *         Ed25519 密钥派生 + ECDSA 证明）→ clientinit → 进入服务器；</li>
 *     <li>身份（P-256 + PoW 安全等级）持久化到 config/tsbridge/identity.txt，
 *         跨重启保持同一 TS uid，配对关系稳定；</li>
 *     <li>MC→TS：混音后的 Opus 帧以 OpusVoice 编码经 UDP 语音通道发送；</li>
 *     <li>TS→MC：接收语音包，按 clid 解析出发送者 TS uid 后回调注入 SVC；</li>
 *     <li>连接由单一 "TsBridge-Voice" 线程全权管理：连接、监测掉线、
 *         指数退避重连（首次启动失败也会持续重试），不阻塞服务器主线程；</li>
 *     <li>进入桥接频道由该线程自动重试（ServerQuery 侧频道就绪即加入）。</li>
 * </ul>
 */
public class Ts3NativeTransport implements TsAudioTransport {

    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    /** 连接成功后轮询存活状态的间隔（毫秒）。 */
    private static final long ALIVE_POLL_MS = 500L;
    /** 自动加入桥接频道的重试间隔（毫秒；对已加入频道重复 clientmove 为服务器端 no-op）。 */
    private static final long JOIN_RETRY_MS = 15_000L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;

    private final BridgeConfig config;
    private final Path identityPath;
    private final IntSupplier bridgeChannelIdSupplier;
    private final String nickname;

    private volatile Ts3VoiceClient voiceClient;
    private volatile Thread voiceThread;
    private volatile boolean started = false;
    private volatile boolean stopped = false;

    private volatile Consumer<TsAudioFrame> onTsAudioListener;
    private final AtomicLong receivedSeq = new AtomicLong(0);
    /** 当前 voiceClient 已成功加入的频道（-1 = 未加入；新连接后重置）。 */
    private volatile int joinedChannelId = -1;
    /** 上次下发的昵称（与当前相同则跳过 clientupdate —— TS3 对重设同名昵称报 513）。 */
    private volatile String lastNickname = null;
    /** 上次请求 clientlist 的时间（未知 clid 时限频补查）。 */
    private volatile long lastClientListRequestMs = 0;

    public Ts3NativeTransport(BridgeConfig config, Path identityPath, IntSupplier bridgeChannelIdSupplier) {
        this.config = config;
        this.identityPath = identityPath;
        this.bridgeChannelIdSupplier = bridgeChannelIdSupplier;
        this.nickname = "MCBridge";
    }

    @Override
    public void start() throws Exception {
        if (config.tsHost == null || config.tsHost.isBlank()) {
            throw new IllegalStateException("tsHost is not configured");
        }
        if (stopped) {
            return;
        }
        stopped = false;
        // 连接全部发生在后台线程：身份 PoW（可达数秒）与 UDP 握手（超时 15s）
        // 都不允许阻塞 SERVER_STARTED 的服务器主线程
        Thread t = new Thread(this::voiceLoop, "TsBridge-Voice");
        t.setDaemon(true);
        t.start();
        voiceThread = t;
        started = true;
        TsBridgeMod.LOGGER.info("[tsbridge] Ts3NativeTransport starting in background (voice={}:{})",
                config.tsHost, config.tsVoicePort);
    }

    /**
     * 语音连接主循环：连接 → 存活监测 → 掉线清理 → 退避重连。
     * 启动失败同样走该循环（首次启动时 TS 服务器不可达也能自动恢复）。
     */
    private void voiceLoop() {
        long backoffMs = INITIAL_BACKOFF_MS;
        while (!stopped) {
            try {
                connect();
                backoffMs = INITIAL_BACKOFF_MS;
            } catch (Exception e) {
                TsBridgeMod.LOGGER.warn("[tsbridge] Voice connect failed: {}. Retrying in {}ms",
                        e.getMessage(), backoffMs);
                cleanupVoiceClient();
            }
            if (stopped) {
                return;
            }

            // 已连接阶段：轮询存活 + 自动加入桥接频道（频道就绪即可加入）
            long lastJoinAttempt = 0;
            while (!stopped) {
                Ts3VoiceClient client = voiceClient;
                if (client == null || !client.isConnected()) {
                    break;
                }
                long now = System.currentTimeMillis();
                int bridgeCid = bridgeChannelIdSupplier.getAsInt();
                // 只在确实不在桥接频道时发送 clientmove（移入自己所在频道会被
                // TS3 以 770 already member of channel 拒绝）；频道被删时服务器
                // 会推送 notifyclientmoved，myChannelId 变化后自动重新加入
                if (bridgeCid >= 0 && bridgeCid != client.getMyChannelId()
                        && now - lastJoinAttempt >= JOIN_RETRY_MS) {
                    lastJoinAttempt = now;
                    boolean changed = bridgeCid != joinedChannelId;
                    try {
                        client.joinChannel(bridgeCid);
                        joinedChannelId = bridgeCid;
                        if (changed) {
                            TsBridgeMod.LOGGER.info("[tsbridge] Voice client joining bridge channel (cid={})", bridgeCid);
                        }
                    } catch (Exception e) {
                        joinedChannelId = -1;
                        TsBridgeMod.LOGGER.warn("[tsbridge] Failed to join bridge channel: {}", e.getMessage());
                    }
                }
                try {
                    Thread.sleep(ALIVE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (stopped) {
                return;
            }

            // 掉线：清理旧客户端（释放 socket/线程），退避后重连
            Ts3VoiceClient old = voiceClient;
            voiceClient = null;
            joinedChannelId = -1;
            if (old != null) {
                try {
                    old.disconnect();
                } catch (Exception ignored) {
                }
            }
            TsBridgeMod.LOGGER.warn("[tsbridge] Voice connection lost — reconnecting in {}ms", backoffMs);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
    }

    /**
     * 建立一次语音连接并等待 clientinit 完成（阻塞，仅语音线程调用）。
     * 失败时调用方负责清理半开连接，避免孤儿 socket/线程。
     */
    private synchronized void connect() throws Exception {
        Ts3Identity identity = Ts3Identity.load(identityPath);
        if (identity == null) {
            TsBridgeMod.LOGGER.info("[tsbridge] Generating TS identity (level 24, may take a few seconds)...");
            long t0 = System.currentTimeMillis();
            identity = Ts3Identity.generate(24, 8_000);
            identity.save(identityPath);
            TsBridgeMod.LOGGER.info("[tsbridge] TS identity generated in {}ms (uid={}, level={})",
                    System.currentTimeMillis() - t0, identity.getClientUid(), identity.getSecurityLevel());
        } else {
            TsBridgeMod.LOGGER.info("[tsbridge] TS identity loaded (uid={}, level={})",
                    identity.getClientUid(), identity.getSecurityLevel());
        }

        Ts3VoiceClient client = new Ts3VoiceClient(identity, nickname, "");
        client.setVoiceListener(this::handleVoiceFrame);
        try {
            client.connect(InetAddress.getByName(config.tsHost), config.tsVoicePort, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            // 握手超时/失败时底层 socket 与收发线程可能已就绪，必须显式关闭
            try {
                client.disconnect();
            } catch (Exception ignored) {
            }
            throw e;
        }
        voiceClient = client;
        joinedChannelId = -1;
        lastNickname = null;
        lastClientListRequestMs = 0;
        TsBridgeMod.LOGGER.info("[tsbridge] Voice connected (clid={}, uid={})",
                client.getMyClid(), client.getMyUid());
        // 补齐客户端表：bot 重连后，已在服务器上的用户只会出现在 clientlist 应答里
        //（初始 initserver 的压缩包可能解压失败），没有这份映射其语音帧会被丢弃
        try {
            client.sendCommand("clientlist -uid");
            lastClientListRequestMs = System.currentTimeMillis();
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Failed to request clientlist: {}", e.getMessage());
        }
    }

    private void cleanupVoiceClient() {
        Ts3VoiceClient old = voiceClient;
        voiceClient = null;
        joinedChannelId = -1;
        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /** 加入桥接频道（ServerQuery 侧频道就绪后由编排器触发；语音线程也会自动重试）。 */
    public void joinBridgeChannel() {
        Ts3VoiceClient client = voiceClient;
        if (client == null || !client.isConnected()) {
            return;
        }
        int cid = bridgeChannelIdSupplier.getAsInt();
        if (cid < 0 || cid == joinedChannelId) {
            return;
        }
        try {
            client.joinChannel(cid);
            joinedChannelId = cid;
            TsBridgeMod.LOGGER.info("[tsbridge] Voice client joined bridge channel (cid={})", cid);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] Failed to join bridge channel: {}", e.getMessage());
        }
    }

    /**
     * 通过 voice 协议更新 bot 昵称（存在感同步：MCBridge (N online)）。
     * 昵称与当前相同时跳过 —— TS3 对重设同名昵称报 513（nickname is already in use）。
     */
    public void updateNickname(String newNickname) {
        Ts3VoiceClient client = voiceClient;
        if (client == null || !client.isConnected() || newNickname == null || newNickname.isBlank()) {
            return;
        }
        if (newNickname.equals(lastNickname)) {
            return;
        }
        try {
            client.setNickname(newNickname);
            lastNickname = newNickname;
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] Failed to update voice nickname: {}", e.getMessage());
        }
    }

    private void handleVoiceFrame(Ts3VoiceClient.TsVoiceIn frame) {
        Consumer<TsAudioFrame> listener = onTsAudioListener;
        if (listener == null) {
            return;
        }
        Ts3VoiceClient client = voiceClient;
        if (client == null) {
            return;
        }
        String tsUniqueId = client.getUniqueIdOf(frame.senderClid());
        if (tsUniqueId == null) {
            // 未知 clid（客户端表尚未同步）：限频补拉 clientlist，本帧丢弃，
            // 后续帧在映射补齐后正常注入
            long now = System.currentTimeMillis();
            if (now - lastClientListRequestMs > 2_000L) {
                lastClientListRequestMs = now;
                TsBridgeMod.LOGGER.warn("[tsbridge] Voice frame from unknown clid {} — refreshing clientlist",
                        frame.senderClid());
                try {
                    client.sendCommand("clientlist -uid");
                } catch (Exception e) {
                    TsBridgeMod.LOGGER.debug("[tsbridge] Failed to refresh clientlist: {}", e.getMessage());
                }
            }
            return;
        }
        TsAudioFrame out = new TsAudioFrame(tsUniqueId, frame.opus(),
                receivedSeq.incrementAndGet(), System.currentTimeMillis());
        try {
            listener.accept(out);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.warn("[tsbridge] TS audio listener threw: {}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        stopped = true;
        started = false;
        Thread t = voiceThread;
        if (t != null) {
            t.interrupt();
        }
        cleanupVoiceClient();
        TsBridgeMod.LOGGER.info("[tsbridge] Ts3NativeTransport stopped");
    }

    @Override
    public boolean isStarted() {
        Ts3VoiceClient client = voiceClient;
        return started && client != null && client.isConnected();
    }

    @Override
    public void setOnTsAudioListener(Consumer<TsAudioFrame> listener) {
        this.onTsAudioListener = listener;
    }

    @Override
    public void sendMcAudio(UUID mcUuid, byte[] opusData, long sequence) {
        Ts3VoiceClient client = voiceClient;
        if (client == null || !client.isConnected() || opusData == null || opusData.length == 0) {
            return;
        }
        try {
            client.sendVoice(opusData, Ts3VoiceClient.CODEC_OPUS_VOICE);
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] sendVoice failed: {}", e.getMessage());
        }
    }
}
