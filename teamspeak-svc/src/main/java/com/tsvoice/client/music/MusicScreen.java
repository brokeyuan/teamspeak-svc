package com.tsvoice.client.music;

import com.tsvoice.client.ParticipantVolumeControl;
import com.tsvoice.client.TsAudioInjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 音乐 Bot 操作页（G6/P2-05）：可发现的游戏内入口 —— 连接（UDP）、添加授权
 * 本地曲目、播放/暂停/继续/下一首/停止播放/断开 Bot、队列状态与本机收听
 * 音量/静音。UI 与命令调用同一业务操作；本线程只读不可变快照，连接/IO 异步。
 *
 * <p>本机收听音量/静音走 ParticipantVolumeControl（SVC 键空间同源，稳定参与
 * 者键 = serverUID + Bot TS UID 派生），只影响本机收听，不改全频道发送。
 * 26.3 的 Screen 渲染由 widgets 承担（StringWidget 标签；状态每 tick 刷新），
 * 与 ConfigScreen 同一 idiom。</p>
 */
public class MusicScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("teamspeak-svc");

    private EditBox hostBox;
    private EditBox portBox;
    private EditBox channelBox;
    private EditBox wavBox;
    private EditBox volumeBox;

    private StringWidget statusLabel;
    private StringWidget queueLabel;

    private Button startBtn;
    private Button addBtn;
    private Button playBtn;
    private Button pauseBtn;
    private Button resumeBtn;
    private Button nextBtn;
    private Button stopBtn;
    private Button stopBotBtn;
    private Button muteBtn;

    /** 本机收听音量（0..100 映射 0..1）。 */
    private int listeningVolume = 100;
    private boolean listeningMuted;

    /** Bot 的稳定参与者键（确认频道后由 serverUID + Bot UID 派生；未连接 null）。 */
    private java.util.UUID botParticipantKey;
    /** 上次派生用的快照（避免每 tick 重派生）。 */
    private String lastServerUid;
    private String lastTsUid;

    public MusicScreen() {
        super(Component.literal("Music Bot"));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 180;
        int y = 40;
        int w = 170;

        addRenderableWidget(label(x, y, w, Component.literal("TS target (UDP direct)"), 0xFFFFFF));
        hostBox = new EditBox(this.font, x, y + 12, w, 18, Component.literal("TS host"));
        hostBox.setHint(Component.literal("TS host"));
        hostBox.setValue("127.0.0.1");
        addRenderableWidget(hostBox);

        portBox = new EditBox(this.font, x + w + 10, y + 12, 50, 18, Component.literal("port"));
        portBox.setHint(Component.literal("port"));
        portBox.setValue("9987");
        addRenderableWidget(portBox);

        channelBox = new EditBox(this.font, x + w + 70, y + 12, w - 60, 18, Component.literal("channel"));
        channelBox.setHint(Component.literal("channel (optional)"));
        addRenderableWidget(channelBox);

        startBtn = addRenderableWidget(Button.builder(Component.literal("Start Bot (UDP)"), b -> {
            try {
                int port = parsePort();
                MusicBotManager.start(hostBox.getValue().trim(), port,
                        channelBox.getValue().trim(), null);
            } catch (Exception e) {
                error(e.getMessage());
            }
        }).bounds(x, y + 36, w, 18).build());

        addRenderableWidget(label(x, y + 62, 2 * w + 80,
                Component.literal("Authorized local track (.wav)"), 0xFFFFFF));
        wavBox = new EditBox(this.font, x, y + 74, 2 * w + 80, 18, Component.literal("wav path"));
        wavBox.setHint(Component.literal("absolute path to a local .wav file"));
        addRenderableWidget(wavBox);

        addBtn = addRenderableWidget(Button.builder(Component.literal("Add track"), b -> {
            try {
                MusicBotManager.add(wavBox.getValue().trim());
                wavBox.setValue("");
            } catch (Exception e) {
                error(e.getMessage());
            }
        }).bounds(x, y + 96, w / 2, 18).build());

        playBtn = addRenderableWidget(Button.builder(Component.literal("Play"), b -> {
            try {
                MusicBotManager.play();
            } catch (Exception e) {
                error(e.getMessage());
            }
        }).bounds(x, y + 118, w / 2, 18).build());

        pauseBtn = addRenderableWidget(Button.builder(Component.literal("Pause"), b -> {
            try {
                MusicBotManager.pause();
            } catch (Exception e) {
                error(e.getMessage());
            }
        }).bounds(x + w / 2 + 5, y + 118, w / 2, 18).build());

        resumeBtn = addRenderableWidget(Button.builder(Component.literal("Resume"), b -> {
            try {
                MusicBotManager.resume();
            } catch (Exception e) {
                error(e.getMessage());
            }
        }).bounds(x, y + 140, w / 2, 18).build());

        nextBtn = addRenderableWidget(Button.builder(Component.literal("Next"), b -> {
            try {
                MusicBotManager.next();
            } catch (Exception e) {
                error(e.getMessage());
            }
        }).bounds(x + w / 2 + 5, y + 140, w / 2, 18).build());

        stopBtn = addRenderableWidget(Button.builder(Component.literal("Stop playback"), b -> {
            MusicBotManager.stopPlayback();
        }).bounds(x, y + 162, w / 2, 18).build());

        stopBotBtn = addRenderableWidget(Button.builder(Component.literal("Disconnect bot"), b -> {
            MusicBotManager.stopBot();
            botParticipantKey = null;
            lastServerUid = null;
            lastTsUid = null;
        }).bounds(x + w / 2 + 5, y + 162, w / 2, 18).build());

        // ---- 本机收听音量/静音（P2-05：实际生效） ----
        addRenderableWidget(label(x, y + 188, 2 * w + 80,
                Component.literal("Local listening volume/mute (only affects your own playback)"), 0xFFFFFF));
        volumeBox = new EditBox(this.font, x, y + 200, 60, 18, Component.literal("listen vol"));
        volumeBox.setHint(Component.literal("0-100"));
        volumeBox.setValue(String.valueOf(listeningVolume));
        addRenderableWidget(volumeBox);

        addRenderableWidget(Button.builder(Component.literal("Apply volume"), b -> {
            try {
                int v = Integer.parseInt(volumeBox.getValue().trim());
                if (v < 0 || v > 100) {
                    error("volume must be 0..100");
                    return;
                }
                listeningVolume = v;
                applyListeningVolume();
            } catch (NumberFormatException e) {
                error("volume must be a number 0..100");
            }
        }).bounds(x + 65, y + 200, 110, 18).build());

        muteBtn = addRenderableWidget(Button.builder(Component.literal(listeningMuted ? "Unmute (local)" : "Mute (local)"), b -> {
            listeningMuted = !listeningMuted;
            muteBtn.setMessage(Component.literal(listeningMuted ? "Unmute (local)" : "Mute (local)"));
            applyListeningVolume();
        }).bounds(x + 180, y + 200, 120, 18).build());

        statusLabel = addRenderableWidget(label(x, y + 226, 2 * w + 80,
                Component.literal(MusicBotManager.statusText()), 0xC0C0C0));
        queueLabel = addRenderableWidget(label(x, y + 238, 2 * w + 80,
                Component.literal(queueText()), 0x808080));
    }

    private StringWidget label(int x, int y, int w, Component text, int color) {
        var styled = text.copy().withStyle(st -> st.withColor(color));
        return new StringWidget(x, y, w, 10, styled, Minecraft.getInstance().font);
    }

    private int parsePort() {
        try {
            return Integer.parseInt(portBox.getValue().trim());
        } catch (NumberFormatException e) {
            return 9987;
        }
    }

    @Override
    public void tick() {
        // 状态/队列每 tick 刷新（StringWidget 文本更新）
        if (statusLabel != null) {
            statusLabel.setMessage(Component.literal(MusicBotManager.statusText()));
        }
        if (queueLabel != null) {
            queueLabel.setMessage(Component.literal(queueText()));
        }
        // Bot 身份就绪后首次派生参与者键并重放已有音量/静音设置
        ensureParticipantKey();
    }

    private String queueText() {
        var queue = MusicBotManager.queueSnapshot();
        return queue.isEmpty() ? "Queue is empty" : "Queue: " + queue.size() + " track(s)";
    }

    /**
     * 本机收听音量实际生效（G6/P2-05）：写入参与者键（serverUID + Bot TS UID
     * 稳定派生）复用 SVC 音量存储；不乘发送 PCM、不改全频道发送音量。
     */
    private void applyListeningVolume() {
        var key = ensureParticipantKey();
        if (key == null) {
            return; // 未连接/身份未就绪：保留设置，确认频道后重放
        }
        ParticipantVolumeControl.setVolume(key, listeningVolume / 100.0);
        ParticipantVolumeControl.setMuted(key, listeningMuted);
        ParticipantVolumeControl.reapply(key);
    }

    /** Bot 稳定参与者键：serverUID + Bot TS UID 派生（同名不共用键）。 */
    private java.util.UUID ensureParticipantKey() {
        var session = MusicBotManager.activeSession();
        var snapshot = session == null ? null : session.botIdentitySnapshot();
        if (snapshot == null || snapshot.serverUid() == null || snapshot.tsUid() == null) {
            return null;
        }
        if (botParticipantKey == null
                || !snapshot.serverUid().equals(lastServerUid)
                || !snapshot.tsUid().equals(lastTsUid)) {
            lastServerUid = snapshot.serverUid();
            lastTsUid = snapshot.tsUid();
            botParticipantKey = TsAudioInjector.participantUuidFor(lastServerUid, lastTsUid);
            // 键变化（重连/房间变更）：重放当前 UI 设置
            ParticipantVolumeControl.setVolume(botParticipantKey, listeningVolume / 100.0);
            ParticipantVolumeControl.setMuted(botParticipantKey, listeningMuted);
            ParticipantVolumeControl.reapply(botParticipantKey);
        }
        return botParticipantKey;
    }

    private void error(String message) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[music] " + message));
        }
        LOGGER.debug("[teamspeak-svc] music screen: {}", message);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
