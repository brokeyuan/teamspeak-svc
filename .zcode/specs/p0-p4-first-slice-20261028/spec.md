# teamspeak-svc 首切片（P0–P4）缺陷修复与架构改进 - Product Requirements Document

## Overview
- **Summary**: 按 `review/next-stage-2026-10-28/` 三份文档（架构评估、实施计划、决策记录）实施首切片：修复已证实的 P0 缺陷（F02/F04/F05/F06 + 签发端 revision 单调性），建立单一媒体归属与会话隔离（P1），实现不依赖 SVC 服务端会话的前端适配（P2），独立书签/TS 频道/可理解状态（P3），能力发现与受控回退（P4）。
- **Purpose**: 当前客户端存在语音泄漏到错误网络、身份立即过期、未信任 issuer 入库、成员快照扩大受众等确定缺陷；且 TS 生命周期、书签、频道、回退均与 SVC 会话或旧桥耦合。本切片交付可独立验收的第一段改进，为后续 P5–P7（D 模式、TLS 隧道、互通网关）建立正确的路由与信任基础。
- **Target Users**: 使用 teamspeak-svc 客户端 mod 的 MC 玩家；部署旧桥（tsbridge）的服务器管理员；直连 TS 的手动配置用户。

## Goals
- 修复 F02/F04/F05/F06 四个确定缺陷，且失败用例先行（旧代码上确实失败，修复后通过）。
- 建立"选中后端/归属"最小状态，TS 被选中期间麦克风永不泄漏到 SVC 网络。
- 建立 MC↔TS 身份的明确信任策略（默认未知 issuer 不提升 MC 身份，语音保持 Ghost）与防重放基础（revision 高水位 + 撤销墓碑 + 签发端 revision 跨重启单调）。
- 单一媒体归属：任何时刻每段麦克风音频最多送一个已确认路由；会话回调与代际绑定；TCP 阻塞下连接可限时关闭。
- 客户端可脱离 SVC 服务端会话独立使用 TS（前端适配层 + hook 自检）；真实设备验收项如实标注。
- TS 书签、凭据按 endpoint 隔离；TS 频道/权限成为一级数据；手动目标不受 MC 公告覆盖。
- 能力公告新协议 + 按失败类别驱动的受控回退；旧 beacon 字节不变。

## Non-Goals
- P5（SVC-Extra 兼容 SVC/TCP）、P6（独立 TLS 隧道 v2）、P7（SVC↔TS 媒体网关）、WebRTC/浏览器接入。
- 重做整套 SVC GUI；改变音频增强默认状态（AEC/NS/AGC 保持默认关闭）；改变音色/响度。
- 在本环境执行真实设备/真实服务器联调（无 SVC 服务器双向通话、防火墙故障注入等）；这些项按实施计划规则 10 标注"未验证"，不得宣称已支持。
- 未经开发者批准的 D06–D09 相关承诺（双装支持、网关、per-player 镜像）。
- 全目录合并两套 TS 协议栈到 core（按 D12 分阶段，本切片仅做 P1 计划明确的 Transport/Listener 边界）。

## Background & Context
- 评估基线：`review/next-stage-2026-10-28/ARCHITECTURE-ASSESSMENT.md`（缺陷 F01–F12、优先级）、`IMPLEMENTATION-PLAN.md`（P0–P8 任务与验收规则）、`DECISION-LOG.md`（D01–D12）。
- 2026-10-28 复核：5 个关键文件 SHA-256 与评估一致；F02（MicRedirector.java:22,31,38-40）、F04/F05（ClientIdentityService.java:51-53,84,114-128,127）、F06（BridgeMembershipService.java:55-56）、签发端 revision `AtomicLong(1)`（IdentityProofService.java:53）与撤销单条广播（:153-177）均在当前源码确认存在。
- 用户已批准：实施范围为首切片 P0–P4；基线留存方式为初始化本地 Git（工作区原无 Git 元数据）。
- 决策默认值：本切片采用实施计划已写明的推荐默认（D01 客户端直连为主、D02 固定 26.3/2.6.25 有限适配、D04 AUTO 受控回退/PINNED_TS 不跨后端、D05 换服保留 TS 目标/世界外暂停音频、D10 默认 Ghost + 墓碑、D03 TS 子页）。D06–D09 不在本切片范围。
- 测试基础设施：客户端/旧桥为 `review/` 下离线 javac harness（替身类 + PowerShell runner），由 `.github/workflows/ci.yml` 调用；core 为无 JUnit 的 `protocolTest`（Gradle JavaExec）。新测试沿用该模式接入 CI。

