package com.tsvoice.capabilities;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.tsvoice.protocol.capability.CapabilityWire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 可选能力公告服务端（P4）：仅依赖 Fabric —— 无 SVC、无 TS、无旧桥依赖。
 * 收到客户端 hello（capabilities_hello_v1）后，在当前 MC context 内回复
 * capabilities_v1（候选内容来自管理员配置，未配置时不回复 —— 客户端回退原版 SVC）。
 *
 * <p>协议字节格式由 ts3voice-core 的 CapabilityWire 定义（golden 测试守护）；
 * 本 mod 只负责 MC payload 封装与配置。</p>
 */
public class CapabilityMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("voice-capabilities");

    /** S2C 能力公告（载荷为 CapabilityWire 帧原始字节）。 */
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
                return new CapabilitiesPayload(buf.readByteArray());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S hello（载荷为 CapabilityWire hello 帧原始字节；请求 nonce 由客户端生成）。 */
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
                return new CapabilitiesHelloPayload(buf.readByteArray());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private CapabilityConfig config;

    @Override
    public void onInitialize() {
        config = CapabilityConfig.load();
        PayloadTypeRegistry.clientboundPlay().register(CapabilitiesPayload.TYPE, CapabilitiesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CapabilitiesHelloPayload.TYPE, CapabilitiesHelloPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CapabilitiesHelloPayload.TYPE, (payload, context) -> {
            List<CapabilityWire.Candidate> candidates = config.candidates();
            if (candidates.isEmpty()) {
                return; // 未配置：不回复，客户端走原版 SVC
            }
            // 响应绑定请求 nonce（CapabilityWire 帧头）；畸形 hello 静默丢弃。
            var hello = CapabilityWire.decode(payload.frame());
            if (hello == null) {
                LOGGER.warn("[voice-capabilities] malformed capabilities_hello_v1 from {}",
                        context.player().getName().getString());
                return;
            }
            var response = new CapabilityWire.Capabilities(hello.requestNonce(),
                    CapabilityWire.CURRENT_MAJOR, CapabilityWire.CURRENT_MINOR,
                    config.ttlSeconds(), config.serviceInstanceId(), candidates);
            try {
                ServerPlayNetworking.send(context.player(),
                        new CapabilitiesPayload(CapabilityWire.encode(response)));
            } catch (RuntimeException e) {
                LOGGER.error("[voice-capabilities] failed to encode capabilities response", e);
            }
        });
        LOGGER.info("[voice-capabilities] initialized ({} candidates, ttl={}s)",
                config.candidates().size(), config.ttlSeconds());
    }
}
