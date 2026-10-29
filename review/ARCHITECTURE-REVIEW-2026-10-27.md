# 全量架构评审：tsbridge（服务端）+ teamspeak-svc（客户端）（2026-10-27）

> 审查对象：`teamspeak&svcbridge/teamspeak&svcbridge`（37 文件 / 约 8,300 行，Fabric 服务端 mod，SVC↔TS3 桥）与 `teamspeak-svc`（53 文件 / 约 9,400 行，Fabric 客户端 mod），共 90 个 Java 文件、约 17,700 行。构建目标 MC 26.3 / Java 25。
> 方法：5 个并行子系统逐行精读 + 死代码/重复代码全源码树 grep 验证 + 双副本 CRLF 归一化 diff 量化 + 评审人亲自抽查 13 条关键结论（全部属实）。协议语义对照 TS3AudioBot/TSLib 上游核实。
> 与既往评审的关系：本日早间 `REVIEW-2026-10-27.md` 的 R01–R16 已确认修复落地（评审人抽查 R10/R12 属实，回归套件在 `review/` 下）。本报告为全新范围（架构/SOLID/重复/性能/体验），少量条目与 R 系列互补而非重复。

---

## 0. 执行摘要

**总体结论：协议移植核心质量高于粘合层；最大的结构性风险不是任何单个 bug，而是"复制即架构"——ts3voice 协议栈在两个 mod 中双副本维护（92% 逐字节相同），分叉已经产生了唯一的 P0 级协议 bug（只在服务端）。其次是静态服务定位器贯穿两端（TsBridgeMod 14 个 public static volatile 字段、TsVoiceManager 26 个 static 字段），使并发缺陷与不可测试性成为系统性问题。**

| 严重度 | 数量（本报告收录） | 说明 |
|---|---|---|
| P0 | 1 | 服务端分片重组语义颠倒，多分片命令必丢 |
| P1 | 19 | 正确性/并发 8、安全 3、性能 5、架构 3 |
| P2 | ~60 | 逐条见第 4 节 |
| P3 | ~90 | 按主题归并见第 4.5–4.8 节 |

**Top 10（按"先修这个"排序）：**

1. **[P0] 服务端 Ts3Connection 分片重组把 Fragmented 标志当"还有后续分片"**（§2.1）
2. **[P1] ts3voice 双副本漂移**——抽公共模块，加密层 ~1316 行可零成本合并（§3.1）
3. **[P1] TcpTunnelServer 无认证 + 绑 0.0.0.0 + 无连接上限**，可被当作免认证 UDP 转发跳板（§3.2）
4. **[P1] PairingStore/身份文件非原子写**，崩溃可致全部配对/身份静默丢失（§3.3）
5. **[P1] TsBridgeMod.shutdownAll 顺序竞态**——关闭后初始化线程可重新拉起信标/隧道（§3.4）
6. **[P1] TS→MC 注入类级锁 + perPlayer 模式在 SVC 回调线程同步加密发送**——多说话人时延迟叠加（§3.5）
7. **[P1] McAudioMixer 无积压排水**——延迟可单调爬升 2 秒（§3.6）
8. **[P1] 客户端热路径每帧 `Class.forName`** + 服务端连接状态机三份拷贝（§3.7）
9. **[P1] NVAFX 参数键疑似反转 + jextract 生成物类初始化炸弹**（§3.8）
10. **[P1] "自愈"链使用与故障同源的数据源**——事件丢失时 reconcile 永远无效（§3.9）

---

## 1. P0：必须立即修复

### 1.1 [P0] 服务端命令分片重组器对 FragmentedFlag 语义理解错误（已亲自验证）

- **位置**：`teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/ts3voice/Ts3Connection.java:552-557`（`CommandAssembler.tryAssemble`）
- **问题**：实现按"置位 = 后面还有分片"处理：
  ```java
  while (end.fragmentedFlag() && received.containsKey(id + 1)) { id++; end = received.get(id); }
  if (end.fragmentedFlag()) { return null; } // 还有分片未到
  ```
  已对照 TS3AudioBot `PacketHandler.AddOutgoingSplitData` 核实真实语义：Fragmented 是**边界标记**——首片与尾片置位、中间片不置位。后果：2 分片组两片都置位 → 永久等待（base 卡死）；≥3 分片组只合并到第一个中间片 → 截断命令解压失败被丢弃（`Ts3Connection.java:584-589` 的"initserver/channellist 解压失败丢弃"正是该症状的补丁式缓解）；遗留的置位尾片会与下一条无辜命令错误合并。
- **佐证**：客户端副本 `teamspeak-svc/.../ts3voice/Ts3Connection.java:495-532` 已实现正确语义（hasStart/hasEnd 边界判定 + 孤儿组自愈），且 `Ts3NativeTransport.java:207-211` 的"initserver 可能解压失败 → 补发 clientlist"缓解与本 bug 症状吻合。
- **修复**：直接移植客户端副本的重组器（含孤儿组自愈）；同时删除两处缓解性补丁注释，回归验证 initserver/channellist 正确重组。

---

## 2. P1：高优先级（19 条）

### 2.1 架构 / SOLID

**A1 [P1] ts3voice 双副本漂移——跨项目复制粘贴的协议栈**
- `com.svcb.tsbridge.ts3voice`（服务端 2,676 行）与 `com.tsvoice.client.ts3voice`（客户端）**约 92% 逐字节相同**（CRLF/包名/日志标签归一化后仅 198 行真实差异）。已分叉点：分片重组（服务端为错误版本，§1.1）、QuickerLz 校验策略（`>=` vs `>` maxSize，两套畸形输入防护）、Ts3Packet 未知类型语义相反（服务端回退 VOICE 掩盖协议漂移，客户端返回 null 丢弃）、Ts3Identity.save（客户端版有原子写 + POSIX 600，服务端版劣化丢失）。
- **修复**：抽公共 Gradle 模块（如 `tsproto`，`java-library`），差异点参数化：`Transport` 接口（客户端已有 `TsTransport/TcpTransport`，服务端内嵌 DatagramSocket 应同样抽出）、语音下游策略（服务端混音/直通 vs 客户端注入 SVC）、logger 名。纯协议层 PacketType/Ts3Packet/Ts3Crypt/Ts3Identity/Ed25519Math/QuickerLz ≈1,316 行相同度 ≥97%，零成本合并；Ts3Connection/Ts3VoiceClient 按接口收敛。过渡期至少加 CI diff 校验防继续漂移。

