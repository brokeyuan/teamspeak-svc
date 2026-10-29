基线 / 当前提交：
`81c687c`（post-81c687c 复核基线）→ 本轮（2026-10-29 执行任务书 E0–E9）：
`dfbfd5f`(E1/T31) → `d6a9b9e`(E2/T36) → `5ecb27b`(E3/T32+T34) → `49ee583`(E4/T33+T35)
→ `39aacb5`(E5) → `2e9b08d`(E6/T37) → `2a4d615`(E7/T38) → `06c35d8`(E8 基础)
→ `50cff46`(E9 harness 修复)。全部独立提交，反例先行。

当前阶段 / 当前任务：
T31–T38 全部关闭（见 review/reliability-music-next/DELIVERY.md 的逐项证据表）。
RunAll 全量门 PASS（core test + 26 套件，exit 0；evidence/e9-runall.log）。
下一条：Music-1 继续 —— Bot 加入明确 serverUID/cid 房间（MusicSession 生产
connect() 接线）、开启/暂停/继续/下一首/停止/队列入口、host 离开默认停 Bot；
MUSIC_BOT purpose 授权开关配置节（服务端）。

本轮（E0–E9）关键修复摘要：
- E1/T31：mixin config package 修正 + TsVoiceMixinPlugin（SVC 目标 mixin 条件
  应用）+ RunM7 逐条 FQN→JAR entry 断言（负例已验证 FAIL）。dedicated 无 SVC/
  有 SVC 两种矩阵真实启动验证（mixin error=0；testserver 已恢复原状）。
- E2/T36：SvcMcChannelSocket 双模（原生 UDP 委托默认 → 宣告后单向 MC），宣告/
  初始化顺序无关；S2C/config receiver 生产接线；服务端登记 server+connection、
  地址回收不复用、createUnresolved 合成地址；svc-extra 让渡。28 断言含真实
  UDP 回环。
- E3：TsRelayWire.classifyUpstreamDatagram 唯一外层编码点（raw TS 回包不再被
  生产 sink 拒绝）；pendingOpens 客户端+服务端按 connection/requestId 精确关
  联，迟到 OPENED 不批准新尝试。
- E4：会话级单调 route barrier（旧代际拒绝）；协商 pps token bucket 真实拒绝；
  服务端/客户端接收统一协商 4096；receive(maxLen) 不足明确失败（不静默裁剪）。
- E5：默认单后端收听落地 —— 原生 SVC 下行按协调器选中后端判定（TS 选中即压
  制；PAUSED≠OFF；用户设置不动）。OffGate 断言按已确认产品规则更新。
- E6/T37：MusicEngine 无损背压（容量判定先于 readFrame，1..6 在容量 1/2/512
  下完整输出）、close 终态、PAUSED 不忙转（CPU 探针）、playback generation
  隔离、source 四终态恰好关一次、慢 sink 不阻塞控制面。
- E7/T38：MusicSession 独立身份面/编码器适配/发送许可绑定；废止"玩家会话并
  行发送"方向（实施记录已修订）；编码不可用明确失败。
- E8 基础：Purpose.MUSIC_BOT(2) wire 预留 + 登记按 connection#purpose（玩家
  语音与音乐 Bot 互不覆盖、断连全回收）；MUSIC_BOT 默认 NOT_AUTHORIZED
  （受控开放）。
- E9：E5/E6 生产 hook 传导的 9 个 harness 套件 stub 修复；RunAll 门绿。

本轮实际测试与证据（review/reliability-music-next/evidence/）：
- e9-runall.log：RunAll 全量 CI-EQUIVALENT PASS（core + 26 套件）。
- e2-svc-suite.log：40 PASS（真实 loopback UDP）。
- e3/e4/e8-relay-*.log：64 PASS（四类 relay 回归，含真实 echo）。
- e6/e7-music-suite.log：47 PASS（引擎+边界+独立会话）。
- e5-route-lifecycle.log：全 PASS（含 SingleBackendListenTest 11 断言）。
- e1-dedicated-load.log + e1-*-stdout.log：dedicated 两矩阵启动日志。
- ts3voice-core gradle test 108 protocol checks PASS；mod gradle build 成功。

客户端暂缓项（不变）：
真实 MC 网络/真实 TS 服务器端到端、实体音频听感、SVC-Extra 双端实测、
SVC API OpusEncoder 实机验证、Music-1 UI 验收 —— 均按既定矩阵暂缓，未伪报。

