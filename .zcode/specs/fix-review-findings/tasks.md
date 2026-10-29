# teamspeak-svc 审查问题逐一修复 - 实施计划

## Task 1: 群组密码链路修复（H1+M2）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - `GroupChannelMapper.onGroupInfo` 无条件缓存 payload；新增 `pendingGroupInfo`。
  - `tick` 用 `sameGroup(a,b)`（按 `getId()`）判断群组变化；群组未变时保留 `lastGroupInfo`。
  - `reset` 清 `groupChangedAt`；群组切换时消费 pending payload。
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `rule` TR-1.1: 代码中不存在 `group != lastGroup` 引用比较；`onGroupInfo` 不再因 `lastGroup==null` 丢弃；证据为 grep 与代码审查。
  - `rule` TR-1.2: 连续两次使用不同 `ClientGroupImpl` 实例调用 tick 模拟时，`lastGroupInfo` 不被清空；证据为逻辑推演/单测说明。

## Task 2: `/tsvoice status` 格式串修复（H2）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - `statusText()` 传满 6 个占位符参数；删除重复 append；ERROR/RECONNECTING 仅追加错误。
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `rule` TR-2.1: `tsvoice.status` 的 `%s` 数量与 `.formatted` 参数数量一致；证据为 grep/人工计数。

## Task 3: `TsAudio.refresh` 配置桥修复（H5）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - `duckingLevel`/`aecDelayMs` 读取 config 并 clamp；补 `nvidiaDllPath`/`nvidiaModelDir`。
  - `ModConfig` 新增 `networkToleranceMs`（默认 120）。
- **Acceptance Criteria Addressed**: AC-4
- **Test Requirements**:
  - `rule` TR-3.1: refresh 中无自赋值；四个配置字段均从 `config.*` 读取；证据为 grep。

## Task 4: GUI 密码与音频页返回修复
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 密码框回填、掩码、随锁定禁用；`serverPassword` 移出 `!editLocked`。
  - enabled 切换后刷新锁态；ConfigScreen 增加音频增强入口并传 parent；`VoiceChatScreenMixin` 打开音频页时传 parent。
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `rule` TR-4.1: 打开/保存不再覆盖已存密码；`AudioExtraScreen` 关闭返回来源界面；证据为代码审查。

## Task 5: 手动断开语义修复
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - `TsVoiceManager` 新增 `userDisabled`；manualDisconnect 置位，manualConnect/onServerChanged 清除；evaluateActivation 在置位时休眠；重连 key 含密码哈希。
- **Acceptance Criteria Addressed**: AC-4
- **Test Requirements**:
  - `rule` TR-5.1: AudioExtraScreen 的 `onManualConfigChanged` 不绕过 userDisabled；证据为调用路径审查。

## Task 6: 抖动缓冲阈值语义修复
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: T3
- **Description**:
  - `AudioChannelMixin` 阈值改为 `clamp(toleranceMs/20, 0, 16)`，保留 0=关闭；`require=1`。
- **Acceptance Criteria Addressed**: AC-4
- **Test Requirements**:
  - `rule` TR-6.1: 默认 120ms → 阈值 6；0 可关闭；证据为代码与常量对照。

## Task 7: AEC 同步重写
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T3
- **Description**:
  - 新增 `audio/AecProcessor.java`：麦克风线程内逐 10ms 帧 `processReverseStream` + `processStream`，结果回写原数组；APM 内按 denoiseMode 启用 NS。
  - 删除 `AecScheduler.java`、`AecSyncBuffer.java`。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-7.1: `processStream` 输出写回传入 audio 数组；参考帧消费与麦克风帧 1:1；证据为代码审查。
  - `rule` TR-7.2: 无 AEC 调度线程/队列残留；证据为 grep。

## Task 8: AudioPipeline 并发与资源
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T7
- **Description**:
  - `processShort` 与 init/reconfigure/shutdown 同锁；shutdown 调 `webrtc.close()` 与 `AecReferenceMixer.clear()`；处理器异常捕获降级；AEC 分支先跑 NVIDIA NS。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-8.1: 无未 close 的 WebRtcNative；无先查后用的 null 窗口；证据为代码审查。

## Task 9: 参考混音器调整
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: T7
- **Description**:
  - 上限 50 帧；clear 接入 reconfigure；注释多说话人限制。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-9.1: 队列上限 ≤ 50 且 clear 在 reconfigure 调用；证据为 grep。

