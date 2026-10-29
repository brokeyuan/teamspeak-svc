# N0–N7 / M7 交付复核与后续推进依据

日期：2026-10-29。基线 `b732169`，复核 HEAD `f32416c8a098990b54d277066be2bc5412418d79`。只读业务源码；新增本报告、测试证据及下一轮任务书，没有实现代码修改。客户端验收继续暂缓。

## 结论

本轮有实质性的生产实现：独立播放已改为自有 AudioChannel，死亡租约先释放再获取，OFF 增加原生接收 Mixin，频道失败增加持续状态，书签/频道页面和公告接收链已存在，统一 JAR 的公共入口及可选服务端也已落地。无需重开全部架构设计或回退整轮。

**尚不宜写成所有工程缺口均已关闭。** 本次发现 routeEpoch 仍能跨会话重复，集成服务器 reload 会绕过默认关闭；另有多项 UI、身份/凭据接线仍是已承诺但未交付的功能。下一轮先关闭这些确定缺口，随后连续进入 P6/P5；客户端暂缓不是停工条件。

## 独立核对与测试边界

- 工作区候选 JAR 哈希与交付一致：`2E8BFE430DE304138E240973455CDE084AB45363D76F3C178A536D2CF64ACE73`。
- 已读取对方 `m7-clean-copy-runall.log` 的 core + 23 harness PASS，这是对方证据。
- 本次另用 HEAD 的 git archive 临时副本执行 RunAll。首次结果为非零：core Gradle 和 M7 构建被本机 `Unable to establish loopback connection` 阻塞，core 缺产物引发后续五组编译失败，另有两组 HttpClient 初始化失败。
- 随后直接 javac/java 编译并运行 core，108 + 15 + 22 = **145 项通过**；重跑五组依赖 core 的套件均通过。
- M7 配置/公告测试已通过；向临时副本复制上述已核对哈希的候选 JAR 后，RunM7 的内容/元数据检查通过。**这不是本次干净构建成功的证据。**
- 综合不同次执行，23 组 harness 中 **21 组通过**，session-consumption、upload-confirmation 仍受本机 HttpClient 初始化问题阻塞。本次没有得到一次完整 RunAll 全绿，也未重现对方完整构建通过；不能因此直接断言对方记录造假或产品回归。
- 新增三个无网络状态/输入探针，均复现下列 P1/P2 问题。未运行游戏客户端、实际音频设备或真实 TS 通话，也未启动 dedicated server。

原始输出、补跑记录及探针源码文本见 `evidence/`。

## R21 / P1：全局 routeEpoch 分配不一致，已复现跨会话碰撞

位置：`teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java:175`。

虽然 begin/end/invalidate 使用全局 `nextRouteEpoch()`，`confirmChannel()` 的正常频道变化仍使用 `route.routeEpoch() + 1`，没有推进全局 AtomicLong。多次管理员移动后，全局计数器落后于当前路由值；结束/创建会话时会倒退和复用。

直接调用生产协调器的探针输出：

```text
initial=2, old ready=4, ended=3, new ready=4
```

新旧会话均可持有 ready epoch=4。writer supplier 仍只依据全局 route 就绪与 epoch，未绑定来源 client/session，因此“仅凭全局 epoch 已排除跨会话碰撞”的结论不成立。这里已确认状态碰撞；真实旧 writer 发包的调度窗口本轮未实网复现。

修复：所有路由代际分配统一使用单一计数器；不变频道观察不必递增，任何失效/频道变化/会话切换必须保持严格前进。最终 writer 许可额外校验创建时的会话和来源连接。把连续 FOLLOW 移动→断开→新会话确认，与阻塞 transport 的旧帧复活测试接通。

证据：`evidence/route-epoch-collision-probe.log` 与源码文本。

## R22 / P2：reload 丢失集成服务器开关约束，已复现

位置：`teamspeak-svc/src/main/java/com/tsvoice/server/ServerRuntime.java:75`、`:115`；`ServerCommands.java` reload 调用。

集成门仅启动时调用一次；reload 直接重算 `statusFor(config)`，未保留 dedicated/integrated 环境。配置 `capabilities.enabled=true`、目标有效、`integratedServerEnabled=false` 时：

```text
initial integrated state=DISABLED
after reload state=ACTIVE, integratedServerEnabled=false
```

修复：运行时不可变地保存物理/逻辑服务器环境，并在初始配置、正常重载、失败恢复等所有状态计算时应用该门。测试集成默认关闭、显式开启后重载、再次关闭、无效配置恢复以及 dedicated 不受该门影响。

