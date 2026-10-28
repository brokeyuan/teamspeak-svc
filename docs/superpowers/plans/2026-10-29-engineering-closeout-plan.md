# teamspeak-svc 下一步任务书：源码纠错与功能接线（客户端验收暂缓）

> **执行范围更新：**按用户最新要求，本文件现作为[持续推进总任务书](2026-10-29-continuous-development-master-plan.md)的阶段 A。N7 是检查点，不再是整个执行任务的默认停点；工程条件满足后继续 M7 及后续已确定任务。下文原有“本任务书结束点”的描述仅界定本子任务内容，不要求实施 AI 在此交接。

> **给 GLM 5.3 Flash / 实施 AI：**从上一轮实际交付继续，按下述 N0–N7 实施。此轮不启动 Minecraft 客户端，不要求用户提供截图、麦克风或陪同验收；完成可以独立执行的源码、自动化与无界面集成工作。具备 `superpowers:executing-plans` 时可按其逐任务执行，否则遵循本文清单。不要重做已经通过的稳定模块，不进入 TCP、音乐 Bot、媒体网关或 WebRTC 开发。

**Goal：**修复本次独立复核的 D01–D05，补齐 T09/T10/T12，使核心切片达到可继续开发的工程交付状态。

**Architecture：**沿用 TsVoiceManager 门面、BackendCoordinator 选择与路由、TsChannelRoute 频道确认、现有 TS 协议栈及版本限定 SVC 前端适配。补实际调用链和资源所有权，不另外建立平行管理器。UI 展示统一快照，不能自行决定发送许可。

**Tech Stack：**沿用当前工程 Java 25、MC 26.3、Fabric、目标 SVC 2.6.25 及既有依赖，不顺手升级版本。

**Spec：**以用户最新要求“暂时不进行客户端的验收”为执行边界，结合 [独立复核报告](../../../review/delivery-audit-2026-10-29/REVIEW.md) 与 [上一轮任务书 1.1](2026-10-28-glm53flash-improvement-report.md)。本文修改推进门槛，不删除原产品要求。

## 1. 本轮边界与证据状态

编写时 HEAD 为 `b7321693deb3067315249ce3761f5956696a7ba1`。执行时记录实际 HEAD；如已有后续修复，按代码和证据跳过已解决项，不覆盖他人修改。

将状态分成三列，避免把验收推迟解释为允许保留已知缺陷：

| 状态 | 本轮要求 |
|---|---|
| 实现状态 | 必须完成实际调用链；只有接口、枚举、空替身不算完成 |
| 工程验证 | 实际 ABI 核对、负例回归、生产调用链测试、构建；可运行的无界面/服务端检查继续执行 |
| 客户端验收 | 本轮统一标“按用户要求暂缓”，保留原 E/U 编号与步骤，不填通过 |

本轮不启动游戏客户端，不做手动界面操作、实际 PTT/语音激活、设备切换、原生 TS 双向听感和客户端弱网测试。测试 UI action 的纯逻辑或无界面控制流不等于客户端验收，可以继续执行。

**新的推进规则：**N0–N7 工程条件通过后，可以进入 M7 统一发布的工程实施；无需等待客户端真机验收。但 M6 产品验收、M7 完整安装矩阵及最终发布验收继续保持未完成。这个规则覆盖旧报告“必须先完成全部客户端验收才能进入 M7”的顺序要求，其他产品约束不变。

本任务书的结束点为 N7 工程交付；M7 是紧接着的独立开发阶段，使用已有 M7 任务书及本文的新推进条件，避免把两轮一次性混做。

## 2. 全局约束

- 不要求安装旧 `teamspeak&svcbridge` 或独立 `voice-capabilities-fabric` 才能使用最终产品。后者可以暂作协议测试夹具，不能变成最终安装依赖。
- 无服务器公告仍计算用户手动书签、本地及全局明确启用的自动 TS 配置；没有任何 TS 自动目标才默认 SVC。
- 无 SVC 服务端仍允许 TS 直连，不能恢复 `svcConnected` 启动门来绕过 D01/D02。
- “断开 TeamSpeak”进入 PAUSED，不隐含切换 SVC；“切换 SVC”与“关闭全部语音”分别处理。PAUSED/OFF 不被公告、换服和重试撤销。
- 默认 TS_NATIVE_FOLLOW 接受服务器确认的正常管理员移动；只有锁定或群组约束不满足时持续阻断。书签默认频道是初始目标，不是永久锁定。
- 继续复用原 SVC GUI、快捷键和音频增强；不擅自改变音效默认值，不为本轮迁移整套协议栈。
- 不复制 SVC 内部类到发布 JAR；版本适配行为以实际目标依赖为依据。现有测试 stub 只供测试，不作为第三方运行行为的证明。
- 真实测试未做、环境阻塞、代码缺陷分别记录。只有明确缺信息且无法继续对应工作时再提问，不因暂缓客户端验收停下整轮开发。

