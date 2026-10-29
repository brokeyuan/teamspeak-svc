package com.tsvoice.client.frontend;

import com.tsvoice.client.TeamspeakSvcClient;
import com.tsvoice.client.connection.VoiceState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 固定 SVC 2.6.25 的内部兼容实现。已用 javap 核对（libs 内实际 JAR）：
 * <ul>
 *   <li>{@code MicThread(ClientVoicechat, ClientVoicechatConnection, Consumer<MicrophoneException>)}
 *       —— 字节码在 run 循环与 sendAudioPacket 均显式判空 connection：
 *       null 连接时采集/PTT/激活照常（sendAudio 仍触发 onClientSound 事件 →
 *       MicRedirector），仅跳过网络发送；</li>
 *   <li>{@code ClientVoicechat.getMicThread()/closeMicThread()/getSoundManager()}；</li>
 *   <li>{@code AudioChannel(ClientVoicechat, InitializationData, SoundManager, UUID)}
 *       —— 构造器仅保存 InitializationData；<b>2.6.25 字节码中该字段存储后
 *       从未被读取（0 次 getfield）</b>，因此独立播放传入 null 不触碰任何网络
 *       会话数据，也不伪造 Secret；运行期真正依赖的是 client（getRecorder/
 *       getTalkCache/setRecording）、soundManager（isClosed/isClosing）与
 *       CLIENT_CONFIG/Minecraft 静态上下文；</li>
 *   <li>{@code ClientVoicechat.processSoundPacket(SoundPacket)} —— <b>已核实
 *       偏移 0–7 的 null-connection 早退门</b>：connection 为 null 直接 return，
 *       不会创建 AudioChannel 也不会排队（D01 的实际丢弃路径，不再是未知风险）。
 *       本类的 TS 下行播放因此<b>不经过 processSoundPacket</b>：适配器直接拥有
 *       每条流自己的 AudioChannel（create → addToQueue → start，与原生创建顺序
 *       一致），所有权登记给 TsPlaybackRegistry/TsAudioInjector 复用既有清理。</li>
 * </ul>
 *
 * <p><b>证据分级（N2 更新）：</b>hook 存在（inspect 反射核对）/ 投递动作完成
 * （{@code playAttempts/playEnqueued} —— 包已加入自有 AudioChannel 队列）/
 * 资源失败（{@code playResourceFailures}）/ 真实听感（真机双向，用户要求暂缓，
 * 见 DEFERRED-CLIENT-ACCEPTANCE）。</p>
 */
public final class Svc2625FrontendAdapter implements SvcFrontendAdapter {

    /** mic 资源来源 seam：生产用反射实现，测试注入替身。 */
    public interface MicSource {
        /** SVC 原生 mic 存在则返回其句柄（借用）；否则返回 null。 */
        FrontendLease.MicHandle borrowNativeMic() throws Exception;

        /** 创建 null-connection 隔离 mic（PTT/激活/设备走 SVC 原逻辑）。 */
        FrontendLease.MicHandle createIsolatedMic() throws Exception;
    }

    private static final class Holder {
        static final Svc2625FrontendAdapter INSTANCE =
                new Svc2625FrontendAdapter(new ReflectionMicSource());
    }

