# teamspeak-svc 下一轮直接执行任务书：通道可靠性收口与独立音乐 Bot

> **用户最新安排：本轮结束后进行客户端验收。** 当前唯一开工入口调整为[客户端验收候选任务书 F0–F7](2026-10-29-client-acceptance-candidate-plan.md)。本文继续作为实现细节参考，原“完成后自动继续 Music-2/3”的结束规则已被替代；交付验收候选和手册后停止新增功能，等待用户验收反馈。

> **0072d00 后续接点：**E1 与多项修复已提交，不重复重做。独立复核仍确认 E2 读线程换态异常、E4 客户端代际/屏障及字节限额/有界调度未接通、E5 暂停收听偏差、E7 编码后许可缺口；MusicSession 生产工厂与 Music-1 仍未完成。先读[最新复核与续做顺序](../../../review/post-0072d00-2026-10-29/REVIEW-AND-CONTINUATION.md)，依其第六节继续本任务书，不把 E7/E8 的测试接口和预留功能当产品已交付。

> **给实施 agent：**按 E0–E9 顺序持续执行，用户已确认成品 README 的产品方向。先修复复核确认的问题，再完成通道生产闭环，随后推进 Music-0/1。阶段是检查点，不是默认停点；不要等待客户端真机验收，也不要只交付计划而不实施。可使用当前环境提供的 executing-plans 工作流，普通单 agent 也可逐项完成。

**Goal：**关闭 T31–T38、落实已确认的单后端收听策略，完成可验证的两种 MC 通道，并继续交付独立音乐会话与最小操作体验。

**Architecture：**保留现有 BackendCoordinator、TS 协议/加密、统一可选服务端及 SVC 前端。修复实际配置、数据边界和生命周期；音乐使用独立身份、client、transport、编码器、队列与许可，不共用玩家语音会话。

**Tech Stack：**沿用当前 Java 25 / MC 26.3 / Fabric / SVC 2.6.25 工程，不夹带平台升级。支持范围以实际依赖/ABI 及检查结果为准，不扩大兼容声明。

**Spec（必读，按优先级）：**

1. 本任务书：下一轮具体顺序、完成条件与持续执行范围。
2. [已确认的成品介绍](../../product/README-RELEASE-PREVIEW.md)：用户已回复“可以”，其产品默认值作为本轮依据；描述的是目标，不代表当前已实现。
3. [81c687c 独立复核及证据](../../../review/post-81c687c-2026-10-29/REVIEW-AND-NEXT-STEPS.md)：T31–T38 的源码依据和反例。
4. [持续推进总任务书](2026-10-29-continuous-development-master-plan.md)：长期队列与局部阻塞规则。
5. [V3 传输与音乐约束](2026-10-29-v3-transport-music-design.md)：保留协议/身份/房间边界；与已确认产品默认值冲突的旧待确认措辞按本文执行。

## 1. 执行边界

参考 HEAD 为 `81c687cdfb3b6f067be3c655c23152c6960581c9`。开工记录实际 HEAD；后续已修复事项核对证据后跳过。当前工作区已有未提交文档与测试生成 stub，先分类、保留，不 reset/clean，不覆盖无关改动。

本次至少向“通道正确性 + 独立音乐最小路径”持续推进；可执行的工作没有完成时，不因完成一个修复提交、原型或文档而自动结束。局部外部条件不具备时，记录具体阻塞并继续独立项。

客户端游戏运行、截图、实体音频设备、实际听感等仍按用户要求暂缓。ABI、产物检查、无界面生产 handler、UDP 回环、自动化、构建、可用的 dedicated 启动/重载照常执行。未做的检查明确写未验证，不能以测试替身或 build 成功代替。

不得新增独立必装 Bridge；普通 TS UDP 不要求 MC 服务端安装 SVC/本扩展。P7 普通 SVC↔TS 媒体桥接不是本轮自动实施项，网页/手机不阻塞本轮。

## 2. 已确认的产品默认值