## Task 10: 闪避接通与修正
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T3
- **Description**:
  - 注册 `DuckingManager.tick()`；读取 `duckingEnabled`；修正时序与贝塞尔；`lastAudioTime` volatile。
- **Acceptance Criteria Addressed**: AC-6
- **Test Requirements**:
  - `rule` TR-10.1: tick 只有一个主线程调用点；说话中压低、静音后恢复；证据为代码审查。

## Task 11: 混响重写
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T12
- **Description**:
  - 删除 ForkJoin/join；主线程增量追踪；聚合后 volatile 参数 + pendingApply；AL 线程同步创建/应用；删除死代码。
- **Acceptance Criteria Addressed**: AC-6
- **Test Requirements**:
  - `rule` TR-11.1: 无跨线程 `level.clip/getEntities`；无主线程 join；OpenAL 调用只在 AL 线程；证据为 grep/代码审查。

## Task 12: 跨线程访问治理
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 速度表 ConcurrentHashMap + 首帧修正 + 清理 + 位置快照；TsAudioInjector 用快照；GhostSkins 主线程；新增 OcclusionCache 主线程计算。
- **Acceptance Criteria Addressed**: AC-6
- **Test Requirements**:
  - `rule` TR-12.1: 网络/音频线程不再访问 `mc.level`；SkinManager 调用经 `Minecraft.execute`；证据为代码审查。

## Task 13: NvidiaDenoiser 健壮性
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - 失败路径 DestroyEffect；状态枚举越界检查；48k 帧长约束与异常降级；RTX 非 48k 警告。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-13.1: 初始化失败不泄漏 effect；native 异常不传播到 mic 线程；证据为代码审查。

## Task 14: Ghost 释放接线
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - 会话循环每 2s 调 `TsAudioInjector.reconcile(client)`。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-14.1: reconcile 有且仅有会话线程调用点；证据为 grep。

## Task 15: QuickerLz 输入校验
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - 入口长度校验、拷贝边界检查、统一抛 `Ts3VoiceException`；`Ts3Connection` 解压 catch 放宽为 `Exception`。
- **Acceptance Criteria Addressed**: AC-7
- **Test Requirements**:
  - `rule` TR-15.1: 空/短/越界输入抛 `Ts3VoiceException`；证据为代码审查与构造输入说明。

## Task 16: TCP 超时语义
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - `SoTimeout` 改 35s；接收循环区分 `SocketTimeoutException`。
- **Acceptance Criteria Addressed**: AC-7
- **Test Requirements**:
  - `rule` TR-16.1: 超时值不与应用层 30s 相等；日志可区分；证据为代码审查。

## Task 17: 未知包类型处理
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - `packetType()` 未知返回 null；入站丢弃并 debug。
- **Acceptance Criteria Addressed**: AC-7
- **Test Requirements**:
  - `rule` TR-17.1: 未知类型不进入语音路径；证据为代码审查。

## Task 18: Ed25519 add 例外
- **Status**: `completed`
- **Priority**: low
- **Depends On**: None
- **Description**:
  - `add` 处理 `a==b` 与 `a==-b`。
- **Acceptance Criteria Addressed**: AC-7
- **Test Requirements**:
  - `rule` TR-18.1: 例外输入返回正确点（dbl/恒等元）；证据为代码审查。

## Task 19: 敏感数据与原子写
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - 身份/配置文件 POSIX 600；GroupInfoPayload.toString 掩码；ModConfig/ServerConfigStore 原子写与损坏备份。
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-19.1: 落盘调用设置权限；无明文密码日志；写入使用临时文件+移动；证据为代码审查。

## Task 20: fabric.mod.json 依赖与元数据
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - depends.voicechat >=2.6.20、suggests.modmenu、authors。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-20.1: JSON 合法且依赖存在；证据为文件检查。

## Task 21: 头像与原生解包健壮性
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - marker 前建目录、NativeImage try-with-resources；WebRtcNative 固定缓存目录与失败清理。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-21.1: 无未关闭 NativeImage；解包失败无孤儿目录；证据为代码审查。

## Task 22: 版本日志、文件传输 ID、空配置过滤
- **Status**: `completed`
- **Priority**: low
- **Depends On**: None
- **Description**:
  - 启动日志读 mod 版本；clientftfid 原子递增；save 过滤全空条目。
