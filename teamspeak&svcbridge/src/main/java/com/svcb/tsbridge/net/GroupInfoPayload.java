package com.svcb.tsbridge.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;


import java.util.UUID;

/**
 * 群组信息定向下发：仅发给该群组成员（密码明文绝不下发给非成员，
 * 与 SVC 自身安全模型同级）。供客户端 mod 在 TS 侧建/进带密码的子频道。
 */
public record GroupInfoPayload(UUID id, String name, String password, int typeOrd) implements CustomPacketPayload {

    public static final Type<GroupInfoPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("teamspeak-svc", "group_info"));

    public static final StreamCodec<FriendlyByteBuf, GroupInfoPayload> CODEC = new StreamCodec<>() {
        @Override
        public void encode(FriendlyByteBuf buf, GroupInfoPayload payload) {
            buf.writeUUID(payload.id);
            buf.writeUtf(payload.name);
            if (payload.password == null) {
                buf.writeBoolean(false);
            } else {
                buf.writeBoolean(true);
                buf.writeUtf(payload.password);
            }
            buf.writeInt(payload.typeOrd);
        }

        @Override
        public GroupInfoPayload decode(FriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            String name = buf.readUtf();
            String password = buf.readBoolean() ? buf.readUtf() : null;
            return new GroupInfoPayload(id, name, password, buf.readInt());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
