
package net.minecraft.network.protocol.common.custom;
public interface CustomPacketPayload {
 Type<? extends CustomPacketPayload> type();
 record Type<T>(net.minecraft.resources.Identifier id){}
}
