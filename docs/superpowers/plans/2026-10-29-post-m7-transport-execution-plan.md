# teamspeak-svc 下一轮连续实施：补齐交付缺口 → TS MC-TCP → SVC TCP

> **给 GLM 5.3 Flash / 实施 AI：**当前以 `f32416c` 为参考基线。先完成 S0，再连续实施 S1–S4；具备条件时继续既有音乐路线。阶段是提交/测试检查点，不是默认停工点。客户端验收继续按用户要求暂缓；不要等待用户启动客户端后才继续工程工作。具备 executing-plans 技能时按任务执行，其他环境照本文清单推进。

**Goal：**关闭本轮确认的状态缺陷与功能缺口，交付 unified teamspeak-svc 内的 TS_MC_CHANNEL 完整工程路径，再完成可验证的 SVC_MC_CHANNEL 兼容路径。

**Architecture：**沿用现有协调器、TS 封包/加密和统一服务端运行时；MC 中继作为独立 TsTransport 实现，服务端只中继完整 TS 数据报。SVC TCP 按其实际 Socket/wire 适配，不能复用 TS Socket 强行统一。

**Tech Stack：**沿用当前 MC 26.3 / Java 25 / Fabric / SVC 2.6.25 工程，不夹带平台升级。候选产物更新后重新记录哈希，不能沿用旧 M7 哈希代表新代码。

**Spec：**[持续推进总任务书](2026-10-29-continuous-development-master-plan.md)、[本次独立复核](../../../review/post-m7-2026-10-29/REVIEW.md)、[V3 传输/音乐约束](2026-10-29-v3-transport-music-design.md)。本文是下一次具体执行入口，保留总任务书的长期顺序和产品边界。

## 1. 开工与进度管理

- [ ] 记录实际 HEAD、用户未提交修改和当前候选哈希；已被后续提交修好的项以证据关闭，不重复重写。
- [ ] 更新 `review/continuous-development/STATUS.md`：下一步首先是 S0，之后 S1/P6；删除“等用户恢复客户端验收后才继续”的隐含停点。
- [ ] 不覆盖历史全绿日志。本次独立复核只得到 core 直接运行通过、21/23 harness 分次通过；两组 HttpClient 和 Gradle 构建环境问题分别记录，实施环境应自行跑真实结果。
- [ ] 专用服务端无/有 SVC 的启动、命令 reload、配置生命周期与线程清理属于无客户端工程验证；具备运行条件时继续做，不能一概列为“用户主动暂缓”。缺少服务端启动环境时记录其具体阻塞，继续其他工作。

仓库内路径以下使用 C=`teamspeak-svc/src/main/java/com/tsvoice/client/`，J=`teamspeak-svc/src/main/java/com/tsvoice/`，P=`ts3voice-core/src/main/java/com/tsvoice/protocol/`。

## 2. S0：先关闭已确认问题，补齐正常使用入口

每项先建立相应负例，再修改最小生产路径并验证；保存修复前后证据，一项一个可审阅提交。

