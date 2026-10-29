# teamspeak-svc / tsbridge 当前版本架构审查

- **审查日期**：2026-10-27
- **审查对象**：`teamspeak-svc` 0.9.8、`teamspeak&svcbridge`（服务端 tsbridge）0.3.4，MC 26.3 / Java 25。
- **方法**：逐文件阅读两个产品模块全部 Java 源码、构建脚本、资源声明、配置和文档；对照 SVC 上游调用链；独立构建；重跑现有隔离回归；针对协议边界做离线探针。
- **本轮没有修改产品源码**；本报告是建议，不表示下列问题已经修复。

## 1. 结论先行

当前实现已经解决了旧版报告中的不少问题：AEC 已改为同步写回、群组比较已按 UUID、配置写入已有原子替换、部分生命周期已有代际控制，且两个模块均能编译。**不能继续把 `teamspeak-svc/CODE_REVIEW.md` 中针对 0.8.1 的行号和结论直接套到当前版本。**

仍建议**先完成 P0/P1 修复和真实端到端验证，再扩大发布范围**。最重要的不是再增加音频功能，而是：

1. 消除服务端分片/解压分叉及无认证隧道、凭据泄露风险。
2. 修复协议序号回绕、RSA 固定长度编码和重连音频通道生命周期。
3. 把“网络提交”“音频处理”“原生资源销毁”从全局类锁中拆出，明确资源所有权。
4. 抽共享协议模块，并用真正的 `src/test` + CI 固化边界行为。

严重程度说明：**P0 = 发布阻断或核心协议/安全边界；P1 = 高优先级的功能、稳定性或数据隔离；P2 = 应在下一轮重构完成；P3 = 工程与体验改善。**“静态确认”指由当前代码调用链可确定，不等于已在真实游戏或真实 TS 服务器复现。

---

## 2. 范围、覆盖与验证证据

### 2.1 覆盖范围

| 目录 | 本次处理 |
|---|---|
| `teamspeak-svc/` | 53 个 Java 文件、9,373 行，全部逐文件审查；构建、资源、配置、文档同步审查。 |
| `teamspeak&svcbridge/` | 服务端 37 个 Java 文件、8,525 行，全部逐文件审查；构建、资源、配置、文档同步审查。 |
| `.trae/`、`.zcode/` | 历史需求、规格和修复计划；用于核对行为要求，不将任务状态当成验证证据。 |
| `review/` | 旧报告、隔离探针、回归脚本和构建缓存；重跑现行五组测试，生成物不作为产品源码审计。 |
| `testserver/` | 配置、部署产物、运行环境的安全与一致性检查；未读取或输出身份私钥内容。 |
| `tsbridge-standby/` | 旧二进制备份，只作产物分类。 |
| `simple-voice-chat-26.2/` | 上游参考项目，重点阅读与本 mod 交互的音频、状态和网络路径；**不是对 642 个上游 Java 文件的独立全量缺陷审计**。 |
| `voicechat-discord-main/` | 上游参考项目，用于对照桥接设计；**不是独立产品全量审计**。 |

### 2.2 本次实际执行的验证

- 使用 Java 25、Gradle 9.5.1，在两个正确项目目录中执行 `clean build --no-daemon --offline`：**均 BUILD SUCCESSFUL**。
- 使用了已有的 `review/build-fixes/gradle-home` 离线缓存。默认 Gradle 缓存缺少分发包，在线下载在 wrapper 配置的 10 秒超时后失败；这不是源码编译失败。
- 重跑五组现有隔离回归，**51/51 PASS**：频道状态 11、群组映射 10、客户端注入/状态 12、连接/配置 7、服务端 11。
- 这些测试没有覆盖本报告的序号回绕、RSA 高位、QuickLZ 畸形输入、完整 SVC `AudioChannel` 关闭、真实 AEC 时间轴及真实 TS/MC 联调，不能据此宣称全部功能正确。
- 离线协议探针确认：RSA 64 字节最高位为 1 时产生 65 字节结果并覆盖回显区；QuickLZ 可越过声明压缩长度；非曲线 P-256 点可被当前点导入路径接受。
- 未执行会连接外部 TS 的 `Ts3VoiceSelfTest` / `Ts3ChannelTest`，未做真实 NVIDIA/OpenAL 设备验证，未做双端游戏内端到端测试。

---

## 3. P0：优先处理

### P0-01 服务端分片边界语义错误，两份协议实现已经实质性分叉

- **维度**：逻辑 / 重复代码 / 可读性。
- **位置**：`teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/ts3voice/Ts3Connection.java:542-564`；正确方向参照 `teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Connection.java:488-538`。
- **证据**：服务端把 `fragmentedFlag()` 当作“后续还有分片”，但当前客户端和 TSLib 语义是**首片、尾片都有边界标记，中间片没有**。因此服务端会在尾片处继续等待，或只消费尾片；大型压缩命令、频道表和初始成员状态可能缺漏。
- **影响**：TS→MC 的身份、频道和 Ghost 同步不可靠；继续靠“解压失败就丢弃”会掩盖根因。
- **建议**：先按边界状态机修复并添加真实分片向量；随后抽出共享 `ts3voice-core`，禁止用整目录复制同步修复。
- **验收**：单包、2/3 片、乱序尾片、压缩后分片、孤立尾片、`65535→0` 都有自动测试，并验证完整 `channellist/clientlist`。

### P0-02 信标会把已保存的 TS 密码带往另一个 TS 端点

- **维度**：逻辑 / 安全边界 / 使用体验。
- **位置**：`teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java:116-136,359-365`；默认免确认策略见 `TeamspeakSvcClient.java:88-90`。
- **证据**：密码在采用信标地址之前已从每服或全局配置解析；随后与信标提供的任意 `host/port` 组成会话。恶意 MC 服可指定自己控制的 TS 服务器，使客户端把为其他 TS 环境保存的密码用于该端点。当前 `review/client-fixes` 还将“信标继承全局密码”测为预期行为，说明需同步调整设计与测试。
- **建议**：将凭据按规范化 TS endpoint 隔离；`source=server` 不默认继承全局或其他 endpoint 的密码。首次接受信标及 endpoint 变化时明确授权，提供“信任此服务器/仅此一次/关闭信标”选项。
- **验收**：跨 endpoint 不发送旧密码；用户显式授权后才能保存该 endpoint 的凭据；日志和 UI 不显示密码。

### P0-03 TCP 隧道公开监听、无认证、无会话限额

- **维度**：逻辑 / 性能 / 安全边界。
- **位置**：`teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/net/TcpTunnelServer.java:39-50,83-95,109-153`。
- **证据**：`new ServerSocket(port)` 绑定所有网卡；每个陌生 TCP 连接可创建 UDP socket 和两个线程，并向配置的 TS 地址转发任意 UDP；无鉴权、来源限制、全局/单 IP 限额或速率限制。
- **建议**：默认仅 loopback，公开部署必须显式配置监听地址；隧道首帧增加带时效和随机数的鉴权，必要时加传输加密；有界线程池、会话数/速率限制、空闲回收和指标。未修复前不要在公网启用。
- **验收**：未认证连接不能转发；超限连接有明确拒绝；stop 能关闭全部会话；鉴权失败不泄露有效凭据信息。

### P0-04 测试工作区含真实敏感配置，发布制品与同名 JAR 不一致

