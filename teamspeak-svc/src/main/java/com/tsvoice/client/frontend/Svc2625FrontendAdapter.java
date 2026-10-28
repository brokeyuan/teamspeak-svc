package com.tsvoice.client.frontend;

import com.tsvoice.client.TeamspeakSvcClient;
import com.tsvoice.client.connection.VoiceState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
 *       ABI 差异）；播放循环对 null InitializationData 的安全性<b>未真机验证</b>。</li>
 * </ul>
 * 真机验证矩阵（无 SVC 服务器双向通话等）见交付记录"未验证清单"。
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
        static final Svc2625FrontendAdapter INSTANCE = new Svc2625FrontendAdapter(new ReflectionMicSource());
    }

    public static Svc2625FrontendAdapter instance() {
        return Holder.INSTANCE;
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

    private final MicSource micSource;
    private final AtomicReference<FrontendLease> activeLease = new AtomicReference<>(null);
    private volatile FrontendCapabilities lastCapabilities;

    Svc2625FrontendAdapter(MicSource micSource) {
        this.micSource = micSource;
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
                stoppingHook, List.copyOf(critical), List.copyOf(optional));
        lastCapabilities = capabilities;
        return capabilities;
    }

    @Override
    public FrontendLease acquire(VoiceState.SessionToken token) {
        FrontendCapabilities capabilities = lastCapabilities != null ? lastCapabilities : inspect();
        if (!capabilities.tsUsable()) {
            return null; // 关键 hook 缺失：禁用 TS（调用方报告 FRONTEND_UNAVAILABLE）
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
            try {
                mic.close();
            } catch (Exception ignored) {
            }
            return null; // 已有所有者：单一 mic 所有权
        }
        return lease;
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

    /** SVC 原生连接断开时调用：关闭自有租约（借用资源交还 SVC 生命周期）。 */
    public void onSvcDisconnected() {
        FrontendLease lease = activeLease.get();
        if (lease != null) {
            lease.onSvcDisconnected();
        }
    }
}