| 项 | 文件与动作 | 必须通过的工程检查 |
|---|---|---|
| S0.1 / R21 | C/connection/BackendCoordinator.java：所有代际变化统一 nextRouteEpoch；C/TsVoiceManager.java writer supplier 绑定创建时 session/client；不变观察不递增 | FOLLOW 连续移动至少两次→会话结束→新会话确认，代际不回退/不重复；阻塞 writer 的旧会话帧不能匹配新许可；原 FOLLOW/LOCKED/LEGACY 行为保留 |
| S0.2 / R27 | C/TsVoiceManager.java：CapabilityWire.decode 返回 null 后直接拒绝，接收边界不得解引用；补真实 decoder→handler 负例 | 短帧、坏 magic/major/kind/长度/端口、超大帧安全返回；不改变有效授权/选择；合法零 TTL/撤销按既定语义处理 |
| S0.3 / R22 | J/server/ServerRuntime.java：保存不可变 dedicated/integrated 环境，统一初始与 reload 状态计算；更新 TeamspeakSvcMod 创建参数及 ServerCommands 调用 | integratedServerEnabled=false 在多次 reload/无效配置恢复后仍禁用；显式开启/关闭生效；dedicated 不受集成门误伤 |
| S0.4 / R23 | 新增 C/CapabilityConsentScreen.java；扩展 manager 授权动作、VoiceStatusModel 和入口 | 新服务器仅 capabilities、无旧 beacon 也能经真实 action 授权；拒绝/撤销/过期/换服旧确认不生效；授权绑定来源 MC、serviceInstanceId、目标和能力种类，不把同地址所有未来能力自动批准 |
| S0.5 / R24 | C/config/EndpointCredentialStore.java、TsBookmarkStore.java、TsBookmarkEditScreen.java；增加本地身份引用解析（可用 C/config/TsIdentityStore.java）；manager 实际应用引用 | 同 host:port 两书签独立凭据/身份；编辑不清空既有引用；旧 endpoint/全局身份兼容迁移并备份；私钥不进入书签/日志；缺失引用明确报错，不静默换身份 |
| S0.6 / R25 | C/TsChannelsScreen.java：完整滚动/分页；目标频道密码输入；名称/父频道/可选密码创建表单；异步错误反馈；逐用户音量/静音 action | 超八频道均可访问；不同频道密码不串用；创建/移动/确认状态分开；同 ParticipantKey 重连保音量；原生 SVC 条目不被改写；旧 session 响应不改新屏状态 |
| S0.7 / R26 | J/bootstrap/RuntimeDependencies.java：真实版本约束及 API 检查；TS3 错误结构化传递至 manager 分类，保留数值 id/原因 | 2.6.1/2.60.x 不因字符串前缀误通过；目标 2.6.25 通过；未知异常不默认 NETWORK_TIMEOUT；认证/权限等终止性错误不触发 TCP 回退 |

补充约定：

- 书签凭据引用增加按 ref 的 get/put，旧按 endpoint 的读取保留兼容回退；迁移为两个书签分配独立 ref 时复制原值，不删除旧条目。未修改的已有引用必须原样保存。
- identityRef 只引用本地受控身份目录中的记录，不接受任意磁盘路径或网络私钥；全局 identity.txt 作为迁移默认身份，不能因迁移给所有用户自动换 TS 身份。
- 授权页面通过显式按钮/状态入口进入，只有玩家当前关联的候选才能批准；自动连接是否启用与信任是否存在分开处理。
- 支持版本用明确谓词表达；API 版本与内部前端 ABI 是两个检查。语义范围不应宽于有工程证据的支持范围，其他 SVC 版本保留原生功能并明确提示扩展不可用。
- TS 错误 id 映射须从可信协议资料/目标源码/服务器样本验证，不从本任务书臆造；传输 IOException、取消、内部异常与服务器协议拒绝分开。

测试扩展现有 route-lifecycle、gui-integration、p3-config、m7-unified-release 套件；R21/R22/R27 的复核探针在 `review/post-m7-2026-10-29/evidence/`，应改写为正式断言，修复后不能继续以“成功复现缺陷”为通过。

S0 工程条件通过即继续 S1，不等待真实 UI/设备验收。未解决的 R21 不得被复制进新的中继发送链。

## 3. S1 / P6：协议验证与会话实现，接着完成生产接线

### S1.1 明确模式，打通 transport 注入

- [ ] 新增明确传输类型：`TS_UDP`、`TS_MC_CHANNEL`、`TS_TUNNEL_V1`。原 `tcp=true` 只迁移为旧隧道，不自动变成 MC 通道。
- [ ] 在现有 Ts3VoiceClient 增加 transport 注入入口，复用已有 Ts3Connection(Ts3Crypt, TsTransport)；保留旧构造器委托，不改握手/加密/序号实现。
- [ ] 增加带模式的连接目标/工厂。MC 中继按 targetId 打开，**客户端不先调用 InetAddress.getByName(TS host)**；DNS 及允许 endpoint 解析在服务端。旧 UDP/v1 构造路径继续按原语义解析。
- [ ] SessionConfig、书签 schema、选择候选与 VoiceStatusModel 一起改为明确的 endpoint/transport 语义；不能只有新 Transport 类，manager 仍用 boolean tcp。
- [ ] 保留旧配置读取、备份及未变传输的回归。强制 MC TCP 而服务器不支持时明确不可用；AUTO 才允许按规则试下一种已授权传输。

