package com.tsvoice.client.ts3voice;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TS3 语音客户端协议自测工具（CLI，不依赖 MC/SVC）。
 *
 * <p>用法：{@code java com.tsvoice.client.ts3voice.Ts3VoiceSelfTest [host] [port] [channelName] [durationSec] [tcp]}</p>
 * <p>第 5 个参数传 {@code tcp} 时走 tsbridge 隧道端口（TCP）。</p>
 *
 * <p>测试内容：Ed25519 自检 → 身份生成（level 24）→ init1 握手 → 加密协商 →
 * clientinit → 等待自身 cliententerview → clientmove 到指定频道 → 发送 opus 静音帧 →
 * 统计收到的语音帧 → clientdisconnect。</p>
 */
public final class Ts3VoiceSelfTest {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9987;
        String channel = args.length > 2 ? args[2] : "";
        int durationSec = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        boolean tcp = args.length > 4 && "tcp".equalsIgnoreCase(args[4]);

        Ts3Connection.DEBUG = true;
        System.out.println("=== TS3 voice client self test ===");
        if (!Ed25519Math.selfTest()) {
            System.out.println("[FAIL] Ed25519 self test (RFC 8032 vector 1)");
            System.exit(1);
        }
        System.out.println("[ok] Ed25519 self test (RFC 8032 vector 1)");

        System.out.println("[..] generating identity (level 24, up to 5s)...");
        long t0 = System.currentTimeMillis();
        Ts3Identity identity = Ts3Identity.generate(24, 5_000);
        System.out.printf("[ok] identity generated in %dms: uid=%s level=%d offset=%d%n",
                System.currentTimeMillis() - t0, identity.getClientUid(),
                identity.getSecurityLevel(), identity.getValidKeyOffset());

        Ts3VoiceClient client = new Ts3VoiceClient(identity, "TsBridgeSelfTest", channel, tcp);
        AtomicInteger voiceIn = new AtomicInteger(0);
        client.setVoiceListener(frame -> {
            voiceIn.incrementAndGet();
            if (voiceIn.get() <= 3) {
                System.out.printf("[ok] voice frame from clid=%d codec=%d bytes=%d%n",
                        frame.senderClid(), frame.codec(), frame.opus().length);
            }
        });

        System.out.println("[..] connecting to " + host + ":" + port + " ...");
        t0 = System.currentTimeMillis();
        client.connect(InetAddress.getByName(host), port, 15_000);
        System.out.printf("[ok] connected in %dms (clid=%d)%n",
                System.currentTimeMillis() - t0, client.getMyClid());

        // 发送 opus 静音帧（DTX 3 字节静音包），20ms 一帧
        byte[] silence = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};
        Thread sender = new Thread(() -> {
            long sent = 0;
            long deadline = System.currentTimeMillis() + durationSec * 1000L;
            while (System.currentTimeMillis() < deadline && client.isConnected()) {
                try {
                    client.sendVoice(silence, Ts3VoiceClient.CODEC_OPUS_VOICE);
                    sent++;
                    Thread.sleep(20);
                } catch (Exception e) {
                    System.out.println("[warn] send failed: " + e.getMessage());
                    break;
                }
            }
            System.out.println("[ok] voice frames sent: " + sent);
        }, "selftest-sender");
        sender.start();

        // 观察：打印接收到的命令统计
        long deadline = System.currentTimeMillis() + durationSec * 1000L;
        while (System.currentTimeMillis() < deadline && client.isConnected()) {
            Thread.sleep(500);
        }
        sender.interrupt();
        sender.join(2000);

        System.out.println("clients on server: " + client.debugClients());
        System.out.println("voice frames received: " + voiceIn.get());

        System.out.println("[..] disconnecting...");
        client.disconnect();
        Thread.sleep(500);
        boolean ok = client.wasEverConnected();
        System.out.println(ok ? "=== SELF TEST PASSED ===" : "=== SELF TEST FAILED ===");
        System.exit(ok ? 0 : 1);
    }
}
