# teamspeak-svc 已知缺陷修复与客户端验收收口 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task when available. 没有该技能时，仍按本文的“反例→修复→回归→独立提交”执行。本文是实施交接入口，不是新的产品扩展任务。

**Goal:** 修复上一轮已确认缺陷，补齐已承诺的 F2/F5 缺口，交付新的统一候选供用户进行客户端验收。

**Architecture:** 保留统一 JAR、可选服务端、TS/SVC 协调器和 SVC 操作体验。修复既有生产链，不重写协议、GUI 或稳定音乐引擎；Bot 仍拥有独立身份、连接、传输、编码器和可撤销频道许可。

**Tech Stack:** 沿用当前 Java25、MC26.3、Fabric Loader0.20.1、Fabric API0.165.0+26.3、SVC2.6.25；SVC 支持区间沿用 `[2.6.20,2.7.0)`，不夹带依赖升级。

**Spec:**

- 最新复核：`review/publish-audit-2026-10-30/REVIEW.md`，包括 P1-01/02/03、P2-04/05 和可执行探针。
- 原 F0–F7 要求：`docs/superpowers/plans/2026-10-29-client-acceptance-candidate-plan.md`；只续做未关闭项。
- 产品方向：`docs/product/README-RELEASE-PREVIEW.md`；这是目标说明，不是当前能力证明。
- 原候选手册：`review/client-acceptance-next/`，仅作历史与场景编号参考。

本文是本轮执行顺序和结束条件的依据。不能因为旧 STATUS 写过“全部完成”，跳过最新复核；也不能重复重做已经验证保留的修复。

路径约定：下文 `client/`、`server/` 与 `TeamspeakSvcMod.java` 均相对于 `teamspeak-svc/src/main/java/com/tsvoice/`；`review/`、`docs/`、`ts3voice-core/` 相对于仓库根。新增类是本轮计划，不表示当前已经存在。

## 全局约束

- GitHub：`https://github.com/brokeyuan/teamspeak-svc`；主分支是 **main**。PR #1 已合并，合并提交为 `bdea14e2b757d9c31ee405a4a5298e36573a78e8`。开工检查实际 main；若更新，检查后续提交，不把该哈希当作强制回退点。
- 编写本计划时原本地工作区仍在 `5ec6f2b`，存在未提交文档；其中一些文档已在远端跟踪。不得直接 pull 覆盖，不 reset/clean，不擅自删除这些文件。
- 本轮 G0–G8 连续完成；前三项缺陷修复后继续关闭本文列出的既有 F2/F5 缺口。**不自动进入 Music-2/3、在线曲库、远程点歌、SVC↔TS 媒体桥接或 WebRTC。**
- 保持同一 JAR 双端；TS UDP 不新增 MC 服务端依赖；不要求旧 `teamspeak&svcbridge` 或 `voice-capabilities-fabric`。
- 麦克风单发送后端、默认单收听后端；PAUSED 不自动听 SVC；断开 TS / 切 SVC / OFF 语义分开；不重置用户设备、音量或静音。
- 不自动启动用户客户端，不操作用户现有服务器。允许独立测试实例和无界面检查；用户实机项目一律待用户执行。
- 提交使用中文 conventional commits、单主题；不加生成标识或署名尾注。相关 Mixin/API 用实际目标 JAR 的 javap 核实，编译或 JAR 内容检查不能代替装载验证。
- 本轮输出到 `review/acceptance-repair-2026-10-30/`，不覆盖历史证据。文档分清已实现、已测试、已构建、待用户验证、仍有缺口。
- 未经证实的问题标为风险；源码缺陷与缺实现不能归入“待真机”。必要运行环境确实缺失时记录具体命令、失败和影响范围，不伪造通过。

## 重点复核条件

1. 旧 MC 连接的 OPEN/DNS 回调在新连接建立后才完成：不得批准新尝试、创建孤儿 socket 或重定向目标。归 G4/G5。
2. 控制包伪装、过期语音 epoch0、VOICE_WHISPER：不能靠声明 CONTROL 绕过媒体屏障；正常 ACK/保活不能误挡。归 G2。
3. Bot 编码中取消、writer 已出队但尚未交付、房间变更、重连：旧许可不能复活；已交付真实网络的数据不承诺撤回。归 G3/G4。
4. 慢 DNS、阻塞发送、队列满、关闭/reload：MC 主线程响应，队列条数/字节/任务均有界，控制包不能无限饿死媒体或被媒体填满。归 G4。
5. Bot UID/显示昵称变化、重复昵称、本地音量与另一玩家收听：只操作正确的稳定参与者键，不把本机音量改成全频道发送音量。归 G6。

