# 81c687c 交付复核与下一轮续做要求

> **实施入口更新：**负责人确认成品 README 后，下一轮统一按[直接执行任务书 E0–E9](../../docs/superpowers/plans/2026-10-29-next-round-execution.md)推进。本报告保留缺陷依据和反例，不再作为独立于主任务书的另一套阶段安排。

日期：2026-10-29。复核 HEAD：`81c687cdfb3b6f067be3c655c23152c6960581c9`。本轮只读业务代码，测试在 git archive 临时副本执行；当前工作区原有未提交任务文档及生成 stub 均保留。客户端验收未恢复。

## 1. 当前准确位置

S0 修复已有提交及回归；S1/S2 已增加传输实现，但生产收发与生命周期仍有确定缺口，不能记为“完整工程路径关闭”。Music-0 有纯 Java 音源与调度引擎，但自身存在内容丢失，编码/宿主尚未完成，不能直接按当前实施记录复用玩家会话接线。

本次独立复跑 TS relay、SVC MC channel、Music-0 三个既有套件，均退出 0。新增边界探针仍复现下列问题，表明原套件尚未覆盖必要的生产边界。本轮没有独立复跑全部 RunAll、构建、S0 全清单或 dedicated 启动，不能将对方报告的通过替换为本轮实证。

下一轮应连续完成：**产物装载配置与原生 UDP 保护 → TS/SVC 通道真正闭合 → 音乐引擎纠错 → 独立音乐身份/会话及最小入口**。这些都不需要等待用户做客户端验收。

## 2. 必须解决的发现

### T31 / P1：Mixin 配置的全部类路径错误，候选 JAR 静态检查已确认

`teamspeak-svc/src/main/resources/teamspeak-svc.mixins.json` 声明 package=`com.tsvoice.mixin`；实际九个类仍在 `com.tsvoice.client.mixin` 及其 server 子包。源码和当前候选 JAR 都不一致。

检查候选 SHA-256：`7A75FB4415912F1FE0F6D849FFD2FAEB4556AEDBB8F9077CAAA0A7EB3AF98D60`。配置要求的九个 class 均不存在，对应 client.mixin 路径均存在。当前 `RunM7.ps1` 仅检查列表/数量，无法识别该错误。

修复包前缀或真实包结构，并对每个配置项断言“完整限定名→JAR entry 存在”。`required=true` 的 Mixin 配置必须能解析对应类，不能以构建成功证明可装载。本轮未运行 Loader，实际启动错误堆栈未采集。

另外，新增 SVC 服务端 Mixin 进入通用 mixins 列表，目标是可选依赖 Server.class；尚未发现按 SVC 在位情况控制应用的插件。`defaultRequire=0` 不能作为完整可选装载证据。补无 SVC / 有 SVC 的装载隔离检查，确保 TS relay 与基础公告不被拖垮；此项运行时结果仍未验证。

证据：`evidence/mixin-artifact-check.log`。

### T32 / P1：TS 服务端回包的生产封装不一致

位置：`server/relay/TsRelayRuntime.deliverToMc()` 与 `TeamspeakSvcMod` 的 SERVER_STARTING/McSink lambda。

runtime 把原始 TS UDP 字节交给 sink；生产 sink 却先按 TsRelayWire 解码，只接受已封装的 DATA_S2C/CLOSED。正常 TS 数据报不是 TSRL 帧，解码为 null，sink 返回 false，runtime 随之回收会话。原测试 RecordingSink 直接接受字节，所以 echo PASS 没有覆盖此边界。

本次使用真实本机 UDP echo、实际 runtime 和与生产相同的 sink 解码条件，确认正常回包被拒绝。未运行 Fabric 网络本身。

修复为唯一且清晰的契约：推荐 McSink 按类型区分原始数据与控制通知，由唯一外层编码器生成 DATA_S2C/CLOSED；或 runtime 统一封装后交 sink。不能一端 raw、一端 envelope。新增测试必须让实际生产编码/派发桥消费回包，不能继续用无条件收包的 sink 代替。

### T33 / P1：TS 中继屏障、数据限额和背压未落地

位置：`TsVoiceManager.openMcRelaySession()`、`TsRelayRuntime.onData()/onRouteBarrier()`、`TeamspeakSvcMod` receiver/sink。