- **维度**：工程化 / 安全 / 使用体验。
- **位置**：`testserver/README.md:13`、`testserver/config/tsbridge.json:6-7`、`testserver/server.properties:37`、`testserver/config/tsbridge/identity.txt` 及 `identities/`。
- **证据**：存在非空查询凭据、管理端秘密及身份材料。工作区未发现 `.gitignore`，测试服、日志、身份和部署 JAR 没有隔离。`testserver/mods/tsbridge-0.3.4+26.3.jar` 与本次源码构建同名但内容不同，缺少当前隧道会话修复类。
- **建议**：不在报告中复制秘密；若曾共享或提交，轮换查询密码和管理端秘密并撤销旧身份。用脱敏模板替代真实配置，加入忽略规则和部署 SHA-256 清单；重新干净构建、部署、核对哈希。
- **验收**：发布压缩包/Git 状态中没有秘密、世界、缓存和旧 JAR；部署 JAR 与 CI 产物哈希一致。

---

## 4. P1：高优先级逐条建议

### P1-01 `debug=true` 会记录明文 ServerQuery 登录密码

- **维度**：逻辑 / 可读性 / 安全。
- **位置**：`teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/ts/TsQueryClient.java:74-85`。
- **证据**：登录前开启通信日志；当前打包的 `teamspeak3-api-1.2.0` 对完整命令做 debug 输出，登录参数没有脱敏。
- **建议**：登录完成前禁用通信日志；升级/包装依赖并对 `login`、密码修改、token 做参数级脱敏；不能把 logger pattern 当成秘密过滤。
- **验收**：开启最高日志级别后，日志中仍不含任何配置密码或 token。

### P1-02 COMMAND/COMMAND_LOW 序号回绕后无法继续解密与重组

- **维度**：逻辑。
- **位置**：`teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Connection.java:453-465,493-538`。
- **证据**：`base/id` 按普通 int 增长，`generationFor()` 只算 `-1/0/1`；`base=65536` 时合法下一包 `id=0` 被推断为上一代，且缓存也没有键 `65536`。
- **建议**：抽出 `Seq16` 值对象，统一模 65536 比较；保存真实代际，序号和代际一起作为缓存键，不能零散修补比较符号。
- **验收**：每类命令跨 `65534,65535,0,1` 均可解密、去重和重组；迟到上一代包被丢弃。

### P1-03 RSA 挑战使用带符号 BigInteger 编码，65 字节结果会覆盖回显区

- **维度**：逻辑 / 协议健壮性。
- **位置**：`teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Crypt.java:280-283,307-313`。
- **证据**：最高位为 1 时 `toByteArray()` 返回 65 字节，额外的符号 `0` 被复制到 Y 字段之前。离线探针已复现回显末字节由非零变为零。
- **建议**：实现无符号、固定 64 字节编码：仅删除多余的一个符号前导零，左侧补零，真实超长则拒绝。
- **验收**：最高位 0/1、短结果、零结果、真实超长的测试；完整服务器回显必须逐字节不变。

### P1-04 重连只删除 Java Ghost 状态，没有关闭旧 SVC `AudioChannel`

- **维度**：逻辑 / 资源所有权 / 使用体验。
- **位置**：`TsAudioInjector.java:114-127,336-360`；`TsVoiceManager.java:227-238,323-324`；SVC 参照 `simple-voice-chat-26.2/common-client/src/main/java/de/maxhenkel/voicechat/voice/client/AudioChannel.java:101-154`。
- **证据**：当前清理没有 `closeAndKill()`。同一 UUID 新会话从低序号开始时，SVC 先更新收包时间，再把新包判为旧包；持续讲话可阻止旧通道 30 秒回收，后续重置标记反而先被丢弃。
- **建议**：建立 `SvcAudioChannelAdapter`，由会话代际拥有通道；删除 Ghost、UUID 迁移和换会话时真正关闭并从 SVC 映射移除旧通道，不只删除本 mod 的 map。
- **验收**：旧会话高序号→同 UUID 新会话低序号，首帧即可播放；旧线程、解码器、抖动缓冲均退出。

### P1-05 发送锁内执行阻塞 TCP 写，关闭又要等待同一把锁

- **维度**：性能 / 并发 / 生命周期。
- **位置**：`Ts3Connection.java:135-164,216-221`；`TcpTransport.java:34-38`。
- **证据**：发送持 `sendLock` 做无写超时的阻塞 Socket 写；`stop()` 先等待 `sendLock` 才能 close socket，无法用 close 唤醒写线程。上层 `TsVoiceManager.stopSession()` 又在管理器锁内断开网络。
- **建议**：终态发布、socket 关闭、加密序号、帧写入分别管理；关闭可在不等待发送锁的情况下中断 I/O。管理器锁内只交换对象/代际，锁外关闭资源。
- **验收**：对端停止读取时，disconnect、GUI、服务器停止都能在限定时间完成，无死锁和无界等待。

### P1-06 连接完成与断线竞争，可能把已关闭连接重新标记为 connected

- **维度**：逻辑 / 并发。
- **位置**：`Ts3VoiceClient.java:252-272,329-345`；`TsVoiceManager.java:255-273,343-399`。
- **证据**：`connect()` 检查 `failed` 后无条件 `connected.set(true)`；断线回调可能已把它置 false。manager 在代际提交之前已经发布 MC UUID、上传头像；DNS 阶段也未登记可取消的连接。
- **建议**：用原子状态机 `IDLE→CONNECTING→CONNECTED→CLOSED`，终态不可逆；连接尝试一开始就可取消；身份、头像等副作用移到代际提交成功之后。
- **验收**：connect/timeout/disconnect/change-server 交错测试；关闭后不能回到 connected，旧会话不能发布身份。

### P1-07 音频和文字回调未绑定来源客户端，旧会话可使用新会话身份表

- **维度**：逻辑 / 可读性 / 数据隔离。
- **位置**：`TsVoiceManager.java:361-362,487-498,521-541`。
- **证据**：监听器使用静态方法，回调内部重新读取全局 `client`；重连窗口内，旧客户端语音帧可能按新客户端 `clid→uid/nickname/mcUuid` 解析，文字回调也没有代际检查。
- **建议**：注册时捕获 `c` 和 `gen`，处理前验证 `client==c && active(gen)`，全部身份查询只使用捕获的客户端。
- **验收**：人为延迟旧回调，确认旧音频和旧聊天不会进入新会话，不会错误归属玩家。

### P1-08 音频全局锁包含 DLL 解包、模型加载、native DSP 和 UI 重载

- **维度**：性能 / SOLID / 资源安全。
- **位置**：`audio/AudioPipeline.java:28-72,94-107,165-211`；`NvidiaDenoiser.java:37-75`；`WebRtcNative.java:91-125`；`AudioExtraScreen.java:35-42,144-148`。
- **证据**：每帧处理、能力查询、初始化、重配共用 `AudioPipeline.class` 锁；锁内可做文件 I/O、GPU 初始化和 native 计算。UI 改混响也会同步重建整条麦克风管线。
- **建议**：后台构造不可变 `AudioGraph`，用 `AtomicReference` 一次性发布；单帧只持有当前图引用；旧图在处理线程退出后销毁。配置按 diff 重建受影响阶段。
- **验收**：主线程不做 native/磁盘初始化；配置操作不阻塞实时帧；重载不释放正在使用的处理器。

### P1-09 EFX 未探测能力，句柄又未绑定 OpenAL context 代际