## 执行顺序

| 阶段 | 范围 | 完成条件 |
|---|---|---|
| G0 | 同步现场与建立基线 | 安全使用最新 main，保留用户改动，记录旧缺陷与 CI |
| G1 | P1-03：干净副本 harness | 麦克风回归独立可编译，正反 canSend 分支受测 |
| G2 | P1-02：中继控制/媒体 | 屏障后控制通过，过期媒体拒绝，全部流量仍计费 |
| G3 | P1-01、P2-04：Bot 发送/房间许可 | 实际生产工厂接真实 writer，绑定确认房间并可撤销 |
| G4 | F2：异步与背压 | OPEN、网络/编码等不阻塞 MC，双向调度有界且可回收 |
| G5 | F5：Bot 受控第二中继会话 | 客户端真实使用 MUSIC_BOT，玩家会话不受影响 |
| G6 | P2-05：最小音乐入口与个人音量 | 游戏内可发现、可完整操作，本地音量实际生效 |
| G7 | 干净副本、CI、装载、候选 | 新源码、新 JAR、新哈希和真实日志一致 |
| G8 | 手册、结果表与交接 | 用户可照做；交付后停止新增功能 |

### G0：同步现场，保留原始失败证据

**文件：**读取当前 Git 状态与上述 Spec；新增 `review/acceptance-repair-2026-10-30/STATUS.md`、`evidence/`。

- [ ] 记录当前 HEAD、分支、远端、已有业务改动/文档/生成物、Java 与依赖版本。确认 PR #1 已合并；不得在旧 `5ec6f2b` 上独立重做整轮再覆盖 main。
- [ ] 当前工作区有未提交内容时，优先用最新远端 main 的独立 checkout/worktree，建议分支 `codex/acceptance-repair-20261030`。原本地文档先读取并按内容比较，保留不同版本；不得用清理工作区解决拉取冲突。没有 origin 时先核对仓库身份，使用上述明确 URL，不修改其他项目配置。
- [ ] 本任务书交付时仅在原本地工作区，尚未推送；切换实施 checkout 时将其一并保留，并随本轮文档提交纳入版本记录，不遗漏执行入口。
- [ ] 阅读最新 REVIEW 与两项探针，复跑 `review/publish-audit-2026-10-30/RunProbes.ps1`。**旧脚本 exit0表示复现缺陷成功，不表示产品通过。**保留基线输出，随后另建修复后正向回归。
- [ ] 在干净副本运行 `powershell -NoProfile -ExecutionPolicy Bypass -File review/p0-defects/RunMicRedirector.ps1`，保留两处 canSend 编译失败。若新 main 已修好，核对修复和正反例后关闭，不故意回退。
- [ ] 记录远端已确认的历史失败：`https://github.com/brokeyuan/teamspeak-svc/actions/runs/40918992098`。该次 core 与客户端构建通过，麦克风 harness 失败；不能笼统归因本机 Java。

### G1：修复 P1-03，建立可重复的工程门

**修改：**`review/p0-defects/RunMicRedirector.ps1`、该目录 `stubs/net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java`、`MicRedirectorRegression.java`；必要时 `review/RunAll.ps1`。

**接口要求：**测试替身提供 `public static boolean canSend(Object type)`，或与真实 API 相容的具体参数；测试可设置当前允许的 payload type，并在用例间清空。不要全局恒 true 后声称覆盖无通道场景。

- [ ] 先确认脚本生成源和已跟踪替身的所有权；在该 suite 自身补契约，不借其他 suite 残留 class/stub。
- [ ] 增加“支持 OPEN 通道→进入等待 OPENED”“不支持→明确失败且不发送 OPEN”“关闭通道不可发送→本地清理仍完成”测试，覆盖实际 manager 生产分支。
- [ ] 单独在新干净副本运行该脚本，验证编译及断言通过；再跑 route-lifecycle、ts-mc-relay 受影响回归。
- [ ] `RunAll.ps1` 的套件数量从实际数组/执行结果计算，去除结尾硬编码23等误导；任一失败必须非零退出，日志按本次运行分目录，不追加旧结果冒充新证据。
- [ ] 独立提交，例如 `fix: 修复麦克风回归替身与完整检查计数`。

### G2：修复 P1-02，控制流不能被媒体屏障误挡

