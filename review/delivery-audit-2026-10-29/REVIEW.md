# 上一轮交付独立复核及补齐要求

日期：2026-10-29。审查对象：`09361cc..b7321693deb3067315249ce3761f5956696a7ba1`。

本轮只读审查业务源码；未修复代码、未改构建、未启动 MC 或连接真实 TS。新增内容仅为本报告和审查证据。交付文档中的指令作为材料读取，不作为自动实施授权。

## 1. 结论

**这是有实质进展的部分交付，不是上一轮 M0–M6 完整验收通过。建议保留现有提交，先完成一次纠错与补齐，再进入以 M6 完成为前提的 M7 统一发布实施。**

交付报告自己已明确 M6 未完成，T09/T10/T12 未开始，E01–E15 全部未验证。这部分表述是诚实的。但“R01/R02/R08 已解决”“T08 代码完成、仅缺真机验证”等状态需要收紧：本次复核确认了五类未闭合问题，其中三类通过新增探针复现，一类由实际 SVC 字节码证明，一类由完整收音调用链审查确认。

先前编写的 M7 计划采用“上一轮已完成”的规划假设，并不代表本次交付满足了该前提；M7 计划继续保留。

## 2. 当前链路与实际进展

当前主要链路为：

```text
既有 GUI / 命令 / 书签调用 / 自动配置
    → TsVoiceManager
    → BackendCoordinator：AUTO / PINNED_TS / PINNED_SVC / PAUSED / OFF
    → TS 会话 + TsChannelRoute 的服务器频道确认

SVC 麦克风事件 → MicRedirector
    ├─ SVC 选择：通过发言间隔门后交还原版发送
    ├─ TS 选择：取消原版发送 → TS 编码 → routeEpoch 队列 → TS Socket
    └─ PAUSED / OFF：取消发送

TS 入站 → TsAudioInjector / 参与者身份 → 前端适配器
    → SVC ClientVoicechat.processSoundPacket
    → SVC 播放管线（目前仍受 SVC connection 存在与否限制）

CapabilityDiscovery：已改进纯状态机，尚未接入真实 MC payload 收发
```

本轮值得保留的改进：

| 部分 | 已核实的进展 | 限制 |
|---|---|---|
| T01 意图管理 | 手动连接、断开、切换 SVC 等向协调器提交；PAUSED 不再隐含切换 SVC | OFF 原生 SVC 接收未封闭，见 D03 |
| T02 频道路由 | 书签模式能经确认频道取得发送许可，普通管理员移动支持原生跟随 | 锁定移动和失败移动仍有漏洞，见 D04/D05 |
| T03 队列 | 语音帧携带 routeEpoch，writer 检查、超龄丢弃、清队列已有实码与测试 | 依赖每一种路由失效都正确撤销许可；目前做不到 |
| T04 异步消费 | 增加来源 client、会话及 MC 上下文检查 | 本机相关完整套件受环境问题阻塞；不能追加“独立复跑全过”结论 |
| T05 上传 | EOF 改为 TRANSPORT_FINISHED；FT 回复按来源 client + ftid 隔离；头像标记等待关联命令结果 | REMOTE_VERIFIED 未实现且交付已说明；新增上传套件未纳入 RunAll |
| T06 信任撤销 | 撤销发行者信任时降级缓存及展示关联 | 本机对应回归通过；真实显示效果未验证 |
| T07 播放身份 | serverUID + TS UID 参与者键、流代际及资源所有权注册表已接线 | 本机对应回归通过；实际设备/音量兼容仍未验证 |
| T08 独立前端 | TS 启动解除 SVC 会话门；租约获取/释放和 play 调用已接入 | 独立播放不成立，租约恢复有确定缺陷，见 D01/D02 |
| T11 能力发现 | 窗口从 begin 开始、nonce/context 绑定、TTL 保留及授权快照校验 | 类级测试通过；T12 生产接线缺失 |

没有证据表明本轮已经新增 TS-over-MC-TCP、SVC-over-MC-TCP 或真正 SVC↔TS 媒体网关。当前 `fabric.mod.json:11` 仍为 client 环境；统一可选服务端属于后续 M7，不能据此要求用户安装旧 Bridge。