    /** 生产反射 mic 来源：优先借用 SVC 原生 mic，否则创建 null-connection 隔离 mic。 */
    static final class ReflectionMicSource implements MicSource {
        @Override
        public FrontendLease.MicHandle borrowNativeMic() {
            try {
                Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                        .getMethod("getClient").invoke(null);
                if (client == null) {
                    return null;
                }
                Method getMicThread = client.getClass().getMethod("getMicThread");
                Object mic = getMicThread.invoke(client);
                if (!(mic instanceof Thread thread) || !thread.isAlive()) {
                    return null;
                }
                return new FrontendLease.MicHandle() {
                    @Override
                    public boolean isAlive() {
                        return thread.isAlive();
                    }

                    @Override
                    public void close() {
                        // 借用的原生 mic 由 SVC 生命周期管理（closeMicThread 不在此调用）。
                    }
                };
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public FrontendLease.MicHandle createIsolatedMic() throws Exception {
            Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                    .getMethod("getClient").invoke(null);
            Constructor<?> ctor = Class.forName("de.maxhenkel.voicechat.voice.client.MicThread")
                    .getConstructor(Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechat"),
                            Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechatConnection"),
                            java.util.function.Consumer.class);
            Object mic = ctor.newInstance(client, null,
                    (java.util.function.Consumer<Object>) error ->
                            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] isolated mic error: {}", error));
            ((Thread) mic).setDaemon(true);
            ((Thread) mic).start();
            return new FrontendLease.MicHandle() {
                @Override
                public boolean isAlive() {
                    return ((Thread) mic).isAlive();
                }

                @Override
                public void close() {
                    try {
                        mic.getClass().getMethod("close").invoke(mic);
                    } catch (Throwable ignored) {
                    }
                }
            };
        }
    }

    /** 自有播放通道：SVC AudioChannel 实例 + 绑定的 client 实例 + 首包 start 标志。 */
    private record OwnedPlayback(Object channel, Object ownerClient, AtomicBoolean started) {
        OwnedPlayback(Object channel, Object ownerClient) {
            this(channel, ownerClient, new AtomicBoolean(false));
        }
    }

    private volatile MicSource micSource;
    /** 先占所有权再创建资源（T08b）：预留标志 CAS 成功后才触碰 SVC，失败原子释放。 */
    private final AtomicReference<FrontendLease> activeLease = new AtomicReference<>(null);
    private final java.util.concurrent.atomic.AtomicBoolean reserving =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile FrontendCapabilities lastCapabilities;
    /** TS 下行播放：本扩展直接拥有的 AudioChannel（channelUuid → 通道）。 */
    private final ConcurrentHashMap<UUID, OwnedPlayback> ownedPlayback = new ConcurrentHashMap<>();
    private final AtomicLong injectedFrames = new AtomicLong(0);
    private final AtomicLong playAttempts = new AtomicLong(0);
    private final AtomicLong playEnqueued = new AtomicLong(0);
    private final AtomicLong playResourceFailures = new AtomicLong(0);
    private final AtomicInteger playUnavailableLogged = new AtomicInteger(0);

    Svc2625FrontendAdapter(MicSource micSource) {
        this.micSource = micSource;
    }

    /**
     * 测试注入 seam：替换单例的 mic 来源（生产固定为反射实现，永不调用）。
     * 仅用于 harness 在无真实 SVC 类的环境下驱动租约语义。
     */
    public static void setMicSource(MicSource source) {
        Holder.INSTANCE.micSource = source;
        Holder.INSTANCE.activeLease.set(null);
    }

    public static Svc2625FrontendAdapter instance() {
        return Holder.INSTANCE;
    }

    @Override
    public FrontendCapabilities inspect() {
        List<String> critical = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        boolean micOk = false;
        try {
            Constructor<?> ctor = Class.forName("de.maxhenkel.voicechat.voice.client.MicThread")
                    .getConstructor(Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechat"),
                            Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechatConnection"),
                            java.util.function.Consumer.class);
            micOk = ctor != null;
        } catch (Throwable t) {
            critical.add("MicThread constructor: " + t.getClass().getSimpleName());
        }
        boolean playbackOk = false;
        try {
            Class<?> channelClass = Class.forName("de.maxhenkel.voicechat.voice.client.AudioChannel");
            Constructor<?> ctor = channelClass.getConstructor(
                    Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechat"),
                    Class.forName("de.maxhenkel.voicechat.voice.client.InitializationData"),
                    Class.forName("de.maxhenkel.voicechat.voice.client.SoundManager"),
                    UUID.class);
            playbackOk = ctor != null
                    && channelClass.getMethod("addToQueue",
                    Class.forName("de.maxhenkel.voicechat.voice.common.SoundPacket")) != null;
        } catch (Throwable t) {
            critical.add("AudioChannel direct ownership: " + t.getClass().getSimpleName());
        }
        boolean stoppingHook = false;
        try {
            Class.forName("net.fabricmc.fabric.api.client.lifecycle.v1.ClientLifecycleEvents");
            stoppingHook = true;
        } catch (Throwable t) {
            optional.add("CLIENT_STOPPING lifecycle events: " + t.getClass().getSimpleName());
        }
        FrontendCapabilities capabilities = new FrontendCapabilities(micOk, playbackOk,
                stoppingHook, List.copyOf(critical), List.of(optional.toArray(new String[0])));
        lastCapabilities = capabilities;
        return capabilities;
    }

    @Override
    public FrontendLease acquire(VoiceState.SessionToken token) {
        FrontendCapabilities capabilities = lastCapabilities != null ? lastCapabilities : inspect();
        if (!capabilities.tsUsable()) {
            return null; // 关键 hook 缺失：禁用 TS（调用方报告 FRONTEND_UNAVAILABLE）
        }
        // 先占所有权（单一 mic 所有权），成功后再创建资源；失败原子释放预留。
        if (!reserving.compareAndSet(false, true)) {
            return null; // 已有所有者（或正在创建）
        }
        try {
            // N2/D02：失效租约（closed 或 mic 已死）必须在创建新资源之前释放，
            // 否则 activeLease.compareAndSet(null, …) 永远失败 —— 旧实现反复
            // 创建又立刻关闭新 mic，死租约一直占位。健康租约在位则仍拒绝双采集。
            FrontendLease existing = activeLease.get();
            if (existing != null && !existing.isClosed() && existing.micAlive()) {
                return null;
            }
            if (existing != null) {
                existing.close(); // 幂等；onLeaseClosed 将 activeLease 置回 null
                TeamspeakSvcClient.LOGGER.debug(
                        "[teamspeak-svc] released dead frontend lease (micAlive={} closed={}) before reacquire",
                        existing.micAlive(), existing.isClosed());
            }
            FrontendLease.MicHandle mic;
            boolean borrowed;
            try {
                mic = micSource.borrowNativeMic();
                borrowed = mic != null;
                if (!borrowed) {
                    mic = micSource.createIsolatedMic();
                }
            } catch (Exception e) {
                TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] frontend mic unavailable: {}", e.getMessage());
                return null;
            }
            FrontendLease lease = new FrontendLease(this, token, mic, borrowed);
            if (!activeLease.compareAndSet(null, lease)) {
                lease.close();
                return null; // 并发兜底：单一 mic 所有权
            }
            return lease;
        } finally {
            reserving.set(false);
        }
    }