**修改：**`teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/{Ts3Connection,TsMcChannelTransport,TsTransport}.java` 中确有需要的文件、`client/TsVoiceManager.java`、`server/relay/{TsRelayRuntime,TsRelaySession}.java`、`ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java`；测试 `review/ts-mc-relay/` 与真实 writer 用例。

**推荐最小方案：**保留既有 `TsTransport.send(byte[])` / `send(byte[],long)` 和外层布局；控制使用原有入口，媒体保留入队 epoch。共享核心增加 TS 数据报类型识别工具，供 relay 识别媒体和控制，不为了两类数据重构整个协议栈。

拟新增 `ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsDatagramKind.java`：`classify(byte[] datagram, Direction direction) -> Kind`；Direction 为 C2S/S2C，Kind 为 CONTROL/MEDIA/INVALID。

- [ ] 以真实 `Ts3Packet/Ts3Crypt` 生成的 datagram 建立反例。当前源码 C2S type 位于 MAC8字节+header第5字节，即下标12；S2C 为下标10；只取低4位。核实 INIT、flags、最小长度、分片/重传路径，不把上行/下行位置混用。
- [ ] VOICE/VOICE_WHISPER 均为媒体；COMMAND/COMMAND_LOW/ACK/ACK_LOW/PING/PONG/INIT1 为控制；短包、未知类型拒绝。只识别可见头部，不解密或重写 TS 数据，不宣称中继验证了 TS MAC。
- [ ] 明确 trafficClass 常量和兼容规则。推荐 `0=旧版未分类，1=CONTROL，2=MEDIA`；旧版0仍按真实包头识别，不能全放行。显式类别与包头不符、未知类别拒绝。MC 中继先要求两端使用本轮同一候选，不凭 wire布局未变承诺任意旧端互操作。
- [ ] 真实 manager sender 填正确类别。服务端由包头校验分类，媒体才检查 barrier；控制仍检查所属 connection/session、目标、大小、速率和额度，不能成为无限带宽旁路。
- [ ] 不能简单将 epoch0放行，也不能在出队时给旧媒体贴“最新代际”。清理 VOICE_WHISPER 可能进入控制队列的路径，使其同样受媒体许可和代际检查。
- [ ] 在屏障200后测试：COMMAND/ACK/PING/PONG 正常送达；epoch199媒体拒绝、200媒体通过；epoch0媒体/伪装CONTROL媒体拒绝；旧 session 控制拒绝；控制超额仍拒绝。用真实 writer→transport→外层 codec→runtime→本机 UDP 路径，校验原字节。
- [ ] 旧 relay 测试若使用1字节占位包，改用有真实 TS 头的合法 fixture，同时保留原大小/限额/关联断言；不能为了保留旧假包而放宽生产校验。
- [ ] 跑 `RunTsMcRelay.ps1`、`RunRouteLifecycle.ps1`、`RunProtocol.ps1`；独立提交 `fix: 隔离中继媒体屏障与控制流量`。

### G3：修复 P1-01 与 Bot 房间许可，不能只加常量 supplier

**修改：**`client/music/{MusicBotManager,MusicSession,MusicBotLifecycle}.java`、实际需要的 TS client/connection 回调；新增 `client/music/MusicSendPermit.java` 和 `review/music-0/MusicBotProductionTest.java`（或等价清晰名称）。

**许可契约：**一份不可变快照至少含 Bot 会话标识、单调 epoch、serverUID、已确认 cid、可发送状态。`MusicSendPermit` 提供 `currentEpoch()`、`confirm(...)`、`revoke(reason)`、快照读取；失效时不能返回可发送代际。身份、连接、确认频道共同绑定，不只判断对象还在 ACTIVE。