主要文件：C/ts3voice/{TsTransport,Ts3Connection,Ts3VoiceClient}.java；新增 C/ts3voice/TsMcChannelTransport.java；C/TsVoiceManager.java；connection/config 现有类型。只为连接目标增加必要类型，不把整个协议搬迁到 core。

### S1.2 冻结最小 wire 与兼容公告

新增 P/relay/TsRelayWire.java（纯 Java codec 与限制）、J/common/net/TsRelayPayloads.java（唯一 Fabric 注册所有者）。消息至少有 CAPABILITIES、OPEN、OPENED、DATA 两方向、ROUTE_BARRIER、CLOSE/CLOSED、ERROR；按方向注册，不能拿 SVC-Extra payload ID 承载 TS。

```text
OPEN: version, requestId, targetId, purpose
OPENED: requestId, relaySessionId(随机128位), negotiatedLimits
DATA: relaySessionId, routeEpoch, trafficClass, datagramBytes
ROUTE_BARRIER: relaySessionId, nextRouteEpoch
CLOSE/CLOSED: relaySessionId, reason
ERROR: requestId/sessionId, stableErrorCode
```

服务端以真实 MC connection 对象/代际 + player + sessionId 认领会话，不信客户端自报 player 或连接代际。purpose 初期只启用 PLAYER_VOICE；内部登记表支持多个 sessionId，MUSIC_BOT 后续明确授权再开放。目标 ID 只查管理员 allowlist，DATA 不能携带新 host/port。

**能力兼容要求：**当前 CapabilityWire v1 遇未知 kind 会拒绝整个公告，不能直接追加新枚举再声称老客户端兼容。优先独立 relay v1 发现/能力 payload，按连接通道支持发送；保留原 capabilities v1 的 TS_ENDPOINT/SVC 既有公告。选择层把明确的 relay capability 转为 TS_MC_CHANNEL 候选。若改用升级后的公共 wire，必须同时完成版本协商与旧版本 golden 测试，并记录取舍，不能静默破坏 M7 客户端。

工程初始限制（配置化、协商取较小值，原型测试后可有依据地修订）：

| 项目 | 初始值 |
|---|---|
| 单个 TS 数据报 | 4096 字节，超长拒绝，不截断 |
| 单 OPEN/CLOSE/ERROR 等外层帧 | 16 KiB 上限，分配前校验 |
| 每 MC 连接活动 PLAYER_VOICE 会话 | 1 |
| 全服会话硬上限 | 64，亦受管理员更低配置限制 |
| OPEN 限流 | 每连接 2/s，burst 4；全局另设有界额度 |
| 每 session 数据量 | 256 datagrams/s、512 KiB/s，控制和媒体全部计入 |
| 每方向语音等待队列 | 5 帧，120ms 本地等待上限 |
| 每 session 控制等待队列 | 32 条；溢出明确失败/关闭，不无限积压 |
| 等待队列总字节 | 每 session 256 KiB、全服 16 MiB，包括待交给 MC 的任务 |
| OPEN 超时 | 5 秒，超时后迟到 OPENED 不复活已取消请求 |

这些数值是工程保护初值，不是实测容量承诺；TS 握手/控制突发与合法帧长要用样本验证。每个域使用本地单调时钟；不能把客户端 nanoTime 与服务端 nanoTime 相减。

### S1.3 服务端 per-session UDP 中继

- [ ] 新增 J/server/relay/{TsRelayRuntime,TsRelaySession}.java，或同等职责小类。并入现有 ServerRuntime 的可选 feature，默认关闭，配置含 targetId→host/port、权限和限额；没有 SVC 也可运行。
- [ ] 一个被批准的 relay session 对应一个 connected UDP Socket；回包只来自绑定目标，不能全服共用一个 UDP 源会话。DNS/socket/阻塞 receive 不占 MC 主线程。
- [ ] 使用有界执行/收发队列。检查目标 Fabric 版本发送线程契约；需要回主线程时用有界 drain，不每个包无界 execute。MC/Netty 待写数据也属于背压路径，不能只限制自建队列后继续无限提交。
- [ ] MC 断开、目标禁用、配置重载、服务关闭和超时清理相应 socket/worker/队列；关闭唤醒阻塞 receive，重复 close 幂等。目标配置更改不重定向已建立会话，应明确保留或关闭后重建。
- [ ] TS 的身份、密钥、频道权限、加密仍由客户端协议会话掌握；中继不解码、不混音、不持有玩家 TS 私钥。私网/本机 TS 可由管理员明确 allowlist，不能禁止正常内网部署。

