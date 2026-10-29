# CLIENT-ACCEPTANCE.md — 用户验收手册（候选 4a56e70）

> 每场景：前提 → 精确入口/动作 → 预期 → 失败判据 → 日志 → 清理。
> 结果填 `RESULTS.md`（初始全部"待用户执行"）。**首轮冒烟：A00/A01/A02/A05**；
> 基础不通过先反馈，不必跑完。
>
> 日志：客户端 `.minecraft/logs/latest.log`（`[teamspeak-svc]` 前缀）；服务端
> `logs/latest.log`。`config/teamspeak-svc/identity*.txt` 含私钥，不要外发。
> **若实例装过 80dd40f 旧候选，先删除旧 JAR。**

---

## A00 安装与入口自检
- **前提**：MANIFEST §客户端安装；哈希前缀 `7DDE538B`。
- **动作**：启动到主菜单 → ModMenu 确认 `teamspeak-svc 0.9.8+26.3` → 单机世界 →
  `V` 开 SVC 界面 → `/tsvoice status` → `/tsvoice gui`（应见 "Music Bot..." 按钮）→
  点开音乐页 → `/tsvoice music`（命令帮助）。
- **预期**：无 Mixin/缺类崩溃；SVC 界面正常；音乐页可达且显示状态行/命令帮助。
- **失败判据**：启动崩溃（`com.tsvoice` 栈）；Music Bot 按钮缺失。
- **日志**：latest.log 开头 + 崩溃报告。**清理**：无需。

## A01 TS/UDP 直连（无 SVC 服务端）
- **前提**：A00；一台**未装 SVC/本扩展**的 MC 服；可达 TS 服（地址/端口/频道/密码）。
- **动作**：`/tsvoice gui` → 书签新建（TS 地址/端口/频道）→ 手动连接 → 与原生
  TS 用户同频道通话；PTT 与语音激活各一轮。
- **预期**：CONNECTED；TS 侧 `[MC] <名字>` 进频道；双向语音；SVC 音量条反映 TS。
- **失败判据**：`INTERNAL` 错误码；`sent=` 不增长；单向无声。
- **日志**：`TS voice connected` 行后 1 分钟。**清理**：`/tsvoice disconnect`。

## A02 纯 SVC 服不退化
- **前提**：服务器**只装 SVC**（无本扩展服务端）。
- **动作**：加入 → 与另一 SVC 玩家通话 → 附近语音/群组/按键/设备。
- **预期**：与未装本扩展一致。
- **失败判据**：语音不通；日志出现本扩展接管 SVC 收发。

## A03 TS MC 通道（强制模式）
- **前提**：测试服装本 JAR；`server.json` `relay.enabled=true` +
  `relay.targetIds.local={host,port}` 指向真实 TS；客户端书签同名目标。
- **动作**：连接书签 → `/tsvoice status` 确认传输为 TS_MC_CHANNEL → 通话；
  通话中管理员在 TS 移动你到另一频道（确认跟随）；保持 2 分钟观察保活。
- **预期**：TS 侧进频道；服务端日志 `relay session opened`；双向语音；移动后
  跟随且语音切到新频道；保活不掉线（G2 修复：barrier 后控制包不再被挡）。
- **失败判据**：状态显示 UDP（静默回退）；`relay open rejected`；移动后断线
  或命令失效。
- **日志**：服务端 relay 行 + 客户端 attach 行。**清理**：`/tsvoice-server reload`。

## A04 SVC MC 通道混合
- **前提**：测试服 = SVC + 本 JAR + `relay.svcMcChannelEnabled=true`；甲装本
  扩展、乙只装 SVC。
- **动作**：同服同区域通话；乙断线重连。
- **预期**：甲乙互通（甲 MC 通道、乙原生 UDP）；重连后仍互通。
- **失败判据**：一方无声；`addRawPacket failed` 刷屏。

## A05 三个操作语义
- **前提**：A01 TS 连接在位。
- **动作与预期**：
  1. 说话中 `/tsvoice disconnect` → PAUSED；**不**自动改听 SVC；音乐 Bot（若开）自动停。
  2. 切 SVC → TS 关闭；静默边界后恢复 SVC 发送；本机 Bot 停。
  3. OFF → TS/SVC 收发都停；游戏声音正常；恢复后个人音量/静音不变。
