package net.minecraft.network.codec;
public interface StreamCodec<B,V> {
 void encode(B buf,V value);
 V decode(B buf);
 default <V2> StreamCodec<B,V2> map(java.util.function.Function<V,V2> f,java.util.function.Function<V2,V> g){return null;}
}