- **维度**：逻辑 / 资源 / 跨平台稳定性。
- **位置**：`audio/RayTracedReverb.java:48-53,123-133,208-234`；`mixin/AlSpeakerMixin.java:76-91`。
- **证据**：未检查 EFX/函数地址便调用扩展函数；关闭混响仍调用 aux-send。`auxSlot/reverbEffect` 是进程级静态整数，设备重建后会复用旧 context 的 ID；`disable()/cleanup()` 没有生产调用方。
- **建议**：在正确 ALC context 探测能力，建立 `NOT_ATTEMPTED/READY/UNAVAILABLE`；`EfxResources(context,handles)` 随 context 创建和销毁，失败只降级混响，绝不影响干声。
- **验收**：无 EFX、创建失败、输出设备切换、声音重载四种场景均无异常、无重复创建、无旧 ID 复用。

### P1-10 AEC 参考不是最终扬声器混音，也没有与麦克风时间轴对齐

- **维度**：逻辑 / 性能 / 使用体验。
- **位置**：`mixin/AlSpeakerMixin.java:63-69`；`audio/AecReferenceMixer.java:14-30`；`AecProcessor.java:84-113`；SVC `ALSpeakerBase.java:113-133`。
- **证据**：在音量、空间处理和 OpenAL 排队之前取原始 audio；多个 speaker 简单串入同一 FIFO；队列空补零，没有播放时间戳、实际输出时钟或漂移补偿。固定 `aecDelayMs` 无法代表设备与缓冲变化。
- **建议**：优先 WASAPI loopback 等真实 render 回采；若继续 OpenAL 取参考，应在最终处理后按统一 48kHz 时间戳混音，结合 queued samples/sample offset/device latency 做同步和漂移控制。
- **验收**：单人、多人、静音、欠载、设备切换、外放延迟变化均有回声残余和近端语音损伤指标；不能只验证“函数被调用”。

### P1-11 native 返回值未统一检查，失败时仍可能关闭 SVC 原生降噪

- **维度**：逻辑 / 使用体验。
- **位置**：`AecProcessor.java:101-106`、`WebRtcNative.java:57-68`、`NvidiaDenoiser.java:116-117`、`AudioPipeline.java:100-103`、`mixin/MicProcessorMixin.java:18-24`。
- **证据**：APM 整数结果被忽略；RTX Voice 非零状态只 break；`isNsActive()` 仍认为处理器可用，SVC 原生降噪因此被关闭。NVIDIA ABI 还由路径名推断，现代分支未设置输入采样率：`NvidiaDenoiser.java:47-75`。
- **建议**：每个阶段返回明确成功/失败；检查所有 native 状态；失败后限频熔断并恢复 SVC 原生路径。显式配置 `RTX_VOICE_LEGACY/AFX_SDK`，加载后查询采样率、通道数和帧长。
- **验收**：模拟 native 错误时不用重启即可回退；UI 显示真实状态；不因半处理帧继续覆盖有效输入。

### P1-12 不支持的 codec 只告警，仍送进 Opus 解码器

- **维度**：逻辑。
- **位置**：客户端 `TsAudioInjector.java:99-110,250-266`；服务端 `transport/Ts3NativeTransport.java:261-293`、`ts3voice/Ts3VoiceClient.java:260-277`。
- **证据**：codec 不是 4/5 时仍按 Opus 注入/解码，Speex/CELT 或异常载荷会产生错误音频或通道退出。
- **建议**：协议入口只接受支持的 codec；未知格式立即丢弃并限频计数，不创建 Ghost、不推进序号；需要扩展时按 codec 选择独立解码器。
- **验收**：合法 Opus Voice/Music、空结束帧、Speex、未知 codec 的明确行为测试。

### P1-13 服务端配置无边界校验，可在不可桥接状态下显示已连接

- **维度**：逻辑 / 使用体验。
- **位置**：`config/BridgeConfig.java:79-98,119-142`；`transport/PlayerVoiceClients.java:269-307`；`TsBridgeMod.java:328-377`。
- **证据**：端口、配对长度/时间、`mode` 无统一验证；默认 per-player 模式在缺 query 配置时仍可创建语音客户端，频道 ID 长期为 -1，却可能呈现 connected。
- **建议**：`ConfigValidator` 在 load/reload/link 共用；端口和数值限定范围，模式用 enum。缺必要能力时进入 `NOT_READY/DEGRADED`，不要启动无意义 bot；status 区分“连接成功”和“已进入桥接频道”。
- **验收**：非法配置不会静默变默认；所有失败有唯一、可操作的错误提示，无“看似成功但无声”。

### P1-14 文字桥没有复用语音的频道就绪检查，可能向旧频道发私密消息

- **维度**：逻辑 / 数据隔离。
- **位置**：`TeamspeakSvcClient.java:162-173`；`TsVoiceManager.java:418-426`；`GroupChannelMapper.java:108-112`。
- **证据**：语音发送检查 `isReadyForVoice`，文字发送只检查 connected；`clientmove` 异步确认前仍可能向旧频道发送。
- **建议**：命令层不再直接拿原始 `Ts3VoiceClient`；由 manager/router 统一 `sendText`，复用不可变路由快照，未确认时排队或明确报错。
- **验收**：切换私密群组、密码错误、移动拒绝期间，文字绝不会进入旧频道。

### P1-15 麦克风“接管”与“实际发送”混为一个 boolean，状态会误报且停止帧缺失

- **维度**：逻辑 / 可读性 / 使用体验。
- **位置**：`MicRedirector.java:21-40`；`TsVoiceManager.java:418-434`。
- **证据**：未就绪和发送异常都返回 true，因此取消 SVC 发送，并在没有实际发送时记录“audio path ACTIVE”；PTT/语音激活停止路径没有明确 TS 停止说话帧。
- **建议**：返回 `BYPASSED/ENQUEUED/DROPPED_NOT_READY/SEND_FAILED`，把是否取消 SVC 与是否入队分开。保留防双音频策略，但实际入队才计数；增加 `wasSending`，在停发和会话关闭前发送停止帧。
- **验收**：路由未就绪无 ACTIVE 日志，持续丢帧有限频可见提示，停止说话状态不依赖远端超时。

### P1-16 服务端缺完整 SVC 依赖，Ghost 注入失败后仍提交成功状态

- **维度**：逻辑 / 兼容性 / 可诊断性。
- **位置**：`teamspeak&svcbridge/src/main/resources/fabric.mod.json:20-27`；`ghost/GhostPlayerInjector.java:76-86,294-350`。
- **证据**：仅声明 `voicechat_api`，实现却使用大量 SVC 内部类；`inject()` 在反射/广播结果之前写入 map，后续 reconcile 不再重试。
- **建议**：声明实际支持的完整 `voicechat` 版本范围；把私有 API 访问集中到 `SvcInternalAdapter`；只有广播成功才提交 map，失败有退避重试和明确组件健康状态。
- **验收**：缺 SVC 在加载期明确失败；不兼容版本不会静默无声；一次临时反射失败可恢复。

---

## 5. P2：正确性、性能、SOLID 与体验改进

### P2-01 QuickLZ 的声明长度不是读取边界，短回引还会先写 3 字节

- **维度**：逻辑 / 健壮性。
- **位置**：客户端 `ts3voice/QuickerLz.java:57-60,82-140`；服务端 `ts3voice/QuickerLz.java:33-51,113-143`。
- **证据**：客户端后续读取使用整个 data，而非 `compressedSize`；`matchLen<3` 时也无条件写三个字节。服务端还保留合法长回引静默截断的旧分叉版本。
- **建议**：checked reader 以声明长度为硬边界；写入前验证 match 长度，精确复制或受检失败；真实压缩样本和畸形输入 fuzz 共享给两端。
- **验收**：尾部多余数据不可被消费；截断、短 match、长回引、负长度均受控失败；合法样本结果与参照一致。

