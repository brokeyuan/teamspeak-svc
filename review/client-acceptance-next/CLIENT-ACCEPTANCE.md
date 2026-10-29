# CLIENT-ACCEPTANCE.md — 用户验收手册（候选 80dd40f）

> 2026-10-30 复核提示：A03 中继、A08/A09 音乐存在确定缺陷，修复前不判正常验收通过；可先测 A00/A01/A02/A05。完整状态见 [独立复核](../publish-audit-2026-10-30/REVIEW.md)，下文保留原候选操作说明。

> 每个场景给出：前提 → 精确入口与动作 → 预期现象 → 失败判据 → 保存的日志 → 清理。
> 结果写入同目录 `RESULTS.md`（初始全部"待用户执行"）。首轮冒烟优先级：**A00–A05**；
> A00–A05 有失败即停下反馈，不必跑完后续。
>
> 日志位置：客户端 `.minecraft/logs/latest.log`（本扩展行以 `[teamspeak-svc]` 开头）；
> 服务端 `logs/latest.log`；`config/teamspeak-svc/` 下的 `identity.txt`（玩家）与
> `identity-music.txt`（Bot）不要外发（含私钥）。

---

## A00 候选安装与入口自检
- **前提**：MANIFEST §客户端安装；客户端 `mods/` 与 manifest 哈希一致。
- **动作**：启动客户端到主菜单 → 打开 ModMenu 确认 `teamspeak-svc 0.9.8+26.3` → 进任意单机世界 → 按 `V` 打开 SVC 界面 → 聊天栏输入 `/tsvoice status`、`/tsvoice music`。
- **预期**：无 Mixin/缺类崩溃；SVC 界面正常；`/tsvoice status` 输出状态行；`/tsvoice music` 输出命令帮助。
- **失败判据**：启动崩溃（报 `com.tsvoice` 栈）；SVC 界面缺失；命令不存在。
- **日志**：`latest.log` 开头 200 行 + 崩溃报告。
- **清理**：无需。

## A01 TS/UDP 直连（无 SVC 服务端）
- **前提**：A00 通过；一台 **未装 SVC/本扩展** 的 MC 服务器（模式 A 服务端）；可达的 TeamSpeak 服务器（地址/端口/频道名/如需密码）。
- **动作**：`/tsvoice gui` → 书签页新建书签（填 TS 地址、端口、频道）→ 手动连接 → 与同一 TS 频道里的原生 TS 用户（或第二个客户端）通话；试 PTT 与语音激活各一轮。
- **预期**：状态变 CONNECTED；TS 侧看到 `[MC] <你的名字>` 进入频道；双向语音；SVC 原界面音量条反映 TS 说话。
- **失败判据**：超时/PROTOCOL_REJECTED 之外（网络问题重试即可）的出现 `INTERNAL` 错误码；对方听不到或听不到对方；麦克风帧计数 `sent=` 不增长。
- **日志**：`latest.log` 中 `TS voice connected` 一行及之后 1 分钟。
- **清理**：`/tsvoice disconnect`。

## A02 纯 SVC 服务器不退化
- **前提**：A02 所用服务器 **只装 SVC**（无本扩展服务端、无 TS 公告）。
- **动作**：加入该服 → 用原版 SVC 与另一 SVC 玩家通话 → 试附近语音/群组、按键、设备设置。
- **预期**：与未装本扩展时行为一致（本扩展客户端在场不改变 SVC 原生收发）。
- **失败判据**：SVC 语音不通或音量异常；日志出现本扩展接管 SVC 收发的记录。
- **日志**：`latest.log`。

## A03 TS MC 通道（强制模式）
- **前提**：测试服装本 JAR + `server.json` 配置 `relay.enabled=true`、`relay.targetIds.<id>={host,port}` 指向真实 TS；客户端书签同名目标；客户端选该服。
- **动作**：服务器给出公告后手动连接书签；`/tsvoice status` 确认传输为 **TS_MC_CHANNEL**（而非 UDP）→ 通话。
- **预期**：TS 侧进入频道；服务端日志出现 `relay session opened: player=… target=…`；语音双向。
- **失败判据**：状态显示 UDP（静默回退 = 失败）；`relay open rejected`。
- **日志**：服务端 `latest.log` 的 relay 行；客户端 relay attach 行。
- **清理**：服务端 `/tsvoice-server reload` 或直接停止测试服。

## A04 SVC MC 通道混合
- **前提**：测试服 = SVC + 本 JAR + `relay.svcMcChannelEnabled=true`；两客户端：甲装本扩展、乙只装 SVC。
- **动作**：两人进同一服同一区域通话；甲 `/tsvoice status` 观察；乙断线重连一次。
- **预期**：甲乙互通（甲走 MC 通道、乙走原生 UDP）；重连后仍互通。
- **失败判据**：一方无声；服务端日志 `addRawPacket failed` 刷屏。
- **日志**：服务端 latest.log。

