package com.tsvoice.client.backend;

/**
 * SVC 原生连接的观察者：只记录 SVC 会话是否可用。SVC 连接变化不再直接销毁
 * TS 会话（P1 解耦，F01 的前置）—— 后端切换与资源归属由协调器决策。
 *
 * <p>{@code voiceMuted} 是 OFF（关闭全部语音）时本扩展自身的门控覆盖：只表示
 * 本扩展不再注入/消费语音帧，绝不改动用户在 SVC 里的静音设置；恢复语音时
 * 仅清除本扩展的覆盖。</p>
 */
public final class SvcBackendAdapter {

    private static volatile boolean connected;
    private static volatile boolean voiceMuted;

    private SvcBackendAdapter() {
    }

    public static void onSvcConnected() {
        connected = true;
    }

    public static void onSvcDisconnected() {
        connected = false;
    }

    public static boolean isConnected() {
        return connected;
    }

    /** OFF 覆盖（仅本扩展的门控；SVC 用户设置不受影响）。 */
    public static void setVoiceMuted(boolean muted) {
        voiceMuted = muted;
    }

    public static boolean isVoiceMuted() {
        return voiceMuted;
    }

    /**
     * 原生 SVC 下行是否被本扩展的收听归属压制（N3/D03 + E5/F3 单后端收听默认；
     * mixin 在 {@code ClientVoicechat.processSoundPacket} 头部读取）。
     *
     * <p>规则直接派生自协调器快照（不保存第二份状态）：</p>
     * <ul>
     *   <li>OFF：压制（关闭全部语音）；</li>
     *   <li><b>无选中后端（PAUSED/AUTO 未解析的 Optional.empty）：压制</b> ——
     *       "尚未选择"不是"用户选择了 SVC"（F3：复核探针反例已除）。AUTO
     *       解析完成提交选择后按其结果放行；PAUSED 想听 SVC 必须显式切回；</li>
     *   <li>有选中后端：仅 SVC 被选中时放行 —— TS 被选中时压制（默认单后端
     *       收听；双收听只能作为未来显式设置）。</li>
     * </ul>
     *
     * <p>与用户在 SVC 里的静音/音量设置完全无关。</p>
     */
    public static boolean isNativeReceiveSuppressed() {
        var snapshot = com.tsvoice.client.connection.BackendCoordinator.snapshot();
        if (snapshot.selection().intent()
                == com.tsvoice.client.connection.SelectionPolicy.IntentKind.OFF) {
            return true;
        }
        // 只有明确选中 SVC 才放行原生 SVC 下行；无选择/TS 选中均压制。
        return snapshot.selection().selectedBackend()
                .orElse(com.tsvoice.client.connection.VoiceState.BackendKind.TS3)
                != com.tsvoice.client.connection.VoiceState.BackendKind.SVC;
    }
}
