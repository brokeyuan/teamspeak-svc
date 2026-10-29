package com.tsvoice.client.backend;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.plugins.impl.RawUdpPacketImpl;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SVC_MC_CHANNEL 客户端 Socket（E2/T36 修订）：委托型双模 socket。
 *
 * <p>T36 修订前的缺陷：本 socket 一旦在 initialization event 注册就无条件
 * 接管 SVC 客户端语音收发 —— 接收队列只进 MC 通道帧，send 只进 MC 桥，
 * 没有 UDP 委托。服务端未宣告 MC_CHANNEL（原生 SVC UDP 服务器）时，注册
 * 即打断原生 UDP 语音；关闭该对象也不会让 SVC 换回默认 UDP socket
 * （实现对象在 {@code ClientVoicechatConnection} 构造时已选定）。</p>
 *
 * <p>修复：open() 创建<b>真实 DatagramSocket</b>（与 SVC 默认实现
 * ClientVoicechatSocketImpl 相同的 4096 接收语义）。两种模式：</p>
 * <ul>
 *   <li><b>UDP 模式（默认）</b>：read 阻塞在原生 socket，send 直写原生
 *       socket —— 与默认实现逐字节同语义，原生 SVC 服务器完全不受影响；</li>
 *   <li><b>MC 通道模式</b>：服务端宣告 MC_CHANNEL 后（activateMcChannel）
 *       read 改从 MC 队列取帧、send 进 MC 桥；原生 UDP socket 关闭。</li>
 * </ul>
 *
 * <p>会话级有界队列（1024 帧，满丢最旧并计数）；close 唤醒阻塞 read；
 * {@link #isClosed()} 反映真实状态。</p>
 */
public final class SvcMcChannelSocket implements ClientVoicechatSocket {

    /** 接收队列容量（有界；SVC 语音帧 ~1 KiB/帧，1024 帧 ≈ 20s 缓冲上限）。 */
    static final int QUEUE_CAPACITY = 1024;
    /** 与 SVC 默认实现一致的 UDP 接收缓冲（VoicechatSocketBase.BUFFER）。 */
    static final int UDP_BUFFER = 4096;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final RawUdpPacket[] ring = new RawUdpPacket[QUEUE_CAPACITY];
    private int head;
    private int tail;
    private int count;
    private final AtomicBoolean closed = new AtomicBoolean(true);
    /** false：UDP 模式（原生委托）；true：MC 通道模式。 */
    private volatile boolean mcChannelMode;
    /** open() 前到达的宣告（closed 守卫不能吞掉切换；open() 兑现）。 */
    private volatile boolean pendingMcChannel;
    /** MC 通道模式启用前的原生 UDP socket（关闭即不再可用，不会复活）。 */
    private volatile DatagramSocket udpSocket;
    private long droppedFrames;
    private SocketAddress lastAddress;

    /** SVC 接收线程调用：阻塞取一帧；closed 时抛出（不悬挂）。 */
    @Override
    public RawUdpPacket read() throws Exception {
        lock.lock();
        try {
            while (count == 0) {
                if (closed.get()) {
                    throw new java.io.IOException("socket closed");
                }
                if (mcChannelMode) {
                    if (!notEmpty.await(100, TimeUnit.MILLISECONDS)) {
                        continue; // 周期醒检查 closed
                    }
                } else {
                    // UDP 模式：离开锁去原生 socket 阻塞接收（有界，可中断）。
                    DatagramSocket udp = udpSocket;
                    if (udp == null || udp.isClosed()) {
                        throw new java.io.IOException("native UDP socket unavailable");
                    }
                    lock.unlock();
                    RawUdpPacket packet;
                    try {
                        packet = readUdp(udp);
                    } catch (java.net.SocketException e) {
                        lock.lock();
                        try {
                            // F1：受控换态 —— 本对象仍有效、且 UDP socket 因内部
                            // activateMcChannel() 关闭时，这不是错误：回到循环顶，
                            // 读取线程无缝转入 MC 队列模式。真正的 close（isClosed）
                            // 与其他 socket 故障仍按契约抛出，SVC 读线程据此退出。
                            if (!closed.get() && mcChannelMode && udp == switchedFromUdp) {
                                continue;
                            }
                        } finally {
                            lock.unlock();
                        }
                        throw e;
                    } finally {
                        lock.lock();
                    }
                    return packet;
                }
            }
            RawUdpPacket packet = ring[head];
            ring[head] = null;
            head = (head + 1) % QUEUE_CAPACITY;
            count--;
            lastAddress = packet.getSocketAddress();
            return packet;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 受控换态识别标记：activateMcChannel() 关闭的原生 socket 引用。read() 在
     * 该 socket 上收到 SocketException 且对象未关闭、模式已切 MC 时视为受控
     * 唤醒（不是错误）。任何其他 socket 的异常仍然向外传播。
     */
    private volatile DatagramSocket switchedFromUdp;

    /** 原生 UDP 接收（SVC 默认实现语义：4096 缓冲，超长 IOException 拒绝）。 */
    private RawUdpPacket readUdp(DatagramSocket udp) throws java.io.IOException {
        DatagramPacket p = new DatagramPacket(new byte[UDP_BUFFER], UDP_BUFFER);
        udp.receive(p);
        int len = p.getLength();
        if (len >= UDP_BUFFER) {
            throw new java.io.IOException("UDP packet too large: " + len);
        }
        byte[] data = new byte[len];
        System.arraycopy(p.getData(), p.getOffset(), data, 0, len);
        return new RawUdpPacketImpl(data, p.getSocketAddress(), System.currentTimeMillis());
    }

    /** SVC 发送路径：MC 模式进桥；UDP 模式直写原生 socket。 */
    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed.get()) {
            throw new java.io.IOException("socket closed");
        }
        if (mcChannelMode) {
            java.util.function.Consumer<byte[]> bridge = mcSender;
            if (bridge == null) {
                throw new java.io.IOException("MC channel sender not wired");
            }
            bridge.accept(data);
            return;
        }
        DatagramSocket udp = udpSocket;
        if (udp == null || udp.isClosed()) {
            throw new java.io.IOException("native UDP socket unavailable");
        }
        udp.send(new DatagramPacket(data, data.length, address));
    }

    private volatile java.util.function.Consumer<byte[]> mcSender;

    /** MC 通道发送桥（客户端 manager 接线；Fabric send 线程安全）。 */
    public void setMcSender(java.util.function.Consumer<byte[]> sender) {
        this.mcSender = sender;
    }

    /** Fabric 接收（主线程）：S2C SVC 语音帧入队。 */
    public void onMcDatagram(byte[] data) {
        if (closed.get() || !mcChannelMode || data == null || data.length == 0
                || data.length > com.tsvoice.common.net.SvcMcChannelPayloads.MAX_VOICE_BYTES) {
            return;
        }
        lock.lock();
        try {
            while (count >= QUEUE_CAPACITY) {
                ring[head] = null;
                head = (head + 1) % QUEUE_CAPACITY;
                count--;
                droppedFrames++;
            }
            ring[tail] = new RawUdpPacketImpl(data,
                    InetSocketAddress.createUnresolved("mc", 0), System.currentTimeMillis());
            tail = (tail + 1) % QUEUE_CAPACITY;
            count++;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 服务端宣告 MC_CHANNEL：切换到 MC 通道模式（关闭原生 UDP socket ——
     * 切换是单向的；断连/换服必须整体重建本 socket，不能原地切回 UDP，
     * 否则 SVC 连接的地址状态会与新 socket 的本地端口不一致）。
     *
     * <p>宣告可能先于 SVC 的 open()（initialization event 注册 → 连接构造时
     * open）到达：closed 守卫不能吞掉这次切换 —— 记 pending 标记，open()
     * 兑现（不创建 UDP socket），C4/E2 顺序无关性成立。</p>
     */
    public void activateMcChannel() {
        pendingMcChannel = true;
        if (closed.get() || mcChannelMode) {
            return; // open() 时按 pending 兑现
        }
        mcChannelMode = true;
        DatagramSocket udp = udpSocket;
        udpSocket = null;
        switchedFromUdp = udp; // F1：read() 的受控换态识别标记
        if (udp != null && !udp.isClosed()) {
            udp.close(); // 唤醒阻塞在 receive 的读线程（read 捕获后转入 MC 模式）
        }
    }

    /** 当前是否处于 MC 通道模式（诊断）。 */
    public boolean isMcChannelMode() {
        return mcChannelMode;
    }

    @Override
    public void open() {
        closed.set(false);
        lock.lock();
        try {
            head = tail = count = 0;
        } finally {
            lock.unlock();
        }
        // open() 前到达的 MC_CHANNEL 宣告在此兑现（不创建 UDP socket）；
        // 否则默认 UDP 模式（原生保护）。
        if (pendingMcChannel) {
            mcChannelMode = true;
            return;
        }
        if (!mcChannelMode && udpSocket == null) {
            try {
                udpSocket = new DatagramSocket();
            } catch (Exception e) {
                throw new IllegalStateException("cannot open native UDP socket", e);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        DatagramSocket udp = udpSocket;
        udpSocket = null;
        if (udp != null && !udp.isClosed()) {
            udp.close();
        }
        lock.lock();
        try {
            head = tail = count = 0;
            notEmpty.signalAll(); // 唤醒阻塞 read
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /** 已丢弃帧数（诊断/测试）。 */
    public long droppedFrames() {
        return droppedFrames;
    }

    /** 最近一次 read 的帧地址（诊断）。 */
    public SocketAddress lastReadAddress() {
        return lastAddress;
    }
}