## Functional Requirements

### P0 缺陷修复
- **FR-P0-1 (F02)**: TS 路由被选中期间，`ClientSoundEvent` 必须先取消再处理；编码器创建/编码异常、编码为空、发送失败、会话在检查间隙断开等任何失败场景下，事件保持已取消，SVC 原生网络收到该帧的非空上传次数恒为 0。TS 未被选中（休眠/用户断开）时 SVC 原生路径行为不变。
- **FR-P0-2 (F04/F05)**: 身份声明接收端：存储声明的真实 `expiresAtEpochSeconds`；仅当 issuer 在本地信任目录中被显式信任时才提升 MC 身份展示（默认目录为空 → 全部 Ghost，语音/文字/群组不受影响）；拒绝未来签发时间（偏差 >60s）、超过 24h 的 TTL、跨 serverUID、不支持的声明版本；按 issuer/server/client 维度维护 revision 高水位与撤销墓碑；墓碑持久化，不因换 MC 服务器清除；已撤销 revision 的声明重放被拒。
- **FR-P0-3 (F06)**: 成员快照服务：UNKNOWN 状态按请求 groupId 过滤最后快照，绝不返回全部 key；快照带采样时间戳，超过 TTL（建议 5s）返回空；authoritative EMPTY 返回空。
- **FR-P0-4 (签发端)**: 同一持久签发公钥的 revision 跨进程重启单调：新增 `IdentityRevisionStore`，签发/撤销前原子持久化下一个值（有界计数块预留，崩溃后跳块不复用）；分配失败时暂停身份提升，语音保持 Ghost。玩家撤销时按条目逐一广播 `ClaimRevoked`，不能只发同一 MC UUID 的第一条映射。

### P1 单一媒体归属与会话隔离
- **FR-P1-1 (F03)**: 会话内所有 voice/text/membership/identity/avatar/command 回调绑定来源 client+token（会话代际）；投递与处理两次校验；旧会话回调不得查询"当前 client"解释自己的数据，不得刷新新会话状态。
- **FR-P1-2 (F09)**: TS 连接关闭先原子标记失效并直接关闭底层 Socket（唤醒阻塞读/写），不等待 sendLock；线程 join 有 deadline；阻塞写场景下关闭在限时内（建议 2s）完成。
- **FR-P1-3**: TS 发送使用单会话有界队列（建议 ≤5×20ms 帧、年龄上限 120ms，超限丢最旧并计数）；控制命令独立有界队列；包序号仍在连接层串行分配。
- **FR-P1-4**: TS 播放通道使用稳定参与者 UUID `nameUUIDFromBytes("tsvoice-participant-v2\0" + authority + "\0" + nativeUID)`（不含 session/streamEpoch）；重连/clid 变化后个人音量与说话高亮不分裂；旧通道清理按 (token, ownedObject) 核对，不得删除同 UUID 新通道或持久个人音量。
- **FR-P1-5**: 非 Opus codec 在入站边界明确拒绝且不影响其他用户；活跃路由未确认时麦克风消费但不发送；后端切换后旧帧收发计数为 0。

### P2 独立前端
- **FR-P2-1**: 新增前端适配层（SvcFrontendAdapter / Svc2625FrontendAdapter / FrontendCapabilities）：封装 2.6.25 内部 API 依赖；启动时 hook 自检，关键 mic/route hook 缺失 → 禁用 TS 并明确告警，可选功能失败只禁用该功能。
- **FR-P2-2**: 前端租约（FrontendLease）单一 mic 所有权：原生 SVC mic 存在则借用，无则创建；SVC 断开只关闭其拥有的资源，协调器可重新申请；防止同时打开两个设备。
- **FR-P2-3**: TS 接收侧使用独立播放入口（TS 专用 AudioChannel 生命周期），有/无 SVC 网络同一套播放管线（jitter/个人音量保留）；TS 模式下原版连接图标/群组按钮显示真实 TS 状态或明确入口，不伪造 `isConnected()`。
- **FR-P2-4**: 注册 CLIENT_STOPPING 与世界退出资源编排；native 初始化失败（含 LinkageError）降级，WebRtcNative 处理失败返回失败健康状态。
- **FR-P2-5**: 真实验收矩阵（无 SVC 服务器双向通话等）在本环境执行时逐项标注"未验证"，并记录复现步骤；不得以替身测试或构建通过宣称已支持。