- **Acceptance Criteria Addressed**: AC-9
- **Test Requirements**:
  - `rule` TR-22.1: 日志版本与 gradle.properties 一致；证据为代码审查。

## Task 23: 死代码与细节清理
- **Status**: `completed`
- **Priority**: low
- **Depends On**: T7, T11, T12
- **Description**:
  - 删除死代码与未用导入；修 logOnce 重置；`/tsvoice say` literal 别名；mixin require 调整；自测注释。
- **Acceptance Criteria Addressed**: AC-10
- **Test Requirements**:
  - `rule` TR-23.1: 评审清单中的死代码全部移除或有调用点；证据为 grep。

## Task 24: 文档回填
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: T22, T25
- **Description**:
  - README 重写、ROADMAP 更新、v0.4-audio spec 回填、tasks 状态。
- **Acceptance Criteria Addressed**: AC-9
- **Test Requirements**:
  - `rule` TR-24.1: 文档中的每个命令/配置项在代码中存在；证据为 grep 对照。

## Task 25: 版本号 0.9.0
- **Status**: `completed`
- **Priority**: low
- **Depends On**: None
- **Description**:
  - `gradle.properties` mod_version 0.8.1 → 0.9.0。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-25.1: 构建产物名与版本一致；证据为构建输出。

## Task 26: 全量构建验证
- **Status**: `completed`
- **Priority**: high
- **Depends On**: All code tasks
- **Description**:
  - 使用 `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build`。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-26.1: 构建 exit 0 且 jar 存在；证据为命令输出。

## Task 27: 静态回归检查
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T26
- **Description**:
  - grep 检查关键回归模式；检查 mixins 类齐全。
- **Acceptance Criteria Addressed**: AC-2, AC-4, AC-5, AC-6, AC-7, AC-8
- **Test Requirements**:
  - `rule` TR-27.1: 所有 grep 断言通过；证据为命令输出。

## Task 28: 独立评审
- **Status**: `completed`
- **Completion Evidence**: R1 fail（F1/F2 actionable）→ 修复后 R2 pass（4/5）→ R2 advisory 加固后 R3 pass（4/5）；R3 后对 R3-F1/F2 做了加固并重新构建通过。详见 `review.md`。
- **Priority**: high
- **Depends On**: T27
- **Description**:
  - 派 fresh 只读 reviewer 对照 spec/tasks 验收，产出 review.md。
- **Acceptance Criteria Addressed**: AC-10
- **Test Requirements**:
  - `rule` TR-28.1: 评审结果为 pass 或 actionable findings 已建立修复队列；证据为 review.md。

## Task 29: 人工联调清单
- **Status**: `completed`
- **Priority**: low
- **Depends On**: T26
- **Description**:
  - 输出人工验证清单（真实 TS、双客户端、密码群组、AEC/闪避/混响听感）。
- **Acceptance Criteria Addressed**: AC-10
- **Test Requirements**:
  - `rule` TR-29.1: 清单覆盖全部实机场景；证据为文档。

## 实施完成证据汇总