- [ ] 先把 MusicWriterProbe 的机制改为正式正向回归：通过真实 Bot 生产装配创建 connection/encoder/sender，真实 writer 收到确认代际可发送；未绑定许可、连接中和未确认房间不能发。
- [ ] Bot 工厂实际安装 `setVoiceRouteEpochSupplier`，sender、编码后复核、最终 writer 共用同一份许可。epoch 随撤销推进，不能永久设1，不能改变玩家的全局 supplier 或会话。
- [ ] `attachProductionConnection` 不等于握手成功。明确 CONNECTING/连接已成但频道未确认/READY 的真实状态，避免提前显示已连接、允许播放。保留已有兼容方法时也必须满足该门。
- [ ] Bot 握手后从实际服务器状态确认 serverUID/cid；绑定明确目标，解析歧义、移动失败、超时均禁止媒体。Bot 被移出授权房间立即撤销/清队列并停止播出；不自动追踪到其他房间。
- [ ] 玩家频道跟随/锁定策略保持原生 TS 体验，不将 Bot 固定房间规则强加给玩家。玩家正常管理员移动的已有逻辑不重做。
- [ ] cancel/pause/next/stop/stopbot、连接失败/关闭、玩家退出TS/切SVC/OFF 时，按动作撤销旧播放许可并回收所拥有的资源。下一轮播放取得新许可；pause/resume 的位置保留语义不退化。
- [ ] 定义撤销与最终写出许可获取的共同线性化边界：writer已出队但尚未取得最终许可，撤销后必须丢弃；已取得最终许可并进入底层发送的在途帧可能完成，明确这一物理限制，不把“检查过epoch”笼统当永久许可，也不为等待慢网络而无限阻塞stop。
- [ ] 增加阻塞 encoder、writer 出队后/最终许可前等待、迟到频道事件、重新连接同 cid、serverUID变化、Bot 移房测试；边界后不得新增旧许可提交，另测已进入底层发送的在途帧，不能声称能撤回。
- [ ] 区分编码、入队、实际writer写出、丢弃计数；`sendVoice`未抛异常和PLAYING状态不能冒充实际写出成功。
- [ ] 真实 encoder→sender→writer→可控 transport 是主回归，不能全部停留在 `connectForTest`/假发送计数。采样48kHz单声道20ms/960样本、codec5保留真实编解码验证。
- [ ] 跑 music、route-lifecycle、实际连接代际回归；独立提交 `fix: 绑定音乐发送许可与确认房间`。

### G4：关闭 F2 已承诺的主线程阻塞与背压缺口

**修改：**`TeamspeakSvcMod.java`、`client/TsVoiceManager.java`、`server/relay/{TsRelayRuntime,TsRelaySession}.java`、`client/music/MusicBotManager.java`、相关生命周期。按需新增专用 relay 提交队列类，不新建通用后端框架。

**并发契约：**MC 线程只接收/校验/入队和发布状态；DNS、socket、握手、文件读取/编码在有界工作执行器。任务捕获创建时 server/connection/purpose/requestId/transport；完成后回到正确线程复核所有权。

- [ ] 加入阻塞 DNS/OPEN 和 fake MC dispatcher 反例：主线程可继续 tick/关闭，重连与取消后迟到完成不能生效。
- [ ] 将 OPEN 拆为请求校验、预留额度、异步建立、原连接完成四段。pending 也占额度并可超时撤销；同 connection/purpose 重复 OPEN 幂等/拒绝，不无限提交线程。
- [ ] Bot start 的 identity生成、DNS、最长15秒握手移出客户端线程。提交命令立即返回连接中，状态/错误在客户端线程反馈，stop 能取消当前尝试。
- [ ] 双向 MC 提交不能每帧无限 execute/send。每会话与全局同时限制排队条数和字节；每个目标最多一个待调度 drain，单次 drain 有预算，余量延后。遵循实际 Fabric API 线程契约，不从 UDP 线程无约束读取玩家世界状态。
- [ ] 沿用现有限额；新增缺省建议：媒体每方向每会话最多64帧且256KiB，控制独立最多32帧且128KiB，全局最多16MiB；OPEN执行器最多4个worker、64个待办。若使用更严格的现有限额，记录最终值与依据，测试使用可注入小容量，不依赖这些大值跑满。
- [ ] 媒体满丢最旧并计数；控制满明确失败/关闭相应会话，不静默丢关键控制。调度给控制保留空间但设预算，避免媒体永久饥饿。两类均实际计费；对入口与最终转发的计费口径写明，不能重复扣同一限额造成错误拒绝。
- [ ] 在真正 UDP writer/MC提交交付前复核会话与媒体屏障，覆盖“已出业务队列但尚未提交”的旧帧。关闭/reload/断连清 pending、队列和占用；已经进入实际网络写入的帧不能承诺撤回。
- [ ] DNS Future 超时不等于底层解析被强制停止；迟到结果必须关闭资源并丢弃，不能通过不断创建线程绕过阻塞。
- [ ] 测试队列满、慢发送、关闭唤醒、reload目标变化、旧server回调、pps/bytes回填、worker与资源上界；真实 loopback 保留。独立提交，可按 OPEN 与有界调度拆为两项。