### S1.4 客户端适配与发送边界

- [ ] TsMcChannelTransport 有 CLOSED/OPENING/OPEN/CLOSING 状态，接收队列有界，receive 的最大长度和关闭异常遵循 TsTransport 契约。
- [ ] DATA 发送时携带**入队时**绑定的 protocol session/routeEpoch/trafficClass。为 TsTransport 增加兼容重载或数据报描述；默认实现回落已有 send(byte[])，旧 UDP/v1 不被强制搬迁。不可在最终发送时给旧帧读取“当前 epoch”重新贴标签。
- [ ] ROUTE_BARRIER 与媒体 drain 顺序一致，覆盖客户端队列、MC 提交点、服务端 UDP writer；目标频道改变或许可撤销后，尚未取得当前写出许可的旧媒体帧丢弃。既已提交网络的数据不承诺可撤回。
- [ ] 服务端不能仅凭客户端声称 CONTROL 就让其绕过所有限额；所有流量计费，控制优先只用于受限调度。
- [ ] UDP→MC 通道切换重新建立 TS 会话/token，清旧队列、重新确认频道，保留用户身份和个人音量；MC 换服/断线关闭中继，不承诺跨 MC 连接无缝续传。
- [ ] 手动 TS 目标与服务端 allowlist 不匹配时明确不能通过该中继，不改成另一 TS 服务器。目标拒绝、版本不支持、拥塞和 TS 密码错误分类反馈。

### S1.5 原型后继续生产，不在 echo 成功时结束

新增 `review/ts-mc-relay/RunTsMcRelay.ps1`：

- [ ] 真实 UDP loopback echo（完整原样字节）、两个玩家/两个会话、目标 allowlist、伪造他人 sessionId、旧连接重放、OPEN 取消/迟到应答。
- [ ] codec golden/畸形帧、长度/计数/限额、worker 和 socket 回收、目标重载、服务重启、队列满、MC 提交端背压；所有失败只影响相应 session。
- [ ] 连续 FOLLOW 移动、LOCKED 拒绝、OFF、切 SVC、旧 writer 与新 session 相撞：用真实 route/transport 与阻塞提交器验证，不只测 epoch 纯函数。
- [ ] 生产 action→候选授权→manager→TS 工厂→MC handler→真实 UDP 的无界面集成；如果 MC channel 本身仍用 dispatcher 替身，单独记录真实 MC 网络尚未验证。
- [ ] 服务器公告、配置 status/reload、客户端实际状态与书签设置接线完成；更新同一 JAR 部署说明与候选哈希，纳入 RunAll。

真实 TS 服务可用时继续握手/频道/重连的协议集成，不要求用户陪同音频验收。头像/文件传输的额外 TCP 不在此 relay 范围，失败独立反馈，不拖死语音；不得宣称所有 TS 流量都已穿过 MC。

S1 工程退出条件：不是仅有 echo 或接口，而是可选服务端中继、客户端生产路径、状态/配置、资源限制和测试完整。客户端与真实 TS 音频证据继续暂缓，不能填已验收。达到条件即进入 S2。

## 4. S2 / P5：SVC_MC_CHANNEL 与 SVC-Extra 兼容

