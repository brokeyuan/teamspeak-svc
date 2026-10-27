package com.tsvoice.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * TeamSpeak 配置界面 v2（单屏三区）：
 *
 * <ol>
 *     <li><b>状态区</b>（只读）：连接状态（含 UDP/TCP）、当前服务器、来源（服务器下发/手动）、频道；</li>
 *     <li><b>配置区</b>：本服启用开关、信标开关、地址/端口/频道/密码、传输协议；
 *         信标开关 ON 时地址/端口/频道显示服务器下发值并灰置，OFF 后解锁编辑；</li>
 *     <li><b>操作区</b>：保存并连接（一步到位）/ 断开 / 完成。</li>
 * </ol>
 *
 * <p>音频增强面板不在此界面：入口是 SVC 语音界面左侧的 Extra 按钮。</p>
 *
 * <p>每服务器一份配置档案（servers.json 按服务器地址自动存档），换服自动切换。</p>
 */
public class ConfigScreen extends Screen {

    private EditBox host;
    private EditBox port;
    private EditBox channel;
    private EditBox password;
    private EditBox channelPassword;
    private Button enabledBtn;
    private Button beaconBtn;
    private Button transportBtn;
    private Button connectBtn;
    private final ServerConfigStore.Entry entry;
    private final String address;
    private Screen parent;
    /** 地址/端口/频道是否被信标锁定（每帧 init 重建时刷新）。 */
    private boolean editLocked;

