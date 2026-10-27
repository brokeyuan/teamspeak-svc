# teamspeak-svc 首切片（P0–P4）- Implementation Plan

> 约定：前缀 C=W/teamspeak-svc/src/main/java/com/tsvoice/client，B=W/teamspeak&svcbridge/src/main/java/com/svcb/tsbridge，P=W/ts3voice-core/src/main/java/com/tsvoice/protocol。客户端/旧桥离线 harness 位于 review/（javac+替身+ps1 runner，CI 调用）；core 测试为无 JUnit main 入口。每阶段完成后独立 git 提交。

## Task 1: 基线留存与既有回归绿基线
- **Status**: `completed`
- **Completion Evidence**:
  - git init -b main 完成；.gitignore 追加排除 `/teamspeak&svcbridge.zip`（751MB）、`/voicechat-discord-main/`（641MB 参考仓库）、`/tsbridge-standby/`（jar）、`/review/build-fixes/`（453MB 生成物）；初始提交 `ac4039c`（1164 文件，pack 16MiB），`git status` 干净。
  - 关键文件 SHA-256 与评估一致（TsVoiceManager `811c228c…`、ClientIdentityService `4fb3a208…`、MicRedirector `cdbce4ef…`、BridgeMembershipService `44c42cb3…`、SVC jar `189605fd…`）。
  - core：`gradlew clean test`（Gradle 9.5.1，含 protocolTest 108 项）BUILD SUCCESSFUL，EXIT=0。注：工作区路径含 `&`，cmd 直调 gradlew.bat 会截断路径，须用 PowerShell 引用调用（已记录）。
  - 既有 5 组 harness 回归全部通过（RunProtocol/RunMapper/RunTests/RunManagerTests/RunServerChecks，退出码均 0），即 65 项具名断言 + RSA/握手检查绿基线。
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 在工作区根 `git init`（沿用现有 .gitignore，确认 build/、凭据、testserver 被排除），记录 5 个关键文件 SHA-256 基线，做初始提交（不授权 push 到任何远端）。
  - 构建/产出 ts3voice-core 编译类，依次运行既有 5 组 harness 回归（RunProtocol/RunMapper/RunTests/RunManagerTests/RunServerChecks）与 core protocolTest，确认 108+65 具名断言 + RSA 检查全绿，作为本切片改动前基线证据。
- **Acceptance Criteria Addressed**: AC-10, AC-11
- **Test Requirements**:
  - `rule` TR-1.1: `git status` 干净且初始提交存在；.gitignore 覆盖 build/libs 与身份文件（证据：git log/status 输出）。
  - `rule` TR-1.2: 5 组既有回归 + core protocolTest 在未修改源码上全部通过（证据：各 runner 退出码与输出摘要）。

## Task 2: F02 麦克风路由修复（失败测试先行）
- **Status**: `completed`
- **Completion Evidence**:
  - TR-2.1（旧代码失败证据）：RunMicRedirector.ps1 首次运行输出 9 项 FAIL（waiting 未连接泄漏 / encoder 创建失败 / null 与空编码 / 检查间隙断线 / 发送失败 / PTT 持续期间逐帧泄漏 / healthy path）并以 `Mic redirect regression failed` 退出；对照组（"no TS selection"）旧代码即 PASS。
  - TR-2.2（修复后全绿）：10/10 PASS；5 组既有回归（RunTests/RunManagerTests/RunMapper/RunProtocol/RunServerChecks）退出码全 0；`gradlew compileJava` 通过。RunManagerTests.ps1 编译清单已加入 MicRouteGate.java。
  - 实现：新增 C/audio/MicRouteGate.java（Owner.SVC_NATIVE/TS，volatile）；MicRedirector 改为"TS 选中 → 先 cancel → 就绪检查 → 失败仅报告"；TsVoiceManager.evaluateActivation/manualDisconnect 经 refreshRouteOwnership() 维护归属（activeConfig!=null && !userDisabled → TS）。onSvcDisconnected 不改归属：配置仍在时 TS 保持选中，SVC 恢复时麦克风不会泄漏。
  - 边界说明：TS 选中但未就绪期间，原本由 SVC 原生路径承载的语音现在被消费丢弃（fail-closed，符合计划 §1.3 mic 规则与 D04"运行中失效先静音"）；恢复 SVC 原生需手动断开 TS 或清除配置。UI 通知属 P4。
  - 测试代理注意：SVC API `ClientSoundEvent.cancel()` 返回 boolean（代理须返回 TRUE）；`MicRedirector.encoder` 为静态缓存，测试用例间用反射重置。
  - 提交：c3b9164。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新增 review/p0-defects/MicRedirectorRegression.java + RunMicRedirector.ps1：覆盖 TS 选中时 createEncoder 抛异常、encode 返回空、sendMic 失败、两次检查间会话断开、WAITING_SVC/RECONNECTING 未连接、长按 PTT 连续帧——断言事件始终取消、SVC 非空上传计数为 0；未选 TS 时事件不取消。
  - 先运行失败测试证明旧代码失败；再引入最小"选中后端/归属"状态（C/audio/MicRouteGate.java，P1 正式化），MicRedirector 改为"先 cancel、再就绪检查、失败仅报告"；TsVoiceManager 在 evaluateActivation/stopSession/onSvcDisconnected 维护归属状态。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-2.1: 失败测试在旧代码上至少一条断言失败（证据：修复前 runner 输出）。
  - `rule` TR-2.2: 修复后 MicRedirectorRegression 全绿，且 ManagerTests/ClientStateTests 无倒退。

