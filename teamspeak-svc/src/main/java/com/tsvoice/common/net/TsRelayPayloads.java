package com.tsvoice.common.net;

import com.tsvoice.protocol.relay.TsRelayWire;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * TS 中继 wire 的两端共享 Fabric 外层（S1.2/P6）：payload 封装与有界解码。
 * 类型/注册所有权归 common（主入口一次注册）；不得拿 SVC-Extra 或公告 payload
 * ID 承载 TS 中继帧。帧格式与语义由 ts3voice-core 的 {@link TsRelayWire} 定义，
 * 本类只负责 MC 外层与帧长上限（外层帧 ≤16 KiB，分配前拒绝）。
 *
 * <p>方向注册：clientbound（S2C）= OPENED / DATA_S2C / ROUTE_BARRIER / CLOSED /
 * ERROR；serverbound（C2S）= OPEN / DATA_C2S / ROUTE_BARRIER / CLOSE / ERROR。
 * 反方向的注册不存在 —— 服务器永远不会收到 OPENED，客户端永远不会收到 OPEN。</p>
 */
public final class TsRelayPayloads {

    private TsRelayPayloads() {
    }

    private static StreamCodec<FriendlyByteBuf, byte[]> boundedFrameCodec() {
        return new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, byte[] value) {
                buf.writeByteArray(value);
            }

            @Override
            public byte[] decode(FriendlyByteBuf buf) {
                return buf.readByteArray(TsRelayWire.MAX_FRAME_BYTES);
            }
        };
    }

    /** C2S：客户端请求打开中继会话。 */
    public record RelayOpenPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayOpenPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_open_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayOpenPayload> CODEC =
                boundedFrameCodec().map(RelayOpenPayload::new, RelayOpenPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务端接受 OPEN 并下发协商限额。 */
    public record RelayOpenedPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayOpenedPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_opened_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayOpenedPayload> CODEC =
                boundedFrameCodec().map(RelayOpenedPayload::new, RelayOpenedPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：客户端 → 服务端 TS 数据报。 */
    public record RelayDataC2sPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayDataC2sPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_data_c2s_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayDataC2sPayload> CODEC =
                boundedFrameCodec().map(RelayDataC2sPayload::new, RelayDataC2sPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务端 → 客户端 TS 数据报。 */
    public record RelayDataS2cPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayDataS2cPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_data_s2c_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayDataS2cPayload> CODEC =
                boundedFrameCodec().map(RelayDataS2cPayload::new, RelayDataS2cPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：客户端路由屏障（代际推进边界；服务端据此清空旧代际排队）。 */
    public record RelayRouteBarrierC2sPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayRouteBarrierC2sPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_barrier_c2s_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayRouteBarrierC2sPayload> CODEC =
                boundedFrameCodec().map(RelayRouteBarrierC2sPayload::new, RelayRouteBarrierC2sPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：客户端主动关闭会话。 */
    public record RelayClosePayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayClosePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_close_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayClosePayload> CODEC =
                boundedFrameCodec().map(RelayClosePayload::new, RelayClosePayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务端确认关闭。 */
    public record RelayClosedPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayClosedPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_closed_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayClosedPayload> CODEC =
                boundedFrameCodec().map(RelayClosedPayload::new, RelayClosedPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 双向：稳定错误码 + 人类可读消息。 */
    public record RelayErrorPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayErrorPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_error_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayErrorPayload> CODEC =
                boundedFrameCodec().map(RelayErrorPayload::new, RelayErrorPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：客户端 → 服务端错误反馈（与 S2C ERROR 同 wire 语义，方向注册分开）。 */
    public record RelayErrorC2sPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayErrorC2sPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_error_c2s_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayErrorC2sPayload> CODEC =
                boundedFrameCodec().map(RelayErrorC2sPayload::new, RelayErrorC2sPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：relay 能力发现帧（独立于 capabilities v1；不支持中继的服务器不发送）。 */
    public record RelayCapabilityPayload(byte[] frame) implements CustomPacketPayload {
        public static final Type<RelayCapabilityPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "relay_capability_v1"));
        public static final StreamCodec<FriendlyByteBuf, RelayCapabilityPayload> CODEC =
                boundedFrameCodec().map(RelayCapabilityPayload::new, RelayCapabilityPayload::frame);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static volatile boolean registered;

    /**
     * 注册全部中继 payload 类型（主入口调用一次；同一 JVM 只允许一次）。
     * 方向见类注释 —— 反方向不注册，协议违规由 Fabric 拒收。
     */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(RelayOpenedPayload.TYPE, RelayOpenedPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RelayDataS2cPayload.TYPE, RelayDataS2cPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RelayClosedPayload.TYPE, RelayClosedPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RelayCapabilityPayload.TYPE, RelayCapabilityPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RelayOpenPayload.TYPE, RelayOpenPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RelayDataC2sPayload.TYPE, RelayDataC2sPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RelayRouteBarrierC2sPayload.TYPE, RelayRouteBarrierC2sPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RelayClosePayload.TYPE, RelayClosePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RelayErrorC2sPayload.TYPE, RelayErrorC2sPayload.CODEC);
        // ERROR S2C：clientbound 方向注册
        PayloadTypeRegistry.clientboundPlay().register(RelayErrorPayload.TYPE, RelayErrorPayload.CODEC);
        registered = true;
    }

    /** 类型是否已在本 JVM 注册（PayloadOwnershipTest/诊断用）。 */
    public static boolean isRegistered() {
        return registered;
    }
}