    public ConfigScreen() {
        super(Component.translatable("tsvoice.gui.title"));
        this.address = currentAddress();
        this.entry = ServerConfigStore.get(address);
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    private static String currentAddress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) {
            return mc.getCurrentServer().ip;
        }
        return "";
    }

    private static Component ts(String key) {
        return Component.translatable("tsvoice.gui." + key);
    }

    @Override
    protected void init() {
        Font font = Minecraft.getInstance().font;
        int cx = this.width / 2;
        int y = 30;

        // ===== 1. 状态区（只读）=====
        TsVoiceManager.State state = TsVoiceManager.getState();
        boolean connected = TsVoiceManager.isSessionConnected();
        var cfg = TsVoiceManager.currentSessionConfig();
        String stateText;
        int stateColor;
        switch (state) {
            case CONNECTED -> {
                String transport = cfg != null && cfg.tcp() ? "TCP" : "UDP";
                stateText = Component.translatable("tsvoice.state.connected").getString() + " (" + transport + ")";
                stateColor = 0xFF55FF55;
            }
            case CONNECTING -> {
                stateText = Component.translatable("tsvoice.state.connecting").getString();
                stateColor = 0xFFFFFF55;
            }
            case RECONNECTING -> {
                stateText = Component.translatable("tsvoice.state.reconnecting").getString();
                stateColor = 0xFFFFFF55;
            }
            case ERROR -> {
                stateText = Component.translatable("tsvoice.state.error").getString() + " (" + TsVoiceManager.getLastError() + ")";
                stateColor = 0xFFFF5555;
            }
            case AWAITING_TRUST -> {
                stateText = Component.translatable("tsvoice.state.awaiting_trust").getString();
                stateColor = 0xFFFFAA55;
            }
            case WAITING_SVC -> {
                stateText = Component.translatable("tsvoice.state.waiting").getString();
                stateColor = 0xFFFFFF55;
            }
            default -> {
                stateText = Component.translatable("tsvoice.state.dormant").getString();
                stateColor = 0xFFAAAAAA;
            }
        }
        addRenderableWidget(makeLabel(cx - 100, y, 200, Component.literal(
                Component.translatable("tsvoice.state.label").getString() + " " + stateText), stateColor));
        y += 14;
        String serverLine = Component.translatable("tsvoice.state.server").getString() + " "
                + (cfg != null ? cfg.host() + ":" + cfg.port() : "-")
                + (cfg != null ? " (" + Component.translatable(
                        cfg.source().equals("server") ? "tsvoice.source.server" : "tsvoice.source.manual").getString() + ")" : "");
        addRenderableWidget(makeLabel(cx - 100, y, 200, Component.literal(serverLine), 0xFFE0E0E0));
        y += 14;
        String channelLine = Component.translatable("tsvoice.gui.channel").getString() + " "
                + (cfg != null ? cfg.channel() : "-");
        addRenderableWidget(makeLabel(cx - 100, y, 200, Component.literal(channelLine), 0xFFE0E0E0));
        y += 20;

        // ===== 2. 配置区 =====
        enabledBtn = Button.builder(enabledLabel(), b -> {
            entry.enabled = !(entry.enabled == null || entry.enabled);
            ServerConfigStore.save();
            TsVoiceManager.onManualConfigChanged();
            enabledBtn.setMessage(enabledLabel());
            syncEditBoxes();
        }).bounds(cx + 2, y, 98, 20).build();
        addRenderableWidget(enabledBtn);
        beaconBtn = Button.builder(beaconLabel(), b -> {
            entry.useServerBeacon = !(entry.useServerBeacon == null || entry.useServerBeacon);
            ServerConfigStore.save();
            TsVoiceManager.onManualConfigChanged();
            beaconBtn.setMessage(beaconLabel());
            syncEditBoxes();
        }).bounds(cx - 100, y, 98, 20).build();
        addRenderableWidget(beaconBtn);
        y += 24;

        // 信标 OFF 且手动地址为空 → 预填服务器下发值作编辑起点
        boolean useBeacon = entry.useServerBeacon == null || entry.useServerBeacon;
        var beacon = TsVoiceManager.currentBeacon();
        if (!useBeacon && beacon != null
                && (entry.host == null || entry.host.isBlank())) {
            entry.host = beacon.host();
            entry.port = beacon.port();
            entry.channel = beacon.channel();
            ServerConfigStore.save();
        }

        int lh = 11; // 标签高
        addRenderableWidget(makeLabel(cx - 100, y, 200,
                Component.translatable("tsvoice.gui.host"), 0xFFC8C8C8));
        host = new EditBox(font, cx - 100, y + lh, 200, 18, Component.translatable("tsvoice.gui.host"));
        host.setMaxLength(120);
        y += lh + 20;

        addRenderableWidget(makeLabel(cx - 100, y, 200,
                Component.translatable("tsvoice.gui.port"), 0xFFC8C8C8));
        port = new EditBox(font, cx - 100, y + lh, 200, 18, Component.translatable("tsvoice.gui.port"));
        port.setMaxLength(5);
        y += lh + 20;

        addRenderableWidget(makeLabel(cx - 100, y, 200,
                Component.translatable("tsvoice.gui.channel"), 0xFFC8C8C8));
        channel = new EditBox(font, cx - 100, y + lh, 200, 18, Component.translatable("tsvoice.gui.channel"));
        channel.setMaxLength(40);
        y += lh + 20;

        addRenderableWidget(makeLabel(cx - 100, y, 98,
                Component.translatable("tsvoice.gui.password"), 0xFFC8C8C8));
        password = new EditBox(font, cx - 100, y + lh, 98, 18, Component.translatable("tsvoice.gui.password"));
        password.setMaxLength(60);
        // 密码掩码显示（内容仍是原值，仅渲染替换）
        password.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(makeLabel(cx + 2, y, 98,
                Component.translatable("tsvoice.gui.channel_password"), 0xFFC8C8C8));
        channelPassword = new EditBox(font, cx + 2, y + lh, 98, 18,
                Component.translatable("tsvoice.gui.channel_password"));
        channelPassword.setMaxLength(60);
        channelPassword.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        y += lh + 22;

        syncEditBoxes();
        addRenderableWidget(host);
        addRenderableWidget(port);
        addRenderableWidget(channel);
        addRenderableWidget(password);
        addRenderableWidget(channelPassword);

        transportBtn = Button.builder(transportLabel(), b -> {
            // 三态循环：跟随全局 -> udp -> tcp -> 跟随全局
            String cur = entry.transport;
            entry.transport = cur == null ? "udp" : ("udp".equalsIgnoreCase(cur) ? "tcp"
                    : ("tcp".equalsIgnoreCase(cur) ? null : "udp"));
            ServerConfigStore.save();
            TsVoiceManager.onManualConfigChanged();
            transportBtn.setMessage(transportLabel());
        }).bounds(cx - 100, y, 200, 20).build();
        addRenderableWidget(transportBtn);
        y += 24;

        // ===== 3. 操作区 =====
        connectBtn = Button.builder(connectLabel(), b -> {
            saveConfig();
            if (TsVoiceManager.isSessionConnected()) {
                TsVoiceManager.manualDisconnect();
            } else {
                TsVoiceManager.manualConnect();
            }
            connectBtn.setMessage(connectLabel());
        }).bounds(cx - 100, y, 200, 20).build();
        addRenderableWidget(connectBtn);

        y += 24;

        addRenderableWidget(Button.builder(Component.translatable("tsvoice.gui.done"), b -> {
            saveConfig();
            onClose();
        }).bounds(cx - 100, y, 200, 20).build());
    }

    /** 信标开关 ON 时三个框显示当前生效值并锁定；OFF 时载入手动值解锁。 */
    private void syncEditBoxes() {
        boolean useBeacon = entry.useServerBeacon == null || entry.useServerBeacon;
        var beacon = TsVoiceManager.currentBeacon();
        var cfg = TsVoiceManager.currentSessionConfig();
        boolean locked = useBeacon && beacon != null;
        if (useBeacon && beacon != null) {
            host.setValue(beacon.host());
            port.setValue(String.valueOf(beacon.port()));
            channel.setValue(beacon.channel());
        } else {
            host.setValue(entry.host != null ? entry.host : "");
            port.setValue(entry.port != null ? String.valueOf(entry.port) : "9987");
            channel.setValue(entry.channel != null ? entry.channel : "Minecraft");
        }
        host.setEditable(!locked);
        port.setEditable(!locked);
        channel.setEditable(!locked);
        boolean enabled = entry.enabled == null || entry.enabled;
        ModConfig global = TeamspeakSvcClient.config();
        password.setValue(entry.serverPassword != null ? entry.serverPassword
                : global != null && global.serverPassword != null ? global.serverPassword : "");
        channelPassword.setValue(entry.channelPassword != null ? entry.channelPassword
                : global != null && global.channelPassword != null ? global.channelPassword : "");
        // 密码是每服手动信息（信标不下发），仅在 mod 停用时锁定
        password.setEditable(enabled);
        channelPassword.setEditable(enabled);
        editLocked = locked || !enabled;
    }

    private net.minecraft.client.gui.components.StringWidget makeLabel(int x, int y, int w, Component text, int color) {
        var styled = text.copy().withStyle(st -> st.withColor(color));
        return new net.minecraft.client.gui.components.StringWidget(x, y, w, 10, styled, Minecraft.getInstance().font);
    }

    private Component enabledLabel() {
        boolean on = entry.enabled == null || entry.enabled;
        return Component.literal(ts("enabled").getString() + ": " + (on ? "ON" : "OFF"));
    }

    private Component beaconLabel() {
        boolean on = entry.useServerBeacon == null || entry.useServerBeacon;
        return Component.literal(ts("beacon").getString() + ": " + (on ? "ON" : "OFF"));
    }

    private Component transportLabel() {
        String cur = entry.transport;
        if (cur == null) {
            ModConfig cfg = TeamspeakSvcClient.config();
            return Component.literal(ts("transport").getString() + ": "
                    + Component.translatable("tsvoice.gui.auto").getString() + " ("
                    + ("tcp".equalsIgnoreCase(cfg != null ? cfg.transport : "udp") ? "TCP" : "UDP") + ")");
        }
        return Component.literal(ts("transport").getString() + ": "
                + (cur.equalsIgnoreCase("tcp") ? "TCP (tunnel)" : "UDP"));
    }

    private Component connectLabel() {
        return Component.translatable(TsVoiceManager.isSessionConnected()
                ? "tsvoice.gui.disconnect" : "tsvoice.gui.connect");
    }

    private void saveConfig() {
        if (!editLocked) {
            entry.host = host.getValue().trim();
            try {
                entry.port = Integer.parseInt(port.getValue().trim());
            } catch (NumberFormatException e) {
                entry.port = 9987;
            }
            entry.channel = channel.getValue().trim();
        }
        // 密码框已回填且可选：锁定/停用时保存原值（不覆盖），可编辑时写入用户输入
        entry.serverPassword = password.getValue();
        entry.channelPassword = channelPassword.getValue();
        ServerConfigStore.save();
        TeamspeakSvcClient.LOGGER.info("[teamspeak-svc] Manual config saved for '{}'", address);
        TsVoiceManager.onManualConfigChanged();
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