下一条具体动作：
Music-1：MusicSession.connect() 生产接线（独立 identity + transport 注入
Ts3VoiceClient 构造器）→ /tsvoice music 命令组（start/pause/resume/next/
stop/queue）→ 服务端 relay.svcMcChannelMusicEnabled 配置节（默认 false）
+ OPEN purpose 门放行路径。验证：RunMusicCore 增加生产接线回归 +
RunTsMcRelay MUSIC_BOT 授权用例。

基线 / 当前提交：
`f32416c`（post-m7 复核基线）→ `a979efc`（S0 全部七项关闭）→ `527d85d`（STATUS）→ `dcea5d8`（S1 TS_MC_CHANNEL 完整工程路径）→ `f7c0d44`（STATUS）→ `07e96f2`（S2 SVC_MC_CHANNEL 自有适配）→ `a5ef77f`（STATUS）→ `1b598ac`（S3 四模式部署表+迁移回归）→ `823ab2f`（S4 Music-0 引擎核心）。
本轮工作区还携带未提交的任务书/复核报告文档（用户提交进来的）。

当前阶段 / 当前任务：
S0–S3 与 S4 的 Music-0 核心已完成并提交。下一条：Music-0 宿主接线
（SVC API OpusEncoder + sendVoice(CODEC_OPUS_MUSIC) + 发送许可绑定，工作记录：
docs/superpowers/plans/2026-10-29-music-0-implementation-record.md），
随后 Music-1（独立 Bot 身份/第二会话；relay 的 MUSIC_BOT purpose 授权开放）。
P7 仍待负责人确认，不自动实施。

已完成的生产路径（S0，提交 a979efc）：
- R21/P1：confirmChannel 的代际分配并入全局 ROUTE_EPOCH 单点（原先局部 +1
  在多次管理员移动后回退/重复）；BackendCoordinator.writePermittedFor(session)
  + writer supplier 绑定 sessionLoop 捕获的本代 token —— 旧会话帧即使与新
  会话 epoch 数值重合也不能发送。
- R27/P1：onCapabilitiesAnnouncementFrame 对 decode 返回 null 显式拒绝，
  Fabric 接收任务不再 NPE；9 类畸形帧（空/短/坏 magic/major/TTL/计数/截断）
  安全返回且不扰动已接收快照。
- R22：ServerRuntime 在 start 时固定 dedicated/integrated 环境；初始状态、
  reload、无效配置恢复统一走 computeStatus，集成默认关闭门不再被 reload 绕过；
  显式 integratedServerEnabled=true/off 按 reload 生效；dedicated 不受影响。
- R23：新增 CapabilityConsentScreen（接受/记住/拒绝），授权动作绑定实际接收
  快照 + context；拒绝本 context 内不再弹窗；记住只记 TS_ENDPOINT（不自动批准
  同地址未来能力）；/tsvoice trust-announce 手动入口。
- R24：EndpointCredentialStore 增加按 ref 的 get/set/copyEndpointToRef 与受控
  identities/ 目录解析（拒绝路径分隔符/网络地址）；书签保存保留既有
  credentialRef/identityRef 不再写 null；连接按 ref 解析凭据与身份，同 host:port
  两个书签独立；首次保存复制全局 identity.txt 为书签私有身份（全局身份不动）。
- R25：TsChannelsScreen 重写：分页（全部频道可达）、目标频道独立密码输入、
  创建表单（名称/父频道/可选密码，异常进错误反馈行）、逐用户音量/静音
  （ParticipantVolumeControl，写入 SVC player-volume 存储，参与者键稳定、
  重连重放）；创建父频道按名解析歧义/缺失明确报错。
- R26：RuntimeDependencies 语义版本谓词（仅 2.6.20 ≤ v < 2.7.0；2.6.1/2.60.x/
  2.7.0/解析失败拒绝）；TsProtocolException 携带结构化 TS error id/msg；
  classifyConnectFailure 类型优先：IO/DNS 可重试、服务器协议拒绝
  PROTOCOL_REJECTED（终止）、未知异常 INTERNAL（可重试但不再冒充
  NETWORK_TIMEOUT/UDP 不通）。

