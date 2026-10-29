package net.minecraft.network.protocol.common.custom;
public interface CustomPacketPayload {
 record Type<T>(net.minecraft.resources.Identifier id){}
 Type<? extends CustomPacketPayload> type();
}