- 用户显式选择/本服覆盖优先；有效且已授权的服务器推荐高于普通本地/全局自动目标；没有公告时用户自己的 TS 目标继续生效。优先级不等于失败后悄悄试遍其他网络。
- 麦克风只发送到一个明确后端。**默认仅收听当前选择的后端**；双收听只能作为独立显式选项，不能暗中开启，也不是本轮必须先实现的功能。
- TS 默认按频道通话；SVC 保留附近语音和群组规则。管理员正常移动在服务器确认后跟随；锁定/群组约束不满足时暂停，默认频道不等于锁定。
- “断开 TS / 切换 SVC / 关闭全部语音”仍分开；暂停与 OFF 不被换服、公告或重试撤销。PAUSED 没有活动后端时不自动恢复另一路收听。
- 音乐是项目主线扩展，可与基础正式版分阶段发布。音乐 Bot 与玩家语音完全独立；首期明确 owner/房间，无自动抢占/主持权选举，不宣称全频道强排他。
- 主持人退出、断开自己的 TS、切换 SVC 或关闭全部语音时，默认停止其本机主持 Bot 并给反馈；不停止他人或服务端主持的 Bot。独立持续音乐如以后提供，需用户明确选项，默认关闭。

## 3. 任务总顺序

| 顺序 | 内容 | 对应 |
|---|---|---|
| E0 | 基线、反例与测试入口 | 保护现场与准确记录 |
| E1 | Mixin 类路径、可选 SVC 装载、产物检查 | T31 |
| E2 | 原生 SVC UDP 保护、配置/下行闭环及会话隔离 | T36 |
| E3 | TS 原样回包封装、OPEN 关联与客户端绑定 | T32/T34 |
| E4 | TS 代际屏障、真正限额/背压、线程和长度边界 | T33/T35 |
| E5 | 已确认的默认单后端收听 | 产品方向对齐 |
| E6 | MusicEngine 无损背压、暂停、取消、终态 | T37 |
| E7 | 音乐独立编码/会话，不走玩家麦克风 | T38 / Music-0→1 |
| E8 | 独立 Bot 最小界面与控制、第二中继会话 | Music-1 |
| E9 | 全量工程检查、证据/部署/状态更新，继续后续队列 | 连续交付 |

每项执行：**写能暴露问题的测试 → 运行并记录失败 → 修改最小生产路径 → 对应测试通过 → 受影响回归 → 独立提交 → 更新进度 → 继续下一项。**

## E0：建立本轮基线与可恢复记录

- [ ] 记录 HEAD、未提交改动、依赖版本、候选 JAR 哈希；本轮证据放 `review/reliability-music-next/evidence/`。
- [ ] 阅读复核 evidence 中 RelayBoundaryProbe、MusicBoundaryProbe 和产物检查。保留历史复现日志，将其转成“修复后应拒绝/完整输出”的正式断言，不能继续以复现缺陷作为成功条件。
- [ ] 梳理 `review/RunAll.ps1` 的真实套件数量、执行顺序和环境要求。新检查加入统一入口；不预填全绿、不复用旧哈希。

## E1：修复产物装载配置与可选依赖边界

**文件：**`teamspeak-svc/src/main/resources/teamspeak-svc.mixins.json`；`teamspeak-svc/src/main/java/com/tsvoice/client/mixin/`；`review/m7-unified-release/RunM7.ps1`；必要的 Mixin 条件应用插件。

- [ ] 统一配置 package 与实际九个 Mixin/Accessor 类的位置，优先最小改动，不为改前缀重排全部目录。
- [ ] 对 config 的 mixins/client/server 全列表计算完整限定名，逐条检查 JAR entry，不能只检查数量。必要字段/类解析失败使检查非零退出。
- [ ] SVC server Mixin 仅在兼容 SVC 在位时应用；公共入口、公告、TS relay 在没有 SVC 时仍可装载。不要用 defaultRequire=0 替代缺类/可选目标隔离。
- [ ] 实际检查无 SVC / 有 SVC 的 dedicated 装载，具备环境时保留启动、status/reload/停止日志；客户端安装矩阵继续暂缓。

**退出条件：**候选 JAR 所有配置类名真实存在，可选依赖隔离有明确证据。若实际启动条件缺失，不宣称启动成功，继续其他可执行任务。

## E2：完成 SVC MC 通道，先保护原生 UDP

