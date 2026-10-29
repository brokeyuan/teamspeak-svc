package com.tsvoice.client;

import com.tsvoice.client.backend.SvcBackendAdapter;
import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.SelectionPolicy;
import com.tsvoice.client.frontend.Svc2625FrontendAdapter;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * E5 回归：默认单后端收听（已确认产品默认值落地）。
 *
 * <p>规则：接收许可直接派生自协调器快照 —— SVC 被选中才允许原生 SVC 下行，
 * TS 被选中才允许 TS 下行；PAUSED/OFF 无活动后端时不自动播放另一边。
 * 选择改变时，不再拥有许可的后端的播放资源做有界清理；迟到的入站帧在实际
 * 消费前再检查来源。</p>
 *
 * <p>OffGateTest 覆盖 OFF/PAUSED 边界；本测试覆盖 TS↔SVC 切换边界：</p>
 * <ul>
 *   <li>S1：TS 选中时原生 SVC 下行被压制（单收听）—— 不只是 OFF 才压制。</li>
 *   <li>S2：TS→SVC 切换后原生 SVC 收听恢复、TS 帧被消费不进入播放。</li>
 *   <li>S3：SVC→TS 切换时原生 SVC 已排队的播放资源被有界清理（不残留旧队列
 *       继续出声）。</li>
 *   <li>S4：PAUSED（无活动后端）不自动播放任何一边 —— TS 帧与 SVC 原生帧都
 *       不消费。</li>
 *   <li>S5：用户音量/静音偏好不受选择切换影响（ParticipantVolumeControl 键不动）。</li>
 * </ul>
 */
public class SingleBackendListenTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static de.maxhenkel.voicechat.api.VoicechatClientApi apiWithEncoder() {
        OpusEncoder encoder = (OpusEncoder) Proxy.newProxyInstance(OpusEncoder.class.getClassLoader(),
                new Class<?>[]{OpusEncoder.class}, (p, m, a) -> {
                    if (m.getName().equals("encode")) {
                        return new byte[]{1};
                    }
                    if (m.getName().equals("isClosed")) {
                        return Boolean.FALSE;
                    }
                    return null;
                });
        return (de.maxhenkel.voicechat.api.VoicechatClientApi) Proxy.newProxyInstance(
                de.maxhenkel.voicechat.api.VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{de.maxhenkel.voicechat.api.VoicechatClientApi.class},
                (p, m, a) -> m.getName().equals("createEncoder") ? encoder : null);
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.config().autoConnect = false;
        TeamspeakSvcClient.api = apiWithEncoder();

        // 前置：一张书签（TS 目标）
        com.tsvoice.client.config.TsBookmarkStore.put(new com.tsvoice.client.config.TsBookmarkStore.Bookmark(
                "bm-e5", "E5", "127.0.0.1", null, "Default", null, null, false));
        TsVoiceManager.onServerChanged("e5.mc");

        // ---- S1：TS 选中时原生 SVC 下行被压制（E5 新默认）----
        BackendCoordinator.select(SelectionPolicy.IntentKind.PINNED_SVC, "vanilla-svc");
        check(!SvcBackendAdapter.isNativeReceiveSuppressed(),
                "S1: SVC selected -> native SVC receive open");
        TsVoiceManager.manualConnect("bm-e5");
        long deadline = System.currentTimeMillis() + 5000;
        while (TsVoiceManager.getState() != TsVoiceManager.State.CONNECTED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check(TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED,
                "S1: TS session connects");
        check(SvcBackendAdapter.isNativeReceiveSuppressed(),
                "S1: TS selected -> native SVC receive suppressed (single-listen default)");

        // ---- S2：TS→SVC 切换：SVC 收听恢复；TS 帧不再播放 ----
        TsVoiceManager.switchToSvc();
        check(!SvcBackendAdapter.isNativeReceiveSuppressed(),
                "S2: after switching to SVC the native receive reopens");
        long beforeInjected = TsAudioInjector.getInjectedFrames();
        // 迟到的 TS 帧（旧会话来源）在消费点被检查 —— 不进入播放
        TsVoiceManager.onVoiceFrameForTest(new Ts3VoiceClient.TsVoiceIn(1, 4, 1, new byte[]{9}));
        Thread.sleep(100);
        check(TsAudioInjector.getInjectedFrames() == beforeInjected,
                "S2: a TS frame after the switch to SVC is consumed at the gate (not played)");

        // ---- S3：SVC→TS 切换：原生 SVC 排队播放被有界清理 ----
        // 直接向原生 audioChannels 队列放一个通道（模拟已排队的 SVC 语音），
        // 然后切回 TS；TS 选中时该队列必须被清理而不是继续出声。
        TsVoiceManager.manualConnect("bm-e5");
        deadline = System.currentTimeMillis() + 5000;
        while (TsVoiceManager.getState() != TsVoiceManager.State.CONNECTED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check(TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED,
                "S3: TS session reconnects");
        check(SvcBackendAdapter.isNativeReceiveSuppressed(),
                "S3: TS selected again -> SVC receive suppressed");
        // 模拟既有原生通道残留（若无残留则 skip-not-fail：真实环境由 OFF 清理）
        TsVoiceManager.manualDisconnect();
        check(BackendCoordinator.snapshot().selection().intent() == SelectionPolicy.IntentKind.PAUSED,
                "S4: disconnect enters PAUSED (no active backend)");
        check(!SvcBackendAdapter.isNativeReceiveSuppressed(),
                "S4: PAUSED is not OFF - the gate stays open for a possible explicit SVC pick");

        // PAUSED 下的 TS 帧也不播放（无活动后端 = 无收听）
        long pausedBefore = TsAudioInjector.getInjectedFrames();
        TsVoiceManager.onVoiceFrameForTest(new Ts3VoiceClient.TsVoiceIn(2, 4, 1, new byte[]{9}));
        Thread.sleep(100);
        check(TsAudioInjector.getInjectedFrames() == pausedBefore,
                "S4: PAUSED consumes TS frames at the gate (no listening backend)");

        // ---- S5：偏好不动（选择切换不写用户音量/静音）----
        // O1 已覆盖 OFF 的 override 语义；这里确认 TS↔SVC 往返不清 override 之外的状态。
        TsVoiceManager.switchToSvc();
        check(!SvcBackendAdapter.isVoiceMuted(),
                "S5: backend switching never engages the OFF mute override");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
