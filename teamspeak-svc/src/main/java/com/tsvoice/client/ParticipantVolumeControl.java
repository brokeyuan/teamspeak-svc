package com.tsvoice.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 逐用户音量/静音（S0.6/R25）：键为参与者稳定 UUID
 * （{@link TsAudioInjector#participantUuidFor}），与原生 SVC 键空间同源 ——
 * 直接读写 SVC 的 {@code PLAYER_VOLUME_CONFIG}（volume=1 为默认，0 为静音，
 * 与原生音量滑条同一语义），重连保留。
 *
 * <p>音量存放于 SVC 自己的 player-volumes.properties：不新增第二份持久化，
 * 不覆盖原生 SVC 条目（参与者 UUID 由 serverUid+tsUid 派生，与原生玩家 UUID
 * 空间不重叠 —— T07/R10 约束）。SVC 音量 ABI 不可用时退化为内存态（本次会话
 * 有效），不抛异常。</p>
 */
public final class ParticipantVolumeControl {

    private record State(double volume, boolean muted, boolean persisted) {
    }

    private static final Map<UUID, State> OVERRIDE = new ConcurrentHashMap<>();

    /** 逻辑音量（0–4）：静音返回 0；无覆盖返回 -1 表示跟随 SVC 默认。 */
    public static double volumeOf(UUID participant) {
        State s = OVERRIDE.get(participant);
        if (s == null) {
            return -1;
        }
        return s.muted() ? 0 : s.volume();
    }

    public static boolean isMuted(UUID participant) {
        State s = OVERRIDE.get(participant);
        return s != null && s.muted();
    }

    /** 设置音量（0–4；0 等价静音并保留原音量值便于取消静音恢复）。 */
    public static void setVolume(UUID participant, double volume) {
        State old = OVERRIDE.get(participant);
        double v = Math.max(0, Math.min(4, volume));
        State next;
        if (v <= 0) {
            next = new State(old != null ? old.volume() : 1, true, false);
        } else {
            next = new State(v, false, false);
        }
        OVERRIDE.put(participant, next);
        apply(participant, next);
    }

    /** 静音/取消静音（保留原音量值）。 */
    public static void setMuted(UUID participant, boolean muted) {
        State old = OVERRIDE.get(participant);
        double base = old != null ? old.volume() : 1;
        State next = new State(base, muted, false);
        OVERRIDE.put(participant, next);
        apply(participant, next);
    }

    /** 参与者音频通道重建（重连/升降级）后恢复已保存的音量/静音。 */
    public static void reapply(UUID participant) {
        State s = OVERRIDE.get(participant);
        if (s != null) {
            apply(participant, s);
        }
    }

    private static void apply(UUID participant, State s) {
        try {
            Object config = Class.forName("de.maxhenkel.voicechat.VoicechatClient")
                    .getField("PLAYER_VOLUME_CONFIG").get(null);
            double effective = s.muted() ? 0 : s.volume();
            config.getClass().getMethod("setVolume", UUID.class, double.class, String[].class)
                    .invoke(config, participant, effective, (Object) new String[]{"[tsvoice]"});
            config.getClass().getMethod("save").invoke(config);
        } catch (Throwable t) {
            // SVC 音量 ABI 不可用：内存态维持（本次会话有效），不抛异常
            com.tsvoice.client.TeamspeakSvcClient.LOGGER.debug(
                    "[teamspeak-svc] participant volume apply failed (in-memory only): {}", t.toString());
        }
    }

    /** 已设置的参与者数（诊断/测试）。 */
    public static int size() {
        return OVERRIDE.size();
    }

    /** 清空（仅测试使用；生产状态跨会话保留）。 */
    static void clearForTest() {
        OVERRIDE.clear();
    }
}