## Task 3: F06 成员快照修复（失败测试先行）
- **Status**: `completed`
- **Completion Evidence**:
  - TR-3.1（旧代码失败证据）：RunBridgeMembership.ps1 首次运行 3 项 FAIL——"UNKNOWN keeps last snapshot filtered to the requested group"、"UNKNOWN never includes members of another group"、"UNKNOWN keeps other groups filtered as well"（UNKNOWN 返回全部 key，群 A 目标含群 B 成员）。
  - TR-3.2（修复后全绿）：16/16 PASS，含 UNKNOWN 按群过滤、6s 过期后一律为空（UNKNOWN 与 authoritative 皆然）、republish 刷新 TTL、authoritative EMPTY、群组切换、reset；RunServerChecks.ps1 退出码 0（无倒退）；旧桥 `gradlew compileJava` 通过。
  - 实现：BridgeMembershipService 增加 sampledAtNanos（publish 时刷新）与 SNAPSHOT_TTL_NANOS=5s（BeaconSender 2s 轮询的容错上限）；getBridgeGroupMembers 的 UNKNOWN 分支改为按请求群过滤最后快照，过期（sampling=0 或超 TTL）一律返回空；availability() 对过期快照返回 UNKNOWN。BeaconSender 采样节奏未改。
  - 提交：见 git log（P0/F06）。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新增 BridgeMembershipRegression（含 UNKNOWN 扩大受众复现、TTL 过期、authoritative EMPTY、混合群/群组切换、null groupId）+ runner。
  - 修改 B/svc/BridgeMembershipService.java：publish 记录采样时间戳（nanoTime）；UNKNOWN/过期（建议 5s）按请求 groupId 过滤最后快照，过期返回空；EMPTY 语义保留；BeaconSender 2s 轮询即采样节奏（不改旧桥其他行为）。
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `rule` TR-3.1: 失败测试在旧代码上复现"UNKNOWN 时群 A 目标包含群 B 成员"（证据：修复前输出）。
  - `rule` TR-3.2: 修复后全绿；ServerRegression 无倒退。