**文件：**`client/VoicechatPluginImpl.java`、`client/backend/SvcMcChannelClient.java`、`SvcMcChannelSocket.java`、`client/TeamspeakSvcClient.java`、`common/net/SvcMcChannelPayloads.java`、`server/svc/{SvcMcChannelAdapter,SvcServerBridge}.java`、server Mixin、`TeamspeakSvcMod.java`；扩展 `review/svc-mc-channel/`。

- [ ] 初始化注册不能无条件以无 UDP 能力的自定义 Socket 替换原实现。使用按连接选择/工厂或能正确委托原 UDP 的 Socket，先核对目标 SVC API；关闭自定义对象不能冒充“恢复默认 UDP”。
- [ ] 接通真实 client config/S2C receiver→当前连接 handler→socket；处理初始化在先/公告在先两种顺序。所有旧连接响应在执行时复核 context/session。
- [ ] 断连、换服、MC_CHANNEL→UDP、服务端禁用/重载、SVC 重连清理旧地址/队列/发送桥，避免静态状态跨世界串用。
- [ ] 真正调用 Extra 元数据所有者判断；只支持有依据的组合，无法双装时明确停用本适配，TS 路径不受影响。
- [ ] 会话登记按 server instance + 真实 MC connection 管理。消除线程 ID 冒充 connectionInstance 和 `%60000` 冒充无碰撞；分配与回收不能重用尚活跃/未退役的 SVC 地址。合成地址明确 unresolved，不做无意义 DNS。
- [ ] 测试覆盖正常仅 SVC UDP、MC 通道双向原始消息、协商拒绝、UDP/TCP 混合玩家、重连及旧包、地址循环/复用、Extra 唯一所有者。驱动生产 initialization action 和 receiver，不能只验证 registry 的 count。

**退出条件：**UDP 默认路径保持，MC 配置与上下行真实接线；旧连接不能污染新连接。Extra 真实版本互操作没有实测就继续标未验证。

## E3：修复 TS 数据封装和请求关联

**文件：**`server/relay/{TsRelayRuntime,TsRelaySession}.java`、`TeamspeakSvcMod.java`、`client/TsVoiceManager.java`、`client/ts3voice/TsMcChannelTransport.java`、`common/net/TsRelayPayloads.java`、`protocol/relay/TsRelayWire.java`；扩展 `review/ts-mc-relay/`。

- [ ] 统一 McSink 契约：原始 TS 数据与 relay 控制通知按类型区分，由唯一外层编码点包装；不得将 raw TS UDP 当 TSRL 解码。生产 sink 与测试 sink 使用同一 codec/dispatcher。
- [ ] 服务端 OPEN 登记使用真实 connection + requestId；不同玩家相同 requestId 可以分别连接。正常完成、失败、取消和超时释放在途登记。
- [ ] 客户端 OPENED/ERROR/CLOSED 按 requestId + MC context + 创建时 TS session 关联，不能遍历完成任意 waiter。关闭旧尝试后，迟到 OPENED 不能批准新尝试，并清理可能已创建的服务端旧会话。
- [ ] sender 闭包绑定不可变的 relaySessionId/transport/token，不从可变全局重新取得“当前会话”给旧数据换身份。
- [ ] 管理员 targetId 是显式配置键；客户端选择/授权该目标，不把 host:port 规范化结果强制当所有 allowlist 的键。数据消息仍不允许任意 host/port。
- [ ] 测试真实 UDP 回包→生产编码桥→客户端 handler→transport.receive 原样还原；多玩家同 requestId、同目标快速取消重连、换服旧回复、晚到错误均覆盖。

**退出条件：**生产桥能双向搬运同一数据报，旧请求/旧连接不批准新会话，目标来源明确。

## E4：落实 TS 代际、限额、背压与长度

**文件：**E3 文件；`client/ts3voice/{TsTransport,Ts3Connection}.java`；现有 coordinator/route 失效发布点；服务端 relay config 与调度器。