- [ ] 固定 SVC-Extra 上游 commit、许可证及目标平台，阅读实际 packet envelope、协商、Socket、服务端接口与 Mixin。旧参考曾针对 26.2，不能假定可与本工程 26.3 直接双装；兼容声明按源码与实验记录。
- [ ] 写出与当前 SVC 2.6.25 Socket 生命周期/secret/session 的对照，先完成 byte-level 双向互操作 fixture，再实现 J/common/net/SvcMcChannelPayloads.java、J/server/svc/SvcMcChannelAdapter.java、C/backend/SvcMcChannelSocket.java 或等价适配。
- [ ] UDP 与 MC_CHANNEL 按玩家路由，不能切全服 transport；会话地址映射不能用 UUID.hashCode 小端口空间充当唯一标识。
- [ ] 本项目自有服务端适配装在同一 teamspeak-svc；该功能需要 SVC 服务端时明确条件，但不能让基本 TS 直连/TS relay 因无 SVC 而失败。SVC 专用类按依赖/环境延迟加载。
- [ ] 双方同时安装时明确唯一 socket/payload/mixin/DSP 所有者；支持可验证的协作模式，无法协作时提前诊断并停用冲突功能，不重复处理音频。不要仅为规避研究就把 SVC-Extra 永久列成必装项。
- [ ] `review/svc-mc-channel/RunSvcMcChannel.ps1` 覆盖实际封装/长度、协商拒绝、session 重建、UDP/TCP 混合玩家分发、分组/距离语义、过期包、断连与资源清理；双装用固定版本 fixture 检查。
- [ ] 更新选择策略和 UI：SVC_UDP / SVC_MC_CHANNEL，能力可用性来自真实服务端支持；不能用 TS relay 能力推断 SVC TCP 可用。

若固定版本上游互操作确实阻塞，继续完成本扩展两端自有适配和独立测试，上游兼容标为未验证/不支持，不能冒充已兼容；如产品部署方式需改变，单列决策。TCP 队头阻塞、真实游戏网络和听感留待后续验收。

## 5. S3：工程体验收尾；S4：继续 Music-0 / Music-1

S1/S2 通过对应工程检查后，统一四模式的配置迁移、能力/错误提示、诊断与部署表，明确各模式服务端条件和“麦克风目标”。不要另造一套 GUI 或重置音效默认值。

继续执行 V3 音乐路线：

1. Music-0：授权本地 PCM/WAV/测试音、独立队列/节拍/暂停取消、headless Opus 方案及许可验证，不依赖 SVC 私有编码实现。
2. Music-1：独立 Bot 身份/client/socket/编码器与最小操作入口；玩家语音不中断；房间绑定 serverUID/cid，不按 MC 服务器划分。
3. TS 定向控制协议须验证真实 sender UID/clid/targetMode；未经核实不能拿昵称或 JSON 自报 actorUid 授权。跨 MC 的点歌不能只靠当前 MC payload。协议阻塞时继续本机队列与 Bot 生命周期，不擅自引入新的强制服务器组件。
4. Music Bot 使用 MC relay 时开放第二个明确授权的 MUSIC_BOT session，独立额度；不能抢玩家 session。没有 relay 支持时可继续 TS UDP Bot，不把后续音乐全部绑死在 TCP。
5. 客户端退出、主动断开/全关默认停止本机主持音乐，并明确展示；他人/服务端主持 Bot 不被本地 OFF 停止。不同主持端不自动争抢或复制队列双播，强全局排他需求仍单列决策。

音乐后续具体参数在阶段开始时按总任务书完成小型实施记录再执行，不需要因常规细化再开一轮总体规划。Music-2/3 继续排队；P7 是否必做仍待负责人确认，不自动实现普通 SVC 音频外送。

## 6. 回归、回滚与交接

- S0–S4 每阶段至少有生产链路、对应负例、原始测试日志、受影响回归与构建结果；客户端暂缓与无客户端工程验证分列。
- stage/protocol/config 有明确版本，旧 UDP 与历史 v1 默认路径可保留。实验功能默认关闭，不公告未实现能力；回滚只回对应提交/开关，保留用户凭据、书签、身份和迁移备份。
- 新增 TCP/音乐代码不默认启动外部服务、不上传源码/音频/秘密；服务端组件仍由同一扩展交付。需要新增库时记录版本、用途、许可与两端装载结果，避免把客户端 native 强制初始化到公告服务端。
- 每过检查点更新 `review/continuous-development/STATUS.md` 的当前提交/任务/实际证据/阻塞/下一条具体动作，然后继续下一项。不要因完成 S0、原型 echo、23 套件通过或写出交付文档而默认结束。
- 未完成事项必须指向文件、方法、失败场景与下一动作；不要把“全部客户端验收暂缓”当作无关工作也不能继续的理由。若本次执行受环境/运行限制必须交接，保持项目未完成的准确状态。