已完成的生产路径（S1，提交 dcea5d8）：
- wire：ts3voice-core protocol/relay/TsRelayWire（TSRL v1：OPEN/OPENED/DATA 两向/
  ROUTE_BARRIER/CLOSE/CLOSED/ERROR + 独立 relay 能力发现帧；外层 16 KiB、数据报
  4096 B 上限，超长拒绝不截断；长度/计数严格校验；16 字节 SecureRandom
  relaySessionId；稳定数字错误码）。
- 注册：common/net/TsRelayPayloads 方向化注册（clientbound/serverbound 分开），
  有界解码，主入口一次注册。
- 服务端：server/relay/TsRelayRuntime+TsRelaySession（每会话 connected UDP
  socket、专属 worker 线程、targetId 只查管理员 allowlist、每连接 1 会话/全服
  64 上限、OPEN 限流 2/s burst 4、OPEN 超时不复活、全流量计费、空闲清理、
  断连/重载/关闭回收；重载不重定向已建立会话）；ServerConfig relay 节（校验）；
  ServerRuntime 生命周期 + /tsvoice-server status 中继状态；MC 断连回收；
  TeamspeakSvcMod payload 接收 + 真实 McSink。
- 客户端：connection/VoiceTransport 枚举（AUTO/TS_UDP/TS_TUNNEL_V1/TS_MC_CHANNEL，
  旧 tcp=true 只迁移为 TS_TUNNEL_V1）；SessionConfig 明确 transport（兼容构造器
  保留）；Ts3VoiceClient 传输注入构造器 + connect(timeout)（握手/加密/序号不变）；
  TsMcChannelTransport（状态机/有界接收队列/协商上限/幂等关闭/attach）；
  manager：MC 通道路径 OPEN→OPENED→attach→TS 握手，上行 DATA_C2S 桥、下行分发、
  stopSession 发 CLOSE；服务器无通道时显式失败（绝不静默回退 UDP）；客户端不做
  TS 主机名解析。

S1 实际测试与证据：
- review/ts-mc-relay/evidence/relay-suite.log：29 项全 PASS —— codec golden/畸形
  家族、真实 UDP loopback echo（原样字节）、两会话隔离、伪造 sessionId 拒绝、
  allowlist/每连接/全服限额、禁用拒绝与稳定错误码、CLOSE/回收三路径、
  目标 DNS 不可达（解析留在服务端）、route-barrier 绑定。已纳入 review/RunAll.ps1。
- 受影响 harness 全部更新并复跑：route-lifecycle、gui-integration、client-fixes、
  p1-isolation、p3-config、session-consumption、p0-defects、channel-fixes×2、
  p1-playback、playback-ownership、standalone-frontend、p3-channel、
  p4-discovery、server-fixes、m7-unified-release —— 全部 exit 0。
- gradle build（mod）与 core 测试仍通过。

已完成的生产路径（S2，提交 07e96f2）：
- 上游核实（SVC-Extra @6669d6f，GPL-3.0，MC 26.2/loader 0.19.3/voicechat>=2.5.0，
  逐文件抓取源码）：voice payload 为 SVC NetworkMessage 1:1 原始字节；config 为
  单个 UTF transport 串；C2S 以 mc:abs(UUID.hashCode()%65535) 喂 addRawPacket
  （碰撞空间）；sendPacketRaw 拦截为全服开关；客户端 Socket 静态无界队列、
  close 不唤醒、isClosed 恒 false。上游为 26.2 —— 不能对 26.3 做安装组合声明。
- 自有实现（不要求装 SVC-Extra）：SvcMcChannelPayloads（自有 ID，字节契约与
  Extra 一致，8 KiB 有界解码；双装在注册层隔离）；SvcMcChannelAdapter（按玩家
  路由、单调计数器唯一会话地址、断连回收、重连新地址、无 SVC 即 no-op）；
  SvcMcChannelSocket（官方 initialization event 注册、会话级有界环形队列、
  close 唤醒、isClosed 真实）；SvcServerSendPacketMixin（只拦已宣告玩家、
  writeServer 保留 SVC 加密、拦截失败回落 UDP；双装时适配层空集 = Extra 赢得
  SVC 域，TS 路径不受影响）；JOIN 宣告 transport（relay.svcMcChannelEnabled，
  默认关）。
- 与 Extra 端点的真实互操作（本服务端 ↔ Extra 客户端）保持"framing 契约一致、
  未双端实测"的如实状态 —— 不冒充已兼容。

