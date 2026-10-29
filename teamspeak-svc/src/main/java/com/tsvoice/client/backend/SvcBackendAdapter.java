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
     * 原生 SVC 下行是否被本扩展的收听归属压制（N3/D03 + E5 单后端收听默认；
     * mixin 在 {@code ClientVoicechat.processSoundPacket} 头部读取）。
     *
     * <p>规则直接派生自协调器快照（不保存第二份状态）：</p>
     * <ul>
     *   <li>OFF：压制（关闭全部语音）；</li>
     *   <li>PAUSED：不压制 —— 它不是 OFF，保留显式选择前的接收状态，只是不
     *       主动改听 SVC（TS 帧由 {@code TsVoiceManager.handleVoiceFrame} 的
     *       PAUSED 门消费）；</li>
     *   <li>其他意图：仅当协调器当前选中的后端是 SVC 时放行原生下行 ——
     *       <b>TS 被选中时原生 SVC 下行被压制</b>（默认单后端收听；双收听
     *       只能作为未来显式设置，当前不开启）。AUTO 未解析（无选择）时放行，
     *       避免在解析窗口误吞原生语音。</li>
     * </ul>
     *
     * <p>与用户在 SVC 里的静音/音量设置完全无关。</p>
     */
    public static boolean isNativeReceiveSuppressed() {
        var snapshot = com.tsvoice.client.connection.BackendCoordinator.snapshot();
        var intent = snapshot.selection().intent();
        if (intent == com.tsvoice.client.connection.SelectionPolicy.IntentKind.OFF) {
            return true;
        }
        // E5：只有 SVC 被选中（或 AUTO 未解析成 TS）才放行原生 SVC 下行
        return snapshot.selection().selectedBackend()
                .orElse(com.tsvoice.client.connection.VoiceState.BackendKind.SVC)
                != com.tsvoice.client.connection.VoiceState.BackendKind.SVC;
    }
}