### P2-02 命令重组没有内存/时间预算，“孤儿分片自愈”分支不可达

- **维度**：性能 / 逻辑。
- **位置**：客户端 `Ts3Connection.java:449-479,493-562`。
- **证据**：`received` 不受 `RECEIVE_WINDOW` 限制，无分片数、总字节数、等待时间上界；`hasEnd=true` 已使外层条件不成立，所以 `hasEnd` 的孤儿清理代码到不了。
- **建议**：独立 `BoundedCommandAssembler`，限制窗口、单组分片、合并大小、等待时间；明确 `COMPLETE/WAITING/SKIP` 结果，而不是都用 null。
- **验收**：丢 base、持续乱序、超大分片等情况下内存有上界，后续正常命令可恢复。

### P2-03 握手参数、ECDH 点和身份文件缺少统一输入校验

- **维度**：逻辑 / 安全健壮性。
- **位置**：`Ts3Crypt.java:91-109,128-147,182-204,557-601`；`Ts3Identity.java:205-247`。
- **证据**：缺 base64 参数可 NPE，alpha 长度不固定，派生异常未统一转为握手失败；`curve.createPoint` 未验证曲线点；身份私钥范围和负 offset 未拒绝。
- **建议**：协议入口做必需字段、长度、曲线点/无穷远点和身份范围验证，统一转成 `Ts3VoiceException` 并立即失败。这里确认的是输入验证缺口，**不宣称已证明私钥可恢复**。
- **验收**：畸形握手立即返回可操作错误而非等 30 秒超时；损坏身份文件不会生成负 offset 或无效私钥会话。

### P2-04 超时使用墙上时钟且重传退被实际上限压在 1 秒

- **维度**：逻辑 / 性能。
- **位置**：`Ts3Connection.java:65-71,338-350,377-424`；服务端对应 `Ts3Connection.java:63-65,367-427`。
- **证据**：`lastMessageMs` 跨线程读写却无 volatile/同步；`currentTimeMillis` 受校时影响；初始/最大 RTO 都是 1000ms，同一轮多个包到期会连续翻倍，重传 ACK 也用于 RTT。
- **建议**：间隔统一 `System.nanoTime()`；时间戳发布有可见性保证；按 RFC6298 思路维护 SRTT/RTTVAR 和 min/max，重传 ACK 遵循 Karn 原则，出站队列有界。
- **验收**：系统校时和丢包模拟下不会误超时或重传风暴；重连时间可预测。

### P2-05 配置使用共享可变 Entry，跨线程可拼出混合配置；合法 null 还可使初始化 NPE

- **维度**：SOLID / 并发 / 逻辑。
- **位置**：`ServerConfigStore.java:28-62,87-89`；`ConfigScreen.java:119-145,284-299`；`VoicechatPluginImpl.java:43-51`。
- **证据**：GUI 逐字段写共享 Entry，SVC 网络回调逐字段读，可能得到新 host + 旧 port/password；`{"server":null}` 会在 `ConcurrentHashMap.putAll` 抛未捕获 NPE。
- **建议**：Entry/SessionConfig 不可变，完整校验后原子替换；SVC 生命周期统一在主线程形成快照，后台只读快照。
- **验收**：保存与网络事件并发时只能读到完整旧版或完整新版；null/错类型条目隔离并提示，不中断 mod 初始化。

### P2-06 配置恢复会留下旧版本号，备份失败又被静默忽略

- **维度**：逻辑 / 可读性。
- **位置**：`ModConfig.java:60,83-104`；`ConfigFileUtil.java:46-54`。
- **证据**：损坏配置恢复为 `configVersion=0`，用户保存新设置后下次仍会迁移并关闭音频项；备份失败无返回，后续保存可能覆盖可人工修复文件。
- **建议**：统一 `createCurrentDefaults()`；恢复即当前版本。备份失败进入只读恢复模式并明确提示，不允许继续覆盖原文件。
- **验收**：损坏→恢复→修改→重启不再重复迁移；备份失败可见且不丢原文件。

### P2-07 表单会固化继承密码、静默丢输入和吞掉非法端口

- **维度**：使用体验 / 逻辑。
- **位置**：`ConfigScreen.java:222-249,284-299`。
- **证据**：继承后的密码被回填并无条件保存为每服覆盖值；禁用 mod 时地址仍可编辑却不保存；非数字端口静默变 9987，0/负数/大于 65535 不校验。
- **建议**：区分 raw 与 effective 配置；密码采用“继承/覆盖/显式为空”三态，只在编辑后更新 raw。统一表单校验，非法输入行内提示并禁用连接，不静默替换。
- **验收**：只打开并保存不改变继承关系；所有可编辑字段都能保存；非法端口不发起连接。

### P2-08 配置窗口不刷新后台状态，固定布局可能让完成按钮不可达

- **维度**：使用体验。
- **位置**：`ConfigScreen.java:72-115,203-219,279-282`。
- **证据**：状态和按钮只在 init 计算，后台已连接仍可能显示连接；连接中没有取消；完成按钮逻辑 y=290、高 20，窗口不足时没有滚动。
- **建议**：`tick()` 只更新现有控件，不每帧重建；CONNECTING/RECONNECTING 提供取消；使用可滚动/自适应布局。
- **验收**：状态变化无需重开窗口；小窗口、高 UI 缩放可访问全部控件；录音/连接中操作语义明确。

### P2-09 AEC 失败不恢复独立 WebRTC NS，AGC 又可能与 SVC 串联

- **维度**：逻辑 / 音频一致性。
- **位置**：`AudioPipeline.java:34-64,171-209`；`mixin/MicProcessorMixin.java:18-24`；`AgcProcessor.java:29-45`。
- **证据**：配置 AEC 时不创建独立 WebRTC NS，AEC 初始化/运行失败后直接 return；SVC 原生 AGC 未被接管，自研 AGC 可与之串联，且 AEC/非 AEC 模式处理顺序不同。
- **建议**：把 NS、AEC、AGC 建模为独立阶段，固定顺序；增益控制只有一个所有者，优先统一 APM 增益模块，或明确关闭 SVC AGC。
- **验收**：每个组件单独失败不影响其他已选功能；切换 AEC 不发生响度/底噪突变。

### P2-10 Ducking 改增益表但未刷新现有声音，且把所有 SVC 入队当成 TS 发声

- **维度**：逻辑 / 使用体验。
- **位置**：`audio/DuckingManager.java:36-39,55-68,92-137`；`mixin/AlSpeakerMixin.java:63-68`。
- **证据**：仅写 `gainBySource`，不调用音量刷新，现有音乐/循环声可能不变；任意 SVC speaker、静音预填充也 markActive，未区分 TS 来源和实际播放进度。
- **建议**：在音量计算处注入独立 ducking multiplier，变化时刷新受影响 category；只给 TS 流打标，忽略静音/零音量；使用单调时钟和实际队列进度。
- **验收**：正在播放声音能压下/恢复，普通 SVC 和静音不触发 TS Ducking，用户音量滑条不被拉回。

### P2-11 混响参数跨批次残留，射线条数预算不等于时间预算

- **维度**：逻辑 / 性能。
- **位置**：`audio/RayTracedReverb.java:38-42,90-107,139-193,259-320`。
- **证据**：无 early/late 命中时不清零；late delay 可能减旧 early delay 变负；多个 volatile 字段不是一个原子快照。600 射线、每 tick 40、最多 5 次反弹，完成后再等 500ms，理想周期约 1.25 秒，且实体/碰撞成本不可控。
- **建议**：单 `volatile ReverbParams` 原子发布完整默认值，限制 EAX 合法范围；每 tick 使用 nanoTime 时间预算，只查 Player 候选，移动/环境变化驱动更新。
- **验收**：室内到开阔环境不残留；无负延迟/混合批次；复杂场景帧时间有上限。

