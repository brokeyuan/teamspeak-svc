package com.tsvoice.client.ts3voice;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * TS3 语音协议 UDP 连接层（源：TSLib PacketHandler.cs 的客户端方向移植）。
 *
 * <ul>
 *     <li>出站：按类型维护包计数（Init1 固定 id=101）、命令包 ack 重发（RTO 退避）、
 *         分片、keepalive ping（1s 间隔）；</li>
 *     <li>入站：代际窗口去重（语音）、命令分片重组 + QuickerLz 解压、ping→pong；</li>
 *     <li>30 秒无任何消息判超时断开。</li>
 * </ul>
 */
public final class Ts3Connection {

    public static boolean DEBUG = false;
    public static boolean DUMP_FRAGMENTS = false;

    /** 最大出站包大小（含 MAC+header）。 */
    public static final int MAX_OUT_PACKET_SIZE = 500;
    /** 接收窗口大小。 */
    public static final int RECEIVE_WINDOW = 128;
    private static final long PACKET_TIMEOUT_MS = 30_000;
    private static final long MAX_RETRY_INTERVAL_MS = 1_000;
    private static final long PING_INTERVAL_MS = 1_000;
    private static final int CLOCK_RESOLUTION_MS = 100;

    private final Ts3Crypt crypt;
    /** 传输层（UDP 原生 / TCP 隧道），构造时决定。 */
    private final TsTransport transport;
    private final Object sendLock = new Object();
    private Ts3VoiceClient client;

    void bindClient(Ts3VoiceClient c) {
        this.client = c;
    }

    private volatile boolean closed = false;
    private int clientNid = 0;

    /**
     * 出站写线程与有界队列（F09）：sendLock 内只做计数/加密/入队（快），
     * transport.send 由独立写线程执行 —— TCP 写阻塞不卡采集线程，也不卡
     * stop()（stop 先关底层 Socket 唤醒阻塞写）。语音队列有界且丢最旧，
     * 超过 120ms 的帧直接丢弃；控制命令独立队列不受语音挤占。
     */
    private static final int VOICE_QUEUE_CAPACITY = 5;
    private static final int CONTROL_QUEUE_CAPACITY = 32;
    private static final long MAX_VOICE_AGE_NANOS = 120_000_000L;
    private final java.util.concurrent.ArrayBlockingQueue<OutgoingFrame> voiceQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(VOICE_QUEUE_CAPACITY);
    private final java.util.concurrent.ArrayBlockingQueue<byte[]> controlQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(CONTROL_QUEUE_CAPACITY);
    private final java.util.concurrent.atomic.AtomicLong droppedVoiceFramesCounter =
            new java.util.concurrent.atomic.AtomicLong(0);
    private Thread writerThread;

    private static final class OutgoingFrame {
        final byte[] data;
        final long enqueuedNanos;
        /** 出站语音帧绑定的路由代际（R08/T03）；非语音帧忽略。 */
        final long routeEpoch;

        OutgoingFrame(byte[] data, long enqueuedNanos, long routeEpoch) {
            this.data = data;
            this.enqueuedNanos = enqueuedNanos;
            this.routeEpoch = routeEpoch;
        }
    }

    /** 已丢弃（超龄/溢出）的语音帧数，诊断用。 */
    public long droppedVoiceFrames() {
        return droppedVoiceFramesCounter.get();
    }

    /**
     * 当前有效语音路由代际（R08/T03）：writer 在最终写出点复核。返回
     * {@code Long.MIN_VALUE} 表示当前无有效路由（全部语音帧丢弃，fail-closed）。
     * 由会话层接线到协调器快照，本类不感知会话。
     */
    public void setVoiceRouteEpochSupplier(java.util.function.LongSupplier supplier) {
        this.voiceRouteEpochSupplier = supplier == null ? () -> Long.MIN_VALUE : supplier;
    }

    private volatile java.util.function.LongSupplier voiceRouteEpochSupplier = () -> Long.MIN_VALUE;

    private final int[] packetCounter = new int[Ts3Crypt.PACKET_TYPE_KINDS];
    private final long[] generationCounter = new long[Ts3Crypt.PACKET_TYPE_KINDS];