**A2 [P1] TsBridgeMod：静态服务定位器 + 上帝编排器**
- `TsBridgeMod.java:52-89`：14 个 `public static volatile` 字段（mcServer/config/pairings/tsQueryClient/bridgeChannelManager/pairingManager/tsAudioTransport/forwarder/mixer/presenceUpdater/bridgeGroup/bridgeGroupId/perPlayerMode/maintenanceExecutor）被 7 个包反向读取；`initializeTsComponents()`（178-381）约 200 行、11 个编号步骤（含 2.5/8.5 补丁编号）。
- **修复**：引入不可变 `BridgeContext` 聚合全部组件，构造注入；`TsBridgeMod` 只剩事件注册与装配；`initializeTsComponents` 拆为 `ensureSvcGroup/ensureQuery/initChannelAndPairing/initAudioPath/initMaintenance`。

**A3 [P1] ts3voice 连接层与客户端层双向耦合（依赖倒置缺失）**
- `Ts3Connection` 持有并回调具体类 `Ts3VoiceClient`（bindClient、onVoicePacket/onCommand），且 `Ts3Connection`/`Ts3Crypt` 静态引用 `Ts3VoiceClient.LOG`（共 10+ 处）；反向 `Ts3VoiceClient` 又调 `connection.notifyInitComplete()/setClientNid()`。
- **修复**：`Ts3Connection` 定义 `interface Listener { onVoice; onCommand; }`；日志移到包级 `Ts3Log`；两侧同步修（并入 A1 的公共模块）。

### 2.2 正确性 / 并发

**B1 [P1] 服务端语音"去重"= 最大序号 wins，乱序包被无条件丢弃**
- `Ts3Connection.java:477-484`：id 小于当前最大值的从未收过的包（网络乱序）直接丢。TSLib 用 GenerationWindow 位图接受窗口内乱序；客户端副本已改为全放行、交给 SVC 抖动缓冲。公网桥接下服务端方向会有可感知丢帧。
- **修复**：移植窗口位图，或与客户端一致下沉到下游。

**B2 [P1] TsBridgeMod.shutdownAll 顺序竞态（已亲自验证）**
- `TsBridgeMod.java:530-561`：先停 Beacon/Tunnel，后置 `shutdownRequested` 并 interrupt 初始化线程（join 5s 超时放行）。若 SERVER_STOPPING 到来时初始化线程卡在 `tsQueryClient.connect()`（socket 连接不可中断），线程恢复后会继续执行 166-170 行的 `BeaconSender.startGroupSync()`/`TcpTunnelServer.start()`（无 shutdownRequested 检查），在服务器关闭后**重新拉起**信标与隧道。
- **修复**：顺序改为"置 shutdownRequested → interrupt → join → 逆序停组件"；166-170 前补 `if (shutdownRequested) return;`。

**B3 [P1] Ts3VoiceClient 二次 connect() 产生静默僵尸连接**
- `Ts3VoiceClient.java:137-157`：CountDownLatch 不可重置，重连时 `connectLatch.await` 立即返回且 `failed=false` → `connected.set(true)` 但实际无连接，sendVoice 全部静默丢帧。同文件 180-183 行 sendVoice 未连接时无声消失，调用方无从感知。
- **修复**：单次使用语义（复用抛 IllegalStateException）；sendVoice 丢帧至少 debug 计数。

**B4 [P1] McAudioMixer.stop() 不 join 混音线程即关编码器（已亲自验证）**
- `McAudioMixer.java:112-118` vs `307-314`：`enc.close()` 与混音线程的 `outputEncoder.encode(pcm)` TOCTOU，可对已 close 的原生 Opus 编码器调 encode（native 崩溃风险）；`outputEncoder = null` 与裸字段读可 NPE。
- **修复**：`running=false` 后先 `mixerThread.join(timeout)` 再关编码器。

### 2.3 性能（实时音频路径）

**C1 [P1] McAudioInjector 类级锁串行化所有 TS 用户（已亲自验证）**
- `McAudioInjector.java:83`：`public static synchronized void playTsAudio(...)` —— 每个 TS 用户 50 帧/秒全部争一把全局锁，锁内含解码与 `createEncoder()` native 分配。N 人同时说话时延迟线性叠加。
- **修复**：改 per-user 锁（`synchronized(user)`，`computeIfAbsent` 已保证实例唯一）。

**C2 [P1] perPlayer 模式在 SVC 回调线程同步加密 + UDP 发送**
- `PlayerVoiceClients.java:180` → `Ts3Connection.addOutgoingPacket` 的 `sendLock` 内 `crypt.encrypt(packet); sendRaw(packet);`。SVC 事件线程要求快速返回（`McToTsDispatcher.java:63` 注释自证），多玩家说话时全部串行压在该线程。
- **修复**：每客户端出站队列 + 独立发送线程（与 mixer 路径对齐），SVC 线程只入队。

**C3 [P1] McAudioMixer 无积压排水（已亲自验证）**
- `McAudioMixer.java:203`：每 tick 每 sender 只 `poll()` 一帧，消费速率恒等于生产速率；突发积压不收敛，延迟单调爬至 `SENDER_QUEUE_CAPACITY=100`（2 秒）才开始丢最旧帧。
- **修复**：`q.size() > N`（如 5）时连续 drain，端到端延迟钳制 ~100ms。