### P2-12 原生资源和缓存的生命周期仍未闭合

- **维度**：资源 / 性能。
- **位置**：`AudioPipeline.java:74-91`、`mixin/MicProcessorMixin.java:27-32`、`NvidiaDenoiser.java:37-44`、`WebRtcNative.java:91-128`。
- **证据**：native 处理器静态常驻，没有 mic/client close 的完整生产钩子；NVIDIA 同时 LoadLibraryEx/System.load 且未管理模块句柄；WebRTC 缓存只判断存在，损坏后不会重解包。
- **建议**：统一 `NativeLibraryOwner` 和 `AudioGraph.close()`，接入 SVC mic/client stop，幂等关闭；缓存临时文件原子替换，加载失败隔离并重新解包。
- **验收**：重复启动/停止和设备切换不积累处理器、模块引用或临时文件；损坏缓存能自愈。

### P2-13 低延迟只改预填参数，没有真正控制现存队列

- **维度**：逻辑 / 使用体验。
- **位置**：`mixin/AlSpeakerMixin.java:49-60`；SVC `ALSpeakerBase.java:81-128`。
- **证据**：只在 stopped 预填充和 32 buffer 溢出补偿使用 bufferSize；预填 N 个静音后再写真实数据，N=2 实际为 3 包。连接时已形成的延迟不会主动缩小。
- **建议**：把预填目标与实时队列控制分开；切换时明确重置，运行中按真实 queued samples/sample offset 有界调整，不盲删有效语音。
- **验收**：测量冷启动、网络抖动、设备延迟后的实际可听延迟，不只断言返回值为 2。

### P2-14 服务端成员读取重复，且“无人”与“读取失败”语义混淆

- **维度**：重复代码 / 逻辑 / 数据隔离。
- **位置**：`ghost/GhostPlayerInjector.java:176-212`、`net/BeaconSender.java:175-200`、`svc/McAudioInjector.java:220-238`、`ts/presence/TsPresenceUpdater.java:135-154`。
- **证据**：反射逻辑重复；失败返回 empty，使 null→全员回退不可达；后台线程直接读 MC/SVC 状态。若简单改为全员回退，又可能把私密群组密码/音频发给非成员。
- **建议**：唯一 `BridgeMembershipService` 在主线程形成不可变快照，后台只发网络；返回 `UNKNOWN/EMPTY/MEMBERS`，未知时保留上次已验证快照或 fail-closed。
- **验收**：群组成员、密码、接收者来自同一快照；读取失败绝不扩大受众。

### P2-15 群组类型未映射，group-sync 又按名称认领他人同名群组

- **维度**：逻辑 / 领域建模。
- **位置**：客户端 `GroupChannelMapper.java:77-112`、`net/GroupInfoPayload.java:15-39`；服务端 `ts/TsChannelGroupSync.java:29-30,74-118`；规格 `.zcode/specs/teamspeak-svc-v0.2/spec.md:12-15,34-37`。
- **证据**：`typeOrd` 无消费者，OPEN/NORMAL/ISOLATED 都进子频道，与现存规格不一致；服务端看到同名群组就加 ownGroupNames，删除判断 `api.getGroup(id)!=null` 也不等价于真实存在。
- **建议**：先明确每种群组在 TS 的可实现语义，不能支持则显式降级/UI 说明。镜像群组以 cid 派生 UUID 并持久化 cid→UUID 所有权，名称只作显示。
- **验收**：三类本地群、镜像群、同名冲突、重启后的归属均有测试，绝不误删管理员手工群组。

### P2-16 配对 pending 静态残留、异常吞码，消息上下文也未验证

- **维度**：逻辑 / 生命周期 / 使用体验。
- **位置**：`pairing/PairingManager.java:54-56,197-207,269-334`；`TsBridgeMod.java:654-661`。
- **证据**：pending 为 static 且不随实例生命周期清空；bind 失败时 code 已 remove，却提示重试；消息处理不验证 invoker 是否在桥接频道或允许的上下文。
- **建议**：pending 归 manager 实例；验证→绑定→消费做成明确状态转换；校验成员身份并限流。只有确认绑定成功才最终消费，通知失败与绑定失败分开处理。
- **验收**：重启、bind 异常、重复提交、跨频道私聊、暴力尝试均符合预期，旧码不复活。

### P2-17 玩家 bot 与传输的 connect/stop 仍需统一代际所有权

- **维度**：并发 / 资源。
- **位置**：`transport/PlayerVoiceClients.java:99-115,269-307`；`transport/Ts3NativeTransport.java:67-84,168-195,296-305`。
- **证据**：stop 可与进行中的 connect 交错，native transport 可在停止后再次发布 voiceClient；管理/协议线程没有完整有界 join。
- **建议**：统一 `ConnectionWorker`，每次 attempt 有 generation/cancellation token，资源仅单一 owner 提交；stop 先终态，再关闭 I/O，有界等待线程。
- **验收**：连接中离线、reload、服务器停止都不产生孤儿连接，状态不会从 CLOSED 回 CONNECTED。

### P2-18 多路 PCM 混音在每次加法后提前饱和，破坏正负抵消

- **维度**：逻辑 / 音频质量。
- **位置**：`audio/McAudioMixer.java:323-352`。
- **证据**：每加入一路就 clamp 到 short，与注释“所有输入求和最后 clamp”不一致；前序正值饱和后，后续负值无法完整抵消。
- **建议**：每 tick 用 `int[960]` 累加所有输入，最后统一 clamp；短数组和格式在入口明确处理。
- **验收**：多路极值、正负抵消、单路零拷贝语义测试；结果与顺序无关。

### P2-19 静态音频 target 全量 clear/add，发送线程可观察到空集合

- **维度**：并发 / 性能。
- **位置**：`svc/McAudioInjector.java:220-243`。
- **证据**：SVC 分别锁 clear/add，但整个批量过程不是一个原子操作；每 5 秒和成员事件都会刷新。
- **建议**：按成员快照做 diff，仅 remove 离开者和 add 新增者；集中到单线程，若 SVC 提供批量 API 优先使用。
- **验收**：成员刷新期间无空 target 窗口，无重复全量广播，压力测试不丢帧。

### P2-20 音频路由存在无效配置、死代码和不可靠回退

- **维度**：重复代码 / 可读性 / 性能。
- **位置**：`TsBridgeMod.java:328-377`、`svc/McToTsDispatcher.java:60-79`、`audio/AudioCodec.java:14-108`、`config/BridgeConfig.java:41`、`audio/McAudioMixer.java:210-214`。
- **证据**：默认 1:1 不启动 mixer，`sendSilenceFrames` 实际无效；mixer 与 fallback 同开但通常只有一路被选择，mixer 失败后旧引用又可阻断回退；AudioCodec 无调用方，格式逻辑另写两份。
- **建议**：明确单一 `AudioRoute` 状态机，只启动活动路由；删除废弃回退或真正完成切换；集中 `AudioFormat`；删除无效配置或放到传输层实现。
- **验收**：每种 mode 只有一条音频所有者，故障切换可测，status 只展示实际生效的配置。

### P2-21 查询通信、默认权限、私钥与配对存储缺少一致的秘密管理