## A05 三个操作语义（断开/切 SVC/OFF）
- **前提**：A01 的 TS 连接在位。
- **动作与预期**：
  1. 说话中途 `/tsvoice disconnect` → 立即 PAUSED；**不**自动改听 SVC（此时若在同一 SVC 服，SVC 收听保持压制）；原生 TS 侧停止收听你。
  2. `/tsvoice gui` 或命令切 SVC → TS 会话关闭；静默边界（约 250ms）后恢复 SVC 发送；本机音乐 Bot（若开着）自动停。
  3. `/tsvoice` 界面 OFF（关闭全部语音）→ TS 与 SVC 收发都停；游戏本身声音正常；再恢复后个人音量/静音不变。
- **失败判据**：PAUSED 状态下能听到 SVC 语音（= 未选 SVC 却在收听，违反单收听默认）；OFF 后 SVC 仍出声。
- **日志**：各操作后 `latest.log` 10 行。

## A06 书签/身份/频道管理
- **前提**：A01 环境。
- **动作**：书签增删改、每书签独立凭据/昵称、频道浏览/搜索/加入（含密码频道）、有权限时创建频道、对某用户单独音量/静音；请管理员把你移出/移回频道。
- **预期**：加入失败有具体原因；创建成功即进入；单独音量只影响该人；管理员移动后被跟随（未锁定）或暂停发送并提示（已锁定）。
- **失败判据**：向错误频道发送语音；同名用户共享音量设置。

## A07 公告与选择优先级
- **前提**：测试服可配置公告（trust-announce 流程）。
- **动作**：无公告服 → 手动目标不被影响；有公告 → 拒绝一次后不再弹窗；授权后目标按 README 优先级生效；公告过期后再试。
- **预期**：与 README-RELEASE-PREVIEW §连接如何选择一致。
- **失败判据**：未授权公告自动连接；公告覆盖正在使用的手动目标。

## A08 独立音乐 Bot（先 UDP）
- **前提**：A01 的 TS 在位；本地准备 1–2 个 **.wav**（PCM，16bit 建议）；游戏内。
- **动作**：`/tsvoice music start <TS地址> 9987 <频道名>` → `/tsvoice music add <wav 绝对路径>`（可多次）→ `/tsvoice music play` → 另一客户端（TS 原生或 A01 第二实例）进同一频道听。
- **预期**：TS 侧出现 `[Music Bot] <你的名字>`；听到音乐；你自己的麦克风同时可用（说话叠加）；`/tsvoice music status` 显示 playing 与队列。
- **失败判据**：start 报 identity/connect 错误（看消息）；play 后无声音但 status=PLAYING → 检查 TS 侧 Bot 是否进频道、是否被服务器静音。
- **日志**：`music bot connected`、`music bot playing` 行。
- **清理**：`/tsvoice music stopbot`（TS 侧 Bot 离开）。

## A09 音乐控制与边界
- **动作与预期**：`pause`（Bot 停发，位置保留）/ `resume`（继续）/ `next`（队列下一条，循环）/ `stop`（回到 CONNECTED）/ 播放中途 `stopbot`；音乐音量 `volume 30` 只影响描述，不改变远端；你的 TS 断开/切 SVC/OFF 时 Bot **自动停**且只停你自己的。
- **失败判据**：停止后 TS 侧仍出声（隐蔽发送）；他人 Bot 被误停。

## A10 跨服同频道（模式 A×2）
- **前提**：两个 MC 服（都不装扩展），两客户端 + 一原生 TS 用户同 TS 频道。
- **预期**：三方互通语音；音乐 Bot 也可被三方听到。
- **注意**：这是 TS 直连互通，**不是** SVC↔TS 媒体桥接（后者未支持）。

## A11 错误路径
- **动作**：错误密码加入、权限不足建频道、被封禁的 TS 身份、拔网线 30s。
- **预期**：具体错误文案（PROTOCOL_REJECTED/权限/可重试网络类）；断网恢复后重连或明确失败原因；无静默回退 UDP、无重复采集。

## A12 30 分钟稳定性
- **动作**：A01 通话保持 30 分钟，期间切 3 次频道、断连重连 2 次、音乐启停 2 轮。
- **预期**：无内存/线程明显堆积（任务管理器观察）、无残音、无重复 Bot；`sent=` 计数持续增长。
- **日志**：结束时的 `latest.log` 全量。

---

## 不支持清单（验收遇到直接 N/A）
远程点歌/跨服控制 Bot、在线曲库/搜索、MP3 等格式、SVC↔TS 媒体桥接、网页/手机、双后端同时收听、svc-extra 任意版本双装。
