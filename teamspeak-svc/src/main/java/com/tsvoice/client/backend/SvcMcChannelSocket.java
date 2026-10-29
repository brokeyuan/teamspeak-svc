package com.tsvoice.client.backend;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.plugins.impl.RawUdpPacketImpl;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SVC_MC_CHANNEL 客户端 Socket（S2/P5）：实现 SVC 官方
 * {@link ClientVoicechatSocket} API，经 ClientVoicechatInitializationEvent
 * 注册（不使用改写 final 字段的 Mixin —— Extra 的 ClientConnectionMixin 方案
 * 在构造完成后换 socket，旧 socket 的资源生命周期取决于目标 SVC 构造路径，
 * 且与其双装会争用同一注入点）。
 *
 * <p>与 Extra @6669d6f McChannelClientSocket 的差异（源码核实后的自有实现）：
 * <ul>
 *   <li>接收队列<b>会话级有界</b>（1024 帧，满丢最旧并计数），不是静态无界
 *       globalQueue（跨连接共享、无上限）；</li>
 *   <li>{@link #close()} 置 closed 标记、清队列并<b>唤醒阻塞的 read</b>；
 *       {@link #isClosed()} 反映真实状态（Extra 两者皆缺）；</li>
 *   <li>read 的 100ms poll + 阻塞等待在 closed 时立即抛出，SVC 读取线程
 *       正常退出，不悬挂。</li>
 * </ul></p>
 */
public final class SvcMcChannelSocket implements ClientVoicechatSocket {

    /** 接收队列容量（有界；SVC 语音帧 ~1 KiB/帧，1024 帧 ≈ 20s 缓冲上限）。 */
    static final int QUEUE_CAPACITY = 1024;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final RawUdpPacket[] ring = new RawUdpPacket[QUEUE_CAPACITY];
    private int head;
    private int tail;
    private int count;
    private final AtomicBoolean closed = new AtomicBoolean(true);
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
                if (!notEmpty.await(100, TimeUnit.MILLISECONDS)) {
                    continue; // 周期醒检查 closed
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

    /** SVC 发送路径：MC 通道桥由 Fabric 层接线（send 只产出字节，投递在桥）。 */
    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed.get()) {
            throw new java.io.IOException("socket closed");
        }
        // 实际 MC 发送由 SVC 客户端连接桥执行（VoicechatClientApi 不暴露 socket
        // 发送回调；桥在 onSvcConnected 时注入）。
        java.util.function.Consumer<byte[]> bridge = mcSender;
        if (bridge == null) {
            throw new java.io.IOException("MC channel sender not wired");
        }
        bridge.accept(data);
    }

    private volatile java.util.function.Consumer<byte[]> mcSender;

    /** MC 通道发送桥（客户端 manager 接线；Fabric send 线程安全）。 */
    public void setMcSender(java.util.function.Consumer<byte[]> sender) {
        this.mcSender = sender;
    }

    /** Fabric 接收（主线程）：SVC NetworkMessage 原始字节入队。 */
    public void onMcDatagram(byte[] data) {
        if (closed.get() || data == null || data.length == 0
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

    @Override
    public void open() {
        closed.set(false);
        lock.lock();
        try {
            head = tail = count = 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lock.lock();
        try {
            head = tail = count = 0;
            notEmpty.signalAll(); // 唤醒阻塞 read（Extra 缺失的关闭唤醒）
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