## Task 4: 客户端身份信任/过期/防重放修复（F04/F05，失败测试先行）
- **Status**: `completed`
- **Completion Evidence**:
  - TR-4.1（旧代码失败证据）：RunClientIdentity.ps1 首次运行 10 项 FAIL——真实 expiry 未存（F04）、未信任 issuer 入库（F05，经 map 内容断言暴露；评估已指出 F04 会使 lookup 全 null，仅查 lookup 会假通过）、未来 issuedAt / 超 24h TTL 未拒、撤销重放重入库、重启后墓碑缺失、重连后等 revision 重放。对照项（跨 serverUID、过期、畸形、撤销移除）旧代码即 PASS。
  - TR-4.2（修复后全绿）：15/15 PASS；core `gradlew clean test` 108 项通过（SignedPlayerClaim/ServerTrustDirectory 变更无倒退）；5 组既有回归 + F02/F06 新回归全部退出码 0；客户端 `gradlew compileJava` 通过。
  - 实现：P/SignedPlayerClaim 新增 MAX_TTL_SECONDS=24h、CLOCK_SKEW_SECONDS=60、verifyWithPolicy()（版本/未来签发/超 TTL/签名/到期）；P/ServerTrustDirectory 新增 isTrusted(issuerKeyId)、revocationWatermark(claimKey)；C/ClientIdentityService 重写——ServerTrustDirectory 实例默认空（D10：未知 issuer 不提升，语音 Ghost）、所有策略通过的声明推进持久高水位（identity-trust.json，ConfigFileUtil.writePrivate 原子写，换服/断线不清除）、ClaimRevoked 写墓碑并按 revision 移除、pinTrustedIssuer/unpinTrustedIssuer 显式信任操作、存储 claim 真实 expiresAtEpochSeconds。wire 格式未变。
  - 边界说明：墓碑"至少保留至 revokedAt+TTL+偏差"以无限期保留最高 revision 实现（强于下限）；多 issuer 同一 (server,tsClientUid) 各自独立 claimKey；信任配置 UI 属 P3+，当前目录为空即全部 Ghost。
  - 测试设施：receiver 录制型 stub（ClientPlayNetworking.RECEIVERS）走真实注册路径；PowerShell 包装 javac stderr 注记 + stdout 重定向会令 `powershell -File` 退出码失真，脚本末尾显式 `exit $LASTEXITCODE`。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新增 review/p0-defects/ClientIdentityServiceRegression.java + runner：真实 expiry 存储、受信 issuer 才提升、未信任 issuer 拒绝（语音 Ghost）、未来 issuedAt（>60s 偏差）、TTL>24h、跨 serverUID、版本不支持、撤销后重放旧 revision 被墓碑拒绝、多 issuer 相同用户、F04+F05 联合场景、换 MC 服务器不清除持久防重放状态。
  - 修改 C/ClientIdentityService.java：持有 ServerTrustDirectory 实例（默认无受信 issuer）+ 持久化墓碑/高水位（config/teamspeak-svc 下私有文件，原子写）；handleClaim 存 claim 真实 expiry 并执行信任策略；ClaimRevoked 写墓碑并按 revision 移除。
  - 按需扩展 P/ServerTrustDirectory.java（按 claimKey 查询墓碑/高水位、issuerKeyId 索引）与 P/SignedPlayerClaim.java（TTL/偏差/版本校验辅助）；不改变 wire 格式。
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `rule` TR-4.1: 失败测试在旧代码上复现立即过期与未信任 issuer 入库（证据：修复前输出）。
  - `rule` TR-4.2: 修复后回归全绿；secret/凭据不进入日志断言通过。

## Task 5: 签发端 IdentityRevisionStore 与逐条撤销广播
- **Status**: `completed`
- **Completion Evidence**:
  - 失败证据（旧代码）：RunIdentityIssuing.ps1 首次运行 2 项 FAIL——"revocation broadcasts one payload per issued claim (got 1)"、"revocation payloads cover distinct claims"（撤销多声明玩家只广播第一条）。
  - 修复后全绿：IdentityIssuingRegression 6/6（签发 revision≥2、逐条撤销广播、墓碑高于全部已签发、撤销后重新证明 revision 超越墓碑）；IdentityRevisionStoreRegression 8/8（首号=2 且单调、重启跳过整个预留块、持久化失败抛 RevisionAllocationException 且恢复后不回退、跨 4 次重启长链严格单调、文件持久化往返）。
  - core：PT/IdentityTrustRegression 15 项通过，接入 Gradle `identityTrustTest`（test 依赖它）；`gradlew clean test` = 108 protocol + 15 identity trust 全部通过。
  - 实现：新增 B/identity/IdentityRevisionStore.java（预留计数块 RESERVE_BLOCK=100；FilePersistence 单行 long 原子写；损坏/不可读抛出而非返回 0——宁可暂停提升不可复用编号）；IdentityProofService 移除 AtomicLong(1)，签发与撤销都经 store.allocate()，分配失败暂停提升（语音 Ghost），撤销按条目逐一广播、分配失败时以声明自身 revision 作墓碑。
  - 其余 8 组回归退出码全 0；旧桥 `gradlew compileJava` 通过。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新增 B/identity/IdentityRevisionStore.java：签发/撤销前原子持久化下一个 revision（计数块预留，崩溃跳块不复用；写入失败抛错使身份提升暂停）；接入 IdentityProofService 的 issue/revoke 路径，替换 AtomicLong(1)。
  - revokeByMcUuid 改为按条目逐一广播 ClaimRevoked（每条声明一条 payload）。
  - 新增桥 harness 回归（review/p0-defects/IdentityIssuingRegression.java + runner）：签发→撤销→重启→重新证明（新 revision > 旧墓碑）、预留块崩溃跳过、计数写入失败暂停提升、多声明逐条广播；core 侧 PT/IdentityTrustRegression.java 覆盖目录 verify/墓碑/高水位纯逻辑并接入 protocolTest。
