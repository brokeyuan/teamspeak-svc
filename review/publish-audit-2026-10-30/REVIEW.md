# F0–F7 独立复核与推送说明

日期：2026-10-30。审查基线：`5ec6f2b`；功能源码：`80dd40f`；打包提交：`77fc8ea`。

结论：**候选及手册已交付，但 F0–F7 不能判定全部完成。** 发现两个生产链路缺陷、一个干净副本回归编译缺陷；F2/F5 另有任务书要求未实现。本次只审查、补充报告与公开仓库说明，不修改业务代码，不启动用户客户端或运行服务器，不新增功能。

## 1. 已确认问题

下列位置均按当前源码实际行号记录。可信度分别注明源码、候选字节码和可执行反例，不把推演写成真实客户端结果。

| # | 位置（文件:行） | 正常机制 → 当前偏离 → 用户后果 | 可信度 |
|---|---|---|---|
| P1-01 | `teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicBotManager.java:165,187,193,222`；`client/ts3voice/Ts3Connection.java:93,370`（同模块源码根） | Bot 的 writer 应绑定其有效发送许可 → 生产工厂发送 epoch=1，却没有调用 `setVoiceRouteEpochSupplier`，writer 默认返回 Long.MIN_VALUE → 帧入队后被丢弃，播放状态不能证明 TS 端有声音 | 源码 + 实际候选 javap + 真实 writer 反例 |
| P1-02 | `teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Connection.java:343,379`；`TsMcChannelTransport.java:88`；`client/TsVoiceManager.java:1313`；`server/relay/TsRelayRuntime.java:276`（后两项同模块源码根） | 屏障应阻止过期媒体，同时允许必要控制流 → control 调 `send(byte[])`，变成 epoch=0；manager 所有数据均填 trafficClass=0；服务端对所有数据执行代际门 → 正代际频道屏障生效后，命令/ACK/PING 等控制包被拒，模式 B 后续命令和保活受影响 | 源码 + 真实 transport/runtime 反例；TS 实服表现待验证 |
| P1-03 | `review/p0-defects/RunMicRedirector.ps1:176`；`review/p0-defects/stubs/net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java:2`；`teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java:1287,1452` | 干净副本测试应具备生产代码使用的 API 表面 → 该 harness 使用已跟踪的网络替身，未提供 canSend → javac 报两处找不到方法，RunAll 无法全绿，不能用脏工作区日志代替交付证据 | 干净 HEAD 副本实际编译失败 |
| P2-04 | `teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicBotManager.java:144,165,212,222` | F5 要求 UDP/受控 MC 第二会话与已确认房间许可 → 当前工厂只构造 UDP，许可常量1，频道确认/移出房间停发未接线 → 服务端开放 MUSIC_BOT 不等于客户端可走该模式；移动 Bot 后可能向非授权房间继续播出 | 源码；移房真实后果待实服验证 |
| P2-05 | `teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicBotManager.java:123`；`client/ConfigScreen.java:231`（同模块源码根）；`review/client-acceptance-next/CLIENT-ACCEPTANCE.md:83` | F5 要求可发现音乐入口、个人音量和静音 → GUI 没有音乐入口；volume 只改状态字段，验收手册将“只影响描述”当预期 → 玩家无法完成任务书要求的最小音乐体验 | 源码与现有手册 |

P1-01 的探针对真实连接 writer 提交 epoch=1 帧，观察 droppedVoiceFrames 增加1；仅在探针对照组显式绑定 supplier 后，同代际帧才到达 transport。连接上层 client 回调使用现有替身，不声称完成 TS 握手/实服播放。候选 JAR 的 javap 同样显示 Bot 常量1且没有安装 supplier。

P1-02 的探针使用真实 `TsMcChannelTransport`、真实 `TsRelayRuntime` 和本机 UDP 目标；包装形状与 manager 相同。屏障前控制数据通过，屏障200后同一 `send(byte[])` 被拒，`send(data,200)` 对照通过。没有假设已经完成真实 MC/TS 端到端验证。

另：F2 已明确要求将 DNS/socket 移出 MC 主线程、建立有界 MC 提交队列；交付报告承认尚未实现，不能在总进度中同时写“F2 完成”。Bot start 命令同步调用 DNS/最长15秒握手，也应作为控制面阻塞风险处理。

## 2. 实际复核证据

| 项目 | 本次结果 | 限制 |
|---|---|---|
| 候选大小/哈希 | 39,396,995字节；`8585708afc5e3740309411b28b46138fb0886b5e08552fe99e20f5ad6c4d6a1c`，与交付一致 | 未重打包 |
| 候选内容/元数据 | RunM7 对已交付 JAR 的 required entries、禁止内容、environment、入口、依赖、Mixin FQN/环境检查通过 | 将原候选复制到临时 build/libs 后执行，**不是重新构建，也不是实际注入/启动证明** |
| core 实际测试 | 直接 javac 编译当前 main/test，运行构建定义的三个测试入口：108+15+22=145检查通过 | Gradle 启动失败，因此明确记录为直接运行，不写 Gradle PASS |
| 原样 RunAll | exit1，10项失败 | core 未生成导致若干依赖套件级联失败；完整日志单独保留 |
| core 手动编译后26套件 | 22组exit0，4组exit1 | 麦克风为确定编译缺陷；session-consumption/upload-confirmation 为本机 HttpClient 故障；M7 自动构建因同类本机回环故障失败 |
| 真实 codec | 960样本→196字节→960样本，rms4660，PASS | 只验证编解码，不证明 Bot 网络发送 |
| 本轮新增2个探针 | 均复现缺陷并通过对照组 | **探针exit0表示缺陷复现成功，不是产品通过** |
| 客户端/设备/实服 | 未运行 | RESULTS 保持待用户执行；未操作用户服务器 |