- **失败判据**：PAUSED 时能听到 SVC（违反单收听默认）；OFF 后 SVC 仍出声。
- **日志**：各操作后 latest.log 10 行。

## A06 书签/身份/频道管理
- **动作**：书签增删改、独立凭据/身份、频道浏览/加入（含密码）、创建频道、
  单用户音量/静音；管理员移出/移回。
- **预期**：失败有具体原因；单用户音量只影响该人；管理员移动被跟随（未锁定）
  或暂停发送并提示（已锁定）。
- **失败判据**：语音送到错误频道；同名用户共享音量。

## A07 公告与选择优先级
- **动作**：无公告服手动目标不被影响；公告拒绝一次不再弹；授权后按 README
  优先级；过期公告限制后续自动连接但不强制结束手动通话。
- **失败判据**：未授权自动连接；公告覆盖手动目标。

## A08 独立音乐 Bot（UDP）
- **前提**：A01 环境；本地 1–2 个 .wav（PCM 16bit 建议）。
- **动作**：`/tsvoice gui` → "Music Bot..." → 填 TS 地址/端口/频道 →
  **Start Bot (UDP)** → 填 .wav 绝对路径 → **Add track** → **Play**；
  另一 TS 客户端同频道收听；期间你正常说话。
- **预期**：TS 侧 `[Music Bot] <名字>` 进频道；音乐可听；你说话不受影响；
  音乐页状态行显示 PLAYING 与队列数。
- **失败判据**：start 报 identity/connect 错误；PLAYING 但 TS 侧无声（先查
  Bot 是否进频道/被服务器静音）；`sent`/drops 计数异常。
- **日志**：`music bot connected (uid=…, awaiting channel confirm)` →
  自频道确认后 `music bot playing`。
- **清理**：**Disconnect bot**。

## A09 音乐控制与边界
- **动作与预期**：Pause（停发保留位置）/ Resume（继续）/ Next（下一条循环）/
  Stop playback / Disconnect bot；播放中 Pause 后 Resume 继续；Next 换曲无
  旧曲残留；**音乐页底部音量框填 30 → Apply volume**：只影响你听到的 Bot
  音量（另一 TS 客户端音量不变）；Mute (local) 后你听不到 Bot，对方仍正常
  听音乐；Unmute 恢复 30% 不重置 100。
- **失败判据**：Disconnect bot 后 TS 侧仍出声；本机调音改变对方听到的音量；
  Unmute 后音量变 100%。
- **日志**：`music bot stopped`。

## A10 跨服同频道（A 模式×2）
- **前提**：两个 MC 服（都不装扩展），两客户端 + 一原生 TS 用户同 TS 频道。
- **预期**：三方互通；Bot 音乐三方可听。
- **注意**：这是 TS 直连互通，不是 SVC↔TS 媒体桥接（未支持）。

## A11 错误路径
- **动作**：错密码加入、权限不足建频道、被封禁身份、断网 30s。
- **预期**：具体错误文案；断网恢复后重连或明确失败；无静默回退 UDP、无重复采集。

## A12 30 分钟稳定性
- **动作**：A01 通话 30 分钟；切 3 次频道、断连重连 2 次、音乐启停 2 轮。
- **预期**：无明显内存/线程堆积、无残音、无重复 Bot；`sent=` 持续增长。
- **日志**：结束 latest.log 全量。

---

## 不支持清单（N/A）
远程点歌/跨服控制 Bot、在线曲库/搜索、MP3/OGG、SVC↔TS 媒体桥接、网页/手机、
双后端同时收听、svc-extra 任意版本双装。

## 已知代码缺口（本轮如实，未归入待真机）
- MC Bot 中继模式的端到端（Netty + 实服）未验证：客户端 OPEN/attach 接线已由
  回归覆盖，真实组合待 A03 类似环境验证。
- OPEN 异步建立已实现；仍保留同步兼容路径（无建立器时）——生产 dedicated 已注入。
- Bot 频道确认依赖 TS 自频道事件；服务器极端情况下未确认前 startPlayback 明确
  失败（不静默播出）。
