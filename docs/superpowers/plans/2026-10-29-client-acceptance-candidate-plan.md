# teamspeak-svc 下一轮：修复收口并交付客户端验收候选

> **实施 agent 直接执行入口。** 用户明确要求：本轮完成后由用户进行客户端验收。本轮按 F0–F7 连续完成修复、最小音乐生产链和验收准备，交付候选后进入验收阶段，**不自动继续 Music-2/3、复杂在线音源、P7 或 WebRTC**。这条结束规则覆盖此前“完成后继续后续功能”的默认安排；长期路线仍保留。

**Goal：**交付可由用户按说明直接安装、连接、操作和记录结果的候选版本，关闭当前确定缺陷；不能只交付接口、测试日志或一段“待真机”的总结。

**Architecture：**保留统一 JAR、可选服务端、现有 TS/SVC 协调器和 SVC 操作入口。修复生产数据与生命周期；音乐独立身份/client/transport/encoder，与玩家语音隔离。

**Tech Stack：**沿用工程当前 Java/MC/Fabric/SVC 版本，不夹带升级；最终交付列出实际验证的完整版本与依赖，而非写“最新版”。

**Spec（必读）：**

1. 本任务书：本轮顺序、范围和客户端验收交接条件。
2. [0072d00 最新复核与证据](../../../review/post-0072d00-2026-10-29/REVIEW-AND-CONTINUATION.md)：仍未关闭的 E2/E4/E5/E7。
3. [已确认的成品介绍](../../product/README-RELEASE-PREVIEW.md)：功能与默认行为。
4. [上一份 E0–E9 实施细节](2026-10-29-next-round-execution.md)：继续复用未完成项的实现方法，不重新做已解决工作；本轮边界以本文为准。

## 1. 本轮范围与基线

参考 HEAD：`0072d00a047c89eaf56ad6614129807c7a2c3b6e`。开工先看实际 HEAD；后续已修复的项按代码/证据关闭，避免重复修改。保护已有未提交文档、测试生成物与用户配置，不 reset/clean，不覆盖无关改动。

必须交付：四模式相关已知缺陷收口、明确单后端收听、可操作的独立客户端音乐 Bot 最小路径、工程回归、统一候选包和客户端验收手册。

本轮不要求用户在开发过程中陪同测试客户端，也不自动启动用户客户端。可执行的无界面测试、真实 codec 测试、隔离 dedicated 启动/reload/关闭验证继续进行；不要自动重启或改动用户现有运行服务器。结束时将客户端项目转为“待用户执行”，不是“通过”。

## 2. 直接执行顺序

| 顺序 | 任务 | 可交接条件 |
|---|---|---|
| F0 | 固定基线、复现现存缺口 | 原始证据、现场和任务状态准确 |
| F1 | SVC UDP→MC 读线程切换与重建 | 内部换态不导致 SVC 接收线程退出 |
| F2 | TS 生产代际/屏障、字节额度、调度背压 | 真正的生产 sender/handler/writer 联通并通过负例 |
| F3 | PAUSED/单后端收听与播放队列 | 未选中的后端不被悄悄恢复 |
| F4 | 音乐取消后最终许可与真实工厂 | 生产连接/编码/发送可用，不只通过测试注入 |
| F5 | 独立 Bot 最小操作、UDP/第二中继会话 | 用户有实际入口完成一次完整播放流程 |
| F6 | 最终工程检查和候选打包 | 源码提交、日志、唯一 JAR 哈希和依赖清单一致 |
| F7 | 客户端验收手册与阶段交接 | 用户可照步骤验收，交接后停止功能扩展 |

每项按“复现负例→最小修复→对应回归→受影响检查→独立提交”推进。原有 Mixin、回包封装、OPEN 关联和 MusicEngine 已解决部分保留回归，不从旧轮次重做。

## F0：建立本轮可追踪基线

- [ ] 记录 HEAD、业务改动与原有未提交内容、Java/Gradle/Fabric/SVC 版本、候选 hash。
- [ ] 使用最新复核 `evidence/` 中的 MusicCancelBoundaryProbe、SvcSwitchReadProbe、PausedReceiveProbe 建立修复后正式测试，保留原复现日志。
- [ ] 整理 `review/continuous-development/STATUS.md`：只留一个当前阶段/下一动作，旧轮次归历史；不能同时写“全部关闭”和列出未完成的同一生产链。
- [ ] 本轮证据统一放 `review/client-acceptance-next/evidence/`，历史日志不覆盖。

## F1：修复 SVC 读线程模式切换

