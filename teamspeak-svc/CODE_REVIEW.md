# teamspeak & svcbridge 项目审查报告

- **审查日期**：2026-10-26
- **审查对象**：`teamspeak-svc` v0.8.1（MC 26.3 / Fabric / Java 25）及其配套 `tsbridge` v0.3.4 协议栈拷贝
- **审查方式**：核心链路逐行精读 + 3 个并行子系统审查 + 对照 SVC 26.2 / JDK 实测验证
- **总体结论**：协议栈核心（Ed25519 / AES-EAX / TCP 隧道 / 命令重组）质量高、经交叉验证算法正确；但从 SVC-Extra 移植的音频增强层问题密集（两个功能为死代码、AEC 完全无效、多个配置项永不生效）；另确认 2 个高危逻辑 bug（群组密码映射失效、`/tsvoice status` 必抛异常）。文档整体停留在 v0.1。

---

## 1. 项目概览

| 目录 | 内容 | 角色 |
|---|---|---|
| `teamspeak-svc/` | Fabric 客户端 mod，约 9000 行 Java | 核心交付物：SVC 界面 + TS 后端 |
| `teamspeak&svcbridge/`（嵌套同名目录） | tsbridge 服务端 mod v0.3.4 | 服务端 SVC↔TS 桥（ServerQuery、隧道、信标） |
| `tsbridge-standby/` | `tsbridge-0.3.0+26.3.jar` | 旧构建备份 |
| `simple-voice-chat-26.2/` | SVC 上游源码 | 调研参考 |
| `voicechat-discord-main/` | SVC↔Discord 桥源码 | 参考 |
| `testserver/` | 本地 MC 26.3 测试服 | 联调环境（注意：README 含明文 ServerQuery 密码） |
| `.zcode/specs`、`.trae/specs` | 各版本 spec/tasks | 需求文档 |

---

## 2. 问题总览

| 严重度 | 数量 | 分布 |
|---|---|---|
| 高 | 7 | 群组映射 1、命令 1、AEC 2、死功能 1、配置桥 1、native 崩溃 1 |
| 中 | 18 | 音频 8、线程安全 4、安全 3、工程化 3 |
| 低 | 20+ | 清理、性能、健壮性、文档细节 |
| 文档不一致 | 12 | README/ROADMAP/spec 与代码 |

---

## 3. 高危问题（建议立即修复）

| ID | 文件:行号 | 问题 | 影响 | 建议 |
|---|---|---|---|---|
| H1 | `GroupChannelMapper.java:52` | 用 `group != lastGroup` 引用比较判断群组变化，但 SVC 的 `getGroup()` 每次返回新 `ClientGroupImpl`（已核对 SVC 源码） | 每 2 秒 tick 误判“群组变了”→ 清空 tsbridge 下发的频道密码、刷新 6 秒等待窗口 → **带密码群组永远进不了子频道**，每 2 秒刷 warn | 改比较 `group.getId()` 或 `Objects.equals` |
| H2 | `TeamspeakSvcClient.java:162-166` + `lang/*.json:19` | `tsvoice.status` 含 6 个 `%s`，`.formatted()` 只传 4 个参数（且尾部手动 append 重复内容） | `/tsvoice status`、`/tsvoice connect` **必抛** `MissingFormatArgumentException` | 占位符与参数对齐（建议 4 个并删除重复 append） |
| H3 | `AudioPipeline.java:196` + `AecScheduler.java:98-117` | `submitMic` 处理的是队列内的音频**副本**，结果写回副本；参考流消费速率只有生产一半（20ms 推 2×480 帧只 poll 1 帧） | 回声消除**零效果**，仅耗 CPU；参考队列堆到 300 上限后 APM 拿到约 3 秒前的陈旧参考 | 同步处理或回写原数组；消费侧按采样数对齐并压低队列上限 |
| H4 | `DuckingManager.java:35`、`RayTracedReverb.java:60-75` | `tick()` 全工程无调用点（已 grep 确认） | 闪避功能从未运行；混响槽从不设参数（OpenAL 默认值）；`isUnderwater()` 恒 false，水下滤波永不触发 | 注册到客户端 tick；接通前先修“接通即坏”的两处（见 M9/M10） |
| H5 | `audio/TsAudio.java:43,47` | `duckingLevel = clamp(TsAudio.duckingLevel)`、`aecDelayMs = TsAudio.aecDelayMs` 自赋值；`nvidiaDllPath`/`nvidiaModelDir` 也未从 config 刷新 | 闪避深度、AEC 延迟、NVIDIA 路径覆盖**四项配置永不生效** | 改为读 `config.*` |
| H6 | `AudioPipeline.java:78`、`AecScheduler.java:119-125` | 重建 WebRTC 实例不 `close()`；`stop()` 在 `shutdown()` 后不等待即 `apm.dispose()` | 每次 reload 泄漏 native APM；AEC 线程在途时 dispose 是 **use-after-free（JVM 崩溃）** | `awaitTermination` 后再 dispose；shutdown 中 `close()` |
| H7 | `AudioPipeline.java:186-203` | AEC 分支不调用 NVIDIA/WebRTC 降噪，而 `isNsActive()` 同时禁用了 SVC 原生降噪 | **NVIDIA_AI + AEC 组合下无任何降噪在跑** | AEC 分支内同样调用 NS，或在 AEC 的 APM 里启用 NS |