- **T1-T25（代码/文档）**：`teamspeak-svc/build-final.log` — `BUILD SUCCESSFUL in 16s`，`EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.0+26.3.jar`（fabric.mod.json 含 `voicechat >=2.6.20`、authors、suggests）。
- **T1 群组密码**：`grep sameGroup GroupChannelMapper.java` 命中；无 `group != lastGroup`。
- **T2 status**：`tsvoice.status` 6 个占位符 + 6 个 formatted 实参。
- **T3/T5/T6**：`TsAudio.refresh` 读取 5 个 config 字段；`userDisabled` 5 处；`AudioChannelMixin require=1` 且阈值 `toleranceMs/20`。
- **T4 GUI**：密码回填/掩码/保存移出锁定分支；ConfigScreen 与 VoiceChatScreenMixin 均 `setParent`。
- **T7-T9 AEC**：`AecProcessor` 同步处理并 `asShortBuffer().get(audio, ...)` 写回；`AecScheduler/AecSyncBuffer` 已删除；`AudioPipeline.shutdown` 调 `webrtc.close()` 与 `AecReferenceMixer.clear()`。
- **T10-T12 线程模型**：END_CLIENT_TICK 注册 4 个 tick；`RayTracedReverb` 无 `RAY_POOL/parallelStream/ForkJoinPool`；`OcclusionCache` 主线程射线；`PlayerVelocityTracker` 使用 ConcurrentHashMap；`GhostSkins.register` 经 `Minecraft.execute`。
- **T13-T14**：NvidiaDenoiser 失败路径 DestroyEffect；`TsAudioInjector.reconcile` 在会话线程调用。
- **T15-T18 协议**：QuickerLz 最小长度/边界校验 + `Ts3VoiceException`；TcpTransport 35s；`packetType()` 未知返回 null；Ed25519 `add` 例外处理。
- **T19-T22 安全/部署**：`ConfigFileUtil.writePrivate` 用于 ModConfig/ServerConfigStore/Ts3Identity；`GroupInfoPayload.toString` 掩码；`clientftfid` 原子递增；WebRtcNative 固定缓存目录、无 `deleteOnExit`；启动日志读 mod 版本。
- **T26 构建**：见上。
- **T27 静态检查**：上述 grep 全部通过；`mixins.json` 8/8 类存在。
- **T29 人工清单**：`.zcode/specs/fix-review-findings/manual-checklist.md`。
- **评审闭环**：R1 fail → R1-F1/F2 修复（Ed25519 数值回归 `ED25519 ADD TESTS PASSED`）→ R2 pass（`build-remediation.log`）→ R2 advisory 加固 → R3 pass（`build-r3.log`）→ R3-F1/F2 加固 → 最终构建 `build-final2.log` BUILD SUCCESSFUL / EXIT:0，产物 `teamspeak-svc-0.9.0+26.3.jar`。
- **残余人工项**：真实 TS/MC 联调、`Ts3Identity.leadingZeroBits` 位序验证（R2-F4）见 manual-checklist；未读取字段等 cosmetic 项（R2-F5）列为后续清理。
- **0.9.1 体验修正**：移除 TS 配置界面重复的音频增强入口；混响湿声压低约 12dB（early ≤1.5、late ≤2.5、EAX 增益 0.25、RT60 ≤8s）；删除 `tsvoice.gui.audio_open` 死键。构建 `build-0.9.1.log` 通过，产物 `teamspeak-svc-0.9.1+26.3.jar`。
- **0.9.2 清晰度优先**：音频增强默认全关并使用 SVC 原生降噪；移除抖动缓冲覆盖 mixin 与 `networkToleranceMs`；旧配置一次性迁移（`configVersion` 0→1）；混响关闭时跳过遮挡/水下滤波。构建 `build-0.9.2.log` 通过，产物 `teamspeak-svc-0.9.2+26.3.jar`。
- **0.9.3 接收链路根因修复**：TS→MC 改为注入 SVC `SoundPacket`（编码 Opus + 16 位语音序号）到 `processSoundPacket`，恢复抖动缓冲/重排/FEC/PLC；连接层不再丢乱序包；移除裸 PCM 通道与每 Ghost 解码器。构建 `build-0.9.3.log` 通过，产物 `teamspeak-svc-0.9.3+26.3.jar`。
- **0.9.4 低延迟目标**：新增 `lowLatencyMode`（默认 true），TS 会话期间把 SVC 播放缓冲压到 2 包（≈40ms）；发送侧保持 SVC 原生 RNNoise + Speex AGC。构建 `build-0.9.4.log` 通过，产物 `teamspeak-svc-0.9.4+26.3.jar`。
- **0.9.5 同服身份/静音修复**：昵称 `[MC] 名字` 反查在线玩家 UUID；晚到 UUID 迁移派生 Ghost；TS 音频统一等响度 `GroupSoundPacket`；修复 `[TS] [MC]` 双前缀。构建 `build-0.9.5.log` 通过，产物 `teamspeak-svc-0.9.5+26.3.jar`。
- **0.9.6 诊断增强**：`/tsvoice status` 增加 `injected=`；未知 clid 与 SVC 未就绪各告警一次。构建 `build-0.9.6.log` 通过，产物 `teamspeak-svc-0.9.6+26.3.jar`。
- **0.9.7 移除定位音频**：删除 `OcclusionCache`/多普勒/`PlayerVelocityTracker` 位置追踪；新增 `OnlinePlayers`；TS 音频全部等响度。构建 `build-0.9.7.log` 通过，产物 `teamspeak-svc-0.9.7+26.3.jar`。
- **0.9.8 换服频道同步**：连接后主动 `channellist`；主频道缺失时刷新 + `channelfind` 兜底；status 增加 `sent=`。构建 `build-0.9.8.log` 通过，产物 `teamspeak-svc-0.9.8+26.3.jar`。