- **Acceptance Criteria Addressed**: AC-4, AC-2
- **Test Requirements**:
  - `rule` TR-5.1: 重启后重新证明的 revision 大于旧墓碑且验证通过；崩溃预留块不复用（测试用可注入存储实现）。
  - `rule` TR-5.2: 撤销多声明玩家时广播条数=声明条数；core IdentityTrustRegression 并入 protocolTest 通过。

## Task 6: P0 收口——CI 接入与全量绿
- **Status**: `completed`
- **Completion Evidence**:
  - TR-6.1: ci.yml 新增 4 个 P0 runner 步骤（RunMicRedirector / RunClientIdentity / RunBridgeMembership / RunIdentityIssuing）。本地 CI 等价序列全绿：core `clean test`（108+15，EXIT=0）→ 客户端 `clean build`（EXIT=0）→ 旧桥 `clean build`（EXIT=0）→ 9 组 harness 回归（RunProtocol/RunMapper/RunTests/RunManagerTests/RunMicRedirector/RunClientIdentity/RunBridgeMembership/RunIdentityIssuing/RunServerChecks）退出码全 0。
  - TR-6.2: P0 阶段提交齐全（c3b9164 / b2ce9e6 / 8263c3e / 483d6ee + 本提交）。
  - P0 缺陷修复总结：F02（麦克风先取消后处理）、F04/F05（真实 expiry + 显式信任 + 持久防重放）、F06（UNKNOWN 按群过滤 + 快照 TTL）、签发端（revision 跨重启单调 + 逐条撤销广播）。所有失败用例均在旧代码上验证过确实失败。
- **Priority**: high
- **Depends On**: Tasks 2,3,4,5
- **Description**:
  - .github/workflows/ci.yml 增加新 runner 调用步骤；本地按 CI 顺序全量执行（5 组既有 + P0 新增 + core test）；git 提交 P0 阶段。
- **Acceptance Criteria Addressed**: AC-10, AC-11
- **Test Requirements**:
  - `rule` TR-6.1: ci.yml 包含全部新 runner；本地 CI 等价命令序列退出码全 0（证据：输出摘要）。
  - `rule` TR-6.2: P0 阶段 git 提交存在且包含全部 P0 变更。