## 3. 文件与任务范围

仓库根目录为 `C:/Users/Administrator/Documents/Workspace/Brokeyuan/mods/teamspeak&svcbridge`。
下文 C 表示 `teamspeak-svc/src/main/java/com/tsvoice/client/`，R 表示 `teamspeak-svc/src/main/resources/`。

| 任务 | 主要修改 / 新增位置 |
|---|---|
| N0 | `review/RunAll.ps1`、`review/glm53flash/STATUS.md`、`ACCEPTANCE.md`、`DELIVERY.md`；本轮证据单独放 `review/engineering-closeout/` |
| N1 | C/connection/{BackendCoordinator,TsChannelRoute,VoiceState}.java；C/ts3voice/{Ts3VoiceClient,Ts3Connection,TsCommandTracker}.java；C/TsVoiceManager.java；`review/route-lifecycle/` |
| N2 | C/frontend/{SvcFrontendAdapter,Svc2625FrontendAdapter,FrontendLease,FrontendCapabilities}.java；C/TsVoiceManager.java；C/VoicechatPluginImpl.java；C/TsAudioInjector.java；必要的版本限定 Mixin 和 R/teamspeak-svc.mixins.json；`review/standalone-frontend/` |
| N3 | C/backend/SvcBackendAdapter.java；C/VoicechatPluginImpl.java；C/TsVoiceManager.java；N2 的播放覆盖；必要的原生 SVC 接收 Mixin；`review/route-lifecycle/` |
| N4 | C/config/{TsBookmarkStore,EndpointCredentialStore}.java；C/ConfigScreen.java；C/mixin/VoiceChatScreenMixin.java；新增 C/TsBookmarksScreen.java、TsBookmarkEditScreen.java、C/connection/VoiceStatusModel.java；R/assets 下现有语言文件；新增 `review/gui-integration/` |
| N5 | 新增 C/TsChannelsScreen.java；复用 N1 命令/路由、TsChannelSnapshot、TsPlaybackRegistry、ClientStateReflector；`review/gui-integration/` |
| N6 | C/net/CapabilitiesPayloads.java（新增）；C/TeamspeakSvcClient.java；C/TsVoiceManager.java；C/connection/{CapabilityDiscovery,SelectionPolicy}.java；现有公告模块 CapabilityMod/CapabilityConfig；`review/p4-discovery/` |
| N7 | `review/engineering-closeout/{STATUS,DELIVERY,DEFERRED-CLIENT-ACCEPTANCE}.md`（新增）及本轮原始日志 |

以上新增名称为实现约定；如后续 HEAD 已有等价类则复用。不得机械照旧文档行号修改，按类和方法定位。没有计划删除业务文件。

## 4. 按顺序执行

### N0：修正基线记录与统一测试入口

- [ ] 记录 HEAD、Java/Gradle、目标依赖哈希及已有未提交变更；保留对方交付原始日志，更新状态时注明本轮日期。
- [ ] 核实 `teamspeak-svc/libs/voicechat-fabric-2.6.25+26.3.jar`。复核基线 SHA-256 为 `189605FD1C07D582DB3F243201F0435A48545F18481FB79847F1965011579407`；不一致时检查版本差异，不假定旧字节码仍适用。
- [ ] 修正“JAR 不存在”、T07 状态及任务/测试数量不一致；D01–D05 分别登记为未关闭。
- [ ] 将 core 编译/测试移到依赖其 class 的 harness 之前，并将 `review/upload-confirmation/RunUploadConfirmation.ps1` 纳入 RunAll；每组单独日志、任意失败非零退出，禁止只保留最后一组输出。
- [ ] 在临时干净副本验证 `powershell -NoProfile -ExecutionPolicy Bypass -File review/RunAll.ps1`，避免依赖旧 build 产物和测试生成配置。
- [ ] 两组历史失败先用独立 HttpClientSmoke 判断环境。正常环境能运行后复测，不把本机 JDK 回环问题当成产品修复任务；若仍阻塞，其他任务继续，最终状态不可写全绿。

