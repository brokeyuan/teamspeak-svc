package com.tsvoice;

import com.tsvoice.client.ParticipantVolumeControl;
import com.tsvoice.client.TsAudioInjector;
import com.tsvoice.client.music.MusicSendPermit;

import java.util.UUID;

/**
 * G6/P2-05 回归：本机收听音量实际生效。
 *
 * <ul>
 *   <li>键 = serverUID + Bot TS UID 稳定派生（与 TsAudioInjector 同一键空间）；
 *       同名不同 UID 不共用；</li>
 *   <li>setVolume/setMuted 实际写入 SVC 音量存储并可读取回放；0..100 → 0..1；</li>
 *   <li>重连（同 serverUID/UID）重放已有设置，不强制恢复 100/取消静音；</li>
 *   <li>本机调音不改发送 PCM/许可（发送链不受 ParticipantVolumeControl 影响）。</li>
 * </ul>
 */
public class MusicPersonalVolumeTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        String serverUid = "fixture-server-uid";
        String botTsUid = "bot-uid-1";
        UUID key = TsAudioInjector.participantUuidFor(serverUid, botTsUid);

        // 稳定派生：同输入同键
        check(key.equals(TsAudioInjector.participantUuidFor(serverUid, botTsUid)),
                "G6: participant key derivation is stable for (serverUid, tsUid)");

        // 同名不同 UID 不共用键
        UUID otherBot = TsAudioInjector.participantUuidFor(serverUid, "bot-uid-2");
        check(!key.equals(otherBot), "G6: same-name bots with different UIDs get distinct keys");

        // 音量写入实际生效（0..100 → 0..1）
        ParticipantVolumeControl.setVolume(key, 0.35);
        check(Math.abs(ParticipantVolumeControl.volumeOf(key) - 0.35) < 1e-9,
                "G6: setVolume writes the SVC volume store (readable back)");
        ParticipantVolumeControl.setVolume(key, 1.0);
        check(ParticipantVolumeControl.volumeOf(key) == 1.0, "G6: 100 -> 1.0");

        // 静音单独保留恢复音量（生产语义：muted 时 volumeOf 报 0 —— SVC 实际
        // 应用静音；unmute 恢复存储的基础音量 0.5，不强制 1.0）
        ParticipantVolumeControl.setVolume(key, 0.5);
        ParticipantVolumeControl.setMuted(key, true);
        check(ParticipantVolumeControl.isMuted(key), "G6: mute is recorded separately");
        check(ParticipantVolumeControl.volumeOf(key) == 0.0,
                "G6: while muted the effective volume is 0 (SVC applies the mute)");
        ParticipantVolumeControl.setMuted(key, false);
        check(ParticipantVolumeControl.volumeOf(key) == 0.5,
                "G6: unmute restores the stored base volume (0.5, not forced 1.0)");

        // 重连重放：同键 reapply 不改变已存设置
        ParticipantVolumeControl.reapply(key);
        check(ParticipantVolumeControl.volumeOf(key) == 0.5 && !ParticipantVolumeControl.isMuted(key),
                "G6: reapply after 'reconnect' keeps user settings (no forced 100/unmute)");

        // 发送许可不受参与者音量影响（本机调音不改发送）
        MusicSendPermit permit = new MusicSendPermit(serverUid);
        permit.connectedUnconfirmed();
        permit.confirm(serverUid, 5);
        long epochBefore = permit.currentEpoch();
        ParticipantVolumeControl.setVolume(key, 0.1);
        check(permit.isSendable() && permit.currentEpoch() == epochBefore,
                "G6: local listening volume never touches the send permit/PCM");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