---

## 4. 中危问题

### 4.1 正确性 / 功能

| ID | 文件:行号 | 问题 | 影响 / 建议 |
|---|---|---|---|
| M1 | `ConfigScreen.java:210-229, 263-274` | `saveConfig` 把 `serverPassword` 放进 `if (!editLocked)`，但密码框不随信标锁定 | 信标模式下密码可见可编辑却永不落盘；`TsVoiceManager` 的“信标+每服密码”路径不可用。密码移出锁定分支 |
| M2 | `GroupChannelMapper.java:31-36` | `onGroupInfo` 在 `lastGroup` 未就绪时直接丢弃 payload（定向单发，不会重发） | 等 6 秒退化为**无密码频道**，违背“密码只发给成员”的安全模型。缓存 pending payload 待匹配 |
| M3 | `ConfigScreen.java:198-199` | `new AudioExtraScreen()` 未 `setParent(this)`；`AudioExtraScreen.setParent` 无任何调用方 | 从配置屏进音频页后“完成”直接退游戏，未保存编辑丢失 |
| M4 | `TsVoiceManager.java:155-156` | 重连 key 只含 `password != null`，不含密码内容 | 修改密码不触发重连 |
| M5 | `TeamspeakSvcClient.java:79-80` | 任意 MC 服务器的 beacon 免确认触发自动连接任意 host:port | 设计取舍（`useServerBeacon` 可关，默认开），建议首次提示 + 文档明示 |
| M6 | `TsVoiceManager.java:176-179` | `manualDisconnect` 后 state=DORMANT，但后续 beacon/配置事件会再次 `evaluateActivation` 自动重连 | 用户的“断开”意图可能被下一次事件覆盖 |
| M7 | `AudioChannelMixin.java:20-28` | 默认 `120/20/3=2`，**小于** SVC 原生默认 3（`ClientConfig.java:106`）；注释却称“调大” | 实际是缩小抖动缓冲，适得其反；`networkToleranceMs` 在 ModConfig 无字段 |
| M8 | `AudioPipeline.java:169-180` | `pushRemoteReference` 无调用者；`AecSyncBuffer`、`getNearestPlayerVelocity`、`reconcile`、`displayNameFor` 亦为死代码 | 删除或接线 |
| M9 | `DuckingManager.java:51-59` | 闪避时序倒置：说话中 `elapsed≈0` → 不压音量；**闭嘴后**才越来越深 | 接通 tick 前必须修，否则“闭嘴才闪避” |
| M10 | `RayTracedReverb.java:85-95, 223-226` | 接通后：8 个 ForkJoin 线程访问非线程安全 `ClientLevel` 并在主线程 `join()`；`CopyOnWriteArrayList` 并发 add O(n²) | 世界查询回主线程/快照，纯计算才进线程池；改用每线程局部 List |
| M11 | `AlSpeakerMixin.java:116` | `mc.level.clip` 在 SVC 音频线程执行（每说话人每 20ms 一次，**现行活跃路径**） | 与主线程 tick 并发，可能崩溃/脏读。改为预计算或主线程快照 |