退出条件：可追溯的测试清单与基线，工程失败原因已分类。此任务不要求客户端启动。

### N1：先修复频道受众与排队语音边界（D04/D05）

**接口约束：**保留 `requestMove(client, cid, password)`、`observeSelf(client)` 门面；内部补移动请求代际、目标 cid、阶段、失败原因。推荐在现有 TsChannelRoute 内增加 `RESOLVING / MOVING / READY / FAILED / BLOCKED_BY_POLICY` 状态，不另建频道管理器。成功命令结果和 self 频道确认必须分开。

- [ ] 从复核探针转为正式回归：锁定频道 5→管理员移动到 7；断言当前许可撤销、epoch 变化、旧队列无可用许可。
- [ ] 增加错误密码异步回复、权限拒绝、超时、重名/缺失初始目标、连续请求 A→B→迟到 A 回复的测试；失败后推进多个对账周期，不能只断言失败瞬间。
- [ ] 将真实 clientmove 纳入已有命令 tracker，结果绑定创建时 session/client/request；异步错误进入当前移动状态。对未知或无关联回复保持明确未知状态，不猜测成功。
- [ ] 任何已确认的非法频道都撤销发送许可并失效旧队列；FOLLOW 的合法管理员移动可以批准新路由，LOCKED/LEGACY 则等待约束满足或明确用户操作。
- [ ] 初始目标未解析、移动失败或超时后，不允许 tick 自动批准原频道。增加明确“在当前频道继续”的操作入口供 N5 使用，用户选择后才恢复；普通成功跟随不需要额外确认。
- [ ] 让真实 Ts3Connection 队列测试消费生产路由状态，校验 session/来源连接/routeEpoch，不只在 stub 中模拟“epoch 不同即丢弃”。
- [ ] 运行 `review/route-lifecycle/RunRouteLifecycle.ps1` 与 `review/p3-channel/RunCommandTracker.ps1`，保存修复前后结果。

退出条件：语音、频道文字、UI 状态的许可来自同一确认状态；D04/D05 的反例已自动化关闭。已经提交网络的数据不属于可撤回范围。

### N2：重新完成独立前端与采集所有权（D01/D02）

**接口约束：**沿用 SvcFrontendAdapter 与 FrontendLease。`play()` 返回值只表达明确完成的投递动作，不表达真实听感；诊断分别记录尝试、已入队、资源失败。租约绑定新签发的 SessionToken。

- [ ] 核对实际 JAR 的 MicThread、ClientVoicechat、AudioChannel、SoundManager，记录方法描述符、connection/data 依赖、线程及关闭条件，修正 ABI.md 中将已知早退当作未知风险的表述。
- [ ] 建立忠实反例：原生 processSoundPacket 在 null connection 时丢弃；新入口必须真正绕开其网络会话依赖。不得仅修改 stub 让其无条件接收。
- [ ] 先完成独立播放最小适配验证：能创建属于本扩展的资源、接收包、恢复/关闭并沿用原个人音量/输出设置；禁止伪造 SVC 网络 secret。直接拥有 AudioChannel 只是候选路径，必须验证运行期数据要求后再确定。
- [ ] 修复失效 lease 释放→重新获取的顺序，接通 SVC 连接建立/断开与前端交接；借用资源不越权关闭，自有资源停止后明确移交。
- [ ] 增加死亡租约连续恢复、旧恢复任务撞新会话、隔离 mic 与原生 mic 交接、停止期间资源创建失败测试。断言同一时刻唯一有效采集所有者，不能只断言 lease 非空。
- [ ] 运行 `review/standalone-frontend/RunStandaloneFrontend.ps1`、`review/p2-frontend/RunFrontend.ps1`、相关 route-lifecycle/playback-ownership 回归；保留实际 ABI 与替身的差异说明。

退出条件：已知 null-connection 静默丢帧路径被移除，恢复反例通过，独立采集/播放代码实际接入。标记“工程实现完成，客户端音频验收暂缓”，不可标“独立通话已实测”。如具体播放方案尚无源码/ABI 支撑，保持该子项阻塞并继续其他独立任务，不能将占位实现算完成。