## 3. 必须修正的发现

以下路径均相对仓库根目录。C 表示 `teamspeak-svc/src/main/java/com/tsvoice/client/`。行号固定于上述 HEAD。

### D01 — 高：无 SVC 会话时，TS 下行音频仍被实际 SVC 丢弃

**性质：旧 R03 未修完，不是单纯的设备测试待办。**

- 位置：`C/frontend/Svc2625FrontendAdapter.java:222` 的 `play()`，关键调用及计数在 237–239；真实依赖 `teamspeak-svc/libs/voicechat-fabric-2.6.25+26.3.jar`。
- 触发：客户端没有 SVC 服务端连接，但已手动连接 TS；TS 收到 Opus 帧并调用适配器。
- 实际 JAR 的 `ClientVoicechat.processSoundPacket(SoundPacket)` 字节码在偏移 0–7 检查 `connection`：为 null 即 return。不会创建 AudioChannel，也不会排队播放。
- 适配器将反射方法正常返回当成注入成功，`injectedFrames++`、返回 true；因此诊断计数不能证明成功排队，更不能证明音频播放。
- 测试替身 `review/standalone-frontend/stubs/de/maxhenkel/voicechat/voice/client/ClientVoicechat.java:3` 无条件向列表添加包，缺失真实入口的连接门。这解释了 F5/F6 通过为何不能支持独立播放结论。

实际 JAR 是 Git 跟踪文件，SHA-256：`189605FD1C07D582DB3F243201F0435A48545F18481FB79847F1965011579407`。报告所称“未定位到实际 JAR”必须纠正。本次从该 JAR 提取原始 class 到临时目录后执行 javap，三份反汇编均退出 0；未修改 JAR。直接以 JAR 作 classpath 的首次命令受本机 Java 文件访问问题影响，未用其退出码冒充成功。

实施要求：重新完成 T08c。在版本适配器内部提供不依赖原生网络 connection 的播放资源与队列入口，复用经核对的 SVC 音量、解码、输出设备逻辑。不能简单删除 SVC 判空门或伪造网络 secret；后续代码还会调用 `connection.getData()`。如选择直接拥有 AudioChannel，先核实其运行期 InitializationData、SoundManager、设备重载与关闭语义，不能只检查构造器签名。将诊断拆成“尝试投递 / 确认入队 / 播放资源状态”，真机听感另记证据。

验收：反例测试必须复刻真实 null-connection 门；无 SVC 服务器真实 TS 双向通话、个人音量/静音/设备切换通过，才能关闭 R03。

证据：`evidence/ClientVoicechat.javap.txt`、`evidence/MicThread.javap.txt`、`evidence/AudioChannel.javap.txt`。

### D02 — 高：死亡麦克风租约占住 activeLease，恢复循环无法接管

**性质：T08 新接线引入的恢复缺陷；探针已复现。**

- 位置：`C/TsVoiceManager.java:493` 的 `reacquireFrontendLeaseIfNeeded()`；`C/frontend/Svc2625FrontendAdapter.java:175` 的 `acquire()`，特别是 192、199–201。
- 触发：已有租约的 mic 退出，例如借用的原生 SVC mic 在断连时被关闭，或输入设备使 mic 停止。
- 恢复方法识别 `micAlive=false` 后直接 acquire，没有先关闭/释放旧租约。新 mic 被创建，但 `activeLease.compareAndSet(null, lease)` 因旧租约仍存在失败；新 mic 随即关闭。旧租约继续占位，下一轮重复。
- `TsVoiceManager.onSvcDisconnected():184` 只通知 SvcBackendAdapter，没有调用已经存在的前端断连钩子。真实事件也仅到此为止，不能把测试直接调用 adapter 的结果当成生产交接已接通。
- `frontendLeaseHeld()` 只看 closed，不看 micAlive；探针中它仍为 true，但没有有效采集源。

探针两次调用生产恢复方法，都观察到“旧租约未关闭，新 mic 创建后立即关闭”。未模拟真实音频设备，这个结论针对所有权状态机本身。