S2 实际测试与证据：
- review/svc-mc-channel/evidence/svc-suite.log：13 项全 PASS（100 并发会话地址
  无碰撞、宣告门控、按玩家接管 vs UDP 不动、注册簿记账、断连/重连生命周期、
  无 SVC no-op、重置）。已纳入 review/RunAll.ps1。
- RunM7 更新允许已记录的 common mixin（server.SvcServerSendPacketMixin），
  产物校验 PASS；候选 JAR 哈希更新为
  C1D5BC2A64DF5A368B3C623CFFCB8E9EDE8496021B4F00CF03840C011B7BA6A9。
- ts-mc-relay/route-lifecycle/gui-integration 复跑全 PASS；mod gradle build 通过。

已完成的生产路径（S3/S4-Music-0，提交 1b598ac / 823ab2f）：
- S3：docs/superpowers/plans/2026-10-29-s3-modes-deployment.md（四模式部署表/
  配置迁移规则/server.json 参考/管理诊断/错误类别表/资源边界）；
  S3TransportMigrationTest（p3-config 套件，11 项 —— 旧 tcp 只迁移为
  TS_TUNNEL_V1 的硬约束等）。
- S4/Music-0：ts3voice-core protocol/music（PcmSource/TestToneSource/
  WavPcmSource/MusicEngine，纯 Java 零依赖，headless 可测）—— 本地 PCM WAV
  （非 PCM 明确拒绝，待许可核对）+ 确定性测试音；有界队列（512 帧）满时
  背压（不丢帧，有序播放）；绝对节拍（20ms，时钟可注入）；暂停保位置/
  恢复/取消复位/关闭终态；sink/音源异常 → FAILED 带原因（不静默吞）；
  逐帧计数诊断。宿主接线（OpusEncoder+sendVoice）是剩余 glue，未宣称完成。

S3/S4 实际测试与证据：
- review/music-0/evidence.log：27 项全 PASS（音源确定性、WAV 重采样/折叠、
  显式拒绝、假时钟精确出帧、暂停/恢复位置、背压 capacity-1、许可丢帧计数、
  失败原因）。已纳入 review/RunAll.ps1。
- 最终复跑：ts-mc-relay/svc-mc-channel/music-0/p3-config/route-lifecycle/
  gui-integration 全 exit 0；ts3voice-core gradle test 145 项 PASS；
  mod gradle build 通过（候选 JAR 以最终提交为准，交付前重算哈希）。


S1 客户端暂缓项与如实记录：
- 真实 MC 网络（netty 上的 payload 收发）与本 wire 一致，但端到端仅由回环
  harness 验证；真实 TS 服务器经中继的握手/音频留待外部验证（用户暂缓范围）。

本次实际测试与证据路径：
- review/s0-closeout/route-lifecycle-s0.log：RouteLifecycle + Bookmark +
  QueuedVoice + RouteMoveLifecycle + OffGate + 新 S0FixRegressionTest
  （R21 单调性/会话绑定许可、R27 畸形公告、R23 授权流）全部 PASS（82 项）。
- review/s0-closeout/gui-integration-r26.log：GuiFlowTest + 新
  R26CompatibilityTest（版本谓词 + 分类）全部 PASS（82 项）。
- review/s0-closeout/m7-r22.log：AnnouncerTest 扩展 R22 段（reload 不绕过
  集成门、显式开/关、恢复路径、dedicated 不受门影响）全部 PASS。
- 其余受影响套件复跑零 FAIL：client-fixes、p0-defects、p1-isolation、
  p3-config、session-consumption、upload-confirmation、p3-channel、
  p4-discovery、p1-playback、playback-ownership、standalone-frontend、
  channel-fixes（Mapper/Protocol）。
- ts3voice-core gradle test：145 项 PASS；teamspeak-svc gradle build 成功。
- 候选 JAR 重新生成：teamspeak-svc-0.9.8+26.3.jar
  SHA-256 df5f5af9a421e9e5b966469f29412a018d7c354ef033ad18ffa8c60c92ea6db7
  （S0 代码变更，M7 哈希不能沿用；交付前以最终提交重算）。

客户端暂缓项（用户主动暂缓，编号保留）：
E01–E15 / U01–U06 不变；本轮新增 UI（Consent 屏、频道页表单/分页/音量）的
真实渲染/点击同样暂缓，动作控制流已由 harness 覆盖。

