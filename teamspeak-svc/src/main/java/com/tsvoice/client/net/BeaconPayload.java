package com.tsvoice.client.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务器信标：向装了 teamspeak-svc 客户端 mod 的玩家下发 TS 连接信息
 * （与原版 SVC "服务端告知语音端口" 同一范式）。客户端收到即自动连接。
 */
public record BeaconPayload(String host, int port, String channel, String backendMode, int tunnelPort) implements CustomPacketPayload {

    public static final Type<BeaconPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "beacon"));

    public static final StreamCodec<FriendlyByteBuf, BeaconPayload> CODEC = new StreamCodec<>() {
        @Override
        public void encode(FriendlyByteBuf buf, BeaconPayload payload) {
            buf.writeUtf(payload.host);
            buf.writeInt(payload.port);
            buf.writeUtf(payload.channel);
            buf.writeUtf(payload.backendMode == null ? "direct" : payload.backendMode);
            buf.writeInt(payload.tunnelPort);
        }

        @Override
        public BeaconPayload decode(FriendlyByteBuf buf) {
            return new BeaconPayload(buf.readUtf(), buf.readInt(), buf.readUtf(), buf.readUtf(), buf.readInt());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