**C4 [P1] 客户端音频热路径每帧反射查找（已亲自验证）**
- `TsAudioInjector.java:241-248`：`clientVoicechat()` 每帧执行 `Class.forName("...ClientManager").getMethod("getClient").invoke(null)`；50 帧/秒/说话者，多人时每秒数百次 `Class.forName`（内部同步）。
- **修复**：`ensureReflection()` 时缓存 Method，或缓存 client 实例仅在注入失败时刷新；同文件 218-222 行 `ensureReflection` 每帧进 synchronized 监视器，先无锁读 volatile 标志。

**C5 [P1] 客户端 DSP 管线：NVAFX 参数键疑似反转 + jextract 类初始化炸弹（已亲自验证键值）**
- `NvidiaDenoiser.java:70` 把 `isRtxVoice` 传给签名命名为 `NVAFX_PARAM_MODEL_PATH(boolean gtx)` 的方法（`NvAudioEffects.java:1242-1248`：gtx=true → "denoiser_model_path"，gtx=false → "model_path"），即**旧 RTX Voice DLL 拿到新 SDK 的键、新 SDK 拿到旧键**，与两个键名的语义互相矛盾；至少一处命名错误，若确为反转则 RTX Voice 用户首次设参即 INVALID_PARAM。
- `NvAudioEffects.java:13-16`：static 初始化即在类加载时 `libraryLookup("nvAudioEffects")`，正确性依赖"NvidiaDenoiser 先 System.load"的隐式时序；提前引用 → `ExceptionInInitializerError` 且此后永久 `NoClassDefFoundError`。
- **修复**：对照真实 DLL 确认键映射后拆成两个显式方法；以实际使用的 ~10 个符号手写 ~150 行绑定类替换 1,354 行 jextract 全量生成物（~90% 死代码），一并消灭初始化炸弹。

### 2.4 安全

**D1 [P1] TcpTunnelServer 无认证 + 绑全网卡（已亲自验证）**
- `TcpTunnelServer.java:44`：`new ServerSocket(port)` 绑 0.0.0.0；整条链路无认证、无连接数上限、无速率限制。任何可达主机可把它当免认证 UDP 转发跳板向 `tsHost:tsVoicePort` 注入报文；每连接 2 线程（93/123 行）可耗尽线程/句柄。默认 `tcpTunnelPort=0` 关闭，**一旦启用应视为 P0**。
- **修复**：默认绑 127.0.0.1 且 bind 地址可配；首帧共享令牌握手（magic+token）；连接上限（如 16）+ per-IP 限流。