- 客户端所有 DATA_C2S 都写入 `(session, 0, 0, datagram)`，没有传递入队时绑定的 routeEpoch/trafficClass；sender 又读取可变全局 mcRelaySessionId。
- onRouteBarrier 只确认 session 归属，不记录或推进代际；onData 不检查代际。探针先提交 barrier=100，再提交 epoch=99，仍被转发。
- 协商 pps/byte 限额仅保存/返回/打印；onData 直接 sendToTarget，无对应 token bucket/窗口拒绝。配置每秒 1 包时，连续四个 DATA 仍全部接受；累计统计不等于限流。
- 代码未建立既定有界发送/drain 链。OPEN receiver 直接调用包含 DNS/socket 的 onOpen，DATA receiver 直接 UDP send，sink 直接 Fabric send，不能据此宣称主线程、MC/Netty 待写任务均已受控。

修复时覆盖客户端 writer→MC 提交→服务端 UDP writer 三段：帧绑定来源会话、入队代际和流量类型；barrier 单调推进并清旧媒体队列；控制/媒体均计费；会话及全局队列字节有界，等待操作离开 MC 主线程。MC 发送线程规则以目标版本源码核对，不能只在注释中声称线程安全。

测试用真实生产调度器加阻塞提交器，校验切频道/取消/重连后旧帧不再获得许可，以及队列满和低限额下的拒绝/清理。不得承诺撤回已提交网络的数据。

### T34 / P1：TS OPEN 请求跨玩家冲突，客户端也未核对 requestId

服务端 pendingOpens 仅以 int requestId 为全局 key；不同玩家都从 requestId=1 开始时，后一连接被当成重复。探针已复现两个不同 connection 的 requestId=1：第一个成功、第二个失败。正常完成后 pending 还会保留至后续清理。

客户端 PENDING_OPENS 以 targetId 保存，onRelayOpenedFrame 遍历并完成任意 waiter，不比较 OPENED.requestId，也未将等待项与创建时 MC context/TS session 绑定。晚到的旧应答可能批准新尝试。

修复服务端 key 为实际 connection identity + requestId；客户端 key 为 MC context + requestId，并绑定 TS session/目标，CLOSE/ERROR/取消相同处理。成功、失败和超时都释放登记；迟到 OPENED 不能成为新会话，并主动清理服务器上可能已建立的旧会话。测试同 ID 多玩家、同目标快速取消重连、换服旧应答。

### T35 / P2：4096 字节协商与实际 2048 接收不一致，发生静默截断

TsRelayWire 允许 4096，但 TsRelaySession UDP 缓冲及客户端 TsMcChannelTransport 的接收门只有 2048；receive(maxLen) 还会 Arrays.copyOf 截断。

真实回环探针发送 3000 字节，上行接受，回包只有 2048 字节。应按协商值统一边界，UDP 接收使用可判定超长的缓冲策略，超长明确拒绝；不能把截断结果当合法 TS 数据。客户端 receive 的 maxLen 不足时明确失败，不能静默裁剪。覆盖 2048/2049/3000/4096/4097 和连续不同长度包。

证据 T32–T35：`evidence/relay-boundary-probe.log`、对应 Java 源码文本。

### T36 / P1：SVC 客户端闭环缺失，原生 UDP 保护不足

源码调用关系确认：

- VoicechatPluginImpl 初始化时只要 supported() 即 setSocketImplementation(customSocket)，并不等待具体连接选择；自定义 Socket 只会经 MC 发包，没有原生 UDP 委托。关闭该对象不等于让 SVC 自动换回默认 UDP Socket。
- `SvcMcChannelClient.onServerAnnouncement/onDownstreamVoice` 只有声明，未发现生产 client receiver 调用；SvcTransportConfig/SvcVoiceS2c 只有 codec 注册和服务端发送。宣告/回包因而没有接到 socket。
- `setSvcExtraPresent()` 亦没有生产调用；默认 false，无法支持报告所称的客户端双装让渡。

这不是“Extra 实网互操作暂未验证”这一项能涵盖的缺口。先保护普通仅 SVC UDP 服务器：只有当前连接确实选择 MC 通道时才接管，或提供有真实 UDP 委托的按会话 socket。补配置/下行 receiver、初始化与宣告任意先后、断连/换服复位、Extra 元数据唯一所有者接线。通过真实初始化 action 和生产 handler 检查，而不是只测服务端登记表。

服务端地址 `1024 + sessionId % 60000` 仍有限空间循环；`connectionInstance` 使用线程 ID，也不是真实 MC connection。不能写“无碰撞”。按 server instance + connection 管理登记，分配/回收不与活动/未退役连接冲突的地址，避免静态跨世界状态。`new InetSocketAddress("mc", ...)` 也不是代码注释所称的显式 unresolved，应统一确定且不做 DNS 的合成地址方案。

