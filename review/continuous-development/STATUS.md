基线 / 当前提交：
`f32416c`（post-m7 复核基线）→ `a979efc`（S0 全部七项关闭）→ `527d85d`（STATUS）→ `dcea5d8`（S1 TS_MC_CHANNEL 完整工程路径）→ `f7c0d44`（STATUS）→ `07e96f2`（S2 SVC_MC_CHANNEL 自有适配）。
本轮工作区还携带未提交的任务书/复核报告文档（用户提交进来的）。

当前阶段 / 当前任务：
S0/S1/S2 已完成并提交；下一步为 S3（四模式工程体验收尾）与 S4（Music-0/
Music-1，按总任务书先做小型实施记录再执行）。P7 仍待负责人确认，不自动实施。

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