- [ ] transport 传递入队时绑定的 data/session/routeEpoch/trafficClass，必要时增加小型不可变数据报对象及兼容 send 重载；旧 UDP/v1 路径继续工作。不得把 epoch/traffic 全填零，不在最终发送时给旧帧读取当前代际重贴标签。
- [ ] barrier 实际单调推进服务端许可、清旧媒体队列，并在最终 UDP writer 复核；客户端本地队列、MC 提交点、服务端队列有一致顺序。控制包不依赖麦克风就绪，但仍计入额度。
- [ ] 协商 datagrams/s、bytes/s、打开频率、会话数、队列条数与总字节真正生效；突发容量明确配置。采用注入时钟测试超额拒绝，不能只累计统计或把限额打印出来。
- [ ] DNS、socket 建立/读写离开 MC 主线程；采用有界工作队列/发送 drain，MC/Netty 待提交任务也纳入背压，禁止每包无限 execute。控制队列溢出明确失败/关闭，不静默丢握手；单会话失败不拖垮其他玩家。
- [ ] 统一 4096/协商接收边界，使用能检测超长的 UDP 接收方案；maxLen 不足或超长明确拒绝，不静默截断。测试 2048/2049/3000/4096/4097、连续不同长度数据报。
- [ ] 有阻塞 writer/提交器的切频道、LOCKED 拒绝、OFF、重连、队列满和关闭唤醒测试；已提交网络的数据不承诺撤回。

**退出条件：**T33/T35 正式负例通过；echo 成功不再是唯一验收。真实 MC 网络和 TS 音频仍列对应待验收项。

## E5：落实默认单后端收听

**文件：**`client/backend/SvcBackendAdapter.java`、`client/mixin/ClientVoicechatMixin.java`、`client/TsVoiceManager.java`、前端 owned playback 和选择状态提交点；扩展 OffGate/route-lifecycle 测试。

- [ ] 接收许可直接派生自协调器快照：SVC 被选中才允许原生 SVC 下行，TS 被选中才允许 TS 下行；PAUSED/OFF 无活动后端时不自动播放另一边。
- [ ] 选择改变时对不再拥有许可的播放队列/资源做有界清理；迟到的入站帧在实际消费前再检查来源 session/选择。不要只挡新包却留下旧队列继续播放。
- [ ] 不修改用户持久音量/静音偏好；OFF 不关闭游戏本身声音。未来双收听只能是显式设置，当前不必新增未验证的双收听能力。
- [ ] 测试 TS/SVC 同时有原生网络连接时只播放当前后端；切换、暂停、OFF、旧帧和设置恢复均覆盖。

**退出条件：**UI 当前后端、实际收听与麦克风目标一致；这个任务是新确认产品默认值的落地，不需要把旧 PAUSED 放行行为当兼容要求保留。

## E6：修复 MusicEngine 正确性

**文件：**`ts3voice-core/src/main/java/com/tsvoice/protocol/music/{MusicEngine,PcmSource,WavPcmSource}.java`、`review/music-0/`。

- [ ] queue 容量判定先于 source.readFrame，确保无损背压；编号源 1–6 在容量 1/2/512 下完整、有序、恰好一次输出，不能只验证 queueDepth。
- [ ] CLOSED 拒绝 play；PAUSED 阻塞等待、不忙循环；cancel/replacement 使用独立 playback generation，使已取出旧帧不能进入新播放/发送。
- [ ] 自然结束、解码/编码失败、取消/替换和 close 均释放拥有的 source；阻塞/慢 source/sink 不使控制操作永久等待。
- [ ] 绝对时钟在暂停恢复、长时间卡顿后不突发追赶无限旧帧。明确正常源预读的无损背压与发送拥塞时的过期策略，不能混为队列悄悄丢内容。
- [ ] 测试精确帧序列、关闭后调用、暂停耗时/等待、跨 generation 旧帧、失败释放与慢 sink，保留真实线程测试及确定性时钟测试。

**退出条件：**复核漏帧/终态反例关闭，源和线程生命周期可验证。此时继续 E7，不将“音乐核心 PASS”当独立音乐完成。

## E7：独立编码与音乐会话

**文件：**修订 `docs/superpowers/plans/2026-10-29-music-0-implementation-record.md`；保持 core/music 与客户端/服务端无关；新增最小的 MusicSession/encoder 适配及测试，遵循既有源码职责。

