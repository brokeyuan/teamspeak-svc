package com.tsvoice;

import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.relay.TsRelayRuntime;

import java.util.Map;

/**
 * E8/Music-1 回归：受控 MUSIC_BOT 第二会话（登记结构支持 purpose/session，
 * 不再是 player→socket 单会话；分别计费/清理；不抢 PLAYER_VOICE）。
 *
 * <p>当前边界（如实）：</p>
 * <ul>
 *   <li>Purpose.MUSIC_BOT 在 wire 层定义（编码/解码往返稳定）；</li>
 *   <li>服务端登记按 (connectionId, purpose) 维度持有会话 —— PLAYER_VOICE 与
 *       MUSIC_BOT 互不覆盖、分别回收；</li>
 *   <li>MUSIC_BOT 的 OPEN 默认拒绝（"purpose not enabled"）—— 授权开放是
 *       管理员显式配置项，本轮不默认开启（产品边界：MC 中继第二会话"开放
 *       受控开放"）。</li>
 * </ul>
 */
public class RelayMusicBotPurposeTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static TsRelayRuntime.RelayConfig config(int port) {
        return new TsRelayRuntime.RelayConfig(true,
                Map.of("ts", new TsRelayRuntime.RelayConfig.RelayTargetConfig("127.0.0.1", port)),
                TsRelayRuntime.MAX_SESSIONS_GLOBAL,
                new TsRelayRuntime.Limits(TsRelayWire.MAX_DATAGRAM_BYTES,
                        TsRelayRuntime.MAX_DATAGRAMS_PER_SECOND,
                        TsRelayRuntime.MAX_BYTES_PER_SECOND));
    }

    public static void main(String[] args) throws Exception {
        // ---- W：MUSIC_BOT purpose wire 往返（无需网络） ----
        byte[] musicOpen = TsRelayWire.encodeOpen(new TsRelayWire.OpenFrame(
                1, "ts", TsRelayWire.Purpose.MUSIC_BOT));
        TsRelayWire.Decoded decoded = TsRelayWire.decode(musicOpen);
        check(decoded != null && decoded.type() == TsRelayWire.MessageType.OPEN
                        && decoded.open().purpose() == TsRelayWire.Purpose.MUSIC_BOT,
                "W: MUSIC_BOT purpose survives the wire round-trip");
        check(TsRelayWire.Purpose.PLAYER_VOICE.wireValue() == 1
                        && TsRelayWire.Purpose.MUSIC_BOT.wireValue() == 2,
                "W: wire ids are stable (PLAYER_VOICE=1, MUSIC_BOT=2)");

        // ---- S：PLAYER_VOICE 与 MUSIC_BOT 分开判定；MUSIC_BOT 默认未授权 ----
        int freePort = 50000; // 不可达端口即可：授权判定在连接之前
        TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> true, config(freePort));
        var playerResult = runtime.onOpen("conn-m", "player-m", TsRelayWire.encodeOpen(
                new TsRelayWire.OpenFrame(1, "ts", TsRelayWire.Purpose.PLAYER_VOICE)));
        // 目标不可达时 PLAYER_VOICE 得到 TARGET_UNAVAILABLE（说明 purpose 门已过）
        TsRelayWire.Decoded playerErr = playerResult.ok() ? null : TsRelayWire.decode(playerResult.errorFrame());
        check(playerResult.ok() || (playerErr != null
                        && playerErr.error().stableErrorCode() == TsRelayWire.ERR_TARGET_UNAVAILABLE),
                "S: PLAYER_VOICE passes the purpose gate (fails later only on reachability)");
        var musicResult = runtime.onOpen("conn-m", "player-m", musicOpen);
        TsRelayWire.Decoded musicErr = musicResult.ok() ? null : TsRelayWire.decode(musicResult.errorFrame());
        check(!musicResult.ok() && musicErr != null
                        && musicErr.error().stableErrorCode() == TsRelayWire.ERR_NOT_AUTHORIZED,
                "S: MUSIC_BOT is rejected as not-authorized by default (controlled enablement)");
        // MUSIC_BOT 拒绝不波及玩家语音：p1 的 PLAYER_VOICE 会话仍活着
        //（p2 因"连接已持有会话"被拒 —— 是 p1 的正常单会话约束，不是
        // MUSIC_BOT 拒绝造成的污染；错误码必须是 LIMIT_SESSIONS 而非
        // NOT_AUTHORIZED/MALFORMED）。
        var retry = runtime.onOpen("conn-m", "player-m", TsRelayWire.encodeOpen(
                new TsRelayWire.OpenFrame(2, "ts", TsRelayWire.Purpose.PLAYER_VOICE)));
        TsRelayWire.Decoded retryErr = retry.ok() ? null : TsRelayWire.decode(retry.errorFrame());
        check(retryErr != null && retryErr.error().stableErrorCode() == TsRelayWire.ERR_LIMIT_SESSIONS,
                "S: a rejected MUSIC_BOT OPEN does not poison the connection's PLAYER_VOICE "
                        + "(p2 rejected only by p1's live single-session rule, not by MUSIC_BOT state)");
        check(runtime.sessionCount() == 1,
                "S: exactly the one PLAYER_VOICE session exists after the MUSIC_BOT rejection");
        runtime.close();

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
