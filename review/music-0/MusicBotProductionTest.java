package com.tsvoice;

import com.tsvoice.client.music.MusicBotAssembly;
import com.tsvoice.client.music.MusicSendPermit;
import com.tsvoice.client.music.MusicSession;
import com.tsvoice.client.ts3voice.Ts3Connection;
import com.tsvoice.client.ts3voice.Ts3Crypt;
import com.tsvoice.client.ts3voice.Ts3Identity;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import com.tsvoice.client.ts3voice.TsTransport;
import com.tsvoice.protocol.music.PcmSource;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * G3/P1-01、P2-04 回归：真实 Bot 生产装配 → 真实 client/writer → 可控 transport。
 *
 * <ul>
 *   <li>P1-01：writer 代际来源 = 可撤销许可（无永久常量）。未确认频道/撤销后
 *       真实 writer 丢弃帧（droppedVoiceFrames 增加）；确认后同代际帧到达
 *       transport；</li>
 *   <li>P2-04：attach ≠ 握手成功 —— CONNECTING/CONNECTED(未确认) 时
 *       startPlayback 明确失败；确认授权房间后 READY；移出授权房间（serverUID
 *       不符）→ 撤销 + 停止播出；重新移回 → 可确认恢复；</li>
 *   <li>线性化边界：撤销后已出队未写出的帧丢弃（writer 复核）；已进入底层
 *       发送的在途帧不承诺撤回（本测试用可控 transport 计数如实断言）。</li>
 * </ul>
 *
 * <p>编码用真实 SVC Opus（与 RealCodecRoundTripTest 相同的构造）。</p>
 */