### G5：完成 Bot MC 客户端第二会话，服务端开关不是交付终点

**修改：**MusicBotManager/生命周期、`TsVoiceManager` 中必要的中继请求关联、`TsMcChannelTransport`、relay runtime/config、相关回归。只提取玩家与 Bot 确实共享的 OPEN/关联代码，避免复制整个玩家 manager。

- [ ] 将 Bot 连接参数明确为 UDP目标或 MC中继 targetId；MC模式不在客户端解析 TS DNS。禁止 UI选择了MC却悄悄走UDP。保留已有 UDP 命令入口，不强迫所有用户安装服务端。
- [ ] 建立完整 `OPEN(purpose=MUSIC_BOT)→OPENED→attach→TS握手→确认房间→播放`；关联键含 connection、purpose、requestId、context、具体transport，迟到响应不批准新 Bot。
- [ ] `relay.musicBotEnabled=false` 默认拒绝；启用仍受 allowlist、玩家权限、session/global额度、独立速率和资源边界约束。PLAYER_VOICE/MUSIC_BOT各最多一个，不互相覆盖；关闭 Bot 只关 Bot。中继只授权MC连接/target/purpose，TS身份和频道权限仍由TS实服校验，不凭昵称或自报UID授予权限。
- [ ] 测试玩家和 Bot 同时在位、Bot拒绝/断线不终止玩家、重复OPEN、换服迟到OPENED、关闭/reload全回收。真实本机UDP桥穿过MC payload编解码和生产关联层，不只单测 wire枚举。
- [ ] 实际 MC Netty+TS 实服若无可用环境，明确待用户验证；但客户端缺 OPEN/attach 接线不能记待真机。独立提交 `fix: 接通音乐中继独立会话`。

### G6：补齐已有 F5 最小入口与本机音乐音量

**修改：**`client/ConfigScreen.java`、按当前 SVC入口需要的 Mixin/适配、`client/TeamspeakSvcClient.java`、MusicBotManager；新增 `client/music/MusicScreen.java`；复用 `ParticipantVolumeControl`、`TsAudioInjector.participantUuidFor`；同步当前语言资源和 GUI 回归。

- [ ] 在现有扩展配置页提供可发现的“音乐”入口，不重制 SVC GUI。页面可选择既有目标/传输和本地WAV，完成连接、添加、播放、暂停、继续、下一首、停止播放、断开Bot；显示队列、当前曲目、本机提交者、连接状态及具体错误。
- [ ] UI与命令调用同一业务操作；UI仅客户端线程读取不可变快照，连接/IO异步。加载/失败/未确认房间时按钮状态准确；`stop`停止播放与`stopbot`断开Bot含义明确。
- [ ] 把现有 `/tsvoice music volume` 及对应滑条接到实际**本机收听**链：使用已确认 serverUID+Bot TS UID 派生参与者 UUID，复用 SVC 音量/静音存储及播放通道更新。0..100映射0..1；静音单独保留恢复音量。不能只改描述，也不能乘发送PCM导致全频道音量改变。
- [ ] 不凭昵称识别 Bot；持久化不可用时明确仅内存态。重连通道重建重新应用，页面初始化先读取已有用户设置，不能强制恢复100或取消静音。
- [ ] 测试 UI动作→生产操作、不可用状态、稳定UUID、重连重放音量、同名不同UID互不影响；模拟另一收听者确认本机调音不改变网络编码帧/发送许可。真实渲染与听感仍待用户验收，不预填通过。
- [ ] 同步实际命令帮助、错误文本和用户文档。保留授权本地WAV，不新增在线音源/搜索/远程控制；先前未支持功能仍明确未支持。
- [ ] 独立提交 `fix: 补齐音乐操作入口与个人收听音量`。

### G7：新干净副本与远端 CI 通过后生成候选

**修改：**已有测试入口/CI仅在必要时更新；新增 `review/acceptance-repair-2026-10-30/{STATUS,DELIVERY,MANIFEST}.md` 与 `evidence/`；新增候选目录。