### 4.2 线程安全 / 资源

| ID | 文件:行号 | 问题 | 影响 / 建议 |
|---|---|---|---|
| M12 | `AudioPipeline.java:188-203` | 静态字段（`aecScheduler`/`webrtc`/`nvidia`/`agc`）与 `reconfigure()` 竞态，先查后用时可能被置 null | NPE 打穿 SVC 麦克风线程；字段加 volatile 或全程持锁 |
| M13 | `TsAudioInjector.java:235-247` | 在 TS 网络线程访问 `mc.level.getEntity` | 非线程安全访问。改 `Minecraft.execute` 或缓存主线程快照 |
| M14 | `GhostSkins.java:49-64`（调用方 `TsAudioInjector.java:134`） | 网络线程调 `SkinManager.createLookup`（TextureManager 注册） | 跨线程竞态/崩溃。包 `Minecraft.execute` |
| M15 | `PlayerVelocityTracker.java:20,28,53` | `HashMap` 主线程写、音频线程读 | 改 `ConcurrentHashMap` |
| M16 | `AudioPipeline.java:186` | 每 20ms 帧调用 `synchronized init()` | 与 reload 争锁时 mic underrun；改 volatile 快检 + DCL |
| M17 | `NvidiaDenoiser.java:61-74` | 部分初始化失败时已创建的 effect 句柄不销毁 | 每次失败泄漏 native 资源。catch 中 DestroyEffect |
| M18 | `TsAudioInjector.java:286-294` | `reconcile` 无调用点；TS 用户离线后通道/解码器不释放 | 泄漏随“见过的用户数”增长。接到 `syncAllGhostVisibility` / 周期 tick |
| M19 | `AvatarPipeline.java:145-148, 61, 117-143` | marker 写入前不建目录；两个 `NativeImage` 未 close | 首连成功路径 marker 落盘失败 → 每次重连重复上传头像；本地内存泄漏 |

### 4.3 协议健壮性

| ID | 文件:行号 | 问题 | 影响 / 建议 |
|---|---|---|---|
| M20 | `QuickerLz.java:19-27,36,54,83,140-143` + `Ts3Connection.java:625` | 无最小输入长度校验，畸形数据抛 AIOOBE（RuntimeException），上游只 catch `Ts3VoiceException` | 优雅降级路径失效（输入已过 MAC，仅恶意 TS 服务器触达）。入口校验 + catch 放宽 |
| M21 | `TcpTransport.java:27` | `SoTimeout=30s` 与应用层 `PACKET_TIMEOUT_MS=30s` 完全相等 | 计时器竞争，半开隧道以含糊日志断开。错开（35s）并区分异常类型 |
| M22 | `NvidiaDenoiser.java:113` | `frameSize=sampleRate/100` 硬编码，不查询 SDK 实际帧长 | 模型要求不同帧长时 `check` 抛异常打穿 mic 线程（AudioPipeline 未捕获） |
| M23 | `Ts3Packet.java:77-80` | 未知包类型（0x9-0xF）被静默映射为 VOICE | 扰动语音去重状态。返回 null 并丢弃 |
| M24 | `Ed25519Math.java:124-133` | 仿射 `add()` 不处理 `a==b` / `a==-b` 例外 | 恶意 license 理论上可构造异常派生密钥（无泄露风险）。转 `dbl` / 返回恒等元 |

### 4.4 安全 / 部署