## Task 7: P1 控制平面骨架与会话 token 隔离（F03）
- **Status**: `completed`
- **Completion Evidence**:
  - 失败证据（旧代码）：RunSessionIsolation.ps1 首次运行 5 项 FAIL——旧会话语音帧经全局新 client 解析后注入（F03 核心缺陷）、旧会话文字经静态方法引用送达 MC 聊天、旧会话成员回调仍以旧 client 同步 Ghost、新会话帧/文字计数被旧回调污染。
  - 修复后全绿：10/10 PASS（含当前会话帧正常解析、手动断开关闭会话 client）。
  - 实现：新增 C/connection/VoiceState.java（SessionToken/RouteSnapshot/BackendKind/FailureKind 集中定义）、C/connection/BackendCoordinator.java（token 签发/失效、volatile 不可变路由快照、publishRoute 拒绝旧 token）、C/backend/SvcBackendAdapter.java（SVC 连接观察者）；TsVoiceManager——startSession 签发 token、connect(gen) 将 voice/text/membership 回调绑定 (来源 client, gen) 双检、handleVoiceFrame 改为依赖 source 参数解析（不再查询全局 client）、连接/断线/重连发布路由快照。
  - **SVC 断连解耦（TR-7.2）**：onSvcDisconnected 不再 stopSession——只更新 SvcBackendAdapter 与状态显示；active(gen) 移除 svcConnected 条件，TS 会话在 SVC 断开期间继续运行与重连（麦克风归属不变，不泄漏回 SVC）。这是 P1 验收" SVC 连接变化只更新 SvcBackendAdapter"的直接实现，也是 F01（P2）的前置。**注意**：TS 会话启动仍要求 svcConnected（F01 的启动门在 P2 前端验证后才解除）。
  - **计划偏差说明**：TsBackendSession/SelectionPolicy 未在本任务创建——其消费者（有界队列 Task 8、播放注册表 Task 9、能力回退 P4）尚未存在，先创建会形成无落点的空壳；Task 8/9/16 引入并在此记录。Token 当前与 sessionGeneration 并行存在，Task 8/9 逐步收编。
  - 全部 10 组回归退出码 0；客户端 compileJava 通过；RunMicRedirector/RunManagerTests 编译清单已加入新类。
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - 新增 C/connection/VoiceState.java（SessionToken/RouteSnapshot/ParticipantKey 等集中定义）、C/connection/SelectionPolicy.java（纯函数）、C/connection/BackendCoordinator.java（主线程串行状态机，volatile 不可变 route 快照）、C/backend/TsBackendSession.java（封装 Ts3VoiceClient 单会话）、C/backend/SvcBackendAdapter.java（观察 SVC 连接，不再直接启停 TS）。
  - 回调绑定：voice/text/membership/identity/avatar/command 全部携带来源 client+token，投递与处理双检；旧回调不得查询全局 current client（消除 TsVoiceManager:447-450,477-482,627-646 模式）；TsVoiceManager 作为薄门面逐步委托。
  - 新增 CT/connection/BackendCoordinatorTest.java（确定性 fake clock/latch）：延迟旧回调、会话替换、contextEpoch 变化不影响显式目标。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-7.1: 旧会话延迟 voice/text/membership/identity 回调对新会话状态、Ghost、发送计数的全部影响为 0（测试断言）。
  - `rule` TR-7.2: RouteSnapshot 为不可变快照；SVC 连接变化只更新 SvcBackendAdapter，不销毁 TS 会话（测试断言）。

## Task 8: P1 连接健壮性——阻塞关闭与有界队列（F09/F11 发送侧）
- **Status**: `completed`
- **Completion Evidence**:
  - 失败证据（旧代码）：RunTransportShutdown.ps1 首次运行 4 项 FAIL——无队列（dropped=0，发送线程在 addOutgoingPacket 内持 sendLock 阻塞于 transport.send）、stop() 5s 未返回（等待 sendLock）、发送线程不退出、非 Opus codec（1）直通监听器。
  - 修复后全绿：8/8 PASS——确定性阻塞 Transport 下队列丢最旧（dropped=44）且采集线程入队不阻塞、阻塞写下 stop() 95ms 内返回、真实 TCP 黑洞下 stop() 1ms、并发 send+close 0ms 无死锁、仅 Opus 帧到达监听器。
  - 实现：Ts3Connection 新增独立写线程 + 有界队列（语音 ≤5 帧、年龄 120ms 丢最旧计数 droppedVoiceFrames()；控制命令独立 ≤32，满则抛错不静默）；sendLock 内只做计数/加密/入队，transport.send 由写线程执行；stop() 先 closed=true + transport.close()（不取 sendLock，唤醒阻塞写）再以 2s deadline join 写/收/重发线程；新增 Ts3Connection(Ts3Crypt, TsTransport) 注入构造器；TcpTransport 支持系统属性 tsbridge.tunnel.secret 回退（测试与 P6 铺垫）；Ts3VoiceClient 入站边界拒绝非 Opus codec（每 codec 告警一次，F11 入站侧）。序号分配/ACK/窗口逻辑未动（仍在 sendLock 内串行）。
  - 语义变化说明：命令发送从同步变异步入队——传输层错误不再同步抛给 sendCommand 调用方，经连接超时/写线程失败路径上报；clientdisconnect 命令在 stop() 关闭前尽力发出（健康连接通常来得及，黑洞连接放弃发送优先保证关闭）。
  - 全部 11 组回归退出码 0；客户端 compileJava 通过；core test（108+15）通过。