**文件：**`client/backend/SvcMcChannelSocket.java`、`SvcMcChannelClient.java`、SVC 接收/断连/服务端配置处理链，`review/svc-mc-channel/`。

- [ ] 当 read() 已阻塞 UDP receive、内部切 MC 关闭旧 socket 时，识别本对象仍有效且模式已改变的受控唤醒，回到新模式读取；不能把该 SocketException 直接交给上游读取循环。
- [ ] 真正 close、取消、其他网络故障仍按契约唤醒并退出，不能吞掉所有异常或忙循环。实际 SVC 2.6.25 读取线程在外层捕获该异常后会 return，参考复核 javap 证据。
- [ ] 测试同一读线程“UDP 阻塞→MC 宣告→读取 MC 帧”；宣告先/初始化先；关闭与切换并发；MC→UDP 需要的显式连接重建；换服不继承旧宣告/旧包。
- [ ] 保留原生仅 SVC UDP、按玩家混合路由、无 SVC 服务端路径、Extra 唯一所有者检查；未实测的 Extra 版本不扩大兼容声明。

## F2：闭合 TS 中继的生产发送边界

**文件：**`client/TsVoiceManager.java`、`client/ts3voice/{TsTransport,TsMcChannelTransport,Ts3Connection}.java`、协调器/频道路由发布点、`server/relay/{TsRelayRuntime,TsRelaySession}.java`、`TeamspeakSvcMod.java`、relay codec/payload/config 与回归。

- [ ] 生产 DATA 带入队时的真实 routeEpoch/trafficClass，与创建时音乐或玩家 session 绑定，不能仍写 `(session,0,0,datagram)`，不能从可变全局取新 session 给旧帧改身份。
- [ ] 生产路由失效/频道变化实际发送 barrier，按同一会话有序处理；服务端最终 writer 复核当前许可并清旧媒体队列。握手/控制与媒体区分，所有流量仍计费；不得仅在测试中调用 onRouteBarrier。
- [ ] 协商 bytes/s 与 packets/s、突发容量、session/global 队列条数和总字节真正生效；双向收发与控制流量不能绕过，超额有明确反馈。时钟可注入，负例检验拒绝和回填。
- [ ] DNS、socket 建立/读写离开 MC 主线程；工作队列、MC 待提交任务和发送 drain 有界。核对当前 Fabric 发送线程契约，不能一边无限提交一边声称已背压。
- [ ] 保留 E1/E3/E4 已修复的包名、raw/envelope 桥、请求精确关联、接收长度不截断、关闭唤醒；修复中不得重新引入旧缺陷。
- [ ] 测试真实生产 sender→codec→handler→阻塞提交器/UDP writer，覆盖切频道、暂停、关闭、重连、旧请求、队列满和 byte 限额。数据已经提交到网络的不承诺撤回。

## F3：落实已确认的暂停与收听规则

**文件：**`client/backend/SvcBackendAdapter.java`、native receive Mixin、TS 入站、owned playback 清理与 SingleBackendListenTest/OffGate。

- [ ] 默认仅收听选中后端；PAUSED 无活动后端时不因 Optional.empty→SVC 的默认值而恢复原生收听。AUTO 解析期间是否暂缓按明确状态处理，不把“尚未选择”解释成用户选择了 SVC。
- [ ] 断开 TS、切 SVC、OFF 的界面和发送意图继续分开；“PAUSED 不等于 OFF”不能用作允许未经选择的 SVC 收听的理由。
- [ ] 改选时清理无许可队列/资源，迟到包在实际消费前复核；恢复不重置用户个人静音/音量，不影响游戏本身声音。
- [ ] 修正仍断言“PAUSED 放行 SVC”的旧测试，增加双连接同时在位、旧队列残留、换服/公告不覆盖用户意图的回归。

## F4：音乐生产工厂及编码后最终许可

**文件：**`client/music/MusicSession.java`、实际 identity/client/transport/encoder 工厂、音乐 route，`review/music-0/`。

