package com.tsvoice.client.ts3voice;

import com.tsvoice.protocol.relay.TsRelayWire;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TS_MC_CHANNEL 客户端传输（S1.4/P6）：以 MC 服务端中继通道为 TS 数据报载体。
 * 实现 {@link TsTransport} 契约（CLOSED/OPENING/OPEN/CLOSING 状态、有界接收
 * 队列、receive 最大长度、close 幂等），由 {@link Ts3Connection} 注入使用 ——
 * 握手/加密/序号与原生 UDP 路径完全一致。
 *
 * <p>地址语义：{@link #connect(InetAddress, int)} 忽略参数（协议规定客户端
 * 不解析 TS 主机名 —— 目标 endpoint 由服务端按管理员 allowlist 解析）。调用方
 * 必须先经 {@link #attachRelaySession} 绑定已建立的 MC 中继会话（OPENED 之后的
 * relaySessionId 与协商限额），再调用 {@link Ts3Connection#connect}。</p>
 *
 * <p>发送边界（R21 修订约束）：本类不重贴路由代际 —— Ts3Connection 的
 * OutgoingFrame 在入队时已绑定入队时的 routeEpoch；本类只搬运完整数据报。
 * 服务端在转发前按 ROUTE_BARRIER 推进的代际与帧内代际比对（数据报原样字节，
 * 不解码 TS 加密载荷）。</p>
 */
public final class TsMcChannelTransport implements TsTransport {

    /** 接收队列容量（有界；满时丢弃最旧并计数 —— 与 Ts3Connection 语音队列同策略）。 */
    static final int RECEIVE_QUEUE_CAPACITY = 64;
    /** 接收单包最大长度（T35：与协商外层上限一致 —— 4096；不再 2048 静默截断）。 */
    static final int MAX_RECEIVE_BYTES = TsRelayWire.MAX_DATAGRAM_BYTES;

    public enum State { CLOSED, OPENING, OPEN, CLOSING }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition dataAvailable = lock.newCondition();
    private final ArrayDeque<byte[]> receiveQueue = new ArrayDeque<>(RECEIVE_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile State state = State.CLOSED;
    /** 已建立的 MC 中继会话 id（OPENED 下发；16 字节）。 */
    private volatile byte[] relaySessionId;
    /** 服务端协商限额（OPENED；本类只记录供诊断，实际执行在服务端）。 */
    private volatile int negotiatedMaxDatagramBytes = TsRelayWire.MAX_DATAGRAM_BYTES;
    private volatile long droppedReceiveFrames;
    private volatile IOException pendingCloseException;

    /**
     * 绑定 MC 中继会话（客户端网络层收到 OPENED 后调用）。此后 transport 进入
     * OPEN；close() 之前可重复 attach（会话重建）。
     */
    public void attachRelaySession(byte[] relaySessionId, int maxDatagramBytes) throws IOException {
        if (closed.get()) {
            throw new IOException("transport closed");
        }
        if (relaySessionId == null || relaySessionId.length != TsRelayWire.RELAY_SESSION_ID_BYTES) {
            throw new IOException("invalid relay session id");
        }
        this.relaySessionId = relaySessionId.clone();
        this.negotiatedMaxDatagramBytes = Math.max(1, Math.min(TsRelayWire.MAX_DATAGRAM_BYTES, maxDatagramBytes));
        state = State.OPEN;
    }

    public State state() {
        return state;
    }

    public byte[] relaySessionId() {
        byte[] id = relaySessionId;
        return id == null ? null : id.clone();
    }

    public long droppedReceiveFrames() {
        return droppedReceiveFrames;
    }

    @Override
    public void connect(java.net.InetAddress address, int port) {
        // MC 中继：地址参数无意义（endpoint 解析在服务端）。OPENING 状态由
        // attachRelaySession 推进到 OPEN；这里是协议层 connect 的生命周期占位。
        if (state == State.CLOSED) {
            state = State.OPENING;
        }
    }

    @Override
    public void send(byte[] data) throws IOException {
        if (closed.get()) {
            throw new IOException("transport closed");
        }
        byte[] session = relaySessionId;
        if (session == null || state != State.OPEN) {
            throw new IOException("relay session not attached");
        }
        if (data == null || data.length == 0 || data.length > negotiatedMaxDatagramBytes) {
            throw new IOException("datagram out of negotiated bounds: "
                    + (data == null ? 0 : data.length) + " > " + negotiatedMaxDatagramBytes);
        }
        // 交给 MC 网络层；编码与限额在那里执行（DATA_C2S 帧）。
        relaySender.accept(data);
    }

    /** MC 网络层发送桥（客户端 manager 接线；在 MC netty 线程安全的发送路径上执行）。 */
    private volatile java.util.function.Consumer<byte[]> relaySender = data -> {
        throw new IllegalStateException("relay sender not wired");
    };

    public void setRelaySender(java.util.function.Consumer<byte[]> sender) {
        this.relaySender = sender == null ? data -> {
            throw new IllegalStateException("relay sender not wired");
        } : sender;
    }

    /**
     * MC 网络层下行投递（DATA_S2C 帧解出的完整 TS 数据报）。有界队列；满时
     * 丢最旧（与语音队列语义一致，计数可观测）。
     */
    public void onRelayDatagram(byte[] datagram) {
        if (closed.get() || datagram == null
                || datagram.length == 0 || datagram.length > MAX_RECEIVE_BYTES) {
            return;
        }
        lock.lock();
        try {
            while (receiveQueue.size() >= RECEIVE_QUEUE_CAPACITY) {
                receiveQueue.poll();
                droppedReceiveFrames++;
            }
            receiveQueue.offer(datagram.clone());
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] receive(int maxLen) throws IOException {
        lock.lock();
        try {
            while (receiveQueue.isEmpty()) {
                if (closed.get()) {
                    throw pendingCloseException != null ? pendingCloseException
                            : new IOException("transport closed");
                }
                try {
                    dataAvailable.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("receive interrupted");
                }
            }
            byte[] data = receiveQueue.poll();
            // T35：调用方缓冲不足时明确失败 —— 绝不静默裁剪（截断的 TS 包不是
            // 合法数据；调用方必须按协商 maxDatagramBytes 分配缓冲）。
            if (data.length > maxLen) {
                // 帧不丢回队列（调用方缓冲与协商不符是配置错误，明确报错终止）：
                // 丢弃该帧并抛出，调用方可据此重建会话。
                throw new IOException("receive buffer too small: frame=" + data.length
                        + " > maxLen=" + maxLen + " (use the negotiated maxDatagramBytes)");
            }
            return data;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /** 关闭（幂等）：唤醒阻塞 receive，MC 中继会话由调用方（manager）负责 CLOSE。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = State.CLOSING;
        lock.lock();
        try {
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        state = State.CLOSED;
    }

    /** 关闭并让后续 receive 抛出指定异常（会话被服务端关闭时使用）。 */
    public void closeWithException(IOException reason) {
        this.pendingCloseException = reason;
        close();
    }
}