本项为源码调用链与数学边界确认，真实 SVC UDP 收发、Loader 双装行为本轮未运行，报告不声称已采集真实断线现象。

### T37 / P1：MusicEngine 的“无损背压”实际漏帧，关闭也非终态

MusicEngine.run() 在 source.readFrame() **之后**才检查 queue.size≥capacity，因此满队列仍消耗并丢弃一帧，却未更新丢帧计数。

容量 2、编号源 1–6 的确定性探针结果：

```text
source frames=[1,2,3,4,5,6]
submitted=[1,2,4,6]
queue drops=0
```

另已复现 close() 后 play() 重启 CLOSED 引擎。PAUSED 分支未发现阻塞等待，静态上存在忙循环风险，需补线程/时钟测试；当前 probe 未测 CPU。取消/替换与已取出帧之间还需绑定播放代际，避免旧源帧混入新播放。

修复：容量判断先于读取；closed 拒绝新播放；pause 真正等待；cancel/play replacement 使用自身 generation；失败和源结束均释放 owned source。测试精确帧序列、不遗漏、不重复，而不是只断言 queueDepth 小于上限。覆盖慢 sink 后恢复，不能无限追赶突发发送旧音乐帧。

证据：`evidence/music-boundary-probe.log` 与源码文本。

### T38：下一步音乐宿主方案需先纠正，与既定身份/部署要求不符

Music-0 实施记录准备通过玩家 Ts3VoiceClient 与玩家麦克风并行发送两个来源，并把 SVC API 编码器作为主要宿主条件。该方案还未实施，不应继续照此接线。

沿原 V3 决策：玩家语音与音乐使用独立身份/client/socket/sequence/encoder/route；Music-0 是共享引擎，Music-1 再接独立 Bot。允许把官方 SVC API 作为客户端编码实现之一，但共享引擎不能强制存在 SVC 才能编码，未来无 SVC 服务端托管必须另有实际可用、许可明确的实现。没有编码器时明确不可用，不能标 headless Opus 已完成。

先验证授权本地 PCM→明确采样率/声道/帧长→独立编码→独立 Bot 的最小路径；不能将文件 PCM 注入玩家 mic，也不能修改 TS 全频道 codec 或权限来掩盖格式不匹配。

## 3. 下一轮可直接执行的连续队列

1. **修复 T31，升级产物检查。** 每条 Mixin 完整名必须在 JAR 中存在；分清可选 SVC Mixin 的装载条件。构建和专用服务端启动具备条件即做。
2. **并行职责上分别收口 TS 与 SVC；执行上按独立提交连续推进。** TS 先 T32/T34 生产往返与关联，再 T33/T35 限额/代际/队列/长度；SVC 完成 T36 的真正配置、上/下行和 UDP/Extra 保护。此处不要求调用多代理，普通单代理即可逐项完成。
3. **用原测试未覆盖的反例补测。** 把复核 probe 改为修复后通过的正式测试；实现 production bridge 编码/handler 测试。TS echo、SVC registry、music queueDepth 各自 PASS 都不足以宣称模式闭合。
4. **纠正并修复 Music-0。** T37 引擎不丢帧、终态和生命周期通过；T38 明确编码后端与许可，先验证格式和 headless 能力。
5. **继续 Music-1 完整最小体验。** 独立 Bot、独立第二会话、添加/暂停/继续/跳过/停止/队列入口、显式房间/owner、可信请求来源；与玩家通话互不覆盖。MC 中继第二 MUSIC_BOT purpose 经明确额度/权限开放，不抢 PLAYER_VOICE。之后按总计划继续 Music-2/3；P7 仍待产品确认。

不要以“继续加音乐功能”掩盖通道收发尚未闭合，也不要以暂缓客户端为由停止这些源码与无界面验证。客户端真实渲染/设备/听感留在原暂缓矩阵；专用服务端和本地协议检查单独记录。

每项按“反例→最小修复→对应回归→提交→更新 STATUS”推进，任务完成是检查点，不是默认停点。若因环境/运行限制交接，写明当前 HEAD、未提交内容、已经实测的边界和下一条具体动作。

## 4. 证据与状态维护

本次三组既有套件均 PASS，但新增 probe 证明这些 PASS 不能关闭 T31–T38。不要修改历史日志；在连续 STATUS 中将 S1/S2 改为“主要实现已提交，生产边界待修复”，Music-0 改为“核心已提交，正确性/编码接线待完成”。实际代码进展保留，不能退回“尚未开始”，也不能继续标完整工程关闭。

本轮未修改上述生产实现，仅新增复核文档/证据及总任务书接点。
