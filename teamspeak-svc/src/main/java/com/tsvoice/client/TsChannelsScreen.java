package com.tsvoice.client;

import com.tsvoice.client.connection.TsChannelRoute;
import com.tsvoice.client.connection.VoiceState;
import com.tsvoice.client.ts3voice.TsChannelSnapshot;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TS 频道页（T10/N5 → S0.6/R25 补全）：频道树 + 分页（全部频道可访问，不再截断
 * 前八条）+ 每频道独立密码输入（不同频道密码不串用）+ 创建表单（名称/父频道/
 * 可选密码，异步错误反馈）+ 用户列表逐用户音量/静音操作 + 路由操作。所有动作
 * 经生产入口（{@link TsChannelRoute#requestMove} / {@link TsChannelRoute#acceptCurrentChannel}
 * / 协调器 setChannelTarget / {@link Ts3VoiceClient#createChannel}）；命令成功
 * 不代表移动成功，也不代表 self 频道已确认 —— 界面以 {@link TsChannelRoute#movePhase()}
 * 与实际频道展示真实状态。创建/加入命令的异常进入 {@link #lastCommandError}
 * 异步反馈，不再被忽略。
 *
 * <p>真实渲染/点击验收按用户要求暂缓（E07）；动作控制流由 GuiFlowTest 的频道
 * 段落回归覆盖。旧 session 的响应不改新屏状态（rebuild 携带状态代际）。</p>
 */
public class TsChannelsScreen extends Screen {

    private static final int ROW_HEIGHT = 18;
    private static final int PAGE_SIZE = 8;

    private Screen parent;
    /** UI 记忆的上次移动目标（重试按钮用；路由状态仍是唯一事实来源）。 */
    private Integer lastAttemptedCid;
    private String lastAttemptedPassword;
    /** 频道分页（S0.6/R25：全部频道可访问）。 */
    private int page;
    /** 用户列表展开的参与者（每屏一个；逐用户音量/静音入口）。 */
    private UUID expandedUser;
    /** 最近一次命令失败的反馈文本（创建/加入/移动；tick 期间保留供展示）。 */
    static volatile String lastCommandError = "";
    /** 屏幕状态代际：rebuild 后旧实例的异步回调不再写入新屏（S0.6/R25）。 */
    private final long screenGeneration;
    private static final java.util.concurrent.atomic.AtomicLong SCREEN_EPOCH =
            new java.util.concurrent.atomic.AtomicLong(0);
    /** tick 节流。 */
    private long lastRebuild;

    // 创建表单（S0.6/R25：名称/父频道/可选密码；不再自动生成随机名）
    private boolean createFormVisible;
    private EditBox createName;
    private EditBox createParent;
    private EditBox createPassword;
    // 目标频道密码输入（S0.6/R25：针对目标频道，不与会话配置串用）
    private boolean passwordPromptVisible;
    private int passwordPromptCid = -1;
    private String passwordPromptChannelName = "";
    private EditBox targetPassword;

    public TsChannelsScreen() {
        this(null, null, 0, null);
    }

    public TsChannelsScreen(Integer lastAttemptedCid, String lastAttemptedPassword) {
        this(lastAttemptedCid, lastAttemptedPassword, 0, null);
    }

    /** 全参构造（rebuild 用）：保留分页/展开用户/表单可见性（S0.6/R25）。 */
    public TsChannelsScreen(Integer lastAttemptedCid, String lastAttemptedPassword,
                            int page, UUID expandedUser) {
        super(Component.translatable("tsvoice.gui.channels.title"));
        this.lastAttemptedCid = lastAttemptedCid;
        this.lastAttemptedPassword = lastAttemptedPassword;
        this.page = page;
        this.expandedUser = expandedUser;
        this.screenGeneration = SCREEN_EPOCH.incrementAndGet();
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    private boolean current() {
        return screenGeneration == SCREEN_EPOCH.get();
    }

    @Override
    protected void init() {
        Font font = Minecraft.getInstance().font;
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

        // ---- 命令错误反馈（S0.6/R25：创建/加入异常不再被忽略）----
        if (!lastCommandError.isBlank()) {
            addRenderableWidget(makeLabel(cx - 200, y, 400,
                    Component.literal("! " + lastCommandError), 0xFFFF6666));
            y += 12;
        }

        // ---- 频道树（分页；创建/密码表单替换列表空间）----
        if (passwordPromptVisible && passwordPromptCid >= 0) {
            y = initPasswordPrompt(cx, y, font);
        } else if (createFormVisible) {
            y = initCreateForm(cx, y, font, client);
        } else {
            y = initChannelList(cx, y, client, myChannel);
        }

        // ---- 创建频道入口（命令成功 ≠ 移动成功 ≠ 频道确认；状态行如实反映）----
        String createLabel = createFormVisible
                ? Component.translatable("tsvoice.gui.channels.cancel").getString()
                : Component.translatable("tsvoice.gui.channels.create").getString();
        addRenderableWidget(Button.builder(Component.literal(createLabel), b -> {
            createFormVisible = !createFormVisible;
            passwordPromptVisible = false;
            rebuild();
        }).bounds(cx - 200, y, 198, 18).build());
        y += 22;
        addDoneButtonAt(cx, y);
    }

    /** 频道列表渲染（S0.6/R25：分页 + 逐用户音量/静音）。 */
    private int initChannelList(int cx, int y, Ts3VoiceClient client, int myChannel) {
        TsChannelSnapshot snapshot = client.channelSnapshot();
        List<TsChannelSnapshot.Channel> channels = new ArrayList<>(snapshot.ordered());
        int pages = Math.max(1, (channels.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) {
            page = pages - 1;
        }
        int from = page * PAGE_SIZE;
        int to = Math.min(channels.size(), from + PAGE_SIZE);
        Map<Integer, Ts3VoiceClient.ClientEntry> clients = client.clientsSnapshot();
        for (int i = from; i < to; i++) {
            TsChannelSnapshot.Channel channel = channels.get(i);
            String indent = "  ".repeat(Math.max(0, depthOf(channels, channel)));
            List<Map.Entry<Integer, Ts3VoiceClient.ClientEntry>> members = new ArrayList<>();
            for (Map.Entry<Integer, Ts3VoiceClient.ClientEntry> e : clients.entrySet()) {
                if (client.getChannelOf(e.getKey()) == channel.cid()) {
                    members.add(e);
                }
            }
            StringBuilder rowText = new StringBuilder(indent).append(channel.name());
            if (!members.isEmpty()) {
                rowText.append("  — ").append(Component.translatable("tsvoice.gui.channels.users").getString())
                        .append(": ").append(members.size());
            }
            addRenderableWidget(makeLabel(cx - 200, y + 2, 270,
                    Component.literal(rowText.toString()),
                    channel.cid() == myChannel ? 0xFF55FF55 : 0xFFE0E0E0));
            // 加入按钮：有密码的频道先弹独立密码输入（不同频道密码不串用）
            addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.join"), b -> {
                Ts3VoiceClient current = TsVoiceManager.currentClient();
                if (current == null) {
                    return;
                }
                lastAttemptedCid = channel.cid();
                if (channel.passwordKnown()) {
                    passwordPromptVisible = true;
                    passwordPromptCid = channel.cid();
                    passwordPromptChannelName = channel.name();
                } else {
                    lastAttemptedPassword = null;
                    TsChannelRoute.requestMove(current, channel.cid(), null);
                }
                rebuild();
            }).bounds(cx + 74, y, 48, 16).build());
            y += ROW_HEIGHT;
            // 展开第一个成员的音量/静音操作行（逐用户；同 ParticipantKey 重连保音量）
            for (Map.Entry<Integer, Ts3VoiceClient.ClientEntry> e : members) {
                UUID participant = TsAudioInjector.participantUuidFor(
                        snapshot.serverUid(), e.getValue().uniqueId());
                if (!participant.equals(expandedUser)) {
                    continue;
                }
                boolean muted = ParticipantVolumeControl.isMuted(participant);
                double volume = ParticipantVolumeControl.volumeOf(participant);
                String volText = volume < 0 ? "1.0" : String.format("%.1f", volume);
                addRenderableWidget(makeLabel(cx - 190, y + 2, 120,
                        Component.literal("  " + e.getValue().nickname() + " [" + ShortenedUuid(participant) + "] vol=" + volText),
                        0xFFB0B0B0));
                addRenderableWidget(Button.builder(
                        Component.literal(muted ? "unmute" : "mute"), b -> {
                            ParticipantVolumeControl.setMuted(participant, !muted);
                            rebuild();
                        }).bounds(cx - 60, y, 50, 14).build());
                addRenderableWidget(Button.builder(Component.literal("vol-"), b -> {
                            double v = ParticipantVolumeControl.volumeOf(participant);
                            ParticipantVolumeControl.setVolume(participant,
                                    (v < 0 ? 1.0 : Math.max(0, v - 0.25)));
                            rebuild();
                        }).bounds(cx - 6, y, 34, 14).build());
                addRenderableWidget(Button.builder(Component.literal("vol+"), b -> {
                            double v = ParticipantVolumeControl.volumeOf(participant);
                            ParticipantVolumeControl.setVolume(participant, Math.min(4, (v < 0 ? 1.0 : v) + 0.25));
                            rebuild();
                        }).bounds(cx + 32, y, 34, 14).build());
                y += ROW_HEIGHT - 4;
            }
            // 用户数按钮行（展开/收起成员音量面板）
            if (!members.isEmpty() && !members.stream().anyMatch(e ->
                    participantOf(snapshot, e).equals(expandedUser))) {
                // 无已展开成员：提供展开按钮（首个成员）
                UUID first = participantOf(snapshot, members.get(0));
                addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                            expandedUser = first;
                            rebuild();
                        }).bounds(cx + 126, y - ROW_HEIGHT + 2, 20, 14).build());
            }
        }
        if (channels.isEmpty()) {
            addRenderableWidget(makeLabel(cx - 200, y, 300,
                    Component.translatable("tsvoice.gui.channels.empty"), 0xFFAAAAAA));
            y += ROW_HEIGHT;
        } else if (pages > 1) {
            // 分页控件（S0.6/R25：超八条全部可访问）
            int pageY = y;
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                        if (page > 0) {
                            page--;
                            expandedUser = null;
                        }
                        rebuild();
                    }).bounds(cx - 100, pageY, 40, 16).build());
            addRenderableWidget(makeLabel(cx - 50, pageY + 3, 100,
                    Component.literal((page + 1) + " / " + pages), 0xFFC0C0C0));
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                        if (page < pages - 1) {
                            page++;
                            expandedUser = null;
                        }
                        rebuild();
                    }).bounds(cx + 60, pageY, 40, 16).build());
            y += 20;
        }
        return y;
    }

    private UUID participantOf(TsChannelSnapshot snapshot, Map.Entry<Integer, Ts3VoiceClient.ClientEntry> e) {
        return TsAudioInjector.participantUuidFor(snapshot.serverUid(), e.getValue().uniqueId());
    }

    /** 目标频道密码输入（S0.6/R25：独立于会话配置；不同频道不串用）。 */
    private int initPasswordPrompt(int cx, int y, Font font) {
        addRenderableWidget(makeLabel(cx - 200, y, 400,
                Component.literal(Component.translatable("tsvoice.gui.channels.password_for").getString()
                        + " " + passwordPromptChannelName + " (" + passwordPromptCid + ")"), 0xFFFFE08A));
        y += 13;
        targetPassword = new EditBox(font, cx - 100, y, 200, 18,
                Component.translatable("tsvoice.gui.channel_password"));
        targetPassword.setMaxLength(60);
        targetPassword.addFormatter((text, offset) ->
                net.minecraft.util.FormattedCharSequence.forward("*".repeat(text.length()),
                        net.minecraft.network.chat.Style.EMPTY));
        addRenderableWidget(targetPassword);
        y += 22;
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.join"), b -> {
            Ts3VoiceClient current = TsVoiceManager.currentClient();
            if (current != null && passwordPromptCid >= 0) {
                String pwd = targetPassword.getValue();
                lastAttemptedPassword = pwd.isEmpty() ? null : pwd;
                TsChannelRoute.requestMove(current, passwordPromptCid, lastAttemptedPassword);
            }
            passwordPromptVisible = false;
            passwordPromptCid = -1;
            rebuild();
        }).bounds(cx - 100, y, 96, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.cancel"), b -> {
            passwordPromptVisible = false;
            passwordPromptCid = -1;
            rebuild();
        }).bounds(cx + 4, y, 96, 18).build());
        return y + 24;
    }

    /** 创建频道表单（S0.6/R25：名称/父频道名/可选密码；异步错误反馈）。 */
    private int initCreateForm(int cx, int y, Font font, Ts3VoiceClient client) {
        addRenderableWidget(makeLabel(cx - 200, y, 400,
                Component.translatable("tsvoice.gui.channels.create_hint"), 0xFFC8C8C8));
        y += 13;
        createName = new EditBox(font, cx - 100, y, 200, 18,
                Component.translatable("tsvoice.gui.channel"));
        createName.setMaxLength(40);
        createName.setValue("");
        addRenderableWidget(createName);
        y += 20;
        createParent = new EditBox(font, cx - 100, y, 200, 18,
                Component.translatable("tsvoice.gui.channels.create_parent"));
        createParent.setMaxLength(40);
        createParent.setValue("");
        addRenderableWidget(createParent);
        y += 20;
        createPassword = new EditBox(font, cx - 100, y, 200, 18,
                Component.translatable("tsvoice.gui.channel_password"));
        createPassword.setMaxLength(60);
        createPassword.setValue("");
        createPassword.addFormatter((text, offset) ->
                net.minecraft.util.FormattedCharSequence.forward("*".repeat(text.length()),
                        net.minecraft.network.chat.Style.EMPTY));
        addRenderableWidget(createPassword);
        y += 22;
        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.channels.create"), b -> {
            Ts3VoiceClient current = TsVoiceManager.currentClient();
            String name = createName.getValue().trim();
            if (current == null || name.isEmpty()) {
                lastCommandError = Component.translatable("tsvoice.gui.channels.create_needs_name").getString();
                rebuild();
                return;
            }
            // 父频道按名解析；空=根。歧义/缺失明确报错，不再静默落 root。
            Integer parentCid = 0;
            String parentName = createParent.getValue().trim();
            if (!parentName.isEmpty()) {
                List<TsChannelSnapshot.Channel> matches = current.channelSnapshot().byName(parentName);
                if (matches.size() == 1) {
                    parentCid = matches.get(0).cid();
                } else {
                    lastCommandError = (matches.isEmpty()
                            ? Component.translatable("tsvoice.gui.channels.create_parent_missing").getString()
                            : Component.translatable("tsvoice.gui.channels.create_parent_ambiguous").getString())
                            + ": " + parentName;
                    rebuild();
                    return;
                }
            }
            String password = createPassword.getValue();
            try {
                current.createChannel(name, parentCid, password.isEmpty() ? null : password);
                lastCommandError = ""; // 命令成功 ≠ 频道确认；状态行/频道表如实刷新
                createFormVisible = false;
            } catch (Exception e) {
                // S0.6/R25：异常进入反馈（不再被忽略）
                lastCommandError = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            rebuild();
        }).bounds(cx - 100, y, 200, 18).build());
        return y + 22;
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
                new TsChannelsScreen(lastAttemptedCid, lastAttemptedPassword, page, expandedUser));
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
