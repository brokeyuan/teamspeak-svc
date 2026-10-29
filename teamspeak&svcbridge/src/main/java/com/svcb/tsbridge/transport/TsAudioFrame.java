package com.svcb.tsbridge.transport;

/**
 * 不可变的 TS 音频帧。
 *
 * <p>表示一帧从 TeamSpeak 侧（经 TS3AudioBot 订阅）接收到的 Opus 编码音频，
 * 由 {@link TsAudioTransport} 在 {@code onTsAudioFrame} 回调中向上传递。</p>
 *
 * @param tsUniqueId      TS 客户端的 uniqueIdentifier
 * @param opusData        Opus 编码的音频数据
 * @param sequenceNumber  序列号（由 TS3AudioBot 提供，单调递增）
 * @param timestampMs     时间戳（毫秒，TS3AudioBot 端打的时间）
 */
public record TsAudioFrame(
        String tsUniqueId,
        byte[] opusData,
        long sequenceNumber,
        long timestampMs
) {
}