### P3 独立书签与 TS 频道
- **FR-P3-1 (F08)**: 书签 schema v2 与 MC 服务器配置解绑；凭据按规范 endpoint 隔离，迁移仅限明确匹配同一 endpoint，不得复制全局密码到其他 host；密码保留空格与显式清除语义；secret 不进入日志/toString。
- **FR-P3-2**: `manualConnect(bookmarkId)` 创建显式意图，server-bridge 公告不得覆盖；autoConnect=false 不阻止手动连接。
- **FR-P3-3**: 完整 TS 频道/用户快照（cid/pid/顺序/名称/密码存在/权限状态），主键 serverUID/cid；请求-应答经 `TsCommandTracker` 按会话关联（协议支持的关联参数或单会话串行队列）；频道密码错误/权限不足/ban/server full 显示可操作错误并停止无意义重试。
- **FR-P3-4 (F10)**: 文件上传按 session+requestId 关联 Future，不覆盖单个 handler；EOF 仅作传输结束证据，未获 TS 确认不写成功 marker；marker 键含 serverUID+TS UID+MC UUID+skin hash。
- **FR-P3-5**: 群组行为分模式（SVC_NATIVE / TS_CHANNEL_SELECTION / LEGACY_GROUP_MAPPING）；TS 手动频道不被周期映射覆盖；无 SVC 服务器时不发原生群组创建包冒充成功。

### P4 能力发现与受控回退
- **FR-P4-1**: 新能力公告协议（`teamspeak-svc:capabilities_v1` S2C / `capabilities_hello_v1` C2S），纯字节编解码进 core，严格字段验证（长度/端口/版本边界，未知 minor 跳过、未知 major 拒绝）；旧 beacon 字节与语义不变，仅作 legacy 推荐。
- **FR-P4-2**: hello 仅在 Fabric canSend 时发送，contextEpoch+nonce 绑定响应；本地有界收集窗口（建议 1.5s）超时回退原版 SVC；不向任意端口探测。
- **FR-P4-3**: 候选顺序：显式 PINNED → 本服策略 → 已授权推荐 → SVC；显式 OFF 不重连；transport UDP_ONLY/TCP_ONLY 强制遵守；TS UDP 一次真实握手失败（NETWORK_TIMEOUT）且有已授权兼容隧道才尝试；认证/权限/ban/版本错误不通过换传输或换后端掩盖。
- **FR-P4-4**: 重连预算与退避（建议 DNS 3s / 握手 15s / 单轮 35s；2→4→…→60s ±20% jitter，单调时钟）；网络连接/频道确认/前端健康/READY 分开显示；无可用语音服务时不得显示 READY。
- **FR-P4-5**: 服务端能力公告提供方按计划为独立可选工程（voice-capabilities-fabric）；若本环境无法验证其构建，则代码交付但明确标注"构建未验证"，不接入 CI 必过路径。

## Non-Functional Requirements
- **NFR-1**: 不回退评估 4.1 已列修复（命令窗口/分片、RSA 高位、信标凭据隔离、群组发送保护、Ghost 所有权、隧道鉴权、AEC 同步、上传结果分类、CI）。
- **NFR-2**: 未知身份始终可作为普通 TS 用户说话；身份签名不授予任何频道/群组权限。
- **NFR-3**: 每阶段独立 git 提交，附测试命令、环境、结果与已知限制；未完成真实条件标"未验证"。
- **NFR-4**: 新增回归全部接入 CI；本地 CI 等价命令全绿后才算任务完成。
- **NFR-5**: 音频热路径不引入阻塞 I/O 与全局锁；替身测试用确定性时钟/latch，不用 sleep 猜竞争。

## Constraints
- **Technical**: MC 26.3、Java 25、Fabric Loader 0.20.1、SVC 2.6.25（libs 内实际 JAR）、voicechat-api 2.6.20、Gradle 9.5.1/Loom 1.17.21。兼容声明先固定此范围。
- **Business**: 本切片不得扩大宣传范围：P2 真实验收未完成前不得发布"无 SVC 服务器可用"声明。
- **Dependencies**: P0→P1→P2→P3→P4 顺序依赖；P3/P4 的部分真实 UI/网络验收依赖真实环境。
- **Environment**: Windows 工作区，无 Git 元数据（本切片初始化本地 Git）；无真实 TS3 服务器/MC 多实例/音频设备自动化。