- **维度**：安全 / 工程化。
- **位置**：`config/BridgeConfig.java:24-35,104-112`、`ts/TsQueryClient.java:74-85`、`ts3voice/Ts3Identity.java:198-204`、`config/PairingStore.java:149-160`、`net/GroupInfoPayload.java:15`。
- **证据**：当前 Query 库使用普通 Socket；默认 `serveradmin` 权限过大；私钥/配对/配置直接覆盖；服务端 GroupInfoPayload record 默认 toString 含密码。
- **建议**：远程 Query 走 loopback/SSH/VPN 和最小权限专用账号；共享原子私有文件写入，Windows 配置 ACL；secret DTO 脱敏 toString，损坏文件先备份。
- **验收**：远程部署指引不含裸公网 Query；崩溃不留下半截身份；日志/异常不出现密码。

### P2-22 SVC 兼容声明与私有 API 实际适配能力不一致

- **维度**：可维护性 / 使用体验。
- **位置**：客户端 `fabric.mod.json:23-30`、`teamspeak-svc.mixins.json:4,13-14`、`build.gradle:35-41`；服务端 `build.gradle:39-40`。
- **证据**：声明所有 `voicechat>=2.6.20`，实际依赖私有字段、构造器和 mixin；编译 API 2.6.20，实际内部基线/测试 JAR 2.6.25；`defaultRequire=0` 可让关键注入静默失效。
- **建议**：声明实际测试矩阵或加上限；关键 mixin 合理 require；启动时能力探测，版本不兼容明确禁用对应组件，不能假装可用。
- **验收**：支持的每个 SVC 版本有启动/双向音频测试；不支持版本在安装期或状态页清楚提示。

### P2-23 依赖、构建和发布复现性不足

- **维度**：工程化 / 性能（维护成本）。
- **位置**：两端 `gradle.properties:14-18`；`teamspeak-svc/build.gradle:35-58,71-83`；两端 wrapper properties。
- **证据**：BouncyCastle 1.78.1 有精确版本安全公告，当前握手又使用 ASN.1，需升级并回归（不宣称本链路已证实可利用）；Loom 使用 `1.17-SNAPSHOT`，无依赖锁/验证元数据/wrapper SHA；客户端无 Intel macOS native classifier却有加载分支。
- **建议**：升级受支持 BC（评估 1.85+），固定 Loom 正式版，启用依赖锁、校验和 wrapper SHA；两次干净构建比较制品；补齐或声明不支持的平台。
- **验收**：干净环境无需旧 review 缓存即可构建，依赖来源可验证，发布 SBOM/NOTICE 完整。

### P2-24 测试不在标准 source set，诊断 CLI 还可能假阳性

- **维度**：工程化 / 逻辑。
- **位置**：`ts3voice/Ts3VoiceSelfTest.java:83-91`、`Ts3ChannelTest.java:27-71`；服务端 `ts3voice/Ts3VoiceSelfTest.java:39-47,88-90`；两端 `build.gradle`。
- **证据**：没有 `src/test`、没有 CI；本次 Gradle 明确 `test NO-SOURCE`。ChannelTest 的步骤 FAIL 不全部进入最终 ok，服务端 `voiceIn.get()>=0` 恒真；服务端自测类仍进发布 JAR。
- **建议**：现有 51 项迁入 `src/test`，每项修复附回归；真实服务器测试放 integration source set/单独任务，失败必须非零退出；工具类从生产 JAR 排除。
- **验收**：PR 自动跑两模块 `clean build test`，序号/压缩/生命周期/音频路由均有覆盖；任何子步骤失败都不能整体 PASS。

---

## 6. P3：可读性、重复与维护成本

### P3-01 静态 God object 让依赖、线程和所有权难以推理

- **位置**：客户端 `TsVoiceManager.java:40-60,160-239,343-543`、`audio/AudioPipeline.java:17-212`；服务端 `TsBridgeMod.java:52-89`。
- **问题**：一个类同时管配置解析、连接、重连、路由、身份、聊天、音频计数；public static volatile 字段不等于资源所有权。
- **建议**：拆为 `ConfigResolver`、`SessionCoordinator`、`RoutingService`、`VoiceIngress/VoiceEgress`、`ChatBridge`、`StatusSnapshot`；构造器注入，生命周期在单串行 executor 中协调，音频热路径只读快照。
- **原则**：先引入接口和测试，再迁移行为；不要为“符合 SOLID”一次性重写所有代码。

### P3-02 重复的反射、成员解析、异常降级和持久化已出现行为分叉

- **位置**：`ClientStateReflector`、`GhostPlayerInjector`、`BeaconSender`、`McAudioInjector`；`AudioPipeline.java:175-201`；两端 Config/Identity。
- **建议**：集中 `SvcInternalAdapter`、`BridgeMembershipService`、`AudioStage`、`PrivateFileStore`；错误处理统一“状态码 + 熔断 + 可观察状态”，避免每处不同 catch/日志/默认值。
- **收益**：降低 OCP/DIP 破坏，未来 SVC 升级只改适配层，秘密/线程策略也只有一处。

### P3-03 公开 boolean/void 接口没有表达真实结果和线程契约

- **位置**：`TsVoiceManager.sendMic`、`Ts3VoiceClient.sendVoice`（连接断开时静默 return）、`TsAudioTransport`、`GhostPlayerInjector.inject`。
- **建议**：定义明确结果类型、异常/降级契约和调用线程；区分“未连接”“未入队”“已接受”“已发送”，不能把异步接受描述为网络成功。
- **收益**：消除假 ACTIVE、静默无声和测试误判，也让 status/指标可依赖。

### P3-04 实时路径有可避免的持续分配和 O(n) 操作

- **位置**：`WebRtcNative.java:57-65`、`NvidiaDenoiser.java:129-142`、`AecReferenceMixer.java:21-26`、`TsTransport.java:51-60`、`Ts3VoiceClient.java:180-186,238-244`。
- **建议**：固定帧预分配/复用，native arena 与单线程所有权一致；AEC 改有界 ring buffer，避免 CLQ.size()；维护 uid→clid 索引而非每帧扫全表。UDP 用 maxLen+1 判断截断。
- **注意**：先修 AEC 时间轴和协议正确性，再用 JFR/音频 underrun 指标优化，不为微小分配引入共享可变缓冲 bug。

### P3-05 UI 细节与功能状态需要一致

- **位置**：`mixin/VoiceChatScreenMixin.java:65-80`；SVC `VoiceChatScreen.java:102,145-152`；`ConfigScreen`。
- **问题**：新增按钮覆盖 SVC 录音计时区域；状态页显示收到的 TS 帧多不等于已播放，失败状态不够可见。
- **建议**：重新布局整行而非覆盖原生区域；状态展示 `discovered/decoded/played/dropped/not-ready`，提供最近错误和实际生效的 NS/AEC，而非只显示配置值。

### P3-06 文档、许可证和历史材料需要统一权威来源

- **位置**：`teamspeak&svcbridge/README.md:101-103,120-168,202`；`teamspeak-svc/README.md:44-65`；`teamspeak-svc/CODE_REVIEW.md`；`testserver/README.md:20`；`.trae/`、`.zcode/` 历史任务。
- **问题**：服务端版本仍写 0.1.0+26.2，配置示例缺现行字段且未标热更范围；客户端漏 channelPassword/configVersion；旧报告容易被误认为当前问题。两个产品声明 GPL/MIT 却缺根 LICENSE/NOTICE 和完整第三方许可随包文本。
- **建议**：从构建元数据生成版本/配置文档，旧报告标“历史版本”；历史 spec 保留但指定唯一权威需求；发布前补齐许可证、源码/版权和第三方 notices，并经合规确认。