局部阻塞及不受影响的任务：
- RunAll 全量在个别环境曾因 HttpClient/loopback 初始化失败；本轮按套件分跑
  全部真实通过，未伪报全绿。后续仍以 review/RunAll.ps1 为最终门。
- 未解决：本轮未重跑 m7-unified-release 的 RunM7 产物段（需要 gradle 完整
  构建+clean-copy；S0 提交后哈希已变化）。下轮 RunAll 时以最终 HEAD 重算。

下一条具体动作（文件、方法、验证命令）：
S3：四模式（TS_UDP/TS_TUNNEL_V1/TS_MC_CHANNEL/SVC 原生）配置迁移、能力/错误
提示、诊断与部署表（沿用既有界面，不另造 GUI）；S4：Music-0 小型实施记录
（本地 PCM/WAV/测试音、独立队列/节拍、headless Opus 方案与许可核对）后实现。
S2 遗留：SVC-Extra 端点真实互操作实测（需目标版本环境）；S1 遗留：专用服务端
启动/命令 reload 生命周期验证（无客户端工程验证，具备运行条件即做）。原动作：
S2.1：固定 SVC-Extra 上游 commit/许可证/目标平台，读其实际 packet envelope、
Socket 生命周期/secret/session 与 Mixin（26.2 参考不能假定适配 26.3，兼容声明
按源码与实验记录）；S2.2：byte-level 双向互操作 fixture 先行，再实现
SvcMcChannelPayloads/SvcMcChannelAdapter/SvcMcChannelSocket；S2.3：按玩家路由
（不能全服切 transport）；RunSvcMcChannel.ps1 覆盖封装/协商拒绝/session 重建/
混合分发/过期包/清理。S1 技术准备原记录：明确传输类型（TS_UDP / TS_MC_CHANNEL / TS_TUNNEL_V1）迁移 boolean tcp；
Ts3VoiceClient 增加传输注入入口（保留旧构造器委托）；新增
teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsMcChannelTransport.java；
S1.2：ts3voice-core 新增 protocol/relay/TsRelayWire.java + J/common/net/
TsRelayPayloads.java（CAPABILITIES/OPEN/OPENED/DATA/ROUTE_BARRIER/CLOSE/
CLOSED/ERROR，方向化注册）；S1.3：server/relay/{TsRelayRuntime,TsRelaySession}
per-session connected UDP socket；S1.5：review/ts-mc-relay/RunTsMcRelay.ps1
（echo/两会话/allowlist/伪造 sessionId/限额/回收）。验证命令：套件逐一
powershell -NoProfile -ExecutionPolicy Bypass -File review/<suite>/<Run>.ps1，
最终门 powershell -NoProfile -ExecutionPolicy Bypass -File review/RunAll.ps1。

本轮新增或修订的技术决策（S1）：
- 中继 wire 独立于 capabilities v1（relay_capability_v1 发现帧）；不拿公告
  payload 承载 TS 数据。
- 会话认领 = 真实 connectionId + player UUID + 服务端生成的 relaySessionId；
  客户端自报不进入判断；DATA 不携带地址，目标只按 OPEN 的 targetId 查 allowlist。
- 客户端绝不解析 TS 主机名（MC 通道路径）；endpoint 解析与允许判断在服务端。
- 已建立会话不随重载重定向（明确保留或关闭后重建）；总开关关闭即回收全部。
- 旧 boolean tcp 只迁移为 TS_TUNNEL_V1；TS_MC_CHANNEL 必须显式选择且服务器
  公告/注册该通道。

历史技术决策：
- routeEpoch：任何路由代际写入只经 nextRouteEpoch()；不变观察不递增；
  writer 许可 = 全局 epoch + 会话绑定双条件（S0.1）。
- 集成服务器门属于"环境"而不是"启动时刻动作"：环境不可变、参与每次状态计算
  （S0.3）。applyIntegratedServerGate() 保留为兼容 no-op。
- SVC 版本支持范围 = [2.6.20, 2.7.0)（有工程证据的语义区间；新系列须重做
  ABI 核对后再放宽）（S0.7）。
- 服务器结构化协议拒绝（TsProtocolException）默认终止重试（PROTOCOL_REJECTED），
  数值 id 语义未经真实协议资料核实前只传递不解释（S0.7）。
- 书签 credentialRef/identityRef：ref 键与 endpoint 键共存（"ref:" 前缀），
  迁移只复制不删除；身份引用限定受控 identities/ 目录，缺失明确报错（S0.5）。