### N3：补齐 OFF 对原生 SVC 接收的覆盖（D03）

- [ ] 建立原生 SVC 入站与已有播放队列持续存在时执行 OFF 的反例；保留 TS 入站、麦克风发送同时停止的断言。
- [ ] 用经实际版本核对的 API 事件或必要 Mixin，接入原生 SVC 接收/播放门控；处理已有排队音频和正在使用的输出资源，不能只修改 SvcBackendAdapter 标志。
- [ ] 将 OFF 覆盖独立于用户设置保存；恢复只撤销本扩展覆盖，不把用户此前静音/音量重置。
- [ ] 测试 OFF→SVC、OFF→TS、OFF 期间换服/收到公告、用户原先已静音，以及停止后旧播放任务到达。检查 PAUSED 不隐含切至 SVC。
- [ ] 将回归接入 RunAll；记录实际 hook 的 ABI 和覆盖范围。真实设备是否有残余缓冲声音留待客户端验收，不宣称物理设备瞬间静音。

退出条件：原生 SVC 与 TS 的收发控制流都消费 OFF 状态，用户设置保持。

### N4：补 T09 书签与原界面入口

**接口约束：**所有按钮经 TsVoiceManager/协调器现有入口；新增 VoiceStatusModel 只读取统一快照，至少包含选择来源、后端/传输、连接阶段、实际频道、锁定状态、发送许可及失败原因。

- [ ] 扩展书签 schema：nickname、identityRef、channelPolicy；保留现有字段和凭据引用，旧数据备份并原子迁移。默认频道不等于锁定；身份私钥不写入书签或日志。
- [ ] autoConnect 仅表示候选资格；用一个明确默认自动书签或本服绑定选中目标，禁止遍历多个书签同时连接。
- [ ] 新增书签列表/编辑页并接入原 SVC 的 TS 入口，保留原设置与增强页；新增、编辑、删除、连接/重连形成实际调用链。
- [ ] 显示三个不同操作“断开 TeamSpeak / 切换至原版 SVC / 关闭全部语音”，显著展示“麦克风目标：TS / SVC / 未发送”及原因。删除正在使用的书签不得静默换目标。
- [ ] 完成 `review/gui-integration/GuiFlowTest.java`、`RunGuiIntegration.ps1`：生产 action→manager→模拟协议确认→状态模型；覆盖迁移、独立凭据、无 SVC 会话和过期动作。测试 action 不能是另一套测试专用业务实现。

退出条件：界面代码与 action 已接通且可构建，控制流回归通过。布局、可点击性、真实快捷键、无 SVC 时实际屏幕能否打开记录为暂缓，不要求截图。

### N5：补 T10 频道页与用户控制

- [ ] 新增 TsChannelsScreen，读取现有频道快照；网络回调只发布状态，widget 更新归客户端线程。
- [ ] 加入/创建频道使用 N1 关联请求；创建成功不代表移动成功，命令成功也不代表 self 频道已经确认。
- [ ] 提供原生跟随/明确锁定、失败重试与明确接受当前频道的操作，展示实际频道和发送原因，禁止假成功。
- [ ] 用户列表以 ParticipantKey 对应稳定播放 UUID，可信 MC 身份升级仅影响展示，不重置个人音量，不覆盖原生 SVC 条目。
- [ ] GUI action 测试覆盖管理员移动、错误密码、权限、同名频道、创建成功移动失败、关闭页面后迟到回复、旧会话回复；使用 GuiFlowTest 与真实路由集成。

退出条件：频道与用户操作控制流闭合，界面真机操作暂缓。不要把 T10 的未验证界面问题转成永久阻断合法管理员移动的规则。

### N6：补 T12 公告与实际选择策略接线

**接口约束：**复用 CapabilityWire、CapabilityDiscovery 现有 begin/nonce/context/TTL 语义；新增 CapabilitiesPayloads 只负责 Fabric 外层 codec/注册。准备同一 JAR 公共注册归属时要与 M7 衔接，不重复注册同 ID。

