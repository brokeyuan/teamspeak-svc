package com.tsvoice.common.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * SVC_MC_CHANNEL 两端共享 Fabric 外层（S2/P5）：本扩展自有 payload ID ——
 * 绝不复用 SVC-Extra 的 {@code svc-extra:voice/config} ID（双装时重复注册同 ID
 * 类型会直接崩溃，也不把 TS 字节装进 SVC 消息）。
 *
 * <p>与 SVC-Extra @6669d6f 的 wire 契约（源码核实）：其 voice payload 为
 * 完整 SVC NetworkMessage 的 1:1 原始字节（encode writeBytes / decode
 * readableBytes 全读，无长度、版本或额外字段）；config payload 为单个 UTF
 * transport 字符串。本类的 voice/config 编解码遵循同一字节契约（载荷即 SVC
 * NetworkMessage 原始字节），因此与 Extra 的 payload 在字节层互通；ID 不同的
 * 注册隔离是双装安全的结构保证。上游 wire 互操作（本服务端 ↔ Extra 客户端）
 * 是独立承诺，须在固定上游版本下实测后才能声明 —— 当前按任务书标记为
 * "framing 兼容（按字节契约构造），未做双端实测"。</p>
 */
public final class SvcMcChannelPayloads {

    private SvcMcChannelPayloads() {
    }

    /** SVC voice 外层上限（SVC NetworkMessage 内部有 3 字节长度 + 加密载荷；
     *  实测上限远小于 TS relay 的 16 KiB，这里按 8 KiB 收口）。 */
    public static final int MAX_VOICE_BYTES = 8 * 1024;

    private static StreamCodec<FriendlyByteBuf, byte[]> boundedVoiceCodec() {
        return new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, byte[] value) {
                // Extra wire 契约：1:1 原始字节，无长度前缀（MC 帧已保证单包边界）
                buf.writeBytes(value);
            }

            @Override
            public byte[] decode(FriendlyByteBuf buf) {
                // 分配前校验：超限拒绝（绝不无界分配）
                int readable = buf.readableBytes();
                if (readable <= 0 || readable > MAX_VOICE_BYTES) {
                    throw new IllegalArgumentException("SVC voice payload out of bounds: " + readable);
                }
                byte[] out = new byte[readable];
                buf.readBytes(out);
                return out;
            }
        };
    }

    /** C2S：客户端 → 服务端 SVC NetworkMessage 原始字节。 */
    public record SvcVoiceC2sPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<SvcVoiceC2sPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "svc_voice_c2s_v1"));
        public static final StreamCodec<FriendlyByteBuf, SvcVoiceC2sPayload> CODEC =
                boundedVoiceCodec().map(SvcVoiceC2sPayload::new, SvcVoiceC2sPayload::data);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务端 → 客户端 SVC NetworkMessage 原始字节。 */
    public record SvcVoiceS2cPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<SvcVoiceS2cPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "svc_voice_s2c_v1"));
        public static final StreamCodec<FriendlyByteBuf, SvcVoiceS2cPayload> CODEC =
                boundedVoiceCodec().map(SvcVoiceS2cPayload::new, SvcVoiceS2cPayload::data);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务端向客户端声明其 SVC voice 传输通道（UTF；"MC_CHANNEL" 启用本通道）。 */
    public record SvcTransportConfigPayload(String transport) implements CustomPacketPayload {
        public static final String TRANSPORT_UDP = "UDP";
        public static final String TRANSPORT_MC_CHANNEL = "MC_CHANNEL";

        public static final Type<SvcTransportConfigPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "svc_config_v1"));
        public static final StreamCodec<FriendlyByteBuf, SvcTransportConfigPayload> CODEC =
                new StreamCodec<>() {
                    @Override
                    public void encode(FriendlyByteBuf buf, SvcTransportConfigPayload value) {
                        buf.writeUtf(value.transport());
                    }

                    @Override
                    public SvcTransportConfigPayload decode(FriendlyByteBuf buf) {
                        // 有限长度：transport 枚举名不可能超过 32 字符
                        String value = buf.readUtf(32);
                        if (!TRANSPORT_UDP.equals(value) && !TRANSPORT_MC_CHANNEL.equals(value)) {
                            throw new IllegalArgumentException("unknown SVC transport: " + value);
                        }
                        return new SvcTransportConfigPayload(value);
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static volatile boolean registered;

    /** 主入口注册（一次；与 TsRelayPayloads 同处注册，双装 Extra 时 ID 不冲突）。 */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(SvcVoiceS2cPayload.TYPE, SvcVoiceS2cPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SvcVoiceC2sPayload.TYPE, SvcVoiceC2sPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SvcTransportConfigPayload.TYPE, SvcTransportConfigPayload.CODEC);
        registered = true;
    }

    public static boolean isRegistered() {
        return registered;
    }
}