- [ ] 实现生产 connect/startPlayback：真实独立 TS identity、client、transport、确认频道、资源所有权；不能用 ConnectionBundle.connected=true 作为生产连接，createIdentity 不能只是未调用接口。
- [ ] 提供至少一种实际可用的编码器后端，公共 acquireEncoder 不再恒抛异常。核对真实 Opus 的采样率/声道/20ms 帧格式，执行受控真实 encode→decode，不能只验证假编码字节。保留共享核心不依赖 Minecraft 客户端/私有 SVC 的边界。
- [ ] 本轮实现客户端 Bot；未来无 SVC 的服务端音乐托管仍需独立可用后端，不能因只接客户端 API 就宣称已支持托管。若 headless 环境验证受限，区分限制和已完成证据，不将实体设备验收作为编码测试的笼统阻塞理由。
- [ ] 编码前捕获音乐 session、playback generation、encoder、sender 和 route；编码后、网络入队前再复核。cancel/pause/replace/被移出房间/OFF 在编码期间发生，旧帧不得调用新 sender 或继续获得许可。
- [ ] 新增阻塞 encoder 反例，断言 cancel 返回后释放编码器不会产生新发送；连接状态、频道就绪和停止资源有真实生产检查，不能仅测内存 state。
- [ ] 音乐绝不使用玩家 identity/client/socket/encoder，也不调用玩家全局 endSession/currentClient；音乐失败不使玩家通话断开。

## F5：给用户可操作的独立 Bot 最小体验

- [ ] 至少交付独立 Bot 连接、授权本地文件添加、开始、暂停、继续、下一首、停止、队列/当前曲目/点歌者、个人音乐音量和静音的实际入口。命令可辅助诊断；必须有可发现的游戏内音乐入口，不把全部界面推到验收以后。
- [ ] 按实际实现提供 `/tsvoice music` help/命令及 UI 说明；不能让验收文档引用不存在的命令或按钮。不要求本轮制作完整搜索/收藏/历史页面。
- [ ] UDP Bot 使用自己的连接；MC 通道 Bot 使用受控第二会话，配置例如 `relay.musicBotEnabled=false`，启用后仍需目标/身份/权限和单独额度。PLAYER_VOICE 与 MUSIC_BOT 不覆盖，不借 SVC 音乐/桥接名义开放权限。
- [ ] 独立房间绑定 serverUID/cid，管理员移动 Bot 离开授权房间即停；本机退出/断开 TS/切 SVC/OFF 默认停本机 Bot；不得停止他人或服务端 Bot。
- [ ] 多人/跨服控制若声称支持，必须具备可信 sender 元数据、room/owner/requestId/hostEpoch、限额和幂等。若这条可信链本轮尚不能完成，则默认不开放远程控制，并在功能表/验收清单明确“尚未支持”；不能用昵称或自报 UID 临时授权，也不能阻止用户验收基础通话和本地主持音乐。
- [ ] 没有真实编码后端、独立连接或操作入口的音乐，不得标为“已具备验收条件”。仅外部真实 TS/听感缺条件时记录待用户验证，不能把缺实现包装成环境限制。

## F6：生成唯一、可追溯的验收候选

- [ ] 所有本轮业务实现已提交；记录准确终点 HEAD，保留不相关用户文档/生成物，不谎称整个工作区干净。
- [ ] 在最终提交的干净副本执行 RunAll、mod build 和逐条 Mixin FQN/可选依赖/内容检查；汇总数量由实际清单生成，修正“26 条但结尾硬编码 23”的文字。
- [ ] 对 dedicated 做“不装旧 Bridge”的最小矩阵：Fabric 依赖+teamspeak-svc，无 SVC；再加 SVC。记录启动、功能开关、reload、关闭、socket/线程回收。隔离测试实例，不擅自操作用户现有运行服务。
- [ ] 将最终同一个 JAR 复制到 `dist/client-acceptance/<提交短哈希>/`，生成 SHA-256 与大小；客户端和服务端验收使用同一文件。复制打包不是发布到外部平台。
- [ ] 新增 `review/client-acceptance-next/MANIFEST.md`：HEAD、JAR 路径/hash/大小、构建命令与退出码、实际 Java/Loader/Fabric API/SVC 版本、客户端/服务端依赖、已支持/不支持/未验证功能、回滚步骤。
- [ ] 提供四模式最小配置样例与准备步骤：默认关闭可选中继/音乐；管理员允许的 TS targetId 与客户端选择一致；测试配置不包含私钥、密码、票据或用户真实身份材料。不要求用户猜字段、改源码或额外安装旧 Bridge。
- [ ] 若工程检查失败，修复或具体分类，不能用旧日志填通过。源码缺陷/缺实现不能宣称候选就绪；纯外部环境限制逐项记录，并明确哪些客户端场景仍可验收。

## F7：交付客户端验收手册并冻结本轮范围

新增：

- `review/client-acceptance-next/CLIENT-ACCEPTANCE.md`：用户实际照做的步骤。
- `review/client-acceptance-next/RESULTS.md`：待填写的结果记录，初始状态均“待用户执行”。
- `review/client-acceptance-next/DELIVERY.md`：实际修复、残留限制、测试证据、候选与本轮结束说明。

