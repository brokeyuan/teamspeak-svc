package com.tsvoice.client;

import com.tsvoice.client.config.EndpointCredentialStore;
import com.tsvoice.client.config.TsBookmarkStore;
import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.SelectionPolicy;
import com.tsvoice.client.connection.TsChannelRoute;
import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * T09/N4 GUI flow regression: the production entry points the new screens call
 * (bookmark store CRUD incl. v2→v3 migration, manualConnect, the three explicit
 * voice operations, bookmark deletion protection) drive the REAL manager and
 * coordinator, and the VoiceStatusModel the screens render reflects each step.
 * The screens themselves are thin MC widgets over these same entry points —
 * real rendering/clickability is deferred client acceptance (E07).
 */
public class GuiFlowTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    static boolean waitForState(TsVoiceManager.State expected, long timeoutMillis) throws Exception {
        return waitFor(() -> TsVoiceManager.getState() == expected, timeoutMillis);
    }

    static VoicechatClientApi apiWithEncoder() {
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
        return (VoicechatClientApi) Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class<?>[]{VoicechatClientApi.class}, (p, m, a) ->
                        m.getName().equals("createEncoder") ? encoder : null);
    }

    /** Session fixture: bookmark + scripted channels; returns once CONNECTED. */
    static Ts3VoiceClient connectBookmark(String bookmarkId, String channelName,
                                          java.util.function.Consumer<Ts3VoiceClient> extra) throws Exception {
        TsVoiceManager.onServerChanged("gui.mc");
        TsVoiceManager.onSvcConnected();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Ts3VoiceClient.connectScripts.addLast(c -> {
            c.channelNames.put(1, "Default");
            c.channelNames.put(5, "Lobby");
            started.countDown();
            if (extra != null) {
                extra.accept(c);
            }
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        TsVoiceManager.manualConnect(bookmarkId);
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("session never reached the network (state=" + TsVoiceManager.getState()
                    + " error=" + TsVoiceManager.getLastError() + " lease=" + TsVoiceManager.frontendLeaseHeld()
                    + " micAlive=" + TsVoiceManager.frontendMicAlive() + ")");
        }
        release.countDown();
        if (!waitForState(TsVoiceManager.State.CONNECTED, 5000)) {
            throw new AssertionError("session did not connect: " + TsVoiceManager.getLastError());
        }
        return TsVoiceManager.currentClient();
    }

    public static void main(String[] args) throws Exception {
        TeamspeakSvcClient.config().autoConnect = false;
        TeamspeakSvcClient.api = apiWithEncoder();

        // ---- G1: schema v3 CRUD + one-time v2→v3 migration backup ----
        Path bookmarksFile = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("teamspeak-svc").resolve("bookmarks.json");
        Files.createDirectories(bookmarksFile.getParent());
        Files.writeString(bookmarksFile, "[{\"id\":\"bm-legacy\",\"label\":\"Legacy\",\"host\":\"old.local\","
                + "\"port\":9987,\"channel\":\"Lobby\",\"transportPolicy\":null,\"credentialRef\":null,"
                + "\"autoConnect\":false}]");
        TsBookmarkStore.load();
        Path backup = bookmarksFile.resolveSibling("bookmarks.v2-backup.json");
        check(Files.exists(backup) && Files.readString(backup).contains("bm-legacy"),
                "G1: a v2 bookmark file is backed up once before the v3 migration");
        TsBookmarkStore.Bookmark legacy = TsBookmarkStore.get("bm-legacy");
        check(legacy != null && !legacy.lockedChannel() && legacy.effectiveNickname() == null,
                "G1: the migrated v2 bookmark keeps its fields and gains defaults");
        TsBookmarkStore.put(new TsBookmarkStore.Bookmark("bm-gui", "GUI Book", "127.0.0.1", 9987,
                "Lobby", null, null, false, "NickA", null, null));
        check("NickA".equals(TsBookmarkStore.get("bm-gui").effectiveNickname()),
                "G1: v3 fields round-trip through the store");
        check(new TsBookmarkStore.Bookmark("x", "X", "h", null, null, null, null, false).lockedChannel() == false
                        && new TsBookmarkStore.Bookmark("x", "X", "h", null, null, null, null, false,
                        null, null, "locked").lockedChannel(),
                "G1: the 8-arg v2 constructor still works and locked maps to LOCKED policy");

        // ---- G2: connect -> server confirm -> the status model the screens render ----
        Ts3VoiceClient session = connectBookmark("bm-gui", "Lobby", null);
        check(waitFor(() -> session.joinCalls == 1 && session.lastJoinCid == 5, 5000),
                "G2: the bookmark screen's connect action joins the bookmark channel");
        session.simulateSelfMove(5);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(session), 5000),
                "G2: the confirmed channel grants the transmit route");
        VoiceStatusModel model = VoiceStatusModel.snapshot();
        check("TS".equals(model.micTarget()) && model.micTargetDetail().contains("confirmed channel 5"),
                "G2: the model reports the TS mic target with the confirmed channel");
        check("TS (UDP)".equals(model.backendTransport())
                        && "TS_NATIVE_FOLLOW".equals(model.channelPolicy())
                        && "READY".equals(model.movePhase())
                        && "bookmark:bm-gui".equals(model.selectionSource())
                        && model.sessionConnected(),
                "G2: transport/policy/move-phase/source read from the live coordinator");

        // ---- G3: the three explicit voice operations each update the model ----
        TsVoiceManager.disconnectTeamSpeak();
        model = VoiceStatusModel.snapshot();
        check("PAUSED".equals(model.connectionPhase()) && "IDLE".equals(model.micTarget())
                        && !"SVC".equals(model.micTarget()),
                "G3: disconnect TeamSpeak -> IDLE, never an implicit SVC switch");
        TsVoiceManager.switchToSvc();
        model = VoiceStatusModel.snapshot();
        check("SVC".equals(model.micTarget()) && "SVC (native)".equals(model.backendTransport()),
                "G3: switch to vanilla SVC -> SVC mic target");
        TsVoiceManager.disableAllVoice();
        model = VoiceStatusModel.snapshot();
        check("IDLE".equals(model.micTarget()) && "VOICE_OFF".equals(model.connectionPhase()),
                "G3: close all voice -> IDLE with the OFF phase");

        // ---- G4: locked bookmark policy end to end ----
        TsBookmarkStore.put(new TsBookmarkStore.Bookmark("bm-gui-lock", "Locked", "127.0.0.1", 9987,
                "Lobby", null, null, false, null, null, "locked"));
        Ts3VoiceClient locked = connectBookmark("bm-gui-lock", "Lobby", null);
        locked.simulateSelfMove(5);
        check(waitFor(() -> TsChannelRoute.isReadyForVoice(locked), 5000),
                "G4: the locked bookmark confirms and sends in its bookmark channel");
        check(VoiceState.ChannelPolicy.LOCKED_TARGET == BackendCoordinator.channelPolicy()
                        && "LOCKED_TARGET".equals(VoiceStatusModel.snapshot().channelPolicy()),
                "G4: the model reports the locked policy");
        locked.simulateSelfMove(7); // admin move away from the locked channel
        // 持续阻断：跨多个对账周期（远超单个 2s tick）保持不可发送，不是单次瞬间状态。
        boolean g4blocked = true;
        for (int i = 0; i < 6; i++) {
            Thread.sleep(1000);
            boolean stillBlocked = !TsChannelRoute.isReadyForVoice(locked)
                    && TsChannelRoute.MovePhase.BLOCKED_BY_POLICY == TsChannelRoute.movePhase();
            System.err.println("[dbg] cyc" + i + " stillBlocked=" + stillBlocked
                    + " ready=" + TsChannelRoute.isReadyForVoice(locked)
                    + " phase=" + TsChannelRoute.movePhase()
                    + " transmitReady=" + BackendCoordinator.route().transmitReady()
                    + " cid=" + BackendCoordinator.route().channelId()
                    + " policy=" + BackendCoordinator.channelPolicy()
                    + " myChannel=" + locked.getMyChannelId()
                    + " joinCalls=" + locked.joinCalls + " lastJoin=" + locked.lastJoinCid);
            g4blocked &= stillBlocked;
        }
        check(g4blocked,
                "G4: an admin move away blocks the locked route (stays blocked across cycles)");
        model = VoiceStatusModel.snapshot();
        check("TS".equals(model.micTarget()) && !model.sessionConnected() == false,
                "G4: the model still names TS as the target while blocked");
        check(!TsChannelRoute.isReadyForVoice(locked),
                "G4: but the transmit permission follows the revoked route");

        // ---- G5: deleting the ACTIVE bookmark pauses instead of silently retargeting ----
        TsVoiceManager.disconnectTeamSpeak();
        TsVoiceManager.manualConnect("bm-gui");
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "G5: session reconnected on bm-gui");
        TsBookmarkStore.remove("bm-gui");
        TsVoiceManager.onBookmarkDeleted("bm-gui");
        model = VoiceStatusModel.snapshot();
        check(model.connectionPhase().equals("PAUSED") && "IDLE".equals(model.micTarget()),
                "G5: deleting the active bookmark pauses with no silent retarget");
        TsVoiceManager.manualConnect("bm-gui-lock"); // switch to the remaining bookmark: explicit user action
        check(waitForState(TsVoiceManager.State.CONNECTED, 5000), "G5: an explicit connect still works");
        TsBookmarkStore.put(new TsBookmarkStore.Bookmark("bm-other", "Other", "other.local", 9987,
                "Lobby", null, null, false));
        TsBookmarkStore.remove("bm-other"); // deleting an INACTIVE bookmark never touches the session
        check(TsVoiceManager.isSessionConnected() && TsVoiceManager.getState() == TsVoiceManager.State.CONNECTED,
                "G5: deleting an inactive bookmark leaves the session alone");

        // ---- G6: an unknown bookmark id fails loudly and the model shows it ----
        TsVoiceManager.manualConnect("bm-missing");
        check(TsVoiceManager.getState() == TsVoiceManager.State.ERROR
                        && TsVoiceManager.getLastError().contains("bm-missing"),
                "G6: an unknown bookmark id errors with the id in the message");

        // ---- G7: credentials stay endpoint-isolated between two bookmarks ----
        EndpointCredentialStore.set("a.local", 9987, "pass-a", "chan-a");
        EndpointCredentialStore.set("b.local", 9988, "pass-b", "chan-b");
        check("pass-a".equals(EndpointCredentialStore.get("a.local", 9987).serverPassword())
                        && "chan-b".equals(EndpointCredentialStore.get("b.local", 9988).channelPassword())
                        && EndpointCredentialStore.get("a.local", 9988) == null,
                "G7: credentials resolve per endpoint, never across hosts/ports");

        TsVoiceManager.switchToSvc();
        if (failures != 0) {
            throw new AssertionError(failures + " GUI flow checks failed");
        }
        System.out.println("GUI flow regression: all checks passed");
    }
}