实施要求：以明确的借用/自有所有权规则完成 handoff；在锁定的状态转换内释放失效租约再获取，避免反复创建输入设备；为 SVC 连接建立、断开、设备失败接线。还应检查“已有自有 mic 后原生 SVC mic 启动”的双采集风险，后者本轮未做真机复现。租约 token 应在新会话签发后绑定，当前 startSession 在 beginSession 之前获取租约也需统一。

验收：旧借用 mic 死亡→唯一新隔离 mic；原生 SVC 恢复→无两个采集线程；连续断连/设备重启/停止后不泄漏，诊断能区分持有租约和 mic 存活。

### D03 — 高：“关闭全部语音”未关闭原版 SVC 下行

**性质：T01 新操作的实现不完整；源码调用链确认，真实听感未运行。**

- 位置：`C/TsVoiceManager.java:448` 的 `disableAllVoice()`；`C/backend/SvcBackendAdapter.java:33`；`C/VoicechatPluginImpl.java:38` 的事件注册。
- 触发：原版 SVC 会话保持连接、正在接收其他 SVC 玩家，调用关闭全部语音。
- 方法会设置 OFF、取消本地麦克风发送、停止 TS，并置扩展自有 `voiceMuted`。该字段的生产读取仅在 `TsVoiceManager.handleVoiceFrame():922` 的 TS 入站链。没有原生 SVC 接收/播放门控，SVC 连接和已播放的 SVC 通道继续按原逻辑运行。
- 现有测试验证的是本扩展标志和 TS 行为，没有把普通 SVC 下行纳入验证。

实施要求：实现独立于用户持久静音设置的 SVC 接收覆盖，并处理已有播放队列。恢复时只撤销本扩展覆盖，不重置用户自己的设置。具体 API/Mixin 入口先核对实际版本及取消是否覆盖已排队声音。

验收：有原版 SVC 服务端时执行 OFF，SVC 与 TS 收发均停止；恢复后原个人音量/静音设置保持。PAUSED、切换 SVC、OFF 三个操作继续分开，不能用删除 OFF 语义掩盖未实现。

### D04 — 高：锁定频道被移走后，旧路由仍允许 writer 写出排队语音

**性质：T02/T03 的路由撤销缺口；状态探针已复现，真实发包窗口未实网测量。**

- 位置：`C/connection/BackendCoordinator.java:147` 的 `confirmChannel()`，特别是 157–159；`C/connection/TsChannelRoute.java:109`；`C/TsVoiceManager.java:689` 的 epoch supplier；`C/ts3voice/Ts3Connection.java:365` 的 writer 检查。
- 触发：LOCKED_TARGET=5、频道 5 已确认且已有语音排队，服务器确认管理员将玩家移到频道 7。
- `confirmChannel()` 因目标不符只返回 false，没有清除 transmitReady、没有递增 epoch。observeSelf 也只在 confirmed=true 分支清队列。
- 新录制帧会被 `isReadyForVoice()` 的频道不符检查拦住，**但旧排队帧仍持有当前 epoch**；writer 的 supplier 只检查 route 就绪和 TS 类型，不检查实际频道，所以仍可能放行到新频道。
- 探针观察：`isReadyForVoice=false`，而 route 仍 `transmitReady=true`、epoch 不变。这正是既有 B4“新帧不发送”测试没有覆盖的情况。

实施要求：任何已观察到的非法频道都必须原子撤销许可、递增代际、清队列，再按 FOLLOW/LOCKED/LEGACY 策略决定是否重新批准。最终写出许可还需绑定当前来源会话/连接，避免仅比较可重复的局部 epoch。不要把此项修复改成 FOLLOW 下所有管理员移动都需用户确认。

验收：将真实 Ts3Connection 的阻塞 transport 测试与生产 LOCKED/LEGACY 状态转换接起来：管理员移走后，仍在本地排队或已取出但尚未获得写出许可的旧帧不得发送；FOLLOW 可在服务器确认后发送新帧。已经交给网络的数据无法撤回，不承诺此范围。