---

## 7. 推荐目标架构（按依赖方向，而非按包名堆类）

```text
settings.gradle
├─ ts3voice-core                 Java library，无 Minecraft/Fabric 依赖
│  ├─ packet / Seq16 / BoundedCommandAssembler / QuickLZ
│  ├─ crypto / identity / TsTransport / ConnectionStateMachine
│  └─ golden vectors + fuzz + lifecycle tests
├─ bridge-protocol              Beacon/GroupInfo wire schema + compatibility vectors
├─ teamspeak-svc                Fabric client adapter
│  ├─ ConfigResolver / PrivateFileStore
│  ├─ SessionCoordinator / RoutingService / ChatBridge
│  ├─ SvcClientAdapter（私有 SVC API 唯一入口）
│  └─ audio：AudioGraph + stages + native owners + context-bound EFX
└─ tsbridge                     Fabric server adapter
   ├─ BridgeRuntime / LifecycleManager / HealthStatus
   ├─ BridgeMembershipService / PairingService / GroupOwnershipStore
   ├─ AudioRoute（1:1 或 mixer，单一路由所有者）
   └─ SvcServerAdapter / QueryGateway / AuthenticatedTunnel
```

关键约束：

1. `ts3voice-core` 不知道 Minecraft、GUI、日志偏好或 SVC；两个 mod 依赖它，而不是相互复制。
2. 主线程只生成世界/玩家快照；网络线程不读 `ClientLevel/MinecraftServer` 可变状态；音频线程不做磁盘、UI、网络初始化。
3. 每个 socket、线程、native 指针、OpenAL ID 都有唯一 owner 和明确 close 时机。
4. 所有可热更配置一次性解析、校验并发布不可变快照；后台任务不得逐字段读取正在编辑的对象。
5. SVC 私有 API 只在 adapter 中出现；上游升级时首先修 adapter 和兼容测试。

---

## 8. 分阶段实施顺序

| 阶段 | 工作内容 | 完成标准 |
|---|---|---|
| 0：保护与基线 | 秘密轮换/脱敏、关闭公网无认证隧道、固定部署哈希；保留当前 51 项测试。 | 无秘密外发；干净构建和部署可追溯。 |
| 1：协议正确性 | P0-01、P1-02/03、P2-01/02/03/04；先修再抽 core。 | 序号、RSA、分片、压缩、超时全部自动化；真实 TS 握手和大列表通过。 |
| 2：生命周期与隐私 | P1-04/05/06/07/14/15、P2-17；统一 Session/Routing。 | connect/disconnect/换服/重连压力测试，无旧会话音频、文字或身份串流。 |
| 3：服务端配置与成员 | P1-01/12/13/16、P2-14/15/16/18/19/20/21。 | 非法配置可诊断，成员失败不扩大受众，音频路由唯一且可恢复。 |
| 4：客户端音频 | P1-08/09/10/11、P2-09/10/11/12/13；先能力/资源，再 AEC/质量。 | 无 EFX/无 NVIDIA/设备切换可降级；帧时间和真实回声指标达标。 |
| 5：配置与 UX | P2-05/06/07/08、P3-05/06。 | 不丢输入、不固化继承、状态实时、所有窗口可用、文档一致。 |
| 6：工程收口 | 共享模块、adapter、标准测试/CI、依赖锁、许可与发布流程。 | CI 可重复构建；兼容矩阵、SBOM、 NOTICE、发布检查完整。 |

**不建议**先做“全面改 record/接口/包名”的大扫除。当前最危险的是协议、凭据、并发和音频生命周期；应以测试保护下的小步迁移替代大爆炸重构。

---

## 9. 最小回归测试清单

1. **协议**：序号回绕、迟到旧代际、首/中/尾分片、乱序、内存预算、RSA 固定长度、QuickLZ fuzz、曲线点、转义 `\xHH` 往返。
2. **生命周期**：DNS/连接中超时与取消、disconnect 与 connected 竞争、stop 与阻塞写、旧回调、身份提交时机、UDP/TCP 双传输。
3. **音频**：同 UUID 序号重置、旧 AudioChannel 关闭、codec 过滤、PTT 停止、未路由成功不计 ACTIVE、NS/AEC 失败后原生回退。
4. **音频设备**：无 EFX、aux 创建失败、设备切换、SVC 重载、NVIDIA 缺 DLL/错误 ABI/非零状态、WebRTC 缓存损坏。
5. **成员与路由**：OPEN/NORMAL/ISOLATED、群组密码尚未到达、移动拒绝、旧频道文字、membership UNKNOWN、同名群组所有权。
6. **配置**：null JSON、端口边界、损坏恢复、备份失败、继承三态、并发保存、热更/重启字段矩阵。
7. **安全**：debug 日志秘密扫描、信标跨 endpoint 凭据隔离、隧道未鉴权/限额、payload toString、测试服/发布包敏感文件扫描。
8. **真实集成**：至少双 MC 客户端 + 普通 TS 客户端 + tsbridge，覆盖初次连接、换服、私密群组、弱网、30 分钟以上会话、重连及真机外放 AEC。

---

## 10. 逐文件审查索引

以下“无独立问题”仅表示本轮未发现需单列的新缺陷，不等于经过完整动态验证；共享架构、依赖和调用方问题仍可能适用。

### 10.1 客户端 `com.tsvoice.client` 根包（17 个）

- `AudioExtraScreen.java`：配置变更全量重建、主线程 native 初始化，见 P1-08。
- `AvatarPipeline.java`：连接副作用提交时机，见 P1-06；文件上传属于会话资源，应归 adapter。
- `ClientStateReflector.java`：SVC 私有反射集中化，见 P1-16/P2-22/P3-02。
- `ConfigFileUtil.java`：备份失败和 Windows 权限，见 P2-06/P2-21。
- `ConfigScreen.java`：P2-05/07/08、P3-05。
- `GhostSkins.java`：应按会话/主线程快照管理，纳入 P1-06/P2-12。
- `GroupChannelMapper.java`：P1-14、P2-15；已有按 UUID 比较和路由确认修复应保留。
- `MicRedirector.java`：P1-15。
- `ModConfig.java`：P2-06。
- `ModMenuIntegration.java`：parent 传递正确，无独立问题。
- `OnlinePlayers.java`：主线程快照方向合理，无独立新增问题。
- `ServerConfigStore.java`：P2-05。
- `TeamspeakSvcClient.java`：P0-02、P1-14、P1-15。
- `Ts3FileTransfer.java`：文件传输应归会话 adapter，纳入 P1-06；未单列高危缺陷。
- `TsAudioInjector.java`：P1-04、P1-12、P2-22。
- `TsVoiceManager.java`：P0-02、P1-04/05/06/07/14/15、P3-01/03。
- `VoicechatPluginImpl.java`：线程快照见 P2-05，无其他独立问题。

### 10.2 客户端 `audio`（含 `nativ`、`rt`，14 个）

- `AecProcessor.java`：P1-10/11、P2-09。
- `AecReferenceMixer.java`：P1-10、P3-04。
- `AgcProcessor.java`：P2-09。
- `AudioPipeline.java`：P1-08/11、P2-09/12、P3-01/02。
- `DuckingManager.java`：P2-10。
- `NvidiaDenoiser.java`：P1-08/11、P2-12、P3-04。
- `RayTracedReverb.java`：P1-09、P2-11。
- `TsAudio.java`：配置桥职责可并入不可变 AudioSettings，无独立高危问题。
- `WebRtcNative.java`：P1-08/11、P2-12、P3-04。
- `nativ/CShared.java`、`nativ/NvAFX_Status.java`：简单绑定/枚举，无独立问题。
- `nativ/NvAudioEffects.java`：生成式大型绑定已审；实际 ABI 档案/采样率调用问题见 P1-11，绑定本身不另列。
- `rt/BlockSoundProperty.java`、`rt/HitPoint.java`：数据职责清楚，无独立问题。