    // 命令 ack 管理
    private static final class OutPacket {
        final Ts3Packet packet;
        long firstSendMs;
        long lastSendMs;

        OutPacket(Ts3Packet packet, long now) {
            this.packet = packet;
            this.firstSendMs = now;
            this.lastSendMs = now;
        }
    }

    private final Map<Integer, OutPacket> ackManager = new TreeMap<>();
    private OutPacket initPacketCheck;
    private long currentRto = MAX_RETRY_INTERVAL_MS;

    // keepalive / 超时
    private long lastPingMs;
    private long lastMessageMs;

    // 命令重组
    private final com.tsvoice.protocol.PacketSequenceWindow commandWindow =
            new com.tsvoice.protocol.PacketSequenceWindow(RECEIVE_WINDOW);
    private final com.tsvoice.protocol.PacketSequenceWindow commandLowWindow =
            new com.tsvoice.protocol.PacketSequenceWindow(RECEIVE_WINDOW);
    private final com.tsvoice.protocol.BoundedCommandAssembler commandAssembler =
            new com.tsvoice.protocol.BoundedCommandAssembler(this::assembleCommand, System::nanoTime);
    private final com.tsvoice.protocol.BoundedCommandAssembler commandLowAssembler =
            new com.tsvoice.protocol.BoundedCommandAssembler(this::assembleCommand, System::nanoTime);

    private Thread recvThread;
    private Thread resendThread;
    private volatile Runnable onDisconnected;

    public Ts3Connection(Ts3Crypt crypt) {
        this(crypt, false);
    }

    /** @param tcp true = TCP 隧道传输（UDP 受限网络），false = 原生 UDP。 */
    public Ts3Connection(Ts3Crypt crypt, boolean tcp) {
        this(crypt, tcp ? new TcpTransport() : new TsTransport.UdpTransport());
    }

    /** 注入传输层（测试与 P6 独立隧道使用）；协议逻辑与传输无关。 */
    public Ts3Connection(Ts3Crypt crypt, TsTransport transport) {
        this.crypt = crypt;
        this.transport = transport;
    }

    public void setOnDisconnected(Runnable callback) {
        this.onDisconnected = callback;
    }

    /** 本连接的加密器（S1.1/P6：Ts3VoiceClient 传输注入构造器复用既有 crypt）。 */
    public Ts3Crypt crypt() {
        return crypt;
    }

    public void setClientNid(int nid) {
        synchronized (sendLock) {
            this.clientNid = nid;
        }
    }

    public int getClientNid() {
        return clientNid;
    }

    /**
     * 启动连接：绑定 socket、发送 init0、启动接收/重发/写线程。
     */
    public void connect(InetAddress address, int port) throws Ts3VoiceException {
        writerThread = new Thread(this::writerLoop, "teamspeak-svc-voice-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        synchronized (sendLock) {
            try {
                transport.connect(address, port);
            } catch (Exception e) {
                throw new Ts3VoiceException("Failed to connect transport: " + e.getMessage(), e);
            }
            closed = false;
            lastMessageMs = System.currentTimeMillis();
            lastPingMs = 0;
            currentRto = MAX_RETRY_INTERVAL_MS;
            // 旧协议首个 clientinitiv 命令占位（虽然不再发送，但计数必须前移）
            incPacketCounter(PacketType.COMMAND);
            try {
                addOutgoingPacket(crypt.processInit1(null), PacketType.INIT1);
            } catch (Ts3VoiceException e) {
                throw new Ts3VoiceException("Failed to build init0: " + e.getMessage(), e);
            }
        }
        recvThread = new Thread(this::recvLoop, "TsBridge-Ts3VoiceRecv");
        recvThread.setDaemon(true);
        recvThread.start();
        resendThread = new Thread(this::resendLoop, "TsBridge-Ts3VoiceResend");
        resendThread.setDaemon(true);
        resendThread.start();
    }