| ID | 文件:行号 | 问题 | 建议 |
|---|---|---|---|
| M25 | `ServerConfigStore.java:35`、`ModConfig.java:54`、`TsIdentity.java:199-203` | TS 服务器/频道密码、身份私钥全部明文落盘 | POSIX 600 权限；私钥文件可考虑加密/提示；`GroupInfoPayload` 覆写 `toString()` 掩码 |
| M26 | `fabric.mod.json:23-29` | 只依赖 `voicechat_api`，未依赖 `voicechat` 本体；未装 SVC 时 6 个 mixin 静默失效 | 加 `"voicechat": ">=2.6.20"` |
| M27 | `WebRtcNative.java:70-105` | 失败路径残留临时解包目录；每次重试重新解压几十 MB | 固定缓存目录 + 失败标记；失败清理 |

---

## 5. 低危 / 清理项

| ID | 位置 | 问题 | 建议 |
|---|---|---|---|
| L1 | `Ts3Connection.java:31` | 30 秒无消息判超时；与 TCP SoTimeout 相同（见 M21） | 见 M21 |
| L2 | `TsTransport.java:50-60` | UDP `receive` 每包分配 4KB | 复用成员缓冲 |
| L3 | `WebRtcNative.java:53-56`、`NvidiaDenoiser.java:117-127` | 每 10ms 帧多组数组/Arena 分配 | 预分配复用 |
| L4 | `AecReferenceMixer.java:19`、`AecScheduler.java:73,81` | `ConcurrentLinkedQueue.size()` O(n) 在音频线程 | AtomicInteger 计数 |
| L5 | `AgcProcessor.java:30-35` | 上限常量恒 30dB，`min` 无意义；静音时增益冲满 → pumping | 提常量；加噪声门 |
| L6 | `AecScheduler.java:68,76` | `sampleRate` 参数被忽略，硬编码 48000 | 非 48k 重采样或断言 |
| L7 | `AecScheduler.java:65` | `scheduleAtFixedRate(tick, aecDelayMs, 10ms)` 初始延迟语义混乱 | 初始延迟 0/10ms |
| L8 | `AudioPipeline.java:78` | 降噪处理器异常未捕获，一次 native 失败即打穿 mic 线程 | try/catch + 置空降级 |
| L9 | `NvidiaDenoiser.java:126,150-151` | `NvAFX_Status.values()[status]` 越界 AIOOBE 掩盖真实错误 | 范围检查 |
| L10 | `nativ/NvAudioEffects.java:13-16` | 类初始化时 `libraryLookup` 失败抛异常 → 后续 `NoClassDefFoundError` | try/catch 降级 |
| L11 | `AlSpeakerMixin.java:52` | 每 20ms 每通道重复 `alSpeedOfSound(340f)` | 移至 init |
| L12 | `AlSpeakerMixin.java:70,124-126` | `occl_` 前缀 key 永不删除，FILTER_STATE 缓慢泄漏 | closeSync 一并清理 |
| L13 | `RayTracedReverb.java:77-81,84-95` | `disable()`/`cleanup()` 重复；未探测 EXTEfx；gen 失败把 0 当有效槽 | 合并 + `alGetError` + 扩展探测 |
| L14 | `RayTracedReverb.java:184-192` | 反射声像计算后直接丢弃（dead store） | 接线或删除 |
| L15 | `PlayerVelocityTracker.java:29,60` | 首条记录 `time=0` 语义颠倒；离线玩家不清理 | 初始化 `(pos,pos,now,now)`；周期清理 |
| L16 | `rt/BlockSoundProperty.java:69` | 未命中时每次 new 记录对象（热路径） | 静态 DEFAULT |
| L17 | `teamspeak-svc.mixins.json:4,14` | `compatibilityLevel=JAVA_17` 与 release=25 不符；`defaultRequire=0` 全局静默 | 提升级别；关键 mixin 显式 require |
| L18 | `ModConfig.java:76-84`、`ServerConfigStore.java:62-70` | 非原子写、无备份；解析失败后被默认值覆盖 | temp + ATOMIC_MOVE；损坏文件改名 |
| L19 | `ServerConfigStore.java:72-74` | 主菜单打开 GUI 时 `get("")` 创建并持久化空条目 | 空地址返回临时条目 |
| L20 | `Ts3FileTransfer.java:59` | `clientftfid=1` 硬编码 | 递增 ID |
| L21 | `Ts3VoiceSelfTest.java`、`Ts3ChannelTest.java` | 含 main 的测试类打进主源集随 jar 发布；javadoc 包名过期 | 移至 test 源集 |
| L22 | 多处 | 未用导入：`ConfigScreen:3`、`AvatarPipeline:5,19-20`、`Ts3FileTransfer:4`、`AudioExtraScreen:112`、`SelfTest:4`、`ChannelTest:4` | 清理 |
| L23 | `lang/*.json` | `tsvoice.gui.save` 为死键；`tsvoice.gui.duck_level` 拼接语法别扭 | 删除/改写 |
| L24 | `TsVoiceManager.java:277` | 昵称带 `[MC] ` 前缀，与 spec AC-1“昵称=玩家名”不完全一致 | 文档或代码对齐 |
| L25 | `TeamspeakSvcClient.java:67` | 启动日志硬编码 "(v0.2)"，实际 0.8.1 | 改为读版本 |
| L26 | `fabric.mod.json:8` | `authors: ["your-name"]` 占位符 | 填写 |
| L27 | `GroupChannelMapper.java:65` | 每 tick 重置 `lastLoggedTarget`，logOnce 防抖失效 | 修正重置时机 |
| L28 | `QuickerLz.java:80` | 长格式 `matchLen=0` 时 destPos 不前进（忠实 TSLib，无死循环） | 可接受 |
| L29 | `TsTransport.java:20-24` | javadoc 声称“断开返回 null”但实现从不返回 | 修正注释 |
| L30 | `Ts3VoiceSelfTest.java:89` | `voiceIn.get() >= 0` 恒真 | 修正判定 |

