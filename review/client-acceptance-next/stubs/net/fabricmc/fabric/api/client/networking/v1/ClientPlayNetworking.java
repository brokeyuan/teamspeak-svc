package net.fabricmc.fabric.api.client.networking.v1;
public final class ClientPlayNetworking {
 /** Test hook: the last payload bytes passed to send (production bridge path). */
 public static java.util.function.Consumer<byte[]> SENT_HOOK = data -> {};
 public static <P> void registerGlobalReceiver(P type, java.util.function.BiConsumer<P, Context> handler) {}
 public static boolean canSend(Object type) { return true; }
 public static void send(Object payload) {
  try {
   java.lang.reflect.Method m = payload.getClass().getMethod("data");
   Object data = m.invoke(payload);
   if (data instanceof byte[] bytes) { SENT_HOOK.accept(bytes); }
  } catch (Exception ignored) {
  }
 }
 public interface Context { Object client(); }
}