    /**
     * 关闭连接：先原子失效并直接关闭底层 Socket（唤醒阻塞中的读/写），全程
     * 不获取 sendLock —— 即使对端停止读导致写线程阻塞，本调用也在限时内返回。
     * 随后以 deadline join 后台线程；超时线程为 daemon，不阻塞退出。
     */
    public void stop() {
        closed = true;
        transport.close();
        joinQuietly(writerThread);
        joinQuietly(recvThread);
        joinQuietly(resendThread);
    }

    private void joinQuietly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    // ---- 出站 ----

    /**
     * 发送一个协议载荷（自动处理计数、加密、分片、ack 登记）。
     */
    public void addOutgoingPacket(byte[] data, PacketType type) throws Ts3VoiceException {
        addOutgoingPacket(data, type, Long.MIN_VALUE);
    }

    /**
     * 语音帧携带路由代际入队（R08/T03）：writer 在写出点与当前有效代际复核，
     * 不匹配即丢弃 —— 频道移动后旧频道帧不再发出。控制命令使用无代际重载。
     */
    public void addOutgoingPacket(byte[] data, PacketType type, long routeEpoch) throws Ts3VoiceException {
        synchronized (sendLock) {
            if (closed) {
                throw new Ts3VoiceException("Connection closed");
            }
            if (data.length + (Ts3Crypt.MAC_LEN + 5) > MAX_OUT_PACKET_SIZE) {
                // 本客户端的命令都很小，不分片不压缩（超出即协议使用错误）
                if (type == PacketType.VOICE) {
                    throw new Ts3VoiceException("Voice packet too big");
                }
                throw new Ts3VoiceException("Packet too big (" + data.length + "), fragmentation not supported");
            }
            sendOutgoingData(data, type, 0, routeEpoch);
        }
    }

    /** 请求换频道等许可撤销后清空排队中的旧代际语音帧（已取出帧由 writer 复核覆盖）。 */
    public void clearQueuedVoice() {
        voiceQueue.clear();
    }

    private void sendOutgoingData(byte[] data, PacketType type, int extraFlags, long routeEpoch) throws Ts3VoiceException {
        int id;
        long gen;
        if (type != PacketType.INIT1) {
            id = packetCounter[type.value];
            gen = generationCounter[type.value];
            incPacketCounter(type);
        } else {
            id = 101;
            gen = 0;
        }

        Ts3Packet packet = Ts3Packet.out(data, type, id, gen, clientNid);
        long now = System.currentTimeMillis();
        switch (type) {
            case PacketType.VOICE, PacketType.VOICE_WHISPER -> {
                packet.setFlag(PacketType.FLAG_UNENCRYPTED, true);
                packet.data[0] = (byte) (packet.packetId >>> 8);
                packet.data[1] = (byte) packet.packetId;
            }
            case PacketType.COMMAND, PacketType.COMMAND_LOW -> {
                packet.setFlag(PacketType.FLAG_NEW_PROTOCOL, true);
                ackManager.put(packet.packetId, new OutPacket(packet, now));
            }
            case PacketType.PING -> {
                packet.setFlag(PacketType.FLAG_UNENCRYPTED, true);
            }
            case PacketType.PONG -> packet.setFlag(PacketType.FLAG_UNENCRYPTED, true);
            case PacketType.ACK -> { /* 正常加密 */ }
            case PacketType.ACK_LOW -> packet.setFlag(PacketType.FLAG_UNENCRYPTED, true);
            case PacketType.INIT1 -> {
                packet.setFlag(PacketType.FLAG_UNENCRYPTED, true);
                initPacketCheck = new OutPacket(packet, now);
            }
            default -> throw new Ts3VoiceException("Unhandled packet type " + type);
        }

        crypt.encrypt(packet);
        sendRaw(packet, type, routeEpoch);
    }

    private void incPacketCounter(PacketType type) {
        packetCounter[type.value]++;
        if (packetCounter[type.value] == 0x10000) {
            packetCounter[type.value] = 0;
            generationCounter[type.value]++;
        }
    }

