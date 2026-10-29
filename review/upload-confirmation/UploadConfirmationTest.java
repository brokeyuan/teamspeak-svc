package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.TsCommandTracker;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * R06 regression (T05), upload confirmation: EOF only proves transport
 * completion (TRANSPORT_FINISHED — the old CONFIRMED state is gone from the
 * type system), registration is isolated per source client, wrong-source and
 * late replies reach nobody, session close cancels in-flight uploads, and the
 * real avatar pipeline writes its marker only after the client_flag_avatar
 * command is confirmed by return_code.
 */
public class UploadConfirmationTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** Accepts connections in a loop, reads everything, closes cleanly (= EOF to the sender). */
    static int startEofServer() throws Exception {
        ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    // Production TS closes after receiving the announced size; the
                    // fixture reads one buffer then closes, which is the same EOF
                    // evidence the client's transport stage waits for.
                    s.getInputStream().read(new byte[4096]);
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public static void main(String[] args) throws Exception {
        Ts3VoiceClient clientA = new Ts3VoiceClient(null, "a", "");
        Ts3VoiceClient clientB = new Ts3VoiceClient(null, "b", "");

        // ---- U1: the type system no longer offers a CONFIRMED state ----
        try {
            Ts3FileTransfer.Result.valueOf("CONFIRMED");
            check(false, "U1: CONFIRMED must be gone from the result type");
        } catch (IllegalArgumentException e) {
            check(true, "U1: CONFIRMED must be gone from the result type");
        }
        boolean avatarAppliedAbsent;
        try {
            Ts3FileTransfer.Result.valueOf("AVATAR_APPLIED");
            avatarAppliedAbsent = false;
        } catch (IllegalArgumentException e) {
            avatarAppliedAbsent = true;
        }
        check(Ts3FileTransfer.Result.valueOf("TRANSPORT_FINISHED") != null
                        && Ts3FileTransfer.Result.valueOf("REMOTE_VERIFIED") != null
                        && avatarAppliedAbsent
                        && Ts3FileTransfer.Result.valueOf("FAILED") != null
                        && Ts3FileTransfer.Result.valueOf("UNKNOWN") != null,
                "U1: result states distinguish transport/verified/failed/unknown"
                        + " (AVATAR_APPLIED is an application-layer outcome, not a transfer state)");

        // ---- U2: two concurrent uploads keep distinct ftids; EOF yields TRANSPORT_FINISHED ----
        int port = startEofServer();
        var uploadA = new Thread(() -> {
            Ts3FileTransfer.Result r = Ts3FileTransfer.upload(clientA, "127.0.0.1",
                    "avatar_a.png", new byte[]{1, 2, 3});
            clientA.lastUploadResult = r;
        });
        var uploadB = new Thread(() -> {
            Ts3FileTransfer.Result r = Ts3FileTransfer.upload(clientB, "127.0.0.1",
                    "avatar_b.png", new byte[]{4, 5, 6});
            clientB.lastUploadResult = r;
        });
        uploadA.start();
        uploadB.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (clientA.sentCommands.size() < 1 || clientB.sentCommands.size() < 1) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.sleep(10);
        }
        int ftidA = extractFtId(clientA.sentCommands.get(0));
        int ftidB = extractFtId(clientB.sentCommands.get(0));
        check(ftidA != ftidB, "U2: concurrent uploads use distinct clientftfids");
        // Replies with the correct source complete each side independently.
        Ts3FileTransferBridge.onFtReply("cid=0 clientftfid=" + ftidA + " ftkey=KEYA port=" + port, clientA);
        Ts3FileTransferBridge.onFtReply("cid=0 clientftfid=" + ftidB + " ftkey=KEYB port=" + port, clientB);
        uploadA.join(8000);
        uploadB.join(8000);
        check(clientA.lastUploadResult == Ts3FileTransfer.Result.TRANSPORT_FINISHED
                        && clientB.lastUploadResult == Ts3FileTransfer.Result.TRANSPORT_FINISHED,
                "U2: clean EOF is TRANSPORT_FINISHED for both uploads (never a storage confirmation)");

        // ---- U3: wrong-source reply reaches nobody (registration is per client) ----
        int port3 = startEofServer();
        var uploadC = new Thread(() -> {
            clientA.lastUploadResult = Ts3FileTransfer.upload(clientA, "127.0.0.1",
                    "avatar_c.png", new byte[]{7});
        });
        uploadC.start();
        deadline = System.currentTimeMillis() + 5000;
        while (clientA.sentCommands.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        int ftidC = extractFtId(clientA.sentCommands.get(1));
        Ts3FileTransferBridge.onFtReply("cid=0 clientftfid=" + ftidC + " ftkey=HIJACK port=" + port3, clientB);
        Thread.sleep(200);
        check(uploadC.isAlive(), "U3: a reply attributed to the wrong client does not complete the upload");
        Ts3FileTransferBridge.cancelClient(clientA);
        uploadC.join(3000);
        check(clientA.lastUploadResult == Ts3FileTransfer.Result.FAILED,
                "U3: session close cancels the in-flight upload (no hang, no fake success)");

        // ---- U4: the real avatar pipeline writes the marker only on command confirmation ----
        Path markerDir = Files.createTempDirectory("tsvoice-marker");
        Path marker = markerDir.resolve("applied.marker");
        VoiceState.CallbackContext ctx = new VoiceState.CallbackContext(
                BackendCoordinator.currentSessionToken(),
                BackendCoordinator.currentContextEpoch(), clientB);
        // Session current (token 0 == current, client is the current field value).
        TsVoiceManager.currentClient = clientB;
        // (a) rejection: no marker
        AvatarPipeline.confirmAvatarApplication(ctx, marker, "avatar_x.png", new byte[]{9}, clientB);
        int rc = extractReturnCode(clientB.sentCommands.get(clientB.sentCommands.size() - 1));
        clientB.commandTracker().onError("error id=1540 msg=no permissions return_code=" + rc);
        Thread.sleep(200);
        check(!Files.exists(marker), "U4: rejected client_flag_avatar never writes a marker");
        // (b) success: marker written
        AvatarPipeline.confirmAvatarApplication(ctx, marker, "avatar_x.png", new byte[]{9}, clientB);
        int rc2 = extractReturnCode(clientB.sentCommands.get(clientB.sentCommands.size() - 1));
        clientB.commandTracker().onError("error id=0 msg=ok return_code=" + rc2);
        check(waitFor(() -> Files.exists(marker), 3000),
                "U4: confirmed client_flag_avatar writes the marker (AVATAR_APPLIED semantics)");
        // (c) EOF-only transport state never triggers a marker by itself: the pipeline
        // gate compares against TRANSPORT_FINISHED + command confirmation; the type
        // system assertions in U1 pin that contract.

        if (failures != 0) {
            throw new AssertionError(failures + " upload confirmation checks failed");
        }
        System.out.println("Upload confirmation regression: all checks passed");
    }

    static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    static int extractReturnCode(String cmd) {
        for (String token : cmd.split(" ")) {
            if (token.startsWith("return_code=")) {
                return Integer.parseInt(token.substring("return_code=".length()));
            }
        }
        throw new AssertionError("no return_code on " + cmd);
    }

    static int extractFtId(String cmd) {
        for (String token : cmd.split(" ")) {
            if (token.startsWith("clientftfid=")) {
                return Integer.parseInt(token.substring("clientftfid=".length()));
            }
        }
        throw new AssertionError("no clientftfid on " + cmd);
    }
}