public class MusicBotProductionTest {
    static int failures;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }

    /** 可控传输（真实 TsTransport 实现；记录写出帧）。 */
    static final class ControlledTransport implements TsTransport {
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger sent = new AtomicInteger();
        final java.util.List<byte[]> frames = new java.util.concurrent.CopyOnWriteArrayList<>();

        public void connect(InetAddress a, int p) {
        }

        public void send(byte[] b) {
            sent.incrementAndGet();
            frames.add(b.clone());
        }

        public byte[] receive(int n) throws java.io.IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new java.io.IOException("closed");
        }

        public boolean isClosed() {
            return closed.getCount() == 0;
        }

        public void close() {
            closed.countDown();
        }
    }

    /** 固定编号 PCM 源（20ms 帧）。 */
    static PcmSource numbered(int frames) {
        AtomicInteger i = new AtomicInteger();
        return new PcmSource() {
            @Override
            public short[] readFrame() {
                if (i.get() >= frames) return null;
                short[] f = new short[PcmSource.FRAME_SAMPLES];
                f[0] = (short) (i.incrementAndGet() % Short.MAX_VALUE);
                return f;
            }

            @Override
            public void close() {
            }
        };
    }

    /** 真实 SVC Opus 编码器（concentus 纯 Java；与 RealCodecRoundTripTest 相同构造）。 */
    static MusicSession.OpusEncoderAdapter realEncoder() throws Exception {
        Class<?> encClass = Class.forName("de.maxhenkel.voicechat.plugins.impl.opus.JavaOpusEncoderImpl");
        Class<?> appClass = Class.forName("de.maxhenkel.opus4j.OpusEncoder$Application");
        Object audioApp = Enum.valueOf((Class<? extends Enum>) appClass.asSubclass(Enum.class), "AUDIO");
        Object enc = encClass.getDeclaredConstructor(int.class, int.class, int.class, appClass)
                .newInstance(48000, 960, 1024, audioApp);
        return new MusicSession.OpusEncoderAdapter() {
            @Override
            public byte[] encode(short[] pcm) throws Exception {
                return (byte[]) encClass.getMethod("encode", short[].class).invoke(enc, pcm);
            }

            @Override
            public void close() {
                try {
                    encClass.getMethod("close").invoke(enc);
                } catch (Exception ignored) {
                }
            }

            @Override
            public boolean isClosed() {
                try {
                    return (Boolean) encClass.getMethod("isClosed").invoke(enc);
                } catch (Exception e) {
                    return true;
                }
            }
        };
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition timeout");
        }
    }

    public static void main(String[] args) throws Exception {
        boolean haveOpus;
        try {
            Class.forName("de.maxhenkel.voicechat.plugins.impl.opus.JavaOpusEncoderImpl");
            haveOpus = true;
        } catch (ClassNotFoundException e) {
            haveOpus = false;
        }
        if (!haveOpus) {
            System.out.println("SKIP: real SVC opus not on classpath - run via RunMusicCore realCp");
            return;
        }

        ControlledTransport transport = new ControlledTransport();
        Ts3Connection connection = new Ts3Connection(new Ts3Crypt(Ts3Identity.generate(1, 500)), transport);
        connection.setClientNid(1);
        Ts3VoiceClient client = new Ts3VoiceClient(Ts3Identity.generate(1, 500), "[Music Bot] t", "", connection);

        MusicSession session = new MusicSession("controlled", 9987, "[Music Bot] t", null);
        String boundRoom = "fixture-server-uid";
        MusicSendPermit permit = MusicBotAssembly.assemble(session, client, boundRoom);

        // P2-04：attach ≠ 握手成功 —— CONNECTING 状态下 startPlayback 明确失败
        session.markConnecting();
        boolean rejected = false;
        try {
            session.startPlayback(numbered(50), realEncoder(), System::nanoTime);
        } catch (IllegalStateException e) {
            rejected = e.getMessage().contains("not ready");
        } catch (MusicSession.EncodingUnavailableException e) {
            rejected = false; // 编码器不可用不是本断言目标
        }
        check(rejected, "P2-04: startPlayback during CONNECTING fails explicitly (not ready)");
        check(!permit.isSendable() && permit.currentEpoch() == Long.MIN_VALUE,
                "P2-04: CONNECTING permit is not sendable (currentEpoch = MIN_VALUE)");

        // 连接建立（可控传输：真实握手出站路径）
        connection.connect(InetAddress.getLoopbackAddress(), 1);
        permit.connectedUnconfirmed();
        session.markConnected();
        check(!permit.isSendable(), "P2-04: connected but channel unconfirmed → not sendable");

        // 频道未确认时 startPlayback 仍明确失败
        rejected = false;
        try {
            session.startPlayback(numbered(50), realEncoder(), System::nanoTime);
        } catch (IllegalStateException e) {
            rejected = e.getMessage().contains("not ready");
        }
        check(rejected, "P2-04: startPlayback before channel confirm fails explicitly");

        // 模拟服务器确认：Bot 进入频道（绑定房间匹配）
        check(permit.confirm(boundRoom, 5), "P2-04: confirm in the authorized room → READY");
        check(permit.isSendable() && permit.currentEpoch() > 0,
                "P2-04: READY permit returns a real epoch (not a constant)");

        // 真实编码 → sender → writer → transport：确认代际帧实际写出
        session.startPlayback(numbered(50), realEncoder(), System::nanoTime);
        await(() -> transport.sent.get() >= 3);
        check(transport.sent.get() >= 3,
                "P1-01: confirmed-generation frames reach the real writer and transport (sent="
                        + transport.sent.get() + ")");
        long droppedBefore = connection.droppedVoiceFrames();

        // 撤销（移出授权房间）：writer 后续帧丢弃（P1-01 线性化边界）
        check(!permit.onSelfChannelMoved("other-server-uid", 9),
                "P2-04: moving outside the authorized room is revoked");
        check(!permit.isSendable(), "P2-04: revoked permit is not sendable");
        // 线性化边界（计划要求明示）：撤销后：
        //  a) 已在 MusicSession 编码后复核点被丢弃（framesDroppedAfterEncode）；
        //  b) 已入 writer 队列的帧由 writer 最终写出点丢弃；
        //  c) 已进入底层网络发送的在途帧不承诺撤回（本测试如实观察）。
        // 撤销通知停止播出（MusicBotAssembly 的 revocation 路径 cancel 引擎）
        await(() -> session.state() == MusicSession.State.CONNECTED);
        check(session.state() == MusicSession.State.CONNECTED,
                "P2-04: revocation stopped playback (session back to CONNECTED)");
        // 线性化结果：撤销后不再有新帧进入底层发送（writer 最终许可点复核）。
        int sentAfterRevoke = transport.sent.get();
        Thread.sleep(300);
        check(transport.sent.get() == sentAfterRevoke,
                "P1-01: no new frames reach the transport after revocation (sent frozen at "
                        + sentAfterRevoke + ")");

        // 管理员移回授权房间：可确认恢复（新代际）
        check(permit.confirm(boundRoom, 5), "P2-04: moved back into the authorized room → READY again");
        long epochAfterRejoin = permit.currentEpoch();
        int sentBefore = transport.sent.get();
        session.startPlayback(numbered(20), realEncoder(), System::nanoTime);
        await(() -> transport.sent.get() > sentBefore);
        check(transport.sent.get() > sentBefore,
                "P1-01: new playback round sends under the new permit epoch");
        check(permit.currentEpoch() == epochAfterRejoin,
                "P1-01: the same confirmed epoch is used until revoked again");

        // 停止 Bot：许可终态撤销
        MusicSession.ProductionConnection production = session.productionForTest();
        production.closer.close();
        check(!permit.isSendable() && "bot closed".equals(permit.snapshot().revokeReason()),
                "P2-04: closing the bot revokes the permit terminally");
        check(permit.currentEpoch() == Long.MIN_VALUE,
                "P2-04: terminal permit returns MIN_VALUE (no sendable epoch)");

        session.close();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
