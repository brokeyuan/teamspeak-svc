# DELIVERY — 验收修复轮（G0–G8）

日期：2026-10-30。基线 `bdea14e`（PR #1 合并）→ 功能终点 `e0be650` → 最终提交
`4a56e70` → 打包 `f2546d7`。本轮按
`docs/superpowers/plans/2026-10-30-known-defects-and-acceptance-repair.md` G0–G8
执行，**到此交接并停止新增功能**。

## 1. 已修复并验证（反例先行，独立提交）

| 项 | 提交 | 修复 | 验证 |
|---|---|---|---|
| G1/P1-03 | 2edd110 | 麦克风 harness 网络替身补 canSend(Object) 契约（默认拒绝、可按 type 设置/清除、send 载荷断言面）；新增 4 用例驱动真实 manager 分支（通道缺席显式失败不发送 OPEN / 在位发送 OPEN 经真实 onRelayOpenedFrame attach / CLOSE 通道不可发送时本地清理仍完成 / CLOSE 可发送时载荷发出）；RunAll 计数按数组输出 | 干净副本 RunMicRedirector exit 0（10 项 G1 断言） |
| G2/P1-02 | 3adc6c9 | 新增 TsDatagramKind（真实包头分类：C2S 下标 12/S2C 下标 10 低 4 位；javap 核对）；trafficClass 0=旧版按包头识别/1=CONTROL/2=MEDIA，不符或未知拒绝；barrier 只约束 MEDIA，控制流不再被挡；VOICE_WHISPER 归媒体队列（消除控制队列旁路）；pps/bytes 对两类统一计费 | RelayControlMediaBarrierTest 19 断言（真实 TS 头 fixture）：barrier 200 后 COMMAND/ACK/PING/PONG 送达、epoch199/0 媒体拒、伪装 CONTROL 拒、旧连接拒、控制超额仍拒 |
| G3/P1-01,P2-04 | c57c3de | 新增 MusicSendPermit（四相+单调代际；不可发送返回 MIN_VALUE；确认幂等；撤销分可再武装/终态）+ MusicBotAssembly（生产装配单点：writer supplier=permit、频道确认绑定授权房间、撤销→停播、发送桥按当前代际）；MusicSession attach 不再置 CONNECTED，startPlayback 前与编码后复核发送门；pause/next/stop 撤销旧许可，resume/play 再武装 | MusicBotProductionTest（真实 Ts3Connection/writer + 可控 transport + 真实 Opus）：确认代际帧到达真实 transport、移出授权房间撤销后无新帧进入底层发送、移回可恢复、终态撤销 MIN_VALUE |
| G4/F2 | 186efff | 新增 OpenEstablisher（OPEN 四段拆分：主线程校验+占额度 → 有界 worker(4)/待办(64) 异步 DNS/socket → 完成回调回原连接；重复 OPEN 拒绝；迟到结果 discard；断连 revoke；close 全回收）；runtime 拆 establishNow/completeAsyncOpen；Fabric 层 SERVER_STARTING 注入；MusicBotManager.start 异步化（identity/DNS/15s 握手移出客户端线程，statusText 反馈，stop 可取消） | RelayAsyncOpenTest 10 断言（受理语义、worker 执行、重复拒、完成登记、revoke、待办上界） |
| G5/F5 | 6d06525 | 客户端 MUSIC_BOT 会话接线：openMcRelaySession 按 purpose 参数化；PendingOpen 携带 purpose；completeBotRelayOpened/Error（Bot 的 OPENED/ERROR 精确分发，不触玩家全局）；openMcRelaySessionForBot + isMcRelayChannelSupported；MusicBotManager.start 显式 UDP/MC 模式（禁止选 MC 悄悄走 UDP）；Ts3Connection transport 注入 | RelayBotSecondSessionTest 10 断言：默认拒绝 Bot 且玩家不受影响、启用后双会话共存（独立 id）、关 Bot 只关 Bot、玩家数据继续转发 |
| G6/P2-05 | e0be650 | MusicScreen 游戏内音乐页（目标/添加 .wav/播放/暂停/继续/下一首/停止/断开/队列状态/本机音量静音；26.3 Screen idiom）；ConfigScreen "Music Bot..." 入口；音量/静音实际生效（ParticipantVolumeControl，键=serverUID+Bot TS UID 稳定派生，同名不同 UID 独立；不乘发送 PCM/许可） | MusicPersonalVolumeTest 8 断言（键稳定、同名独立、写入读回、muted 语义、重连重放、不触发送） |
| G7 | 45fe160/1436221/4a56e70 | harness prod 列表补 OpenEstablisher/TsDatagramKind（M7、p0、manager、p1-isolation、p3-config） | 干净副本 RunAll 全绿（见下） |

## 2. 工程门与证据（`review/acceptance-repair-2026-10-30/evidence/`）

- `g0-probes-baseline.log`：修复前两探针复现成功（MusicWriterProbe / RelayControlProbe）。
- `g0-mic-baseline.log`：干净副本麦克风 harness 编译失败（两处 canSend 找不到符号），
  与远端 CI https://github.com/brokeyuan/teamspeak-svc/actions/runs/40918992098 一致。
- `g7-runall-final.log`：**RunAll CI-EQUIVALENT PASS（core test + 26 harness，exit 0）**。
- `g7-isolated-nosvc.log` / `g7-isolated-svc.log`：隔离 dedicated（不含旧 Bridge）
  无 SVC / 有 SVC 两矩阵：`common init complete` → `Done` → `Stopping server`，
  exit 0，0 mixin error；SVC 矩阵含 `SVC plugin facade` 与 voicechat 2.6.25 装载。
- core `clean test`：145 检查 PASS（108 protocol + 22 capability + 15 identity）。
- mod `clean build`：exit 0 → 候选 JAR。

## 3. 候选

`dist/client-acceptance/4a56e70/teamspeak-svc-0.9.8+26.3.jar`
SHA-256 `7DDE538B8A0EFA707AA326363211A4B91356D7BF891BBB2D31B55829396D1DE9`，
39,421,864 字节。上一候选 `80dd40f` 保留。

## 4. 已实现 / 已测试 / 已构建 / 待用户验证 / 仍有缺口

- **已实现+回归**：上表 7 项。
- **已构建+装载**：候选 JAR（clean build；RunM7 FQN/元数据 PASS；隔离 dedicated
  双矩阵实启）。
- **待用户验证**：真实 TS 服通话/听感、实体设备、Bot MC 中继端到端（Netty+实服）、
  30 分钟稳定性、Extra 双装。
- **仍有缺口（如实，未归入待真机）**：
  1. MC 提交侧仍为 Fabric send 直发（无独立有界 drain 队列）——限额在服务端
     生效；客户端到服务端的在途帧上界由 Fabric 内部管理。列入下一轮。
  2. Bot 频道确认依赖 TS 自频道事件；未确认前 startPlayback 明确失败（不静默）。
  3. 远程点歌/跨服控制的可信 sender 元数据链未实现（不支持，非暂缓）。

## 5. 推送与交接

- 修复分支 `codex/acceptance-repair-20261030`（本 worktree）已按轮次提交；
  推送/PR 由用户流程决定，本轮不自动合并 PR、不强推 main、不发布 Release。
- 原工作区（teamspeak&svcbridge，5ec6f2b + 用户未提交文档）未做 reset/clean/pull。
- 用户现有运行服务器未被启动/停止/改动。
- **本轮结束：停止功能扩展；用户验收反馈后先修验收缺陷。**
