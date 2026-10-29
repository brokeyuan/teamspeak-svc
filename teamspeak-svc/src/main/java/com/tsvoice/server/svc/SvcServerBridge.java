package com.tsvoice.server.svc;

/**
 * SVC 服务端桥（S2/P5）：延迟解析 SVC 内部对象 —— 无 SVC 的环境不触达任何
 * SVC 类（R26 同策略：元数据判断在前，类加载在后）。
 *
 * <p>voicechat SERVER 类的获取走反射（编译期不依赖 SVC 内部类；SVC API 的
 * VoicechatServer 是插件面，内部 addRawPacket 在 de.maxhenkel.voicechat.
 * voicechat.Server 上）。加载失败返回 null，调用方按"SVC 不在场"处理 ——
 * 本扩展的 TS 直连/TS relay 不因无 SVC 失败。</p>
 */
public final class SvcServerBridge {

    private SvcServerBridge() {
    }

    /** SVC 内部 voicechat Server（SVC 已启用则非 null；否则 null）。 */
    static Object voicechatServer() {
        try {
            Class<?> voicechat = Class.forName("de.maxhenkel.voicechat.Voicechat");
            Object serverInstance = voicechat.getField("SERVER").get(null);
            if (serverInstance == null) {
                return null;
            }
            return serverInstance.getClass().getMethod("getServer").invoke(serverInstance);
        } catch (Throwable t) {
            return null; // SVC 不在场/未启用：明确无服务
        }
    }

    /** 当前 MinecraftServer（由主入口在 SERVER_STARTING 时登记）。 */
    private static volatile net.minecraft.server.MinecraftServer mcServer;

    public static void setMinecraftServer(net.minecraft.server.MinecraftServer server) {
        mcServer = server;
    }

    static net.minecraft.server.MinecraftServer minecraftServer() {
        return mcServer;
    }

    /** 测试清理。 */
    static void resetForTest() {
        mcServer = null;
    }
}