- **Priority**: high
- **Depends On**: Task 7
- **Description**:
  - C/ts3voice/Ts3Connection.java：关闭先原子标记失效并直接关闭底层 Socket（唤醒阻塞读/写），不等 sendLock；写走独立有界队列；线程 join 有 deadline；loopback"接受后不读"故障注入下 2s 内可关闭。
  - TS 语音发送单会话有界队列（≤5×20ms、年龄 120ms、丢最旧计数）；控制命令独立有界队列；包序号仍由连接层串行分配（ACK/窗口/重组不变）。
  - 非 Opus codec 入站拒绝（错误可见、不中断其他用户），VoiceFrame 保留 codec/原始 seq。
  - 新增 CT/ts3voice/TransportShutdownTest.java + runner。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-8.1: 故障注入下 close 限时内完成且后台线程有界退出；与 send 并发 close 无死锁。
  - `rule` TR-8.2: 有界队列溢出丢最旧并计数；序号回绕/重复/乱序既有断言不倒退。

## Task 9: P1 媒体归属——稳定 UUID 与播放注册表
- **Status**: `completed`
- **Completion Evidence**:
  - 失败证据（旧代码）：RunStableVolume.ps1 内嵌 old-behavior probe（git 取 HEAD 版源码编译）复现 2 项旧缺陷——"old derivation is not authority-scoped"（UUID 不含 serverUID，跨服同 uid 共享音量键）、"old close removed the NEW stream channel by UUID alone"（无条件按 UUID 关闭，旧流清理可杀新流通道）。
  - 修复后全绿：10/10 PASS——v2 派生确定性/权威隔离/clid 无关、注入注册于稳定 UUID、序号重置不换通道对象、remove 按 owned handle 精确关闭、重连同 UUID 重建、外部/过期句柄关闭为 no-op、切服后同 uid 隔离。
  - 实现：TsAudioInjector.participantUuidFor(authority, uid) = nameUUIDFromBytes("tsvoice-participant-v2 "+authority+" "+uid)（不含 session/streamEpoch）；GhostAudio 增加 ownedChannel 快照（首次注入成功后经 ClientStateReflector.ownedAudioChannel 快照并 registerOwnedChannel）；ClientStateReflector.closeAudioChannel(UUID, expectedOwned) 改为 channels.remove(uuid, expectedOwned) 两参语义——句柄不符不关闭；onTsFrame 增加 authority 重载（旧签名委托 null→"unknown"，兼容现有 CI 测试）；TsVoiceManager.handleVoiceFrame 传 source.getVirtualServerUid()。
  - 计划偏差说明：独立 TsPlaybackRegistry 类未创建——其所有权账本以 ClientStateReflector.ownedChannels 承载（与 ownedStates 对称），避免与既有 Ghost 生命周期并行维护两套注册表；P2 前端适配接管播放生命周期时再评估是否独立成类。同 UUID 重连 retire→新建路径由测试覆盖（序号重置 + remove/reconnect 场景）。
  - 迁移边界：旧 "ts-ghost-" 音量缓存不迁移（plan P1 要求"可确定 serverUID 对应关系时复制一次"，当前无可靠来源，留待 P3 书签/凭据引入 serverUID 持久化时处理）；per-player 镜像桥的 UUID 互认需服务端同步迁移（旧桥显式适配，未在本切片改动）。
  - 全部 12 组回归退出码 0；客户端 compileJava 通过。
- **Priority**: high
- **Depends On**: Task 7
- **Description**:
  - 新增 C/audio/MicRouteGate.java 正式化（唯一发送归属、未确认 route 消费不发送）+ C/audio/TsPlaybackRegistry.java（(token,streamEpoch,stableUuid,ownedChannelObject) 所有权）。
  - 稳定参与者 UUID = nameUUIDFromBytes("tsvoice-participant-v2\0"+authority+"\0"+nativeUID)；注册 SVC USERNAME_CACHE/PLAYER_VOLUME_CONFIG 命中；重连 retire 旧 ownedChannel 后同 UUID 新通道；异步清理 channels.remove(uuid, ownedObject)+token 核对。
  - C/ClientStateReflector.closeAudioChannel 改为接受所有权句柄；Ghost ownsState 与 ownsPlayback 分离。
  - 新增 CT/audio/RouteIsolationTest.java、CT/audio/StableParticipantVolumeTest.java + runner；接入 CI。