- [ ] 将修复后正式测试加入 RunAll。历史缺陷探针保留；若仍要求“复现旧缺陷”而在新代码失败，应标为历史反例入口，不混入产品通过门，不删除其证据。
- [ ] 从最终业务提交生成干净副本，不带工作区 class/stub/build缓存输出；运行 core `clean test`、26组及新增实际suite、mod `build`。可使用依赖下载缓存，不能使用旧编译产物冒充本次结果。
- [ ] 必跑命令：core目录 `../teamspeak-svc/gradlew.bat clean test --no-daemon`；mod目录 `./gradlew.bat clean build --no-daemon`；仓库根 `powershell -NoProfile -ExecutionPolicy Bypass -File review/RunAll.ps1`。记录命令、Java、准确提交、退出码、套件/检查实际数量与独立日志。
- [ ] 本机 HttpClient/Gradle 若再次失败，先用无项目依赖最小程序区分环境，再使用可用的正常环境/CI验证；直接 javac成功只能写直接测试成功。**麦克风 canSend 编译失败不能归入环境。**不删测试、不放宽断言、不伪造PASS。
- [ ] 检查候选入口、Mixin FQN/插件、可选SVC依赖和禁止内容；变更注入点核对实际SVC/MC字节码。隔离 dedicated 无SVC/有SVC两矩阵启动、reload、关闭及资源回收，旧Bridge不安装。真实Mixin装载与类清单检查分别记结果。
- [ ] 完成中文独立提交并按仓库流程推送修复分支/PR；CI绿再称工程通过，不因用户上次合并红CI而推断今后都允许绕过。本轮不自动合并新PR，不强推 main 或擅自发布 Release。
- [ ] 本轮最新源码提交的同一JAR复制到 `dist/client-acceptance/<新源码短哈希>/`。记录完整SHA-256和大小，客户端/服务端用同一文件；保留 `80dd40f` 原候选，不覆盖旧文件或继续沿用旧哈希。
- [ ] 文件名版本相同时，用提交目录和哈希区分，手册提醒移除测试实例中的旧同ID JAR；不擅自删用户实际安装文件。打包提交/文档提交与源码功能终点分别记录。

### G8：按新候选更新验收材料，然后停止

**新增/更新：**`review/acceptance-repair-2026-10-30/{CLIENT-ACCEPTANCE,RESULTS,DELIVERY,MANIFEST,STATUS}.md`；根 README、`review/continuous-development/STATUS.md`；旧手册增加指向新候选的明确入口，不抹去历史。

- [ ] STATUS只给一个当前状态与下一动作。逐项对应 G0–G8、P1-01/02/03、P2-04/05和F2/F5未完成项；存在生产缺口时不得写全部完成/仅待听感。
- [ ] MANIFEST列准确源码/打包提交、唯一JAR哈希/大小、依赖、四模式部署配置、MUSIC_BOT开关/targetId、实际入口和回滚。不得引入旧Bridge/独立公告模组作为安装条件。
- [ ] 手册沿用A00–A12，更新命令/UI与真实支持范围。新增屏障后切频道/持续保活、UDP/MC双Bot路径、管理员移出Bot房间停播、个人音量只影响本机、慢网络时关闭/换服等具体步骤；不是每项都只写“看看有没声音”。
- [ ] 每个场景写前提、精确操作、预期、失败标准、日志位置、清理；将声音/网络已知代码缺口与外部待测分开。所有RESULTS初始为“待用户执行”，不因自动化通过填写客户端PASS。
- [ ] 验收分两段：先A00/A01/A02/A05基础启动通话与意图，再A03/A04通道模式、A06/A07频道公告、A08/A09音乐与许可、A10跨服、A11错误、A12稳定性。修复后三项反例通过才交用户运行相关场景。
- [ ] 最终报告列已修复/自动化/构建/CI/装载/待用户/残留；提供能直接安装的新候选路径和准确日志。确有阻塞时交付具体失败，不把未实现改标N/A；原F5本轮要求不经用户同意不得删除。
- [ ] 到此交接并停止功能扩展。用户验收反馈后先修验收缺陷，再讨论后续路线。

## 回滚与最终验收门

- 每项独立提交；接口调整的调用者与回归放在同一可验证提交，回滚时按依赖逆序。出现新的语音/生命周期回归，先停后续任务并最小修复，不能用整库reset回避。
- G2/G4/G5分别保留协议、调度、第二会话边界，防止回滚Bot时影响玩家中继。服务端可选中继/音乐开关默认关闭，故障时可停对应可选能力，不静默改用另一个语音网络。
- 工程通过必须同时具备：三项确定缺陷的生产链正向回归、F2/F5缺口真实接线、干净副本完整门、构建、对应CI及装载证据；不将源码缺口留给用户听感排查。
- 客户端通过只能由用户真实操作后填写。本轮完成标准是**交付工程检查通过的新候选并等待用户验收**，不是宣称整个长期项目完成。