## Assumptions
- D01/D02/D03/D04/D05/D10 采用实施计划推荐默认（已随范围选择获得开发者认可）；如开发者后续推翻某决策，受影响任务按变更处理。
- 临时探针目录（Temp/teamspeak-architecture-cf644bd1...）中的场景已由本轮勘察复核，转换为正式回归时以当前源码为准。
- `review/` 现有 harness/runner 模式（javac + 替身 + ps1）继续用于客户端与旧桥测试。

## Acceptance Criteria

### AC-1: TS 选中期间麦克风零泄漏（F02）
- **Type**: `rule`
- **Given**: TS 路由被选中（配置存在且未被用户禁用），且处于以下任一情况：编码器创建抛异常、encode 返回空、sendMic 失败、会话在两次检查之间断开、会话未连接（WAITING_SVC/RECONNECTING）。
- **When**: SVC 产生 `ClientSoundEvent`（含非空 PCM）。
- **Then**: 事件被取消；SVC 网络非空上传计数为 0；异常仅记录报告，不改变路由归属。
- **Pass Condition**: MicRedirectorRegression 全部断言通过；未选 TS 时事件未被取消（SVC 原生路径不受影响）。
- **Evidence**: review/p0-defects/ runner 输出 + 实现源码。

### AC-2: 身份信任与防重放（F04/F05）
- **Type**: `rule`
- **Given**: 客户端收到签名的 MC↔TS 身份声明。
- **When**: 分别注入：立即过期的错误实现声明（修复后应存真实 expiry 并在真实到期前有效）、未信任 issuer 的声明、未来 issuedAt、TTL>24h、跨 serverUID、不支持版本、撤销后重放旧 revision、多 issuer 相同用户。
- **Then**: 仅受信 issuer 的有效声明提升 MC 身份；其余一律拒绝或保持 Ghost；撤销墓碑与高水位持久化且换服不清除；未知身份语音不受影响。
- **Pass Condition**: ClientIdentityServiceRegression 与 core IdentityTrustRegression 全部断言通过。
- **Evidence**: runner 输出 + core protocolTest 输出。

### AC-3: 成员快照不扩大受众（F06）
- **Type**: `rule`
- **Given**: 已发布多群组成员快照后进入 UNKNOWN，或快照超过 TTL。
- **When**: 请求某群组的桥接成员列表。
- **Then**: UNKNOWN 按请求 groupId 过滤最后快照（不包含其他群成员）；过期返回空；authoritative EMPTY 返回空。
- **Pass Condition**: BridgeMembershipRegression 全部断言通过。
- **Evidence**: runner 输出。

### AC-4: 签发端 revision 单调与逐条撤销广播
- **Type**: `rule`
- **Given**: 签发服务持久 issuer 公钥，流程"签发→撤销→签发进程重启→重新证明"。
- **When**: 重启后重新签发同一 (serverUid, tsClientUid)。
- **Then**: 新 revision 大于旧墓碑且客户端可验证；计数预留块在崩溃后跳过不复用；分配失败暂停提升而非回退计数；多声明玩家撤销时每条声明各收到一条 ClaimRevoked。
- **Pass Condition**: 身份签发回归（桥 harness）全部断言通过。
- **Evidence**: runner 输出。

### AC-5: 会话回调隔离与阻塞关闭（F03/F09）
- **Type**: `rule`
- **Given**: 旧会话的延迟 voice/text/membership/identity 回调与新会话并发；TCP 对端停止读造成写阻塞。
- **When**: 旧回调到达、会话切换、调用关闭。
- **Then**: 旧回调不影响新会话状态（闭包绑定 token，投递/处理双检）；关闭原子失效并先关 Socket，限时（建议 2s）内返回；序号仍在连接层串行分配，ACK/窗口行为不变。
- **Pass Condition**: BackendCoordinatorTest/RouteIsolationTest/TransportShutdownTest 全部断言通过。
- **Evidence**: runner 输出。