---

## 6. 文档与代码一致性

| ID | 文档位置 | 文档声称 | 实际代码 | 建议 |
|---|---|---|---|---|
| D1 | `README.md:66` | 产物 `0.1.0+26.3.jar` | 0.8.1 | 更新 |
| D2 | `README.md:46` | 命令 `status\|connect\|disconnect\|reload` | 7 个（另含 `gui`、`<消息>`、`rays`） | 更新 |
| D3 | `README.md:36-44` | 5 个配置字段 | 15 个 + `servers.json` + 信标 + TCP 隧道 | 重写配置章节 |
| D4 | `README.md` 全文 | 无 license/致谢 | fabric.mod.json 为 GPL-3.0；SVC-Extra 移植代码带 GPLv3 头注释 | 补 license 章节 |
| D5 | `ROADMAP.md:3` | 当前版本 0.5.0 | 0.8.1（build/libs 有 0.6.0→0.8.1 共 13 个构建未记录） | 更新并补变更 |
| D6 | `ROADMAP.md:16` | `/tsvoice say` | 是 greedyString **argument**，实际用法 `/tsvoice <消息>` | 改文档；或注册 literal `say` |
| D7 | `v0.4-audio/spec.md` FR-5 | denoiseMode 默认“原生” | 默认 `WEBRTC_MEDIUM` | 更新 spec |
| D8 | `v0.4-audio/spec.md` Non-Goals | “NVIDIA 降噪不做” | 已实现 | 更新 spec |
| D9 | `v0.4-audio/spec.md` FR-5 | AGC 目标电平可配 | 只有布尔开关 | 实现或改文档 |
| D10 | `v0.4-audio/spec.md` FR-5 | GUI 后续迭代 | 已存在 ConfigScreen/AudioExtraScreen | 回填 |
| D11 | `teamspeak-svc/tasks.md` Task 3-8 | pending/blocked | 功能已交付 | 更新状态 |
| D12 | `Ts3VoiceSelfTest.java:10` | javadoc 包名 `com.svcb.tsbridge...` | 实际 `com.tsvoice.client...`（README 正确） | 修正注释 |

---

## 7. ts3voice 协议栈双拷贝分叉