    private void sendRaw(Ts3Packet packet, PacketType type, long routeEpoch) throws Ts3VoiceException {
        // 入队而非直接发送（F09）：写线程负责 transport.send；队列满时语音丢最旧、
        // 控制命令抛错（调用方可见），绝不静默无界积压。
        // G2：VOICE_WHISPER 同为媒体 —— 必须走带代际的语音队列，不得落入
        // 控制队列绕过媒体屏障。
        if (type == PacketType.VOICE || type == PacketType.VOICE_WHISPER) {
            OutgoingFrame frame = new OutgoingFrame(packet.raw, System.nanoTime(), routeEpoch);
            while (!voiceQueue.offer(frame)) {
                if (voiceQueue.poll() == null) {
                    continue; // 队列瞬间被并发清空：重试 offer
                }
                droppedVoiceFramesCounter.incrementAndGet();
            }
            return;
        }
        if (!controlQueue.offer(packet.raw)) {
            throw new Ts3VoiceException("Control command queue full (" + CONTROL_QUEUE_CAPACITY + ")");
        }
    }

    private void writerLoop() {
        while (!closed) {
            byte[] control;
            try {
                control = controlQueue.poll();
            } catch (Exception e) {
                if (!closed) {
                    Ts3VoiceClient.LOG.debug("[teamspeak-svc] writer failed: {}", e.getMessage());
                    handleStop();
                }
                return;
            }
            if (control != null) {
                try {
                    transport.send(control);
                } catch (Exception e) {
                    if (!closed) {
                        Ts3VoiceClient.LOG.debug("[teamspeak-svc] writer send failed: {}", e.getMessage());
                        handleStop();
                    }
                    return;
                }
                continue;
            }
            OutgoingFrame frame;
            do {
                try {
                    frame = voiceQueue.poll(5, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (closed) {
                        return;
                    }
                    frame = null;
                }
                if (frame != null && System.nanoTime() - frame.enqueuedNanos > MAX_VOICE_AGE_NANOS) {
                    droppedVoiceFramesCounter.incrementAndGet();
                    frame = null;
                }
                // 最终写出点复核（R08/T03）：帧的频道代际必须等于当前有效路由代际；
                // 频道移动/许可撤销后旧代际帧（含已出队未写出的帧）一律丢弃。
                if (frame != null && frame.routeEpoch != voiceRouteEpochSupplier.getAsLong()) {
                    droppedVoiceFramesCounter.incrementAndGet();
                    frame = null;
                }
            } while (frame == null && !voiceQueue.isEmpty());
            if (frame != null) {
                try {
                    // F2：携带入队时绑定的路由代际（MC 通道等信封传输写入帧头；
                    // 原生 UDP/TCP 默认实现忽略）。
                    transport.send(frame.data, frame.routeEpoch);
                } catch (Exception e) {
                    if (!closed) {
                        Ts3VoiceClient.LOG.debug("[teamspeak-svc] writer send failed: {}", e.getMessage());
                        handleStop();
                    }
                    return;
                }
            }
        }
    }

    // ---- 入站 ----

    private void recvLoop() {
        while (!closed) {
            byte[] raw;
            try {
                raw = transport.receive(4096);
            } catch (Exception e) {
                if (!closed) {
                    if (e instanceof java.net.SocketTimeoutException) {
                        Ts3VoiceClient.LOG.warn("[teamspeak-svc] Transport read timeout (35s) — closing stale tunnel");
                    } else {
                        Ts3VoiceClient.LOG.warn("[teamspeak-svc] Transport receive error: {}", e.getMessage());
                    }
                    handleStop();
                }
                return;
            }
            if (raw == null || raw.length <= 0) {
                continue;
            }
            try {
                handleIncoming(raw);
            } catch (Exception e) {
                Ts3VoiceClient.LOG.warn("[teamspeak-svc] Failed to handle incoming packet: {}", e.toString());
            }
        }
    }

    private void handleIncoming(byte[] raw) throws Ts3VoiceException {
        Ts3Packet packet = Ts3Packet.in(raw);
        if (packet == null) {
            return;
        }
        PacketType type = packet.packetType();
        if (type == null) {
            if (DEBUG) {
                System.out.println("[in ] UNKNOWN packet type " + (packet.typeFlagged & 0x0F) + " raw=" + hex(raw));
            }
            return;
        }
        // 代际推断（仅命令分片需要；语音交给 SVC 的抖动缓冲/序号处理）
        switch (type) {
            case COMMAND -> packet.generationId = commandWindow.generationFor(packet.packetId);
            case COMMAND_LOW -> packet.generationId = commandLowWindow.generationFor(packet.packetId);
            default -> { /* voice/init/ack/ping 无代际 */ }
        }
        if (DEBUG) {
            System.out.println("[in ] " + packet + " rawLen=" + raw.length);
        }
        if (!crypt.decrypt(packet)) {
            if (DEBUG) {
                System.out.println("[in ] UNDECRYPTABLE " + packet + " raw=" + hex(raw));
            }
            Ts3VoiceClient.LOG.debug("[teamspeak-svc] Dropping undecryptable packet {}", packet);
            return;
        }
        lastMessageMs = System.currentTimeMillis();

        switch (type) {
            case PacketType.VOICE, PacketType.VOICE_WHISPER -> {
                // 不在这里丢弃乱序包：SVC 的 AudioChannel 会按 TS 语音序号重排并做 PLC/FEC 补偿
                client.onVoicePacket(packet);
            }
            case PacketType.COMMAND -> receiveCommand(packet, false);
            case PacketType.COMMAND_LOW -> receiveCommand(packet, true);
            case PacketType.PING -> {
                byte[] pong = {(byte) (packet.packetId >>> 8), (byte) packet.packetId};
                addOutgoingPacket(pong, PacketType.PONG);
            }
            case PacketType.PONG -> { /* RTT 可选 */ }
            case PacketType.ACK -> receiveAck(packet);
            case PacketType.ACK_LOW -> { /* 无低优先级出站命令 */ }
            case PacketType.INIT1 -> receiveInitAck(packet);
        }
    }

    // ---- init1 ack 链 ----

    private void receiveInitAck(Ts3Packet packet) throws Ts3VoiceException {
        synchronized (sendLock) {
            if (initPacketCheck == null) {
                return;
            }
            byte[] reply = crypt.processInit1(packet.data);
            initPacketCheck = null;
            if (reply.length > 0) {
                addOutgoingPacket(reply, PacketType.INIT1);
            }
        }
    }

    /** init 握手全部完成（收到 init4 后由 crypt 状态机判断），清除重发检查。 */
    void notifyInitComplete() {
        synchronized (sendLock) {
            initPacketCheck = null;
        }
    }

    // ---- ack ----

    private void sendAck(int ackId, int ackTypeValue) throws Ts3VoiceException {
        byte[] ackData = {(byte) (ackId >>> 8), (byte) ackId};
        addOutgoingPacket(ackData, PacketType.fromValue(ackTypeValue));
    }

    private void receiveAck(Ts3Packet packet) {
        if (packet.data.length < 2) {
            return;
        }
        int ackId = ((packet.data[0] & 0xFF) << 8) | (packet.data[1] & 0xFF);
        synchronized (sendLock) {
            OutPacket removed = ackManager.remove(ackId);
            if (removed != null) {
                long rtt = System.currentTimeMillis() - removed.lastSendMs;
                // RFC 6298 风格 RTO 平滑
                currentRto = Math.max(100, (long) (0.875 * currentRto + 0.125 * Math.min(rtt, MAX_RETRY_INTERVAL_MS)));
                if (currentRto > MAX_RETRY_INTERVAL_MS) {
                    currentRto = MAX_RETRY_INTERVAL_MS;
                }
            }
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, 48);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
        if (b.length > n) sb.append("...(len=").append(b.length).append(')');
        return sb.toString();
    }

    // ---- 重发/心跳线程 ----

    private void resendLoop() {
        while (!closed) {
            try {
                Thread.sleep(CLOCK_RESOLUTION_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) {
                return;
            }
            long now = System.currentTimeMillis();
            List<OutPacket> toResend = new ArrayList<>();
            boolean timeout = false;
            synchronized (sendLock) {
                Iterator<Map.Entry<Integer, OutPacket>> it = ackManager.entrySet().iterator();
                while (it.hasNext()) {
                    OutPacket op = it.next().getValue();
                    if (op.firstSendMs < now - PACKET_TIMEOUT_MS) {
                        timeout = true;
                        break;
                    }
                    if (op.lastSendMs < now - currentRto) {
                        toResend.add(op);
                    }
                }
                if (!timeout && initPacketCheck != null) {
                    if (initPacketCheck.firstSendMs < now - PACKET_TIMEOUT_MS) {
                        timeout = true;
                    } else if (initPacketCheck.lastSendMs < now - currentRto) {
                        toResend.add(initPacketCheck);
                    }
                }
                if (!timeout) {
                    for (OutPacket op : toResend) {
                        op.lastSendMs = now;
                        currentRto = Math.min(currentRto * 2, MAX_RETRY_INTERVAL_MS);
                        try {
                            crypt.encrypt(op.packet);
                            sendRaw(op.packet, PacketType.COMMAND, Long.MIN_VALUE);
                        } catch (Exception e) {
                            Ts3VoiceClient.LOG.debug("[teamspeak-svc] resend failed: {}", e.getMessage());
                        }
                    }
                }
                // keepalive：加密完成后每秒 ping
                if (!timeout && crypt.isCryptoInitComplete() && lastPingMs < now - PING_INTERVAL_MS) {
                    try {
                        addOutgoingPacket(new byte[0], PacketType.PING);
                        lastPingMs = now;
                    } catch (Exception e) {
                        Ts3VoiceClient.LOG.debug("[teamspeak-svc] ping failed: {}", e.getMessage());
                    }
                }
            }
            if (timeout || lastMessageMs < now - PACKET_TIMEOUT_MS) {
                Ts3VoiceClient.LOG.warn("[teamspeak-svc] Voice connection timeout");
                handleStop();
                return;
            }
        }
    }

    private void handleStop() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            transport.close();
        } catch (Exception ignored) {
        }
        Runnable cb = onDisconnected;
        if (cb != null) {
            cb.run();
        }
    }