    @Override
    public void release(FrontendLease lease) {
        if (lease != null) {
            lease.close();
        }
    }

    void onLeaseClosed(FrontendLease lease) {
        activeLease.compareAndSet(lease, null);
    }

    /** SVC 原生 mic 当前是否可用（探测，不创建资源；借用交接判定用）。 */
    public boolean nativeMicAvailable() {
        try {
            return micSource.borrowNativeMic() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * TS 下行播放（N2/D01 重做）：不再经 {@code processSoundPacket}（其
     * null-connection 早退门会静默丢弃，且适配器无法区分"已入队"与"被丢弃"）。
     * 每条流首次投递时创建本扩展自有的 AudioChannel（InitializationData 传 null
     * —— 2.6.25 字节码存储后从不读取），随后 addToQueue；首包后 start，与原生
     * 创建顺序一致。返回 true 只表达"包已确认加入自有通道队列"，不代表真实听感。
     */
    @Override
    public boolean play(VoiceState.StreamKey stream, UUID channelUuid, byte[] opus, long sequence) {
        if (stream == null || channelUuid == null || opus == null) {
            return false;
        }
        playAttempts.incrementAndGet();
        sweepStoppedPlayback();
        // 通道绑定创建时的 SVC client 实例（AudioChannel 运行期持有 recorder/
        // talkCache）—— client 实例更换（重连）后旧通道作废并按当前实例重建，
        // 语义与原生路径逐帧解析当前 client 一致。
        Object currentClient = resolvePlaybackClient();
        OwnedPlayback owned = ownedPlayback.compute(channelUuid, (uuid, existing) -> {
            if (existing != null && existing.ownerClient() == currentClient
                    && isPlaybackAlive(existing.channel)) {
                return existing;
            }
            if (existing != null) {
                closePlaybackChannel(existing.channel);
            }
            Object channel = createOwnedPlaybackChannel(currentClient, uuid);
            return channel == null ? null : new OwnedPlayback(channel, currentClient);
        });
        if (owned == null) {
            // 资源创建失败：不缓存失败结果，下次投递重试（设备可能恢复）
            playResourceFailures.incrementAndGet();
            return false;
        }
        try {
            Class<?> soundPacketClass = Class.forName("de.maxhenkel.voicechat.voice.common.SoundPacket");
            Constructor<?> groupCtor = Class.forName("de.maxhenkel.voicechat.voice.common.GroupSoundPacket")
                    .getConstructor(UUID.class, UUID.class, byte[].class, long.class, String.class);
            Object packet = groupCtor.newInstance(channelUuid, channelUuid, opus, sequence, (String) null);
            Method addToQueue = owned.channel.getClass().getMethod("addToQueue", soundPacketClass);
            addToQueue.invoke(owned.channel, packet);
            if (owned.started.compareAndSet(false, true)) {
                ((Thread) owned.channel).start();
            }
            playEnqueued.incrementAndGet();
            injectedFrames.incrementAndGet();
            return true;
        } catch (Throwable t) {
            playResourceFailures.incrementAndGet();
            ownedPlayback.remove(channelUuid, owned);
            closePlaybackChannel(owned.channel);
            if (playUnavailableLogged.compareAndSet(0, 1)) {
                TeamspeakSvcClient.LOGGER.warn(
                        "[teamspeak-svc] TS playback enqueue failed (FRONTEND_UNAVAILABLE for playback): {}",
                        t.toString());
            }
            return false;
        }
    }

    /** 当前 SVC client 实例（原生路径逐帧解析同一来源；未就绪为 null）。 */
    private Object resolvePlaybackClient() {
        try {
            return Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                    .getMethod("getClient").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通道是否仍在播放（线程存活且未被关闭）。 */
    private boolean isPlaybackAlive(Object channel) {
        try {
            if (!(channel instanceof Thread t) || !t.isAlive()) {
                return false;
            }
            return !(Boolean) channel.getClass().getMethod("isClosed").invoke(channel);
        } catch (Throwable t) {
            return false;
        }
    }

    private void closePlaybackChannel(Object channel) {
        try {
            channel.getClass().getMethod("closeAndKill").invoke(channel);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 创建独立于 SVC 网络会话的播放通道（N2/D01）：镜像原生路径
     * {@code new AudioChannel(client, connection.getData(), soundManager, channelId)}
     * —— InitializationData 在 2.6.25 中只被存储、从不读取，以 null 传入即不依赖
     * 也不伪造任何网络 secret。soundManager 与原生路径同源（client.getSoundManager()）。
     */
    private Object createOwnedPlaybackChannel(Object client, UUID channelUuid) {
        try {
            if (client == null) {
                return null;
            }
            Object soundManager = client.getClass().getMethod("getSoundManager").invoke(client);
            Class<?> channelClass = Class.forName("de.maxhenkel.voicechat.voice.client.AudioChannel");
            Constructor<?> ctor = channelClass.getConstructor(
                    Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechat"),
                    Class.forName("de.maxhenkel.voicechat.voice.client.InitializationData"),
                    Class.forName("de.maxhenkel.voicechat.voice.client.SoundManager"),
                    UUID.class);
            return ctor.newInstance(client, null, soundManager, channelUuid);
        } catch (Throwable t) {
            if (playUnavailableLogged.compareAndSet(0, 1)) {
                TeamspeakSvcClient.LOGGER.warn(
                        "[teamspeak-svc] standalone playback channel unavailable (FRONTEND_UNAVAILABLE for playback): {}",
                        t.toString());
            }
            return null;
        }
    }

    /** 清理已停止的自有通道（镜像 processSoundPacket 的 stopped-通道回收语义）。 */
    private void sweepStoppedPlayback() {
        if (ownedPlayback.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<UUID, OwnedPlayback> e : ownedPlayback.entrySet()) {
            try {
                Object channel = e.getValue().channel;
                if (!(channel instanceof Thread t) || !t.isAlive()
                        || (Boolean) channel.getClass().getMethod("isClosed").invoke(channel)) {
                    ownedPlayback.remove(e.getKey(), e.getValue());
                }
            } catch (Throwable ignored) {
                // 探测失败时保留条目，下次投递再试
            }
        }
    }

    /** 播放通道退役（流结束/会话清理）：仅关闭本扩展自有的通道对象。 */
    public void retirePlaybackChannel(UUID channelUuid) {
        OwnedPlayback owned = ownedPlayback.remove(channelUuid);
        if (owned != null) {
            closePlaybackChannel(owned.channel);
        }
    }

    /** 按对象身份退役自有播放通道（反射清理路径的桥接；句柄不匹配则为无操作）。 */
    public void retirePlaybackChannelIf(UUID channelUuid, Object expected) {
        OwnedPlayback owned = ownedPlayback.get(channelUuid);
        if (owned == null || owned.channel != expected) {
            return;
        }
        retirePlaybackChannel(channelUuid);
    }

    /** 本扩展当前拥有的播放通道对象（所有权快照；无则 null）。 */
    public Object ownedPlaybackChannel(UUID channelUuid) {
        OwnedPlayback owned = ownedPlayback.get(channelUuid);
        return owned == null ? null : owned.channel;
    }

    /** 自有播放通道绑定的 SVC client 实例（诊断：client 更换后通道应重建）。 */
    public Object playbackOwnerClient(UUID channelUuid) {
        OwnedPlayback owned = ownedPlayback.get(channelUuid);
        return owned == null ? null : owned.ownerClient;
    }

    /** 播放诊断（N2 分级：尝试投递 / 确认入队 / 资源失败）。 */
    public String playbackDiagnostics() {
        return "attempts=" + playAttempts.get() + " enqueued=" + playEnqueued.get()
                + " resourceFailures=" + playResourceFailures.get()
                + " ownedChannels=" + ownedPlayback.size();
    }

    /** 音频已验证层的诊断计数（确认入队帧数；真实听感真机另行验收）。 */
    public long injectedFrames() {
        return injectedFrames.get();
    }

    /**
     * N3/D03：OFF（关闭全部语音）时清空原生 SVC 表内的播放通道 —— 既有排队帧
     * 与正在使用的输出资源一并释放。反射访问 ClientVoicechat.getAudioChannels；
     * 环境缺失/未连接时为无操作。本扩展自有的独立播放通道由注册表退役路径负责。
     */
    public void flushNativePlaybackChannels() {
        try {
            Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                    .getMethod("getClient").invoke(null);
            if (client == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, Object> channels =
                    (java.util.Map<UUID, Object>) client.getClass()
                            .getMethod("getAudioChannels").invoke(client);
            if (channels == null) {
                return;
            }
            synchronized (channels) {
                for (Object channel : channels.values()) {
                    closePlaybackChannel(channel);
                }
                channels.clear();
            }
        } catch (Throwable t) {
            TeamspeakSvcClient.LOGGER.debug(
                    "[teamspeak-svc] flush native playback channels failed: {}", t.toString());
        }
    }

    /**
     * SVC 原生连接断开：关闭自有租约（借用资源交还 SVC 生命周期）；协调器随后
     * 重新申请（原生 mic 已死时自动切换为隔离 mic）。自有播放通道不依赖 SVC
     * 网络会话，保持有效。
     */
    public void onSvcDisconnected() {
        FrontendLease lease = activeLease.get();
        if (lease != null) {
            lease.onSvcDisconnected();
        }
    }

    /**
     * SVC 原生连接建立：若当前租约是自有隔离 mic 且原生 mic 现已可用，关闭自有
     * 租约（明确移交）；协调器重新申请时将借用原生 mic，避免原生/隔离双采集。
     */
    public void onSvcConnected() {
        FrontendLease lease = activeLease.get();
        if (lease != null && !lease.isClosed() && !lease.isBorrowedFromNativeSvc()
                && nativeMicAvailable()) {
            TeamspeakSvcClient.LOGGER.debug(
                    "[teamspeak-svc] handing isolated mic lease over to the native SVC mic");
            lease.close();
        }
    }
}
