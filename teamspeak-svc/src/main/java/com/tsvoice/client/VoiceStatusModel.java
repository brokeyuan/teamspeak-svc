package com.tsvoice.client;

import com.tsvoice.client.connection.BackendCoordinator;
import com.tsvoice.client.connection.SelectionPolicy;
import com.tsvoice.client.connection.TsChannelRoute;
import com.tsvoice.client.connection.VoiceState;

/**
 * 统一语音状态模型（T09/N4）：GUI/命令只读取该快照展示，不自行解释内部状态，
 * 更不能据此决定发送许可 —— 许可的唯一来源仍是协调器/路由。
 *
 * <p>三个麦克风受众与计划约束一一对应："TS"（书签/手动连接且频道已确认）、
 * "SVC"（原生 SVC 选中）、"IDLE"（PAUSED/OFF/无目标 —— 不发送）。detail 携带
 * 路由层的原始原因（移动中/失败/策略阻断等），供界面直接展示。</p>
 */
public record VoiceStatusModel(
        String micTarget,
        String micTargetDetail,
        String backendTransport,
        String connectionPhase,
        Integer confirmedChannel,
        String channelPolicy,
        String movePhase,
        String failureReason,
        String selectionSource,
        boolean sessionConnected) {

    public static VoiceStatusModel snapshot() {
        var snap = BackendCoordinator.snapshot();
        var selection = snap.selection();
        var route = snap.route();
        SelectionPolicy.IntentKind intent = selection.intent();
        boolean connected = TsVoiceManager.isSessionConnected();

        // 麦克风受众（唯一判定来源：协调器选择 + 频道确认状态）
        String micTarget;
        String detail;
        switch (intent) {
            case OFF -> {
                micTarget = "IDLE";
                detail = "voice off (user)";
            }
            case PAUSED -> {
                micTarget = "IDLE";
                detail = "teamSpeak disconnected";
            }
            default -> {
                VoiceState.BackendKind backend = selection.selectedBackend().orElse(null);
                if (backend == VoiceState.BackendKind.TS3) {
                    micTarget = "TS";
                    com.tsvoice.client.ts3voice.Ts3VoiceClient current = TsVoiceManager.currentClient();
                    detail = current != null && TsChannelRoute.isReadyForVoice(current)
                            ? "confirmed channel " + route.channelId()
                            : routeStatusDetail();
                } else if (backend == VoiceState.BackendKind.SVC) {
                    micTarget = "SVC";
                    detail = "vanilla SVC selected";
                } else {
                    micTarget = "IDLE";
                    detail = "no voice target";
                }
            }
        }

        String transport;
        if (intent == SelectionPolicy.IntentKind.OFF || intent == SelectionPolicy.IntentKind.PAUSED) {
            transport = "-";
        } else if (selection.selectedBackend().orElse(null) == VoiceState.BackendKind.TS3) {
            var cfg = TsVoiceManager.currentSessionConfig();
            transport = "TS (" + (cfg != null && cfg.tcp() ? "TCP" : "UDP") + ")";
        } else if (selection.selectedBackend().orElse(null) == VoiceState.BackendKind.SVC) {
            transport = "SVC (native)";
        } else {
            transport = "-";
        }

        return new VoiceStatusModel(
                micTarget,
                detail == null ? "" : detail,
                transport,
                TsVoiceManager.getState().name(),
                route.channelId(),
                BackendCoordinator.channelPolicy().name(),
                TsChannelRoute.movePhase().name(),
                TsChannelRoute.failureReason(),
                selection.targetRef() == null ? "" : selection.targetRef(),
                connected);
    }

    private static String routeStatusDetail() {
        String failure = TsChannelRoute.failureReason();
        if (!failure.isBlank()) {
            return failure;
        }
        String reason = TsChannelRoute.statusReason();
        return reason.isBlank() ? "channel not confirmed" : reason;
    }
}