证据：`evidence/integrated-reload-probe.log` 与源码文本。

## R23：公告首次授权入口缺失，不能归为仅待真机

位置：`teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java:185`。

只对旧 `trustedBeaconHost/Port` 匹配者授权；新统一服务端仅发送 capabilities，未授权候选仅记日志，用户缺少正常接受入口。报告已承认未交付，此缺口在 M7 之后仍存在，并不是打开客户端验收就会自动解决。

补齐带来源、目标、能力种类、有效期的授权动作；绑定 context/nonce/实际接收快照，持久信任也不能扩大为同地址任意新能力的授权。明确 UI 撤销、拒绝和过期反馈。无需真实客户端即可完成逻辑与 action 接线测试。

## R24：书签身份与凭据引用仍未实现，编辑会清除引用

位置：`teamspeak-svc/src/main/java/com/tsvoice/client/TsBookmarkEditScreen.java:173`、`:176`；`TsVoiceManager.java:320`、`:913`。

- 保存书签时 `credentialRef`、`identityRef` 均固定写 null，编辑已有非空引用也会清除。
- 连接仍按 endpoint 读取凭据，并固定使用全局 `identity.txt`；identityRef 只是 schema 扩展位。
- 同 host:port 的两个书签不能通过引用保存独立凭据；保存其中一个仍写同一个 endpoint 条目。

这些限制与先前约定的独立书签身份/凭据不符。至少立即保留现有引用；后续完成按引用解析及兼容迁移，不能把“字段存在”标为身份功能完成。

## R25：频道页操作仍不完整，超八条只是其中一项

位置：`teamspeak-svc/src/main/java/com/tsvoice/client/TsChannelsScreen.java:118`、`:140`、`:167`。

- 只有前八条，无分页/滚动。
- 所有加入操作使用当前会话配置里的同一频道密码，没有针对目标频道输入或重试密码入口。
- 创建操作直接生成名字、使用 root 父频道与空密码，未提供先前要求的创建表单；异常被忽略，无法提供可靠错误反馈。
- 用户成员仅作为昵称与短 UUID 的文本展示，没有逐用户音量/静音操作。

补全上述生产操作，并保留创建命令结果、自身实际频道确认、可发送状态的区别。真实渲染可以继续暂缓；功能 action 不应随之延期。

## R26：错误与依赖兼容分类需收紧

- `TsVoiceManager.classifyConnectFailure():414` 对未识别异常一律返回 NETWORK_TIMEOUT，内部错误、连接关闭和 server-full 等因此仍会混成网络超时。未来 TCP 回退会放大错误判断，应先按异常类型和结构化 TS 错误 id 分类，保留 INTERNAL/UNKNOWN 类别，未知错误不自动推断 UDP 不通。
- `bootstrap/RuntimeDependencies.java:48` 的 `startsWith("2.6")` 不等价于已声明的 `>=2.6.20`，也可能接受 2.6.1 或 2.60.x；解析异常还返回 true。应使用明确版本约束并检查 API；内部音频适配仍按实际 ABI 判定。构建只证明编译目标，不证明整个 2.6.x 系列兼容。

以上为确定的源码判断，具体 TS 错误码语义须依真实协议资料/样本核实，本报告不编造数值。

## R27 / P1：畸形公告解码返回 null，生产处理函数直接解引用

位置：`teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java:160`、`:170`。

`CapabilityWire.decode()` 对短帧、错误 magic、非法计数等输入返回 null，并不保证抛出异常。接收函数只有 try/catch，未判空，随后调用 `capabilities.requestNonce()`。用生产函数输入 `new byte[]{1}`，已复现 NullPointerException。Fabric 接收处理在客户端任务中调用该函数，因此这不是纯测试辅助代码的异常；真实运行时如何处理该任务异常本轮未启动游戏确认。

补齐判空，并测试空/短帧、错误 magic、未知 major/kind、非法端口/长度、合法但无候选等输入，保证接收边界不抛异常、不改变已有授权/选择状态。TTL 为零和候选撤销等合法业务语义应按协议单独处理，不能简单全当畸形帧。

证据：`evidence/malformed-announcement-probe.log` 与源码文本。

## 下一步

保留当前提交作为候选基线，将上述项目列为“待修复/待补齐”，继续执行 [下一轮连续任务书](../../docs/superpowers/plans/2026-10-29-post-m7-transport-execution-plan.md)。不要回到等待客户端真机才继续的流程；专用服务端启动验证也不属于已经暂缓的客户端验收。