- **Acceptance Criteria Addressed**: AC-6, AC-5
- **Test Requirements**:
  - `rule` TR-9.1: 重连/clid 变化后个人音量与高亮不分裂；跨服同 UID 隔离；旧清理不删新通道与持久音量（测试断言）。
  - `rule` TR-9.2: 切换后旧帧收发计数为 0；未确认 route 期间 SVC 非空上传为 0；ClientStateTests 无倒退。

## Task 10: P2 独立前端适配层与自检
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 9
- **Description**:
  - 反编译/核对本地 SVC 2.6.25 JAR 的 MicThread/ClientVoicechat/AudioChannel/SoundManager 目标方法描述符并记录（26.2 参考源码仅辅助；AudioChannel 构造器为 4 参 ABI）。
  - 新增 C/frontend/{SvcFrontendAdapter,Svc2625FrontendAdapter,FrontendCapabilities}.java、mixin hooks（仅保留实际验证需要的）、CT/frontend/{FrontendOwnershipTest,FrontendCompatibilityTest}.java；FrontendLease 单一 mic 所有权与交接；TS 专用播放入口保持 jitter/音量管线；hook 自检（关键失败禁用 TS 告警，可选失败只禁功能）；CLIENT_STOPPING/世界退出资源编排；AudioPipeline 捕获 LinkageError 降级；WebRtcNative 失败健康状态。
  - 真实验收矩阵（无 SVC 双向通话等）逐项标注"未验证"并记录复现步骤，写入交付记录；不宣称已支持。
- **Acceptance Criteria Addressed**: AC-7
- **Test Requirements**:
  - `rule` TR-10.1: 替身级测试通过（mic 单一所有权、hook 缺失自检、native 降级、退出编排）。
  - `rule` TR-10.2: 未验证清单完整（每项含环境要求与复现步骤），无"已支持"声明。

## Task 11: P3 书签与凭据隔离（F08）
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 10
- **Description**:
  - 新增 C/config/TsBookmarkStore.java（schema v2）、C/config/EndpointCredentialStore.java（endpoint 规范化、credentialRevision、secret 不入日志）；迁移仅同 endpoint；ServerConfigStore 仅存 intent/bookmarkRef；manualConnect 显式意图不被 server-bridge 覆盖。
  - 新增 CT/config/BookmarkMigrationTest.java、EndpointCredentialsTest.java + runner。
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-11.1: 不同 endpoint 不自动共用密码；密码空格/显式清除语义保持；迁移不删旧文件。
  - `rule` TR-11.2: manualConnect 在 server-bridge 公告存在时仍可建立显式意图（测试断言）。

## Task 12: P3 频道快照、命令关联与状态 UI（D03）
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 11
- **Description**:
  - 新增 C/ts3voice/TsCommandTracker.java（return_code 关联或单会话串行队列；超时/取消）、频道/用户完整快照模型（主键 serverUID/cid，UNKNOWN 不判不存在）；错误分类可操作提示（密码/权限/ban/full 停止无意义重试）。
  - 群组行为分模式（SVC_NATIVE/TS_CHANNEL_SELECTION/LEGACY_GROUP_MAPPING）；TS 手动频道不被周期映射覆盖；无 SVC 时不发原生群组包。
  - 新增 C/ui/{TsBookmarksScreen,TsChannelsScreen,VoiceStatusModel}.java + lang zh_cn/en_us；原版群组页 SVC 模式原样。
  - 新增 CT/ts3voice/CommandTrackerTest.java、CT/ChannelSelectionTest.java + runner。
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-12.1: 迟到/错误响应按请求关联，不串话；错误分类停止重试（测试断言）。
  - `rule` TR-12.2: TS 手动频道选择不被周期映射覆盖；无 SVC 群组入口转 TS 子页不发原生包（测试断言）。

