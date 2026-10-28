package com.tsvoice.client.net;

import com.tsvoice.protocol.capability.CapabilityWire;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 能力公告的客户端 Fabric 外层（T12/N6）：payload 封装、注册与有界解码。
 * 帧格式与语义由 ts3voice-core 的 {@link CapabilityWire} 定义（与可选服务端
 * 公告 mod 的同 ID payload 互通；本类只负责 MC 外层，不重复注册语义）。
 *
 * <p>两端在分配 byte array 前即限制帧长（{@code readByteArray(MAX_FRAME_BYTES)}
 * /发送前校验），服务端按玩家有界限流由服务端 mod 负责。</p>
 */
public final class CapabilitiesPayloads {

    private CapabilitiesPayloads() {
    }

    /** S2C 能力公告（与 voice-capabilities 服务端 mod 同 ID）。 */
    public record CapabilitiesPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<CapabilitiesPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "capabilities_v1"));
        public static final StreamCodec<FriendlyByteBuf, CapabilitiesPayload> CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, CapabilitiesPayload value) {
                buf.writeByteArray(value.frame());
            }

            @Override
            public CapabilitiesPayload decode(FriendlyByteBuf buf) {
                // 有界解码：超长帧在分配前即拒绝（N6）
                return new CapabilitiesPayload(buf.readByteArray(CapabilityWire.MAX_FRAME_BYTES));
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S hello（载荷为带请求 nonce 的 CapabilityWire 帧）。 */
    public record CapabilitiesHelloPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<CapabilitiesHelloPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "capabilities_hello_v1"));
        public static final StreamCodec<FriendlyByteBuf, CapabilitiesHelloPayload> CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, CapabilitiesHelloPayload value) {
                buf.writeByteArray(value.frame());
            }

            @Override
            public CapabilitiesHelloPayload decode(FriendlyByteBuf buf) {
                return new CapabilitiesHelloPayload(buf.readByteArray(CapabilityWire.MAX_FRAME_BYTES));
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 注册 S2C/C2S 两侧 payload 类型（客户端入口调用一次）。 */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(CapabilitiesPayload.TYPE, CapabilitiesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CapabilitiesHelloPayload.TYPE, CapabilitiesHelloPayload.CODEC);
    }
}