验收手册按以下顺序编排，每项必须列：**编号、安装/配置前提、精确入口与动作、预期现象、失败判据、需要保存的日志、清理/恢复步骤**。沿用原 E/U 编号作映射，不直接复制旧版本 hash。

| 顺序 | 用户验收场景 | 主要检查 |
|---|---|---|
| A00 | 独立客户端测试配置启动、打开 SVC/TS/音乐入口 | 无 Mixin/缺类错误，入口可达；依赖版本及候选 hash 与 manifest 一致 |
| A01 / E01 | 独立 MC 测试服不装 SVC/本扩展，客户端 GUI 手动 TS UDP | 双向通话、PTT/激活、状态、音量；不能用加载了 SVC 服务端的集成世界冒充“无 SVC 服”验证 |
| A02 / E02 | 普通仅 SVC 服务端、没有任何 TS 自动目标 | 原生 UDP、附近/群组、设备/按键不退化 |
| A03 | 服务器启用 TS MC 通道，用户明确强制此模式 | 真实 MC 网络、TS 握手/通话，诊断可确认没有静默改走 UDP；不必要求用户改系统防火墙来证明模式 |
| A04 / U04 | SVC MC 通道及 UDP 玩家混合 | 两种传输按玩家使用，配置/初始化先后与重连稳定；Extra 组合仅列已支持版本 |
| A05 / E14 | 讲话中断开 TS、切 SVC、OFF、恢复 | 不暗中换麦克风受众；PAUSED 不恢复另一边收听；OFF 保留个人偏好及游戏声音 |
| A06 / E07/E15 | 书签、独立凭据/身份、频道密码、加入/创建、跟随/锁定、用户音量 | 功能可操作、反馈真实；管理员移动后规则正确；失败不向错误频道发送 |
| A07 / E08/E13 | 无公告/授权/拒绝/过期公告、手动/本地/全局目标 | 选择与已确认优先级一致，手动目标不被覆盖 |
| A08 | 本地主持独立 Bot，先 UDP，再开启许可后的第二 MC relay session | 玩家讲话不中断；本地文件可播放；独立身份/会话及队列可观察 |
| A09 | 音乐暂停/继续/下一首/停止、编码中取消、移出房间、离开语音 | 无旧音乐残留/隐蔽发送，不误停他人 Bot；个人音乐音量只影响本地 |
| A10 / E03 | 两 MC 测试服玩家和原生 TS 用户同频道 | 跨服通话/听音乐；不把它记录成普通 SVC↔TS 媒体桥接已支持 |
| A11 / E06/E11 | 密码/权限/封禁/目标拒绝、设备切换与短断网 | 有具体错误、可恢复、无错误自动回退或重复采集 |
| A12 / E12 | ≥30 分钟、反复切换/重连/退出 | 无明显堆积、死锁、残音、重复 Bot；记录资源和日志基线 |

A00–A05 是首轮冒烟优先级；基本安装/收发不通过就先记录并反馈，避免用户被迫完整跑完后续。所有不支持功能提前标 N/A 并说明原因，未操作不能填写 PASS。

旧 `review/engineering-closeout/DEFERRED-CLIENT-ACCEPTANCE.md` 保留历史，在本轮交付中说明由新清单承接；客户端真实结果仍由用户执行后填写。此前 dedicated 已运行部分可引用准确证据，不把它们重新混为客户端暂缓。

## 3. 本轮结束条件

1. 已知确定缺陷关闭，有反例转正式测试及生产调用链证据；不存在仅空接口/测试注入却声称可用的验收功能。
2. 工程检查、产物与功能状态准确；可操作音乐最小链已交付，未支持项提前列明。
3. 候选 JAR、依赖/配置、验收手册和结果记录齐全；用户无需反复询问“用哪个包、怎么启用、去哪里点”。
4. 更新 `review/continuous-development/STATUS.md` 为 **“候选已交付，等待用户客户端验收”**，列出候选 hash、手册及剩余限制；不要把任务写成最终产品已验收。
5. **到此交接并停止新增功能。** Music-2/3、在线曲库、P7、网页/手机留后续；待用户验收反馈后优先修验收缺陷，再决定功能扩展。这个有意设置的验收停点不违背长期项目持续推进目标。

如果仍有源码/接线阻塞，不得为了按时交付而勾“候选就绪”；报告具体未完成项和已经可测试的范围。任何外部上传、正式发版或操作用户运行服务器都不属于本任务书默认交付动作。