**D2 [P1] PairingStore / 身份文件非原子写（已亲自验证）**
- `PairingStore.java:157`：`Files.writeString(file, json, UTF_8)` 直接覆写——崩溃/断电产生半截 JSON，下次 load 失败走空 store，**全部配对静默丢失**。服务端 `Ts3Identity.save`（199-204）同样直写，且 load 失败 `catch { return null; }` 静默重新生成身份（TS uid 变化、配对全失效、无日志）。
- **修复**：写 `.tmp` 后 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`（客户端 `ConfigFileUtil.writePrivate` 已是模范实现，直接复用其模式）；load 失败加 warn。

**D3 [P1] 自愈链使用与故障同源的数据源（已亲自验证）**
- `PlayerVoiceClients.reconcile`（121-127）以 `OnlinePlayerRegistry` 判在线，而后者与"残留条目"由**同一个** SVC `PlayerDisconnectedEvent` 维护——事件丢失时事实源与消费方一起失真，声称自愈的场景恰恰无法自愈 → 每残留玩家一个 TS 连接 + 2 线程泄漏至重启。
- **修复**：reconcile 改为对照 `mcServer.getPlayerList()` 等独立事实源；`OnlinePlayerRegistry` 提供权威对账入口。

---

## 3. 跨项目重复代码量化（重构依据）

服务端 `ts3voice` 包 2,676 行，与客户端副本逐行 diff（忽略 CRLF/包名/日志标签）：

| 文件 | 服务端行数 | 差异行 | 相同度 | 分叉点 |
|---|---|---|---|---|
| PacketType | 43 | 1 | ≈100% | 仅包名 |
| Ts3VoiceException | 15 | 1 | ≈100% | 仅包名 |
| Ts3Crypt | 665 | 2 | ≈100% | 仅包名+日志标签 |
| Ed25519Math | 184 | 2 | ≈99% | 客户端 add() 倍点快路径 |
| Ts3Identity | 251 | 4 | ≈98% | save()：客户端原子写/600 权限，服务端劣化 |
| Ts3Packet | 115 | 4 | ≈97% | 未知类型：服务端回退 VOICE vs 客户端 null |
| Ts3VoiceSelfTest | 92 | 5 | ≈95% | 客户端多 tcp 参数 |
| QuickerLz | 158 | 33 | ≈79% | 两套畸形输入加固策略，边界语义 `>=` vs `>` |
| Ts3Connection | 594 | 113 | ≈81% | **重组算法语义相反（服务端错误）**；传输抽象/语音去重策略 |
| Ts3VoiceClient | 559 | 33 | ≈94% | 客户端为超集（+文字/频道表/文件传输） |

**结论**：同一份代码的两条会漂移的演化支。唯一的协议级 P0 正是"一侧修复、另一侧未同步"的直接产物。此外 `net/BeaconPayload`、`GroupInfoPayload` 也是双副本（客户端 A- 级、服务端 B+ 级，服务端缺 decode 范围校验）。

其它包内重复（择要）：
- 服务端连接状态机三份拷贝：`PlayerVoiceClients.runWhileOnline` ≈ `Ts3NativeTransport.voiceLoop`（含 5 个逐字重复常量、同样的 `Ts3Identity.generate(24, 8_000)` 内联），且各持有对方已修的 bug。
- `AudioCodec` 整类死代码却与 `McAudioMixer.decode/emitMixedOpus` 逻辑逐字重复。
- SVC 内部反射：`GhostPlayerInjector` 与 `BeaconSender` 各一份"PlayerStateManager 反射遍历"实现（Method 缓存策略还不一致）；客户端 `ClientStateReflector` 与 `TsAudioInjector` 的 `ensureReflection + Class.forName` 模式重复两遍、另有 4 处现场 `Class.forName`。
- TS3Listener 9 个空方法适配器 ×2（`TsGhostPresenceListener`、`PairingManager`）。
- "try-close-null"失败降级样板：`AudioPipeline` ×3；"丢最旧"队列模板 ×3（mixer/forwarder/injector）。
- `hex()` ×2、SHA 包装 ×3（ts3voice 包内）；`"[MC] "` 魔法前缀 ×4（客户端两文件）。

---

## 4. P2/P3 逐条清单（按主题）

### 4.1 架构与 SOLID（P2 为主）

1. **[P2] `TsVoiceManager`（客户端，543 行）God class**：26 个 static 字段集 5 职责（激活策略/会话生命周期/麦克风路由/聊天桥/帧分发）。拆 `ActivationPolicy`（纯函数可测）/`SessionLifecycle`/`MicRouter`。
2. **[P2] `McToTsDispatcher.java:62-78` 服务定位器反模式**：if-else 三路分发直读 `TsBridgeMod` 静态字段，新增模式必改此方法。抽 `McAudioRoute { accepts(); forward(); }` 路由表注入。
3. **[P2] `TsAudioTransport` 接口契约失真**：`sendMcAudio(UUID, seq)` 为废弃的 AudioBot HTTP 方案量身定做，Native 实现忽略参数（`Ts3NativeTransport.java:320-330`），调用方伪造 `nameUUIDFromBytes("mixer-output")`；`isStarted()` 两实现语义不一致。重订契约：`start/stop/isConnected + sendMixedFrame + setOnTsAudioListener`。
4. **[P2] DSP 管线缺统一抽象**：四处理器签名各异、生命周期各写各的，`AudioPipeline` 静态可变全局单例。定义 `interface AudioStage { boolean process(short[]); default void close(); }`，init/reconfigure 变组装 `List<AudioStage>`。
5. **[P2] `ClientStateReflector`/`TsAudioInjector` SVC 内部桥碎片化**：合并为单一 `VoicechatInternals` 适配器（持有全部反射句柄、一次性初始化、类型安全门面），集中版本兼容点。
6. **[P2] 全静态门面不可测试**：`McAudioInjector`/`PlayerVoiceClients`/`OnlinePlayerRegistry`/`McToTsDispatcher` 同一反模式；至少为 injector/dispatcher 引入可实例化门面 + 注入编解码器工厂。
7. **[P2] `Ts3VoiceClient`/`Ts3Connection` God class（服务端）**：混握手状态机/命令路由/成员表/转义解析/生命周期 + I/O/重传/心跳/窗口/重组。先拆 `CommandAssembler` 独立文件（P0 修复时顺手完成）。
8. **[P2] `TsBridgeCommands.doLink` 错误处理失效**：`BridgeConfig.save()` 内部吞 IOException 正常返回，命令层 catch 是死分支——磁盘写失败玩家仍看到成功。`save()` 抛出或返回 boolean。
9. **[P2] `BridgeConfig` 锁形同虚设**：`reload()` synchronized 但全部字段读写无锁；`doLink` 无锁写共享配置。所有变更走同一把锁的同步方法。
10. **[P2] `TsQueryClient.connect()` 持锁做网络 IO**：connect 期间维护线程/TS 事件线程/全部 BridgeChannelManager 方法集体阻塞在监视器。连接构建移出锁，原子替换 volatile Connection 快照。
11. **[P2] `Ts3Connection`（双端）sendLock 内做网络 I/O 与 RSA 谜题**：`solveRsaChallenge`（level 上限 1,000,000）可在 recv 线程持锁计算数分钟。level 收紧到 ~10^4 或移出锁外；raw 发送移出锁。
12. **[P2] `Ts3Connection`（服务端）connect()/stop() 可重入不隔离旧线程**：不 join 旧 resend/recv 线程即翻回 closed=false，可双重驱动同一 socket。单次使用语义或 join。

### 4.2 逻辑与并发缺陷（P2）

13. **[P2] `GhostPlayerInjector.java:282-289`**：TS 事件线程/维护线程并发读 `getPlayers()` 与主线程增删并发（CME/快照风险）——广播投递主线程执行。
14. **[P2] `TsGhostPresenceListener` clientCache 无界 + clid 重用污染**：leave 事件丢失后条目永存，TS 重用 clid 会解析出错误用户。加 TTL + reconcile 清理。
15. **[P2] `PairingManager.java:270-334`**：`!pair` 不校验 targetMode/来源频道，任意 TS 用户在任意频道/私聊发 `!pair <code>` 都能配对，与注释"桥接频道中发送"不符。校验 targetMode==CHANNEL 且目标为桥接频道。
16. **[P2] `PairingManager.java:81-119`**：同一玩家可同时持有多个有效配对码；cancel 只删第一个。create 前清旧 pending；cancel 删全部匹配。
17. **[P2] `TsChannelGroupSync.java:30,83-118`**：频道/群组所有权仅凭名字判定，TS 子频道与管理员手工 SVC 群组同名时，TS 频道删除会**误删管理员手工群组**。所有权改记录本进程创建的 group UUID 集合。
18. **[P2] `BridgeChannelManager` 永久频道泄漏**：创建 PERMANENT 频道且 `/tsbridge link` 改名后旧频道永久遗留。记录旧 channelId 提供清理。
19. **[P2] `TcpTunnelServer.java:97-105`**：accept 循环任何瞬时 IOException 永久退出而 `running` 仍 true（`isRunning()` 谎报，且该方法零调用）。区分关闭与瞬时错误。
20. **[P2] `TcpTunnelServer.java:114`**：`setSoTimeout(30_000)` 单向空闲踢人——纯收听客户端每 30s 被断（协议无应用层心跳）。加 ping/pong 帧或区分空闲语义。
21. **[P2] `Ts3Connection.java:453-465`（客户端）**：会话累计命令数回绕后，迟到命令包以负 generationId 派生密钥，解密失败被静默丢弃。代推断基于本地"已见最大代"。
22. **[P2] `GroupChannelMapper.java:20-22,152-160`**：`reset()` 与 `tick()` 跨线程读写非 volatile 字段；`tick()` 首行 `routing = null` 先清后建，异常时麦克风被静默丢弃最长 2s。字段 volatile + 局部构建后原子替换。
23. **[P2] `TsAudioInjector.createGhost` 与 `GhostSkins.register` 竞态**：先注册皮肤（内含主线程任务查 activeGhosts）后 put → 主线程抢先时皮肤注册永久放弃。调换顺序。
24. **[P2] `McAudioInjector.java:90,151-171`**：`computeIfAbsent` 的 createUser 返回 null 不缓存 → 每帧重建 StaticAudioChannel 失败 + 50 次/秒日志风暴。失败负缓存 5s。
25. **[P2] `McAudioInjector.shutdownAll` refresher 关闭竞态**：shutdownNow 在锁外，与 createUser→startRefresher 竞态可致 targets 永不刷新。判/关/置 null 全入锁。
26. **[P2] `Ts3NativeTransport` stop/connect 连接泄漏**：`cleanupVoiceClient()` 与 `connect()` 无共同锁，stop 可漏掉刚建立的新客户端（孤儿 UDP socket + 线程）。PlayerVoiceClients 已修，本类照搬。
27. **[P2] `Ts3AudioBotTransport.java:274-289`**：重连线程与 stop() 竞态可新建 stop 后的 WS 连接且无人关闭。可取消的 ScheduledExecutor 或赋值后二次校验回滚。
28. **[P2] `Ts3Connection.java:69,275,422`**（服务端）：`lastMessageMs` 非 volatile 跨线程读写（数据竞态）。volatile 或锁内快照。
29. **[P2] `Ts3Connection.java:401-404`**：RTO 退避按"每个重传包"翻倍（同 tick 3 包 ×8），且初始 RTO==上限使退避失效。按超时事件计。
30. **[P2] `QuickerLz.java:41-44`（服务端）**：缺负数/长度校验，`new byte[decompressedSize]` 负数 → NASE，依赖外层 catch 兜底。采用客户端版本的完整校验。
31. **[P2] `Ts3VoiceClient.java:285-286` + `Ts3Crypt.b64`**：握手参数缺失时 `decode(null)` 抛 NPE 沿 recvLoop 兜底 catch 逃逸，握手静默卡死 30s。b64 对 null 返回 null + cryptoInit 前置校验。
32. **[P2] `PlayerVoiceClients.java:214,216,257`**：退避在连接"刚建立"即重置（抖动型故障永远从 2s 爬）；一次失败打两条语义不同日志。稳定 >30s 再重置。
33. **[P2] `GhostPlayerInjector.java:248`**：循环后二次快照 `onlinePlayers().isEmpty()` TOCTOU。复用循环前快照。
34. **[P2] `PlayerVoiceClients.java:133-147`**：查询谓词 `isMcOwnedUid` 带写副作用（遍历中 add）。拆分登记与查询。
35. **[P2] `knownMcUids` 静态集合无界增长且 shutdownAll 不清理**。对称清理。
36. **[P2] `OnlinePlayers.java:25-39`**（客户端）：每 tick clear+refill，TS 接收线程读空表 → 显示名瞬时跳变。局部构建后 volatile 整体替换。
37. **[P2] `ServerConfigStore.load/get` 弱一致**：reload 时并发 get 可 computeIfAbsent 出被清掉的 Entry。构建新 Map 后整体替换 volatile 引用。
38. **[P2] `ConfigScreen.java:242-296`**（客户端）：全局密码被"晋升"为每服密码（null 继承语义被破坏）；端口解析失败静默回退 9987 且不校验范围；`init()` 内做副作用并落盘（resize 触发磁盘写）。三项分别修。
39. **[P2] `RayTracedReverb.java:123-133`**：`cleanup()/disable()` 零调用，混响关闭后 OpenAL auxSlot/effect 永不释放。接 shutdown 或 tick 内检测删除。
40. **[P2] `RayTracedReverb` 5ms 下限风险**：`reverbIntervalMs` clamp 下限 5ms，此时每 tick 满负荷 200 次 `level.clip` + 实体查询（主线程卡顿）；且该配置**从未被 ModConfig 写入**（死配置，恒 500）。下限 ≥100ms + 接通或删除配置。
41. **[P2] `AlSpeakerMixin.java:63-74`**：AEC 参考信号与真实播放信号在混响/水下开启时系统性失配（回声残留）——至少文档标注，湿声离线混入参考为可选优化。
42. **[P2] `MicProcessorMixin.java:18-25`**：未写 `require = 0`，SVC 更新改名即整个 mixin 硬崩（同目录 GameProfileUtilsMixin 已示范软降级）。统一 mixin 纪律：增强失败必须降级到原生 SVC。
43. **[P2] `VoiceChatScreenMixin`**：三重脆弱（反射读私有字段 + 反射调 addRenderableWidget + 按钮坐标魔法算式硬编码假定 SVC 布局）。@Accessor mixin 取代反射；坐标常量化并注释依赖的 SVC 布局版本。
44. **[P2] `BeaconSender` 每 2s 全量反射扫描 + 明文密码周期下发**：成员/群组快照变化才发包；`readMemberGroups` 的 Method 未缓存；`BeaconSender.java:151` 对反射取得的枚举做引用相等比较（ClassLoader 差异即静默全判 NORMAL）。枚举改 toString 比较。
45. **[P2] `BeaconPayload` 无协议版本号**：双端版本错配字段静默错位。首字节加 protocolVersion。
46. **[P2] `WebRtcNative.java:66-68`**：处理失败既不禁用也不降级，每帧 error 日志（100 条/秒风暴）。对齐 AecProcessor"一次失败即停用"。
47. **[P2] `NvidiaDenoiser.java:127`**：帧长按 `sampleRate/100` 猜测，正确做法 Load 后查询 `NVAFX_PARAM_NUM_INPUT_SAMPLES_PER_FRAME`。
48. **[P2] 采样率是贯穿全链路的假参数**：`TsAudio.micSampleRate` 无人写入（死字段）、AecProcessor/WebRtcNative 硬编码 48k——任何一处接通真实采样率即静默产出坏音频。显式声明仅支持 48k 并入口断言，或真正参数化。
49. **[P2] `AudioPipeline.java:171-174`**：AEC 分支 AGC 在 AEC 之前执行（放大回声削弱收敛），与非 AEC 分支顺序相反。统一 NS → AEC → AGC。
50. **[P2] `AudioPipeline.shutdown()` 零调用**：客户端退出时 apm.dispose/NvAFX_DestroyEffect 不执行。接 ClientLifecycle.CLIENT_STOPPED。
51. **[P2] `McToTsDispatcher.java:49-58`**：群组校验未复用 `TsBridgeMod.isRealGroup` 防线（该防线记录了 SVC GroupImpl 包装缺陷）；fail-closed 分支仅 debug 无限频。
52. **[P2] `McAudioMixer.start()/stop()` "幂等"注释虚假**：`if (running) return;` 非原子。CAS 守卫。
53. **[P2] `PlayerVoiceClients` 锁纪律混用**：类锁与 CHM 两套机制并存。统一 CHM 原子操作 + per-PlayerClient 锁。
54. **[P2] `TsQueryClient.reconnectThread` 非 volatile**：stop 可能读到 stale null 漏 interrupt。

### 4.3 性能（P2/P3，实时路径分配与调度）

55. **[P2] `PlayerVoiceClients.java:209-267` ≈ `Ts3NativeTransport.java:90-162`**：连接状态机两份拷贝（见 §3）。提取 `ReconnectingVoiceClientRunner`。
56. **[P2] 每连接 2 个裸线程**：perPlayer 模式 N 玩家 = 2N 线程 + N socket。共享 ScheduledExecutor（resend 本质是 100ms 周期任务）。
57. **[P2] `Ts3Connection` 接收热路径每包 2 次分配 + `Ts3Crypt` 每包 5+ 临时对象**：复用 DatagramPacket、缓存 KeyParameter。
58. **[P2] `McAudioMixer` 每 tick 稳态分配**：`new LinkedHashMap`（50 次/秒）、静音帧、`short[][]`、失败帧。字段级复用 + 静音帧 static final。
59. **[P2] `WebRtcNative` 实时路径每帧 2 个 byte[]（192KB/s 垃圾）**；`NvidiaDenoiser.processAfxSdk` 每帧 4 次分配 + 2 次拷贝且 `out` 数组从未读。照抄 AecProcessor 的预分配方案。
60. **[P2] `AecProcessor.java:95-105`**：每 10ms 帧 4 次 wrap/asShortBuffer。构造器建一次复用。
61. **[P3] `AlSpeakerMixin.applyReverbFx` 每 writeSync（每 speaker 每 20ms）重发 AL 调用 + synchronized**。缓存上次 applied 状态。
62. **[P3] `AgcProcessor` 每帧重算常数**：`tgtRms/maxGain` 提 static final；`Math.min(30.0, 40.0)`（恒 30）命名常量。
63. **[P3] `McToTsForwarder.java:29`**：`poll(10ms)` 空转 100 次/秒。改 `take()` + interrupt。
64. **[P3] `AecReferenceMixer` 每帧 `new short[480]`** + 静态 CLQ 无满队 O(1) 丢弃路径。ArrayBlockingQueue(50) + offer 失败即丢。
65. **[P3] `RayTracedReverb.java:330`**：`Math.random()`（内部同步）混入主线程聚合。ThreadLocalRandom + 常量。
66. **[P3] `TsVoiceClient.voiceCounter`（双端）**：语音接收热路径每包 incrementAndGet 而 getter 零引用。删除（纯收益）。
67. **[P3] `TsBridgeMod.isRealGroup` 每次现场反射 getMethod**：缓存 Method（BeaconSender.readMemberGroups 同病）。
68. **[P3] `AvatarPipeline.renderFace`**：写临时文件再读回，NativeImage 可内存直编；HttpClient 永不关闭。

### 4.4 可读性 / 死代码（P2/P3）

死代码全部经 grep 全源码树验证零引用：
- 服务端：`AudioCodec` 整类（109 行）；`OnlinePlayerRegistry.size()`；`Ts3AudioBotTransport.sendCounter`；`BridgeChannelManager` 3 个方法（getClientsInBridgeChannel/moveClientToBridge/findClientByUniqueId）；`TsPresenceUpdater` 不可达 else 分支（137-154）；`Ts3Crypt.reset()`/`DERBitString` 导入；`QuickerLz` 死导入+死分支；`Ts3VoiceClient` 3 个死符号 + 恒真条件（SelfTest:88）+ 无效 try/catch 重抛。
- 客户端：`Ts3VoiceClient.getMyUid/setNickname/voiceCounter+getter`；`Ts3Connection.getClientNid`；`ClientStateReflector.updateTalking`（说话指示从未启用）；`TeamspeakSvcClient.configDir`；`Ts3Identity.getPublicKey`；`TsVoiceManager.currentClientForSync`（与 currentClient 重复）；`ModConfig.playerName`；`AlSpeakerMixin` 无用 `@Mutable @Shadow source`；`BlockSoundProperty.occlusion` 死字段（50 个 put 的第 4 实参全部白传）；`HitPoint.round/face` 死组件。
- 工程混置：`Ts3VoiceSelfTest`/`Ts3ChannelTest`（双端 CLI main 工具）与 `public static DEBUG/DUMP_FRAGMENTS` 可变调试开关打进生产源集。移 test sourceset，DEBUG 改 logger 级别。
- 遗留路径：v1 `McToTsForwarder` + `pairings` 链路仅当 mixer 启动失败才可达；`Ts3AudioBotTransport` @Deprecated 且官方端点不存在但仍是运行时回退候选（build.gradle 为此 include 了 Java-WebSocket）。评估整条 v1 路径删除或显式配置开启。
- 文档漂移：`BridgeConfig.botApiUrl/botApiToken` 注释称"从未被读取"实际有读取方（且 token 为明文密钥）；`TsAudioFrame` javadoc 仍称 TS3AudioBot 订阅；`BridgeChannelManager:54` 注释与 API 语义相反；`Ts3Connection RFC6298` 注释实为自定义 EMA；`AudioExtraScreen` 注释与实际入口矛盾；`PairingManager` 注释残留 "Task 8/Task 9" 任务管理器行号。

可读性择要：`TsBridgeMod.initializeTsComponents` 200 行 11 步编号（含 2.5/8.5）；`RayTracedReverb.finishBatch` 魔法数字密集区（17/340/0.6f/3.6f…全部裸数字，无人敢改）；`NvAudioEffects` 1,354 行 jextract 生成物（实际使用 ~10 符号）；`VoiceChatScreenMixin` `guiLeft+6+20+2+20+2…` 坐标算式；`AgcProcessor` `Math.min(30.0,40.0)`；`FRAME_SIZE=960` 三处独立定义；`"group-sync"` 字符串比较 4 处（应枚举）；`getPluginId()="svctsbridge"` vs `MOD_ID="tsbridge"` 双身份；`fabric.mod.json` authors 仍是 "your-name" 占位符；`Ts3Crypt` stringly-typed 握手错误（应抛 Ts3VoiceException）；`Ts3Packet` 8 个 public 可变字段（协议不变量无保护）；`escape()` 输出 `\x` 而 `parseParams` 无对应分支（控制字符往返破坏）。

### 4.5 使用体验（客户端 UI / 服务端命令）

1. **[P2] `ConfigScreen` 状态区/按钮文案只在 init() 计算一次**：点击"保存并连接"后不随后台连接结果刷新，重开界面才更新。Screen.tick() 重算。
2. **[P2] `AudioExtraScreen.java:26-45` 对 public 字段用反射且 `catch (Exception ignored)` 吞掉**（已亲自验证）：按钮点击无反应且无日志、字段重命名静默失效。直接字段访问（ModConfig 字段本来就是 public）。
3. **[P2] `AudioPipeline.java:128-142` 玩家可见文案硬编码中文**而非 translatable key（项目已有 en_us/zh_cn lang 体系）；且 `mc.player==null` 时提示被永久跳过。改 Component.translatable + 延迟到玩家出现。
4. **[P3] `AudioExtraScreen` 闪避档位 {0.30,0.45,0.60} 不含默认值 0.35**：首次点击像"倒退"。加入 0.35 或改滑条。
5. **[P3] `TsBridgeCommands:94-95`**：把内部异常消息直接发给玩家（NPE 显示 "null"）。通用文案 + 详情进日志。
6. **[P3] `/tsbridge status` 12 行拼单个 Component** 聊天栏渲染参差；裸 `/tsbridge` 无帮助文本。
7. **[P3] `ConfigScreen` 主菜单打开时读写 `""` 占位键**：保存产生永不被使用的孤儿条目。主菜单禁用保存并提示。
8. **[P3] `BridgeConfig` reload 把 `debug` 列入"需重启"清单**：改一个日志开关导致整次 reload 被拒。移入热更字段。
9. **[P3] `TsGhostPresenceListener` 断线期间 Ghost 全残留**直到重连（无兜底清理）。onDisconnect 可选清空或缓存兜底移除。
10. **[P3] `AudioExtraScreen` 自称覆盖"降噪/AGC/AEC/混响/Ducking"但缺 aecDelayMs/lowLatencyMode/NVIDIA 路径入口**（仅 JSON 可改）。

### 4.6 工程化（构建 / 测试 / 流程）

1. **`src/test` 为空**：两个 mod 的 Gradle 构建内零单元测试；全部回归套件（`review/*/Run*.ps1`）在 Gradle 之外手工运行，无 CI 防回归。把隔离回归迁入 `src/test`（替身边界已验证可行），至少为重组器/映射器/状态机这类纯逻辑建 JUnit。
2. **`gradle.properties`：`org.gradle.daemon=false`** 拖慢迭代；`fabric_loom_version=1.17-SNAPSHOT` 构建不可复现，锁到 release。
3. **`review/build-fixes/gradle-home` 等 200+ MB 构建缓存/产物混入源码树**：移出仓库或加 .gitignore（本目录非 git 仓库，建议 git init 后一并处理）。
4. **敏感信息**：`testserver/README.md` 含明文 ServerQuery 密码（既往评审已指出）；`botApiToken`、`queryPassword` 明文进 config 文件——至少文档标注权限要求。
5. **`fabric.mod.json`（服务端）**：authors 占位 "your-name"；双端均未声明 `icon`。
6. **无任何静态分析**：建议接入 ErrorProne/NullAway（本项目大量 `catch (Exception ignored)`/可空返回值正是其靶子）。

---

## 5. 六大重构方案（按投入产出排序）

**R1：抽取公共协议模块 `tsproto`（终止双副本）** — 高收益 / 中成本
- 新 Gradle 子模块 `java-library`，先迁相同度 ≥97% 的 6 个纯协议文件（1,316 行零成本合并）；`Ts3Connection/Ts3VoiceClient` 以 `Transport` 接口 + Listener 回调 + logger 注入参数化后合并；两端 mod 依赖之。服务端立即获得客户端已修的分片重组、QuickerLz 校验、身份原子写。
- 验收：`diff -r` 双副本清零；P0 回归（构造 2/3/5 分片命令组验证重组）。

**R2：生命周期统一（消灭 shutdown 竞态与手工编号）** — 高收益 / 中成本
- `interface BridgeComponent extends AutoCloseable { void start(BridgeContext ctx); }`；`LifecycleManager` 执行"置 shutdown 标志 → join initThread → 逆序 close（统一独立 try/catch）"。`TsBridgeMod` 的 130 行 shutdownAll 样板与 B2 竞态一起消失；客户端 `TsVoiceManager.sessionLoop` 同模式复用。

**R3：BridgeContext 依赖注入（两端去静态服务定位器）** — 高收益 / 高成本（可增量）
- 服务端：14 个静态字段 → `BridgeContext` 不可变聚合，组件构造注入；`McToTsDispatcher` 改路由表。客户端：`TsVoiceManager` 拆 `ActivationPolicy/SessionLifecycle/MicRouter`。为 R5 的可测试性铺路。

**R4：音频热路径纪律（成文 + 全链路执行）** — 中收益 / 低成本
- 规范三条：① 每流有界抖动缓冲 + 积压排水（修 C3）；② SVC 回调线程只入队（修 C2）；③ 实时路径零分配（预分配缓冲统一照 AecProcessor 模式，修 59/60/64）。类级锁 → per-user 锁（C1）。

**R5：测试基建** — 高收益 / 中成本
- R1 完成后协议模块天然可测（重组器/加密/身份/QuickerLz 全是纯逻辑）；`review/` 现有隔离回归（替身边界已验证）迁入 `src/test` + 接 CI。这是防止"双副本漂移"类问题复发的唯一长效机制。

**R6：安全加固（隧道 + 持久化 + 协议）** — 中收益 / 低成本
- 隧道：默认回环绑定 + 令牌握手 + 连接上限（D1）；PairingStore/身份原子写（D2）；BeaconPayload 协议版本号；`!pair` 来源校验（4.2-15）。

---

## 6. 文件健康度总表（90 文件，择要分级）

**红（优先处理）**：服务端 `Ts3Connection`（P0+语音窗口+锁内 I/O）、`TsBridgeMod`（上帝类+竞态）、`TcpTunnelServer`（安全）、`NvAudioEffects`（1,354 行生成物+初始化炸弹）、`AudioExtraScreen`（反射+吞异常）、`AudioCodec`（整类死代码）。
**黄**：`PlayerVoiceClients`、`Ts3NativeTransport`、`McAudioMixer`、`McAudioInjector`、`TsVoiceManager`、`Ts3VoiceClient`（双端）、`PairingManager`、`TsGhostPresenceListener`、`BeaconSender`、`TsQueryClient`、`TsChannelGroupSync`、`WebRtcNative`、`NvidiaDenoiser`、`ConfigScreen`、`VoiceChatScreenMixin`、`MicProcessorMixin`、`QuickerLz`（服务端版）。
**绿（可作范式）**：`ConfigFileUtil`（原子写+600+.broken 备份——应成为全项目持久化范本）、`ClientStateReflector`（线程规约清晰）、`Ed25519Math`（RFC 向量自检）、`ModMenuIntegration`、两个 SoundAccessor、`TsPresenceUpdater`（结构最干净）、`GameProfileUtilsMixin`（require=0 范式）、`net/GroupInfoPayload`（toString 脱敏）。

---

## 7. 建议实施顺序（三阶段）

| 阶段 | 内容 | 对应条目 |
|---|---|---|
| 第 1 周（止血） | P0 重组器移植；B2 shutdown 顺序；D2 原子写；D1 隧道默认回环；C4 反射缓存；47/49 NVAFX 键与 AGC 顺序 | §1、B2、C4、C5、D1、D2 |
| 第 2-3 周（结构） | R1 tsproto 模块；R4 音频纪律；死代码批量清理（~600 行）；mixin require=0 统一；使用体验批次（4.5 全部） | A1、C1-C3、4.4、4.5 |
| 第 4 周+（偿还） | R2 生命周期；R3 BridgeContext；R5 测试基建；遗留路径（v1 forwarder/AudioBot）处置 | A2、A3、4.1、4.6 |

---

## 附：验证状态说明

- 评审人亲自读码验证（13 条，全部属实）：§1（P0）、B2、C1、C2、C3、C4、C5（键值反转 + AGC 顺序 + 反射读 public 字段）、D1、D2、D3、A2（静态字段清单）。
- 其余条目来自分包审查代理，均附文件:行号且代理已用 grep/局部复现验证调用方与可达性；行号基于当前工作区源码，重构时可能漂移。
- 上一轮 R01–R16 修复状态：抽查 R10（TsQueryClient 未提交连接 finally 回收）、R12（TcpTunnelServer 会话集合 + stop 全关）属实；全套件声称 51/51 PASS，本次未重跑。
- 范围外（未覆盖）：真实 TS3 服务器端到端联调、NVIDIA/WebRTC 实际设备效果、simple-voice-chat-26.2/voicechat-discord-main 上游源码的独立缺陷审计。