### 10.3 客户端 `mixin`（7 个）

- `AlSpeakerMixin.java`：P1-09/10、P2-10/13。
- `MicProcessorMixin.java`：P1-11、P2-09/12。
- `VoiceChatScreenMixin.java`：P3-05。
- `GameProfileUtilsMixin.java`、`MinecraftGizmoMixin.java`：无独立问题，关键 mixin 失败策略见 P2-22。
- `SoundEngineAccessor.java`、`SoundManagerAccessor.java`：只暴露状态不等于完成音量刷新，见 P2-10；无其他独立问题。

### 10.4 客户端 `net`（2 个）

- `BeaconPayload.java`：字段读写对称，无独立问题；信任与凭据使用见 P0-02。
- `GroupInfoPayload.java`：字段读写对称、密码 toString 已掩码；类型语义见 P2-15。

### 10.5 客户端 `ts3voice`（13 个）

- `Ed25519Math.java`：点解码/运算及现有 RFC 自检已审，未发现独立算法缺陷；调用异常边界见 P2-03。
- `PacketType.java`：枚举映射无独立问题。
- `QuickerLz.java`：P2-01。
- `TcpTransport.java`：P1-05。
- `Ts3ChannelTest.java`：P2-24。
- `Ts3Connection.java`：P1-02/05、P2-02/04。
- `Ts3Crypt.java`：P1-03、P2-03、P2-23。
- `Ts3Identity.java`：P2-03/21。
- `Ts3Packet.java`：未知类型拒绝已正确，无独立问题。
- `Ts3VoiceClient.java`：P1-06/12、P2-24、P3-03/04。
- `Ts3VoiceException.java`：简单受检异常，无独立问题。
- `Ts3VoiceSelfTest.java`：P2-24。
- `TsTransport.java`：P1-05、P3-04。

### 10.6 服务端全部 37 个 Java 文件

- `TsBridgeMod.java`：P1-13、P2-20、P3-01。
- `TsVoicechatPlugin.java`：事件注册和连接链已审，无独立新增问题。
- `audio/AudioCodec.java`：P2-20。
- `audio/McAudioMixer.java`：P2-18/20；sender 回收应将 lastSeen/queue 放入同一原子状态。
- `command/TsBridgeCommands.java`：P1-13；link 应作为频道切换事务，同步迁移 bot/Ghost/target，而非仅改字段。
- `config/BridgeConfig.java`：P1-13、P2-20/21。
- `config/PairingStore.java`：P2-21。
- `ghost/GhostPlayerInjector.java`：P1-16、P2-14/22。
- `ghost/TsGhostPresenceListener.java`：link 切换与成员快照，见 P1-16/P2-14。
- `net/BeaconPayload.java`：读写对称，无独立问题。
- `net/BeaconSender.java`：P2-14。
- `net/GroupInfoPayload.java`：P2-21。
- `net/TcpTunnelServer.java`：P0-03。
- `pairing/PairingManager.java`：P2-16。
- `svc/McAudioInjector.java`：P1-12、P2-14/19。
- `svc/McToTsDispatcher.java`：P2-20；按当前 packet group 授权的设计应保留。
- `svc/OnlinePlayerRegistry.java`：并发容器使用合理，无独立问题。
- `transport/McToTsForwarder.java`：P2-20；有界队列思路可复用。
- `transport/PlayerVoiceClients.java`：P1-13、P2-17。
- `transport/Ts3AudioBotTransport.java`：P2-20；废弃回退若保留，需 pending 上限、超时和单一重连调度，不能每帧无界 sendAsync。
- `transport/Ts3NativeTransport.java`：P1-12、P2-17。
- `transport/TsAudioFrame.java`：byte[] 不是深不可变，纳入 AudioFormat/帧所有权；无独立高危问题。
- `transport/TsAudioTransport.java`：P3-03。
- `ts/BridgeChannelManager.java`：link 跨组件事务见 P1-13/P2-20。
- `ts/TsChannelGroupSync.java`：P2-15。
- `ts/TsQueryClient.java`：P1-01、P2-21；重连成功/首次连接状态和有界 join 纳入生命周期。
- `ts/presence/TsPresenceUpdater.java`：P2-14。
- `ts3voice/Ed25519Math.java`：缺客户端已有的 a==b 倍点处理，回共享 core 后补退化点测试。
- `ts3voice/PacketType.java`：无独立问题。
- `ts3voice/QuickerLz.java`：P0-01、P2-01。
- `ts3voice/Ts3Connection.java`：P0-01、P2-02/04/17。
- `ts3voice/Ts3Crypt.java`：与客户端存在同样 RSA/握手输入风险，统一在 core 修，见 P1-03/P2-03。
- `ts3voice/Ts3Identity.java`：P2-21；PoW 应响应中断并使用单调时钟。
- `ts3voice/Ts3Packet.java`：未知类型回退 VOICE，必须在共享 core 改为拒绝。
- `ts3voice/Ts3VoiceClient.java`：P1-12、P2-17。
- `ts3voice/Ts3VoiceException.java`：无独立问题。
- `ts3voice/Ts3VoiceSelfTest.java`：P2-24。

### 10.7 非 Java 文件与生成物

- 两端 `build.gradle`、`gradle.properties`、`settings.gradle`、wrapper、Fabric/mixin/lang JSON：见 P2-22/23/24、P3-06；entrypoint 和 mixin 类引用存在。
- README/ROADMAP/历史 specs：见 P3-06；历史规格冲突需产品决策后统一。
- `review/` 中 class/JAR/Gradle cache、`build/`、`.gradle/`、`testserver/libraries/`、世界和日志：均为生成物或运行时数据，不当作业务源码重复审查；应隔离出发布/提交范围。
- 上游两个参考目录：用于调用链证据，**不声称完成其所有文件的独立质量背书**。

---

## 11. 需求覆盖审计

| 用户要求 | 本报告对应内容 |
|---|---|
| SOLID | P1-08、P2-05、P3-01/02/03；目标模块依赖方向见第 7 节。 |
| 重复代码 | P0-01、P2-14/20、P3-02；共享协议、SVC adapter、成员服务、私有文件与音频阶段。 |
| 可读性 | P1-15、P2-06/20、P3-01/03/06；结果类型、明确状态机、文档权威源。 |
| 性能瓶颈 | P0-03、P1-05/08/10、P2-02/04/11/19、P3-04。 |
| 逻辑 | P0/P1/P2 各条均给出处、影响、改法与验收。 |
| 使用体验 | P0-02/04、P1-04/11/13/15/16、P2-07/08/10/13、P3-05/06。 |
| 逐条建议及可改进方案 | 4 条 P0、16 条 P1、24 条 P2、6 条 P3，共 50 条；每条含改进建议，重点项含验收。 |
| 整个项目和所有文件 | 两个自有产品全部 Java 逐文件审查并列入第 10 节；配置/文档/生成物分类覆盖；上游参考目录与二进制不伪称为同范围产品全量审计。 |
| 实际验证 | 两次真实 BUILD SUCCESSFUL、51/51 既有隔离 PASS、协议探针和未验证边界见第 2 节。 |
