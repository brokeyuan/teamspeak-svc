package com.svcb.tsbridge.transport;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * TS 音频传输层抽象。
 *
 * <p>定义两条数据流：</p>
 * <ol>
 *     <li><b>MC → TS</b>：{@link #sendMcAudio(UUID, byte[], long)} 把 SVC 捕获到的
 *         MC 玩家 Opus 帧推送到 TeamSpeak（典型实现走 TS3AudioBot HTTP API）。</li>
 *     <li><b>TS → MC</b>：通过 {@link #setOnTsAudioListener(Consumer)} 注册回调，
 *         当 TS 侧推送一帧音频时被调用，由上层（{@link com.svcb.tsbridge.svc.McAudioInjector}）
 *         注入到对应 MC 玩家附近。</li>
 * </ol>
 *
 * <p>实现需保证：</p>
 * <ul>
 *     <li>{@link #start()} 不阻塞调用线程（WebSocket 异步建连、HTTP 客户端复用）。</li>
 *     <li>任一方向失败时只记录日志、降级，不抛异常打断转发循环。</li>
 *     <li>线程安全：{@link #sendMcAudio} 可能被多线程并发调用（来自 forwarder 线程），
 *         {@link #setOnTsAudioListener} 在 start 前设置。</li>
 * </ul>
 *
 * <p>默认实现见 {@link Ts3AudioBotTransport}。后续可通过实现本接口替换为其他后端
 * （如直接 TS3 SDK / 自建 Bot）。</p>
 */
public interface TsAudioTransport {

    /**
     * 启动传输层（建立 WebSocket 订阅、初始化 HTTP 客户端等）。
     * 不阻塞调用线程。
     *
     * @throws Exception 启动失败（如配置缺失）
     */
    void start() throws Exception;

    /**
     * 关闭传输层（断开 WebSocket、释放 HTTP 资源）。幂等。
     */
    void stop();

    /**
     * 把 MC 玩家的 Opus 音频帧发送到 TS。
     *
     * <p>实现可异步执行；失败时只记录日志，不抛异常。</p>
     *
     * @param mcUuid    发送者（MC 玩家）UUID
     * @param opusData  Opus 编码的音频数据
     * @param sequence  序列号（调用方提供，典型为时间戳或递增计数）
     */
    void sendMcAudio(UUID mcUuid, byte[] opusData, long sequence);

    /**
     * 注册 TS 音频帧接收回调。listener 可为 null（取消监听）。
     * 实现需保证 listener 在 WS 接收线程中被调用，因此 listener 内部不应做重 IO。
     *
     * @param listener 接收 {@link TsAudioFrame} 的回调
     */
    void setOnTsAudioListener(Consumer<TsAudioFrame> listener);

    /**
     * 返回传输层是否已启动且未关闭。
     */
    boolean isStarted();
}
