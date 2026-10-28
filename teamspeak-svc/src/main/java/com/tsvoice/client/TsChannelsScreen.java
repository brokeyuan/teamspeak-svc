package com.tsvoice.client;

import com.tsvoice.client.connection.TsChannelRoute;
import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.TsChannelSnapshot;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TS 频道页（T10/N5）：频道树 + 每频道 加入 按钮 + 用户列表（参与者稳定 UUID）+
 * 路由操作（在当前频道继续 / 重试移动 / 跟随与锁定切换）。所有动作经生产入口
 * （{@link TsChannelRoute#requestMove} / {@link TsChannelRoute#acceptCurrentChannel}
 * / 协调器 setChannelTarget）；网络回调只发布状态，widget 全部在客户端线程更新
 * （本屏无任何后台线程，init 时快照一次 + tick 周期重建）。
 *
 * <p>创建频道经 {@link Ts3VoiceClient#createChannel}：命令成功不代表移动成功，
 * 也不代表 self 频道已确认 —— 界面以 {@link TsChannelRoute#movePhase()} 与
 * 实际频道展示真实状态，不制造假成功。真实渲染/点击验收按用户要求暂缓（E07）；
 * 动作控制流由 GuiFlowTest 的频道段落回归覆盖。</p>
 */
public class TsChannelsScreen extends Screen {

    private static final int ROW_HEIGHT = 18;
    private static final int MAX_ROWS = 8;

    private Screen parent;
    /** UI 记忆的上次移动目标（重试按钮用；路由状态仍是唯一事实来源）。 */
    private Integer lastAttemptedCid;
    private String lastAttemptedPassword;
    /** tick 节流。 */
    private long lastRebuild;

    public TsChannelsScreen() {
        super(Component.translatable("tsvoice.gui.channels.title"));
    }

    public TsChannelsScreen(Integer lastAttemptedCid, String lastAttemptedPassword) {
        this();
        this.lastAttemptedCid = lastAttemptedCid;
        this.lastAttemptedPassword = lastAttemptedPassword;
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addRenderableWidget(makeLabel(cx - 200, 16, 400, this.title, 0xFFFFFF));
        int y = 30;

        Ts3VoiceClient client = TsVoiceManager.currentClient();
        if (client == null || !client.isConnected()) {
            addRenderableWidget(makeLabel(cx - 150, y, 300,
                    Component.translatable("tsvoice.gui.channels.empty"), 0xFFAAAAAA));
            addDoneButton(cx);
            return;
        }

        // ---- 路由状态行（实际频道 + 发送原因；禁止假成功）----
        int myChannel = client.getMyChannelId();
        var status = VoiceStatusModel.snapshot();
        String statusLine = Component.translatable("tsvoice.gui.channel").getString() + " "
                + (myChannel >= 0 ? client.getChannelNameByCid(myChannel) + " (" + myChannel + ")" : "?")
                + " | " + Component.translatable("tsvoice.gui.mic_target").getString() + " "
                + Component.translatable("tsvoice.mictarget." + status.micTarget().toLowerCase()).getString()
                + (status.micTargetDetail().isBlank() ? "" : " — " + status.micTargetDetail());
        addRenderableWidget(makeLabel(cx - 200, y, 400, Component.literal(statusLine),
                "TS".equals(status.micTarget()) ? 0xFF55FF55 : 0xFFFFAA55));
        y += 14;

        // ---- 路由操作 ----
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.stay_here"), b -> {
            Ts3VoiceClient current = TsVoiceManager.currentClient();
            if (current != null) {
                TsChannelRoute.acceptCurrentChannel(current);
                rebuild();
            }
        }).bounds(cx - 200, y, 96, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.retry"), b -> {
            Ts3VoiceClient current = TsVoiceManager.currentClient();
            if (current != null && lastAttemptedCid != null) {
                TsChannelRoute.requestMove(current, lastAttemptedCid, lastAttemptedPassword);
                rebuild();
            }
        }).bounds(cx - 100, y, 96, 18).build());
        addRenderableWidget(Button.builder(followLockLabel(), b -> {
            Ts3VoiceClient current = TsVoiceManager.currentClient();
            if (current == null) {
                return;
            }
            if (TsChannelRoute.currentPolicy() == VoiceState.ChannelPolicy.LOCKED_TARGET) {
                // 锁定 → 跟随：交还策略，随服务器确认移动
                TsChannelRoute.setChannelPolicy(TsChannelRoute.currentToken(),
                        VoiceState.ChannelPolicy.TS_NATIVE_FOLLOW);
            } else if (myChannel >= 0) {
                // 跟随 → 锁定当前实际频道
                TsChannelRoute.setChannelPolicy(TsChannelRoute.currentToken(),
                        VoiceState.ChannelPolicy.LOCKED_TARGET);
                TsChannelRoute.lockCurrentChannel(TsChannelRoute.currentToken(), myChannel);
            }
            rebuild();
        }).bounds(cx + 4, y, 96, 18).build());
        y += 22;

        // ---- 频道树（快照一次；tick 周期重建刷新）----
        TsChannelSnapshot snapshot = client.channelSnapshot();
        List<TsChannelSnapshot.Channel> channels = new ArrayList<>(snapshot.ordered());
        int rows = Math.min(MAX_ROWS, channels.size());
        Map<Integer, Ts3VoiceClient.ClientEntry> clients = client.clientsSnapshot();
        for (int i = 0; i < rows; i++) {
            TsChannelSnapshot.Channel channel = channels.get(i);
            String indent = "  ".repeat(Math.max(0, depthOf(channels, channel)));
            List<String> members = new ArrayList<>();
            for (Map.Entry<Integer, Ts3VoiceClient.ClientEntry> e : clients.entrySet()) {
                if (client.getChannelOf(e.getKey()) == channel.cid()) {
                    // 参与者展示：TS 昵称 + 稳定播放 UUID（serverUid+uid 派生；MC 身份
                    // 升级只影响显示，不触碰个人音量，不覆盖原生 SVC 条目）。
                    UUID playbackUuid = TsAudioInjector.participantUuidFor(
                            snapshot.serverUid(), e.getValue().uniqueId());
                    members.add(e.getValue().nickname() + " [" + ShortenedUuid(playbackUuid) + "]");
                }
            }
            String rowText = indent + channel.name() + (members.isEmpty() ? ""
                    : "  — " + Component.translatable("tsvoice.gui.channels.users").getString() + ": "
                    + String.join(", ", members));
            addRenderableWidget(makeLabel(cx - 200, y + 2, 270,
                    Component.literal(rowText),
                    channel.cid() == myChannel ? 0xFF55FF55 : 0xFFE0E0E0));
            // 频道密码沿用会话配置（书签/本服凭据）；不在此新增第二份密码存储
            String channelPassword = TsVoiceManager.currentSessionConfig() != null
                    ? TsVoiceManager.currentSessionConfig().channelPassword() : null;
            addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.join"), b -> {
                Ts3VoiceClient current = TsVoiceManager.currentClient();
                if (current != null) {
                    lastAttemptedCid = channel.cid();
                    lastAttemptedPassword = channelPassword;
                    // N1 关联请求：拒绝/超时进入 FAILED 持续阻断，路由状态行如实展示
                    TsChannelRoute.requestMove(current, channel.cid(), channelPassword);
                    rebuild();
                }
            }).bounds(cx + 74, y, 48, 16).build());
            y += ROW_HEIGHT;
        }
        if (channels.isEmpty()) {
            addRenderableWidget(makeLabel(cx - 200, y, 300,
                    Component.translatable("tsvoice.gui.channels.empty"), 0xFFAAAAAA));
            y += ROW_HEIGHT;
        }

        // ---- 创建频道（命令成功 ≠ 移动成功 ≠ 频道确认；状态行如实反映）----
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.create"), b -> {
            Ts3VoiceClient current = TsVoiceManager.currentClient();
            if (current == null) {
                return;
            }
            try {
                current.createChannel("MC " + Minecraft.getInstance().getUser().getName()
                        + " " + System.currentTimeMillis() % 10_000, 0, null);
            } catch (Exception ignored) {
            }
            rebuild();
        }).bounds(cx - 200, y, 198, 18).build());
        y += 22;
        addDoneButtonAt(cx, y);
    }

    private void addDoneButton(int cx) {
        addDoneButtonAt(cx, this.height - 30);
    }

    private void addDoneButtonAt(int cx, int y) {
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.done"), b -> onClose())
                .bounds(cx - 100, y, 200, 20).build());
    }

    private Component followLockLabel() {
        boolean locked = TsChannelRoute.currentPolicy() == VoiceState.ChannelPolicy.LOCKED_TARGET;
        return Component.literal(Component.translatable("tsvoice.gui.bookmarks.policy").getString() + ": "
                + Component.translatable(locked ? "tsvoice.gui.bookmarks.policy_locked"
                : "tsvoice.gui.bookmarks.policy_follow").getString());
    }

    /** 沿父链计算缩进深度（防环：最多按表长迭代）。 */
    private static int depthOf(List<TsChannelSnapshot.Channel> channels, TsChannelSnapshot.Channel channel) {
        int depth = 0;
        TsChannelSnapshot.Channel current = channel;
        while (current.parentCid() > 0 && depth <= channels.size()) {
            TsChannelSnapshot.Channel parent = null;
            for (TsChannelSnapshot.Channel c : channels) {
                if (c.cid() == current.parentCid()) {
                    parent = c;
                    break;
                }
            }
            if (parent == null) {
                break;
            }
            current = parent;
            depth++;
        }
        return depth;
    }

    private static String ShortenedUuid(UUID uuid) {
        String s = uuid.toString();
        return s.substring(0, 8);
    }

    private net.minecraft.client.gui.components.StringWidget makeLabel(int x, int y, int w, Component text, int color) {
        var styled = text.copy().withStyle(st -> st.withColor(color));
        return new net.minecraft.client.gui.components.StringWidget(x, y, w, 10, styled, Minecraft.getInstance().font);
    }

    @Override
    public void tick() {
        // 网络回调只发布状态；widget 更新归客户端线程 —— 周期重建读取最新快照。
        long now = System.currentTimeMillis();
        if (now - lastRebuild >= 2_000L) {
            lastRebuild = now;
            rebuild();
        }
    }

    private void rebuild() {
        Minecraft.getInstance().setScreenAndShow(
                new TsChannelsScreen(lastAttemptedCid, lastAttemptedPassword));
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreenAndShow(parent);
        } else {
            super.onClose();
        }
    }
}