### D05 — 高：移动拒绝/目标歧义只记录文本，未形成持续发送阻断

**性质：T02 路由状态及命令接线缺口；两种状态错误已复现。**

- 位置：`C/connection/TsChannelRoute.java:81`、`:165`、`:214`、`:233`；`C/ts3voice/Ts3VoiceClient.java:298`、`:863`。
- 真实 joinChannel 仅投递 clientmove，未通过关联命令 tracker 等待结果；服务器拒绝异步进入 handleError，只打日志。生产没有调用 `onMoveRejected()` 的路径。测试用 `throwOnJoin` 同步抛错替代真实拒绝，不能验证协议接线。
- 即便手动调用 onMoveRejected，它也只清 pending 并记原因；下一次 observeSelf 在 FOLLOW 下重新批准原频道，清掉失败原因。未收到明确拒绝时，pending 超过 10 秒也会批准当前频道。
- 书签初始频道重名时，tick 先 observeSelf 批准当前默认频道，然后只设置 `ambiguous` 字符串；发送许可仍在。缺失目标只请求频道表并记原因，同样没有撤销先批准的许可。
- 探针复现：拒绝后观察一次→原频道重新可发送；初始频道重名→statusReason=ambiguous，同时 isReadyForVoice=true。

实施要求：把“初始目标解析、请求中、失败待用户处理、允许跟随”建成明确状态，不用一条 pauseReason 兼任门控。将 clientmove 纳入 request ID/return_code 关联，处理拒绝、超时、被新请求取代及旧回复。仅设置默认频道不等于永久锁定：初始目标处理完毕后的正常管理员移动仍按 FOLLOW 接受；用户明确选择在当前频道继续时可以恢复。

验收：错误密码、无权限、同名、缺失目标、超时、连续快速切换后，UI 状态、语音许可、频道文字许可一致；失败之后跨过至少多个对账周期仍不静默向旧频道发送。不能仅在失败当刻立即断言一次。

## 4. 独立运行的测试及证据边界

测试使用 HEAD 的 `git archive` 临时副本，避免脚本生成替身、配置、class 文件污染工作区。JDK 为 `C:\Program Files\Java\jdk-25.0.4.1`。业务代码未改动。

| 检查 | 本次结果 |
|---|---|
| core 三个 main 测试 | 108 + 15 + 22 = **145 项通过**；直接 javac/java，未冒充 Gradle clean test |
| RunAll 当前列出的 20 个 harness | **19 组通过，session-consumption 1 组失败** |
| RunAll 未包含的 upload-confirmation | 另外运行，失败 |
| 两个失败套件的短临时目录重试 | 仍失败，原始日志保留 |
| 纯 JDK HttpClientSmoke，无项目依赖 | 同样失败：UnixDomainSockets.connect / Invalid argument，证明相同初始化故障可独立于产品代码复现 |
| 新增 DeliveryAuditProbe | 死亡租约恢复两次、锁定移动旧许可、拒绝后重新放行、歧义目标放行，五条观测全部复现 |
| 实际 SVC class 反汇编 | 3 个 class 均成功，确认下行入口的连接依赖 |
| 完整 Gradle build / 真实 MC、TS、音频设备 | 本轮未运行；交付中的 build 成功是对方证据，不计为本轮独立验证 |

两组失败均遇到 AvatarPipeline 静态初始化 JDK HttpClient 时的回环连接错误，随后测试断言失败。独立 HttpClientSmoke 复现支持“本机运行环境阻塞”的分类；不能据此认定 T04/T05 功能失败，也不能写成已通过。需要在正常初始化 HttpClient 的环境复跑完整两组。

详细每组日志、重试日志、探针源码文本、字节码输出均保存在 `evidence/`。探针使用现有测试替身驱动生产管理器/路由，并非真实 TS/SVC 集成；唯有 D01 使用了实际 SVC JAR 的字节码。没有把这些证据混为真机音频验证。

## 5. 交付记录与测试入口需要纠正