## Task 13: P3 文件上传关联（F10）
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 11
- **Description**:
  - C/Ts3FileTransfer.java 改为 session+clientftfid/requestId Future 关联；错误/超时/取消释放注册；data host 使用 TS 独立 file-transfer endpoint；AvatarPipeline marker 键含 serverUID+TS UID+MC UUID+skin hash，EOF 不写成功。
  - 新增 CT/FileTransferRegression.java + runner。
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-13.1: 并发/迟到/错误响应/换身份场景不覆盖不串用；EOF 场景不产生成功 marker（测试断言）。

## Task 14: P3 收口——CI 与回归全绿
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Tasks 12,13
- **Description**:
  - ci.yml 增加全部 P3 runner；本地 CI 等价命令全量执行；git 提交 P3 阶段。
- **Acceptance Criteria Addressed**: AC-10, AC-11
- **Test Requirements**:
  - `rule` TR-14.1: 本地 CI 等价命令退出码全 0；P3 阶段提交存在。

## Task 15: P4 能力公告 wire（core 纯编解码）
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 10
- **Description**:
  - 新增 P/capability/CapabilityWire.java（capabilities_v1 S2C / capabilities_hello_v1 C2S 字节格式；头 major/minor/nonce/ttl/features/serviceInstanceId；候选 TS/隧道/MC_CHANNEL；16KiB 上限、候选≤8、host≤253、TTL≤60s；未知 minor 跳过、未知 major 拒绝）。
  - 新增 PT/capability/CapabilityWireTest.java 并入 protocolTest。
- **Acceptance Criteria Addressed**: AC-9
- **Test Requirements**:
  - `rule` TR-15.1: golden 编解码向量稳定；畸形输入（超长/坏端口/未知 major/超候选）全部被拒且无无界分配。

## Task 16: P4 客户端发现与受控回退
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 15
- **Description**:
  - 新增 C/discovery/{ServerCapabilities,CapabilityDiscovery}.java、C/net/CapabilitiesPayload.java；hello 仅 Fabric canSend；1.5s 收集窗口；授权弹窗绑定完整候选；候选顺序/强制 transport/OFF 语义；失败类别驱动回退（仅 NETWORK_TIMEOUT+已授权隧道试 B 类路径；认证/权限错误不换网）；预算/退避/jitter（fake clock）；状态分开显示。
  - 新增 CT/connection/FallbackPolicyTest.java + runner；旧 beacon（net/BeaconPayload.java）不改字节。
- **Acceptance Criteria Addressed**: AC-9
- **Test Requirements**:
  - `rule` TR-16.1: 四组合候选矩阵 + late capability/错误 nonce/恶意公告/无插件全部按策略通过（fake clock）。
  - `rule` TR-16.2: 认证/权限/ban/版本错误不触发换传输或换后端；显式 OFF 不重连（测试断言）。

## Task 17: P4 服务端可选工程与切片 CI 收口
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 16
- **Description**:
  - 按计划创建独立可选工程 W/voice-capabilities-fabric（CapabilityMod/CapabilitiesResponder/CapabilityConfig，仅依赖 Fabric）；若本环境无法验证 Loom 构建，则代码交付但标注"构建未验证"，不接入 CI 必过路径。
  - ci.yml 增加全部 P4 runner；本地全量执行；git 提交 P4 阶段。
- **Acceptance Criteria Addressed**: AC-9, AC-10, AC-11
- **Test Requirements**:
  - `rule` TR-17.1: 客户端/协议侧 P4 测试全部接入 CI 并本地通过。
  - `rule` TR-17.2: 服务端工程若未验证构建，交付记录含"构建未验证"标注与原因。

## Task 18: 切片收口——证据包与诚实标注
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 17
- **Description**:
  - 汇总全部测试命令/环境/结果/已知限制；未验证清单（真实设备矩阵）归档；兼容声明收窄为 MC 26.3/SVC 2.6.25；核对不回退 4.1 修复清单；git 提交收口。
- **Acceptance Criteria Addressed**: AC-11, AC-12, AC-10
- **Test Requirements**:
  - `rule` TR-18.1: 证据包完整（每任务完成证据可追溯，未验证项全部标注）。
  - `rubric` TR-18.2: 架构契合度（AC-12 维度）；scale 1-5；anchors 1/3/5 同 spec；threshold >= 4；证据 = 新增/修改文件核阅记录。