- [ ] 接通客户端 hello 发送、响应接收、主线程消费、授权及候选选择；只在当前连接支持对应通道时发送。换服失效旧请求，不清除手动 TS/PAUSED/OFF。
- [ ] 两端在分配 byte array 前限制到 CapabilityWire.MAX_FRAME_BYTES，复用现有 wire 与 payload ID；服务端按玩家有界限流。原公告模块仅作过渡测试对端。
- [ ] 将 SelectionPolicy 接入实际 manager 的单一连接/重试循环，候选保留来源、endpoint 与 transport；无公告只淘汰公告候选，不淘汰本地/全局配置。
- [ ] 参数化覆盖：手动 TS；无公告有本地自动目标；无公告有全局目标；无任何 TS 目标但 SVC 可用；无 SVC 手动 TS；禁用自动目标；PAUSED/OFF；过期公告；错 nonce；旧 MC context；重复响应。
- [ ] 将真实失败映射为统一 FailureKind；认证/密码/权限错误不当作 UDP 不通，不静默跨网络切换。没有有效兼容隧道时，强制 TCP 显示不可用；此轮不新增 TCP 方案。
- [ ] 保留既有 2→4→…→60 秒重试基值与计划的 ±20% jitter，注入时钟/随机源测试取消及错误类别；不可重试认证错误给修改入口，不无界重试。
- [ ] 测试真实 codec→receiver handler→发现/授权→策略→manager 选择链。可用无界面 Fabric/协议夹具；若替代真实连接上下文，明确记录尚未验证的运行时边界。运行 `review/p4-discovery/RunFallbackPolicy.ps1` 并扩展统一入口。

退出条件：生产入口不是仅测试可达；选择优先级和取消边界可自动验证。实际客户端与服务器交互验收按用户要求暂缓。

### N7：工程交付与后续入口

- [ ] 从最终 HEAD 在干净副本运行 RunAll 和既有 Gradle build，记录每条退出码、环境、日志与产物哈希；缺失环境对应具体项，不能用旧报告 PASS 填入。
- [ ] 自查五个重点：旧请求撞新会话、已出队旧帧、输入线程死亡、OFF 残余队列、无公告但用户配置 TS。分别引用 N1/N2/N3/N6 的测试，不能以总数量代替覆盖。
- [ ] 输出 STATUS：N0–N7 的实现/工程验证/客户端验收三列。
- [ ] 输出 DELIVERY：提交范围、D01–D05 关闭证据、T09/T10/T12 接线位置、测试结果、未解决事项、回滚影响。
- [ ] 输出 DEFERRED-CLIENT-ACCEPTANCE：保留旧 E01–E15 及相关 U 项原编号、具体步骤、期望结果，统一写“用户主动暂缓”。不再次要求用户现在执行。
- [ ] 逐任务提交并保留配置迁移备份；回滚针对单项提交及依赖，禁止为回滚删除用户书签、凭据或 TS 身份。

退出条件：所有已知确定缺陷有修复与对应自动化证据，未完成功能接入生产，工程测试与构建通过，客户端待验收事项完整保留。仅“真机暂缓”不阻塞本轮结束；尚有已知逻辑缺陷、缺失接线或未解决工程失败则不得写整轮完成。

## 5. 此后如何推进

| 后续工作 | 是否需要现在的客户端验收先通过 |
|---|---|
| M7 入口分离、依赖与 Mixin 环境隔离、公告并入 teamspeak-svc、单 JAR 打包 | 不需要；N7 工程条件通过即可进入 |
| M7 专用服务端无 SVC / 有 SVC 启动及配置生命周期验证 | 不需要客户端；环境具备时实际运行，不因客户端暂缓一并跳过 |
| M7 客户端安装、集成服务器、界面和双向音频完整矩阵 | 暂缓；继续记录未验证，不宣称整个 M7 已验收 |
| M9/P6、M8/P5 TCP 工程规划 | 可沿既有路线准备；本任务不授权把这些实现塞入 N0–N7 |
| 最终对外稳定发布与完整产品验收 | 仍需补齐客户端与真实通信证据；本次暂缓不等于免除 |

进入 M7 时使用 [统一发布任务书](2026-10-29-m7-unified-release-plan.md)，将其“上一轮全部验收通过”的开工前提替换为本文的 N7 工程门槛，其他统一发布要求保留。M7 仍须由 teamspeak-svc 自身交付可选服务端能力，不引入独立必装 Bridge。保持同一候选 JAR 哈希供后续两端验证；客户端矩阵留到恢复验收时完成。

本轮指导只安排实施工作，不代表当前代码已经符合这些退出条件。本会话未实施上述代码修改。
