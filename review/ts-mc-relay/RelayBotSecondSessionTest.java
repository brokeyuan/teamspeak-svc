package com.tsvoice;

import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.relay.TsRelayRuntime;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;

/**
 * G5 回归：Bot 受控第二中继会话（purpose 隔离 + 服务端开关）。
 *
 * <ul>
 *   <li>PLAYER_VOICE 与 MUSIC_BOT 各最多一个会话，互不覆盖；</li>
 *   <li>musicBotEnabled=false 时 Bot OPEN 被服务端拒绝且不影响玩家会话；</li>
 *   <li>启用后 Bot OPEN 通过 purpose 门；关闭 Bot 只关 Bot；</li>
 *   <li>玩家与 Bot 会话 id 独立（不互相覆盖）。</li>
 * </ul>
 */
public class RelayBotSecondSessionTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static TsRelayRuntime.RelayConfig config(int port, boolean musicBot) {
        return new TsRelayRuntime.RelayConfig(true,
                Map.of("ts", new TsRelayRuntime.RelayConfig.RelayTargetConfig("127.0.0.1", port)),
                TsRelayRuntime.MAX_SESSIONS_GLOBAL,
                new TsRelayRuntime.Limits(TsRelayWire.MAX_DATAGRAM_BYTES, 256, 512 * 1024),
                musicBot);
    }

    static TsRelayWire.OpenFrame openFrameOf(int id, TsRelayWire.Purpose purpose) {
        return new TsRelayWire.OpenFrame(id, "ts", purpose);
    }

    public static void main(String[] args) throws Exception {
        try (DatagramSocket echo = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            // ---- 默认关闭：Bot 拒绝，玩家正常 ----
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> true,
                    config(echo.getLocalPort(), false));
            var playerOpen = runtime.onOpen("conn-p", "player-p",
                    TsRelayWire.encodeOpen(openFrameOf(1, TsRelayWire.Purpose.PLAYER_VOICE)));
            check(playerOpen.ok(), "G5: player OPEN succeeds (default config)");
            var botOpen = runtime.onOpen("conn-b", "player-b",
                    TsRelayWire.encodeOpen(openFrameOf(1, TsRelayWire.Purpose.MUSIC_BOT)));
            check(!botOpen.ok() && botOpen.errorFrame() != null,
                    "G5: MUSIC_BOT OPEN rejected by default (musicBotEnabled=false)");
            check(runtime.sessionCount() == 1, "G5: exactly the player session exists");
            // Bot 拒绝不影响玩家后续会话能力：同玩家新连接可再开（single-session
            // 约束是按连接的，Bot 拒绝不是对玩家的惩罚）。
            var playerNewConn = runtime.onOpen("conn-p2", "player-p",
                    TsRelayWire.encodeOpen(openFrameOf(2, TsRelayWire.Purpose.PLAYER_VOICE)));
            check(playerNewConn.ok(),
                    "G5: a NEW connection can still open a player session after bot rejection");
            runtime.close();
        }

        try (DatagramSocket echo = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            // ---- 启用后：Bot 会话独立建立，与玩家共存 ----
            TsRelayRuntime runtime = new TsRelayRuntime((id, p, d) -> true,
                    config(echo.getLocalPort(), true));
            var playerOpen = runtime.onOpen("conn-p", "player-p",
                    TsRelayWire.encodeOpen(openFrameOf(1, TsRelayWire.Purpose.PLAYER_VOICE)));
            check(playerOpen.ok(), "G5: player session opened");
            var botOpen = runtime.onOpen("conn-b", "player-b",
                    TsRelayWire.encodeOpen(openFrameOf(1, TsRelayWire.Purpose.MUSIC_BOT)));
            check(botOpen.ok(), "G5: MUSIC_BOT OPEN passes the purpose gate when enabled");
            check(runtime.sessionCount() == 2, "G5: player and bot sessions coexist (2 sessions)");

            var playerSession = playerOpen.relaySessionId();
            var botSession = botOpen.relaySessionId();
            check(playerSession != null && botSession != null
                            && !java.util.Arrays.equals(playerSession, botSession),
                    "G5: player and bot sessions have distinct ids");

            // 关闭 Bot 只关 Bot：玩家会话仍可 DATA
            byte[] closeFrame = TsRelayWire.encodeClose(TsRelayWire.MessageType.CLOSE,
                    new TsRelayWire.CloseFrame(botSession, "bot stopped"));
            byte[] closedReply = runtime.onClose("conn-b", closeFrame);
            check(closedReply != null, "G5: bot CLOSE processed");
            check(runtime.sessionCount() == 1, "G5: only the player session remains");
            byte[] mediaProbe = new byte[16];
            mediaProbe[12] = 0x00; // VOICE header
            boolean playerDataOk = runtime.onData("conn-p", TsRelayWire.encodeData(
                    TsRelayWire.MessageType.DATA_C2S,
                    new TsRelayWire.DataFrame(playerSession, 0,
                            com.tsvoice.protocol.relay.TsDatagramKind.TRAFFIC_MEDIA,
                            mediaProbe)));
            check(playerDataOk, "G5: player session still forwards after bot close");
            runtime.close();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