    private void receiveCommand(Ts3Packet packet, boolean lowPriority) throws Ts3VoiceException {
        com.tsvoice.protocol.PacketSequenceWindow window = lowPriority ? commandLowWindow : commandWindow;
        boolean accepted = window.accept(packet.packetId);
        sendAck(packet.packetId, lowPriority ? PacketType.ACK_LOW.value : PacketType.ACK.value);
        if (!accepted) {
            return;
        }
        com.tsvoice.protocol.BoundedCommandAssembler assembler = lowPriority ? commandLowAssembler : commandAssembler;
        var fragment = new com.tsvoice.protocol.BoundedCommandAssembler.Fragment(
                packet.packetId, packet.generationId, packet.fragmentedFlag(), packet.compressedFlag(), packet.data);
        try {
            for (String command : assembler.receive(fragment, id -> {
                // ACKs are emitted once in receiveCommand, including for duplicate packets.
            })) {
                client.onCommand(command, lowPriority);
            }
        } catch (Ts3VoiceException e) {
            throw e;
        } catch (Exception e) {
            throw new Ts3VoiceException("Failed to assemble command: " + e.getMessage(), e);
        }
    }

    private String assembleCommand(java.util.List<byte[]> fragments, boolean compressed) throws Exception {
        int length = fragments.stream().mapToInt(part -> part.length).sum();
        if (length > 1024 * 1024) {
            throw new Ts3VoiceException("Command exceeds maximum size");
        }
        byte[] merged = new byte[length];
        int offset = 0;
        for (byte[] fragment : fragments) {
            System.arraycopy(fragment, 0, merged, offset, fragment.length);
            offset += fragment.length;
        }
        if (compressed) {
            try {
                merged = com.tsvoice.protocol.QuickerLz.decompress(merged, 1024 * 1024);
            } catch (Exception e) {
                Ts3VoiceClient.LOG.debug("[teamspeak-svc] Dropping malformed compressed command: {}", e.toString());
                return "";
            }
        }
        return new String(merged, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- 语音序号 ----
    // 去重/重排由 SVC 的 AudioChannel 基于 TS 语音序号处理（见 TsAudioInjector）。

    // ---- 命令重组（RingQueue.TryGetCommand 移植） ----

}
