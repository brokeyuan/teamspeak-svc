package com.tsvoice.client.frontend;

import com.tsvoice.client.TeamspeakSvcClient;
import com.tsvoice.client.connection.VoiceState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 固定 SVC 2.6.25 的内部兼容实现。已用 javap 核对（libs 内实际 JAR）：
 * <ul>
 *   <li>{@code MicThread(ClientVoicechat, ClientVoicechatConnection, Consumer<MicrophoneException>)}
 *       —— 字节码在 run 循环与 sendAudioPacket 均显式判空 connection：
 *       null 连接时采集/PTT/激活照常，仅跳过发送；</li>
 *   <li>{@code ClientVoicechat.getMicThread()/closeMicThread()}（借用原生 mic）；</li>
 *   <li>{@code AudioChannel(ClientVoicechat, InitializationData, SoundManager, UUID)}
 *       —— 构造器仅保存 InitializationData 不解引用（26.2 参考源码为 3 参的
 *       ABI 差异）；</li>
 *   <li>{@code ClientVoicechat.processSoundPacket(SoundPacket)}（下行注入入口，
 *       经 SVC 抖动缓冲/解码/音量/输出设备）。</li>
 * </ul>
 *
 * <p><b>证据分级（T08a）：</b>hook 存在（inspect 反射核对）/ 资源已创建
 * （{@code activeLease} 与 {@code injectedFrames}）/ 音频已验证（真机双向，
 * 见交付记录"未验证清单"）。null-connection 的下行注入与设备切换路径
 * <b>未真机验证</b>；本类不伪造网络会话或 secret 来骗过检查。</p>
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

    private volatile MicSource micSource;
    /** 先占所有权再创建资源（T08b）：预留标志 CAS 成功后才触碰 SVC，失败原子释放。 */
    private final AtomicReference<FrontendLease> activeLease = new AtomicReference<>(null);
    private final java.util.concurrent.atomic.AtomicBoolean reserving =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile FrontendCapabilities lastCapabilities;
    /** 音频已验证层：真实注入管线的成功帧数（诊断；真机验证另行记录）。 */
    private final AtomicLong injectedFrames = new AtomicLong(0);
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
            Class<?> clientClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechat");
            if (clientClass.getMethod("processSoundPacket",
                    Class.forName("de.maxhenkel.voicechat.voice.common.SoundPacket")) != null) {
                playbackOk = true;
            }
        } catch (Throwable t) {
            critical.add("processSoundPacket: " + t.getClass().getSimpleName());
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

    @Override
    public boolean play(VoiceState.StreamKey stream, UUID channelUuid, byte[] opus, long sequence) {
        if (stream == null || channelUuid == null || opus == null) {
            return false;
        }
        try {
            Class<?> soundPacketClass = Class.forName("de.maxhenkel.voicechat.voice.common.SoundPacket");
            Class<?> clientClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientVoicechat");
            Method process = clientClass.getMethod("processSoundPacket", soundPacketClass);
            Constructor<?> groupCtor = Class.forName("de.maxhenkel.voicechat.voice.common.GroupSoundPacket")
                    .getConstructor(UUID.class, UUID.class, byte[].class, long.class, String.class);
            Object client = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
                    .getMethod("getClient").invoke(null);
            if (client == null) {
                return false;
            }
            Object packet = groupCtor.newInstance(channelUuid, channelUuid, opus, sequence, (String) null);
            process.invoke(client, packet);
            injectedFrames.incrementAndGet();
            return true;
        } catch (Throwable t) {
            if (playUnavailableLogged.compareAndSet(0, 1)) {
                TeamspeakSvcClient.LOGGER.warn(
                        "[teamspeak-svc] TS playback injection unavailable (FRONTEND_UNAVAILABLE for playback): {}",
                        t.toString());
            }
            return false;
        }
    }

    /** 音频已验证层的诊断计数（真实注入成功帧数）。 */
    public long injectedFrames() {
        return injectedFrames.get();
    }

    /** SVC 原生连接断开时调用：关闭自有租约（借用资源交还 SVC 生命周期）。 */
    public void onSvcDisconnected() {
        FrontendLease lease = activeLease.get();
        if (lease != null) {
            lease.onSvcDisconnected();
        }
    }
}