独立无项目依赖的 `HttpClient.newHttpClient()` 最小程序在本机两个 Java25 运行时均出现 `Unable to establish loopback connection / UnixDomainSockets Invalid argument`，因此相关失败归入本机环境，不能据此推断产品有上传缺陷。P1-01/02/03 与该环境故障无关。

可重跑缺陷探针：仓库根目录、Java25执行 `powershell -NoProfile -ExecutionPolicy Bypass -File review/publish-audit-2026-10-30/RunProbes.ps1`。脚本只在临时目录编译，探针只建立本机 UDP 目标；不生成身份文件或连接外部 TS。

证据见同目录 `evidence/`。包含原样 RunAll、直接 core 测试、26组重跑明细、候选检查、两类候选 javap 与探针结果；运行日志保留失败，不用旧记录覆盖。文本只统一为 UTF-8、LF 换行并去除行尾空白，未改变检查结果。

## 3. F0–F7 状态更正

| 阶段 | 本次判定 | 交接要求 |
|---|---|---|
| F0 | 基线/历史交付可追溯；进度断言需更正 | 不再笼统写全部完成 |
| F1 | 受控换态相关 suite 通过，真实 SVC 客户端待验收 | 保留修复与回归，不重复重构 |
| F2 | 部分完成 | 修 P1-02；补控制/媒体分类、DNS/socket 异步及有界调度 |
| F3 | route/off/single-listen 回归通过，真机待验收 | 保留 PAUSED 不听 SVC、OFF 与用户设置隔离 |
| F4 | 编码后取消边界、真实 codec 通过；生产发送不完整 | 修 P1-01；把真实 writer 纳入 Bot 生产装配测试 |
| F5 | 部分完成 | 独立 identity/client/encoder 和命令保留；补实际房间许可、游戏入口、个人音量/静音、Bot MC 客户端；未做的不得记通过 |
| F6 | 原候选可核对；完整绿门证据未复现 | 修 P1-03，在新干净副本重跑，替换候选时使用新源提交和新哈希 |
| F7 | 手册/空白结果表已交付，但需纠偏 | A03/A08不可当作正常验收起点；A09不能以音量仅改描述替代要求；补实际入口、清理及错误操作步骤 |

原任务书和产品预览此前未被 Git 跟踪，本次发布显式纳入相关文档，使手册引用和需求依据可由后续开发者查阅。预览是目标，不是当前候选能力清单。

## 4. 修复顺序与验收入口

1. **先 P1-03**：在该 harness 自己的网络替身/生成源补 canSend 契约，覆盖可发送/不可发送分支；不得借用其他套件的残留 class。干净副本单跑 MicRedirector，再跑完整门。不要通过删除 suite 或吞异常“修绿”。
2. **再 P1-02**：保留入队 epoch，明确控制/媒体类别，控制绕过媒体代际门但仍受来源、会话、大小、速率和资源限制。不能简单放行所有 epoch0，否则失效语音可绕过屏障。增加屏障后的 command/ACK/PING、旧媒体拒绝和新媒体通过的生产链测试。
3. **再 P1-01 与 Bot 房间许可**：由独立 Bot 会话拥有可撤销、绑定连接及确认频道的许可；writer supplier 必须使用这份许可。取消/断线/换房撤销，旧帧不能借新会话复活。**不能只把 supplier 永久设1**后声称完成频道安全。真实 Bot 工厂→client→writer→可控 transport 必须纳入回归。
4. **补 F2/F5 已承诺缺口**：异步 OPEN/DNS、受限 MC 调度；Bot MC 第二会话客户端、可发现音乐入口、实际本地音量/静音。保持可选服务端和唯一发送路径，不开展 Music-2/3、媒体桥接或 WebRTC。
5. **重新交接候选**：每个缺陷独立提交，干净副本完整门和构建；保留旧候选，新候选用新源码提交目录、完整哈希/大小；手册按实际功能更新，所有用户验收状态仍待执行。

如用户希望先测基础通话，可先做 A00/A01/A02/A05；其通过也仅代表对应基础场景。A03先修中继、A08/A09先修音乐；A10/A12涉及音乐部分不能凭基础语音结果判定通过。当前不建议用户用30分钟听感测试来发现已能自动复现的确定缺陷。

## 5. 推送边界

推送到 `codex/client-acceptance-20261030` 开发分支，以草稿 PR 供审阅；不发布 GitHub Release，不标稳定版本，不把失败的候选自动合入 main。保留原功能与候选提交历史，并合并 GitHub 原始 README/LICENSE 提交，避免强推覆盖远端历史。原工作区业务代码、未提交用户资料、现有服务器保持不变。

参考用户指定的 Carpet 项目 AGENTS.md，采用简短玩家文档、中文 conventional commit、实际行号及证据分级；其中 Carpet 规则/11版本构建要求不适用于此 Fabric 单版本项目。本项目验收需对应 Java25/MC26.3/SVC版本、实际 Mixin 装载和客户端行为，不能用 artifact 检查替代启动。