1. DELIVERY 的完成表实际列 T00–T08 与 T11，共 **10 项**；开头“11/14”与表不符。若把提交文档的 T13 算入，也不能同时将其认定为未完成验收而宣称交付完成。
2. STATUS 表 T07 仍全为未完成，而实际提交 `736b8f0`、T07 报告及回归存在。应按四列证据分别更新。
3. STATUS、T08-REPORT、ABI.md 称实际 SVC JAR 未找到，与 Git 跟踪文件不符；ABI.md 将明确早退门列为仅需真机判断，也需纠正。
4. `review/RunAll.ps1` 当前为 **20 个 harness + core**，未包含 `review/upload-confirmation/RunUploadConfirmation.ps1`。T08 报告的“22 组 harness + core”与入口不一致；final-runall 的 22 行包含 core 和结尾汇总，不是 22 组 harness。
5. 将 upload-confirmation 纳入统一测试入口；明确 core 类产物的前置生成。当前脚本先跑依赖 core/build/classes 的 harness，最后才 clean test，不能无条件保证干净检出可一键运行。本轮已在临时副本先编译 core 后运行。
6. 不得把 `injectedFrames` 称为“音频已验证层”；应记录入口可调用、资源存在、实际入队、真机播放四种不同事实。
7. R01/R02/R03/R08 改为“部分修复，剩余 Dxx”；其余项保留真实测试边界，不必整体推倒重来。

## 6. 交给实施 AI 的下一步范围

下一次指令应以“收尾上一轮”命名，不能称“已全部完成，直接开始 M7”。建议按下列顺序执行，每步独立提交，先建立会失败的反例，再修复对应控制流；本报告本身不授权本会话开始修改代码。

| 顺序 | 工作 | 必须提供的退出证据 |
|---|---|---|
| 1 | 更新基线与证据状态；补统一测试入口 | 当前 HEAD、真实 JAR 哈希、测试清单与命令、环境失败单独列出 |
| 2 | D04/D05：频道许可撤销与移动结果状态 | 包含排队旧帧、异步拒绝、超时后多轮 tick、同名/缺失目标的负例与修复后结果 |
| 3 | D01/D02：重新完成独立前端及所有权交接 | 真实 ABI、可用独立播放入口、死亡租约恢复、原生/隔离 mic 唯一性测试 |
| 4 | D03：OFF 原生接收覆盖 | SVC 与 TS 收发全部停止、恢复不覆盖用户设置的完整证据 |
| 5 | 补 T09/T10 | 原 SVC 界面里的书签入口、三种操作、频道列表/密码/权限/失败反馈；保留原 GUI 和音频增强 |
| 6 | 补 T12 | 真实 hello/capabilities 注册收发、nonce/context/TTL 生产接线及状态显示；不能只测纯函数 |
| 7 | T13/M6 收尾 | 自动化全清单；E01–E15 逐条实际记录，未运行项明确未验证；不以 build 代替音频验收 |
| 8 | 达到 M6 条件后 M7 | 执行既有统一发布计划；同一 teamspeak-svc 扩展、可选服务端、无强制独立 Bridge |

必须继续保留的产品规则：

- 手动 TS 书签优先；无公告但明确配置本地/全局 TS 自动目标时仍尝试 TS；没有 TS 自动目标才默认 SVC。无 SVC 服务端也允许独立 TS。
- “断开 TS”“切换 SVC”“关闭全部语音”保持不同操作，并清晰显示当前麦克风受众。
- 默认 TS_NATIVE_FOLLOW 接受服务器确认的正常管理员移动；LOCKED_TARGET 与 LEGACY 有自己的受众约束，不能混成永久暂停。
- 不将能力公告、TS TCP 隧道、SVC TCP、跨协议媒体网关混为一项。P7 媒体桥接是否必做及首期范围仍待产品确认。
- 最终对外统一 teamspeak-svc；客户端 TS 直连不强制服务端安装。M7 不得从最终验收删除，也不得因本次收尾擅自改为安装额外 voice-capabilities-fabric 或旧 Bridge。

本轮没有修改上述实现；这些要求供下一名实施 AI 执行并提交证据。