### AC-6: 稳定参与者 UUID 与通道清理所有权
- **Type**: `rule`
- **Given**: 同一 TS 用户重连/换 clid/跨服同 UID，旧流与新流同 UUID。
- **When**: 新流创建、旧流异步清理。
- **Then**: 个人音量与说话高亮不分裂（稳定 UUID 命中音量缓存）；旧清理不得删除新通道（按 ownedObject 核对）；跨服同 UID 保持独立。
- **Pass Condition**: StableParticipantVolumeTest 全部断言通过。
- **Evidence**: runner 输出。

### AC-7: 独立前端适配层与自检
- **Type**: `rule`
- **Given**: 前端适配层与 mixin hook 自检已实现。
- **When**: 关键 hook 缺失、可选功能失败、mic 所有权交接、资源退出编排、native 失败注入（替身级）。
- **Then**: 关键失败禁用 TS 并告警；可选失败只禁用该功能；任一时刻只有一个 mic 所有者；真实验收矩阵未执行项全部标注"未验证"且无"已支持"声明。
- **Pass Condition**: FrontendOwnershipTest/FrontendCompatibilityTest 通过；未验证清单完整记录。
- **Evidence**: runner 输出 + 未验证清单。

### AC-8: 书签与凭据隔离（F08/F10）
- **Type**: `rule`
- **Given**: 旧全局/每 MC 配置迁移、两个书签、两个相同 cid 的 TS 服务端、并发/迟到上传回复。
- **When**: 迁移、手动连接、上传。
- **Then**: 凭据仅同一 endpoint 迁移；manualConnect 显式意图不被公告覆盖；上传按 session+requestId 关联不覆盖 handler；EOF 不当成功；错误响应/迟到/换身份场景安全。
- **Pass Condition**: BookmarkMigrationTest/EndpointCredentialsTest/CommandTrackerTest/FileTransferRegression 通过。
- **Evidence**: runner 输出。

### AC-9: 能力公告与受控回退
- **Type**: `rule`
- **Given**: 四组合候选矩阵、恶意/畸形公告、late capability、错误 nonce、显式 OFF/PINNED。
- **When**: fake clock 驱动选择与回退。
- **Then**: 候选顺序与强制 transport 遵守规则执行；认证/权限错误不换传输；无服务不显示 READY；wire 严格验证拒绝畸形输入；旧 beacon 字节不变。
- **Pass Condition**: CapabilityWireTest/FallbackPolicyTest 通过。
- **Evidence**: runner 输出。

### AC-10: 无回归与 CI 接入
- **Type**: `rule`
- **Given**: 现有 5 组回归（108+65 具名断言+RSA）与 core protocolTest。
- **When**: 本切片各任务合入后运行全部既有测试与新测试（CI 等价命令）。
- **Then**: 既有回归无倒退；新测试全部进入 ci.yml 并本地通过；构建通过。
- **Pass Condition**: 全部 runner + gradle 命令退出码 0。
- **Evidence**: 命令输出记录于 tasks.md 完成证据。

### AC-11: 阶段留存与诚实标注
- **Type**: `rule`
- **Given**: 本切片实施过程。
- **When**: 每阶段完成。
- **Then**: 每阶段有独立 git 提交；真实环境验收项逐项标注"未验证"并附复现步骤；无以构建通过代替功能验收的声明。
- **Pass Condition**: git log 阶段提交齐全；未验证清单存在于交付记录。
- **Evidence**: git log + 未验证清单。

### AC-12: 架构契合度
- **Type**: `rubric`
- **Dimension**: 实现与实施计划目标架构（协调器/会话 token/媒体归属/克制的类职责）的契合度与代码质量。
- **Scale**: 1-5
- **Anchors**: 1 = 偏离计划架构、全局状态继续扩散；3 = 大体符合但存在明显越界重构或职责混杂；5 = 符合计划接口草案与 SOLID 调整方向，无越界重构，音频热路径无阻塞。
- **Pass Threshold**: >= 4
- **Evidence**: 独立审查对新增/修改文件的核阅。

## Open Questions
- [ ] P4 能力公告服务端独立工程在本环境的 Gradle/Loom 构建可行性（网络依赖解析）；不可行时按 FR-P4-5 标注"构建未验证"。
- [ ] P2 真实验收矩阵的执行环境（真实设备/服务器）何时具备；当前全部标注"未验证"。