| 文件 | tsbridge 侧（A） | teamspeak-svc 侧（B） | 结论 |
|---|---|---|---|
| Ed25519Math / PacketType / Ts3Packet / Ts3VoiceException / Ts3Crypt / Ts3Identity | — | — | 仅包名/日志前缀差异 |
| `QuickerLz` | 带防御钳制（**会截断大型压缩应答的旧缺陷版**） | 忠实 TSLib 修复版 | **实质分叉，修复未回移** |
| `Ts3Connection` | 旧版 | 增加传输抽象/TCP 隧道/RTO 平滑/孤儿分片自愈 | B 为活跃版 |
| `Ts3VoiceClient` | 旧版 | 增加频道操作/文字消息/UUID 广播/FT 钩子 | B 为活跃版 |
| `TsTransport` / `TcpTransport` / `Ts3ChannelTest` | 无 | B 独有 | — |

**建议**：抽共享模块，或将 B 的 QuickerLz/Ts3Connection 修复回移 A，避免安全修复双份维护。

---

## 8. 各子系统评价

| 子系统 | 评价 | 主要问题 |
|---|---|---|
| TS3 协议栈（握手/加密/传输） | **良好** | QuickerLz 边界、TCP 超时竞争、未知包类型映射 |
| 连接生命周期/重连 | **良好** | 手动断开被事件覆盖、密码变更不重连 |
| 麦克风改道 | **良好** | 设计正确（已对照 SVC 源码确认）；无重大问题 |
| TS 音频注入 / Ghost | **可用** | 跨线程访问、reconcile 死代码、通道不释放 |
| 群组频道映射 | **有高危 bug** | H1 引用比较、M2 payload 丢弃 |
| 音频增强（AEC/AGC/降噪） | **问题严重** | H3/H5/H6/H7、死代码、竞态 |
| 闪避 / 混响 | **死功能** | H4、M9/M10 |
| GUI / 配置 | **可用** | H2、M1/M3、非原子写 |
| 资源/构建 | **基本可用** | M26、快照版本、libs jar 未引用 |
| 文档 | **过期** | D1-D12 |

---

## 9. 修复优先级

| 顺序 | 项目 | 理由 | 工作量 |
|---|---|---|---|
| 1 | H1 + M2（群组密码） | 两个小改动，恢复密码群组全链路 | 小 |
| 2 | H2（status 格式串） | 一行改动，恢复两条命令 | 极小 |
| 3 | H5（TsAudio 自赋值） | 四项配置立即生效 | 小 |
| 4 | H4（死 tick 接线） | 决定两个功能生死（先修 M9/M10） | 中 |
| 5 | H3 + H6 + H7（AEC） | 功能有效性与 JVM 崩溃风险 | 中 |
| 6 | M12-M19（线程安全/资源） | 稳定性 | 中 |
| 7 | M20-M24（协议健壮性） | 恶意服务器防护 | 小 |
| 8 | M25-M27（安全/部署） | 部署安全 | 小 |
| 9 | 文档回填 + 协议栈去重 | 可维护性 | 中 |

---

## 10. 已验证无问题项

| 项目 | 验证方式 | 结论 |
|---|---|---|
| Ed25519 点运算 | RFC 8032 向量 + 与 JDK 内置 Ed25519 随机 60 组交叉对比 | 60/60 一致 |
| AES-EAX 密钥派生 / init 握手 | 对照 TSLib 逐行审查 | 一致 |
| TCP 隧道粘包 | 4 字节大端长度 + `readFully`，与 tsbridge `TcpTunnelServer` 配对 | 正确 |
| Beacon/GroupInfo Payload 编解码 | 读写索引对照 | 完全对称 |
| mixins.json 类引用 | 8/8 存在 | 通过 |
| entrypoints | client / voicechat / modmenu | 有效 |
| TS 命令转义 | `\`、空格、`\|`、控制字符 | 覆盖完整 |
| MicRedirector 改道设计 | `ClientSoundEvent` 携带原始 PCM、cancel 后 SVC 停止发送 | 正确 |
| 降级路径 | 无 SVC / TS 不可达 / native 失败 | 总体成立（H7 组合除外） |
| TS 身份/uid 持久化 | 路径 `config/teamspeak-svc/identity.txt` 与 README 一致 | 通过 |
