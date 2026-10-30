package com.tsvoice.client.music;

/**
 * 音乐 Bot 发送许可（G3/P1-01、P2-04）：一份可变的许可状态 + 单调推进的
 * 发送代际。身份（独立 Bot 会话）、连接（该会话自己的连接）、确认频道共同
 * 绑定 —— 不是"对象还在 ACTIVE 就可发"。
 *
 * <p>阶段：</p>
 * <ul>
 *   <li>CONNECTING —— 连接建立中（attachProductionConnection ≠ 握手成功）；</li>
 *   <li>CONNECTED_UNCONFIRMED —— 连接已成但频道未确认；</li>
 *   <li>READY —— 已确认授权房间，可发送；</li>
 *   <li>REVOKED —— 已撤销（暂停/跳过/停止/Bot 关闭/移出房间/断线）。</li>
 * </ul>
 *
 * <p>代际语义：confirm/revoke/rearm 每次<b>推进</b> epoch；非 READY 时
 * {@link #currentEpoch()} 返回 {@link Long#MIN_VALUE}（writer 的丢弃哨兵）。
 * 不可发送状态绝不返回可发送代际；禁止"永久常量 1"。</p>
 *
 * <p>线性化边界：writer 出队后以 {@link #currentEpoch()} 作最终许可校验；
 * 撤销推进代际后，已出队未写出的帧一律丢弃。已进入底层网络发送的在途帧
 * 不承诺撤回（物理限制，由调用方明示）。</p>
 */
public final class MusicSendPermit {

    public enum Phase { CONNECTING, CONNECTED_UNCONFIRMED, READY, REVOKED }

    /** 不可变快照（UI/诊断/测试读取）。 */
    public record Snapshot(Phase phase, long epoch, String serverUid, int channelId,
                           String revokeReason) {
    }

    private Phase phase = Phase.CONNECTING;
    /** 单调发送代际：状态每次实质变化推进。 */
    private long epoch;
    private String serverUid;
    private int channelId = -1;
    private String revokeReason = "";
    /** 授权房间（serverUID；null = 不绑定房间约束）。 */
    private final String boundServerUid;
    /** 撤销是否可再武装（paused/skipped/stop-playback 可；bot closed/移房不可直接恢复）。 */
    private boolean terminalRevocation;

    /** 撤销通知（BotManager 挂接：非可再武装撤销时停止播出/回收）。 */
    private volatile Runnable revocationListener;

    public MusicSendPermit(String boundServerUid) {
        this.boundServerUid = boundServerUid;
    }

    public void setRevocationListener(Runnable listener) {
        this.revocationListener = listener;
    }

    /** 连接建立完成（握手成功、频道未确认）。 */
    public synchronized void connectedUnconfirmed() {
        if (phase == Phase.REVOKED) {
            return;
        }
        phase = Phase.CONNECTED_UNCONFIRMED;
        epoch++;
    }

    /**
     * 自频道确认（首次加入或管理员移动后的实际频道确认）。
     *
     * @return true = 进入 READY；false = 被撤销（授权房间不符等）
     */
    public synchronized boolean confirm(String actualServerUid, int actualChannelId) {
        if (actualServerUid == null || actualChannelId < 0) {
            return revokeInternal("unknown channel state");
        }
        if (boundServerUid != null && !boundServerUid.equals(actualServerUid)) {
            return revokeInternal("moved out of authorized room");
        }
        // 已 READY 且房间/频道未变：幂等（不推代际，避免无谓丢弃在途帧）
        if (phase == Phase.READY && actualChannelId == channelId
                && actualServerUid.equals(serverUid)) {
            return true;
        }
        serverUid = actualServerUid;
        channelId = actualChannelId;
        phase = Phase.READY;
        revokeReason = "";
        terminalRevocation = false;
        epoch++;
        return true;
    }

    /**
     * 自频道移动事件（G3 复核条件 3）：管理员移动 Bot 后确认新频道；授权房间
     * 不符 → 撤销。与 {@link #confirm} 同一判定。
     *
     * @return true = 仍在授权房间（READY）；false = 已撤销
     */
    public synchronized boolean onSelfChannelMoved(String actualServerUid, int actualChannelId) {
        return confirm(actualServerUid, actualChannelId);
    }

    /** 撤销（可再武装性由 reason 决定）。 */
    public synchronized void revoke(String reason) {
        revokeInternal(reason);
    }

    private boolean revokeInternal(String reason) {
        if (phase == Phase.REVOKED) {
            return false; // 幂等
        }
        phase = Phase.REVOKED;
        revokeReason = reason;
        terminalRevocation = "bot closed".equals(reason)
                || "moved out of authorized room".equals(reason)
                || "connection lost".equals(reason);
        epoch++;
        Runnable listener = revocationListener;
        if (listener != null) {
            listener.run();
        }
        return false;
    }

    /**
     * 下一轮播放再武装：仅当撤销是播放控制类（paused/skipped/stopped playback）
     * 且此前的房间确认仍有效时，回到 READY（新代际 —— 旧帧全部失效）。
     *
     * @return true = 已再武装可播放
     */
    public synchronized boolean rearmForPlayback() {
        if (phase == Phase.READY) {
            return true;
        }
        if (phase != Phase.REVOKED || terminalRevocation) {
            return false;
        }
        if (channelId < 0 || (boundServerUid != null && !boundServerUid.equals(serverUid))) {
            return false;
        }
        phase = Phase.READY;
        revokeReason = "";
        epoch++;
        return true;
    }

    /** 当前可发送代际；不可发送返回 {@link Long#MIN_VALUE}（writer 丢弃哨兵）。 */
    public synchronized long currentEpoch() {
        return phase == Phase.READY ? epoch : Long.MIN_VALUE;
    }

    public synchronized boolean isSendable() {
        return phase == Phase.READY;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(phase, epoch, serverUid, channelId, revokeReason);
    }
}