- [ ] 删除以玩家 Ts3VoiceClient 并行发送音乐的实施方向。音乐持有独立 TS identity/client/transport/socket/sequence/encoder/route 及停止点，不调用玩家全局 endSession，不覆盖 currentClient。
- [ ] 确定实际可用的 Opus 后端与许可/打包/装载证据。官方 SVC API 可以作为客户端实现之一；共享引擎不能以客户端 API 在位作为唯一前提，给未来无 SVC 的 Music Host 保留并验证实际编码路径。
- [ ] 固定采样率、声道、帧时长、码率和 TS 频道 codec 条件，实际 encode→decode 的受控测试验证格式；不能用 stub encoder 代替 Opus 可用证据。
- [ ] 音乐发送与 mic/DSP 分离；不改 TS 全频道 codec/权限掩盖格式错误，不把 PCM 注入玩家 PTT。编码不可用时明确失败，玩家语音不受影响。
- [ ] sender 许可绑定音乐 session/房间/route/playback generation；暂停、取消、被移出房间后旧音乐帧不继续获得发送许可。

**退出条件：**可独立编码和取消的音乐会话真实接线，身份/资源与玩家隔离；真实听感仍可暂缓。

## E8：继续 Music-1 最小完整体验

- [ ] 独立 Bot 加入明确 serverUID/cid 房间；至少提供开启、添加本地授权曲目、暂停/继续、下一首、停止、队列与点歌者、个人音乐音量/静音入口。先做可操作闭环，再做大型搜索页。
- [ ] 用真实可信 TS sender 元数据验证多人控制，绑定 room/owner/hostEpoch/requestId；不能使用昵称或 JSON 自报身份授权。首次固定队列上限、每用户限额与冷却，记录并测试幂等。
- [ ] TS UDP Bot 可以独立使用；MC relay 开放受控 MUSIC_BOT 第二会话时，登记结构支持 purpose/session，而不是一个 player→socket。分别计费、权限和清理，不抢 PLAYER_VOICE。
- [ ] 主持人离开/断开/切 SVC/OFF 默认停止自己的 Bot；不会向他人/服务端 Bot 发停止命令。Bot 被移出授权房间即停，不自动跟随玩家扩大播放范围。
- [ ] 跨 MC 点歌若 TS 定向控制的可信来源尚无法证明，记录该子项阻塞，继续本地主持、Bot 生命周期和可用控制，不擅自新增强制 MC 服务端或外部 Bot 依赖。

**退出条件：**拥有最小生产入口的独立音乐功能、完整错误/取消/权限边界与对应测试。无需用户现在参与客户端听感验收。

## E9：统一检查与连续交付

- [ ] 新增检查均进入 RunAll；在最终提交的干净副本运行适当全量回归、mod 构建及产物检查，记录实际退出码、环境、唯一候选哈希。失败按源码、脚本、环境分类，不能只勾全绿。
- [ ] 实际可做的 dedicated 无/有 SVC、公告/relay 开关、reload/停止资源清理验证单列结果；本地回环、真实生产 handler、真实 MC 网络、实体音频四类证据分开。
- [ ] 更新四模式部署、安装条件及错误提示：未启用/未实现不公告为可用；不扩大 SVC-Extra 任意版本兼容声明；头像/额外文件 TCP 不冒充已被 TS relay 覆盖。
- [ ] 更新 `review/continuous-development/STATUS.md` 和 `review/reliability-music-next/DELIVERY.md`：本次提交、关闭哪些 T31–T38、实际证据、未验证项、剩余问题、下一条具体动作。
- [ ] 确认原生 SVC UDP、TS UDP、手动书签、三种操作、身份/凭据、音量偏好和已有音频增强均有相应受影响回归；不为重构测试而放宽业务断言。

通过本阶段后继续既有 Music-2/3 队列的可执行工作，不以写出交付报告自动停工。新库选型、常规技术细化自行记录并验证；付费服务、强制新部署组件、P7 范围或改变已确认受众才单列产品决策，同时继续其他工作。

## 4. 风险与回滚

每项单独提交；协议/配置保持版本及旧读入能力。服务端中继、SVC MC 通道、音乐功能可以分别关闭，不能靠删除旧 UDP 路径使测试通过。迁移保留用户书签、凭据、TS 身份与备份。

E1–E5 优先于音乐生产接线；修复期间可独立完成 E6 纯引擎工作。某项需要外部环境时只阻塞对应证明，不删除功能需求，不虚构支持，也不停止整个长期项目。

本轮任务书由架构评估方提供，实施发生在接收任务的 agent；当前会话没有修改业务代码。
