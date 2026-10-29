任务：T07 / 对应 R10（参与者稳定标识与播放资源所有权）

基线与提交：
- 起点：T06 提交 2750c25 之后；本任务提交见 git log T07。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java
  （新增 ParticipantKey(serverUid, tsUid) 与 StreamKey(participant, streamEpoch)）
- teamspeak-svc/src/main/java/com/tsvoice/client/TsAudioInjector.java（重构）
  （ghosts/sequences 键统一为 ParticipantKey；所有 TS 播放通道 UUID 一律派生自
  participantUuidFor —— 包括可信 MC 分支，绝不复用原生 SVC 玩家通道；
  身份升降级改为"原地改名+注册皮肤"，通道与音量键不变；同服 mod 玩家的 TS 音频
  也走参与者通道（独立 ghost），发端已由 MicRedirector 保证不同时双发；
  每段流持 StreamKey；旧可信 MC 音量一次性迁移（尽力而为+记录来源+每参与者一次））
- teamspeak-svc/src/main/java/com/tsvoice/client/TsPlaybackRegistry.java（新增）
  （StreamKey→自有通道登记；retire 仅在该流仍是通道当前所有者时关闭 ——
  旧清理迟到不得删除新流；O5 直接验证）
- teamspeak-svc/src/main/java/com/tsvoice/client/ClientStateReflector.java
  （新增 retireAudioChannel：独立守护线程等待客户端线程完成移除+closeAndKill，
  5 秒超时记录失败并把通道交还 SVC 生命周期；调用线程不再持锁等待）
- review/playback-ownership/（新增）：RunPlaybackOwnership.ps1、PlaybackOwnershipTest.java
  （O1–O6，注册表部分反射容错以支持预修复运行）
- 既有 harness 适配：RunTests.ps1/RunStableVolume.ps1 编译清单补 connection 包+注册表；
  StableParticipantVolumeTest 的 remove 调用改参与者键（断言未放宽）

原缺陷的失败证据：
- T07-prefix-fail.log：8 项断言失败 —— 可信 MC 分支占用原生 MC 通道、跨服同 uid 合并、
  身份升级把通道迁到 MC UUID（音量重置）、同 MC UUID 的两个 TS 身份合并。
  编译通过、仅断言失败（O5 注册表部分反射跳过）。

修复后自动化命令、原始日志、结果：
- RunPlaybackOwnership.ps1 → O1–O6 共 12 项全 PASS（T07-playbackownership.log）。
- RunAll.ps1 → 21 组 harness + core test 全 PASS（T07-runall.log）。
- gradle build → BUILD SUCCESSFUL（T07-teamspeak-svc-build.log）。

相关旧回归：RunStableVolume（p1-playback，含 ownedObject 守卫与序号重置场景）继续通过。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。音量迁移依赖真实 SVC 音量配置 ABI（getVolumes 的实际形态未核对，
  当前为尽力而为+日志）；E09（通道隔离/音量独立/展示降级与音频连续性）需真实多客户端。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（参与者键全覆盖；通道隔离；exact-match 退役；有界后台关闭）。
- 自动化：完成。
- 集成：完成（注入/清理/对账三条路径均按参与者键工作）。
- 真实环境：未验证（音量迁移与 OpenAL 线程约束核对属 T08/E11）。

与本计划偏差及原因：
- 音量迁移的"复制一次"以进程内标记实现（每参与者一次、记录来源日志）；跨重启的
  持久标记待真实 ABI 核对后随 T08 补充。
- 同服 mod 玩家的 TS 音频改走参与者通道（旧实现借道原生 MC 通道）——这是计划
  "所有 TS 播放 UUID 均来自 serverUID+TS UID"的直接结果；显示名保留其 MC 昵称。

已知限制与回滚：
- 音量迁移在 SVC 配置 ABI 未核对时不生效（只记录）；旧升级版用户会感觉个人音量
  重置一次（从旧 MC 键到参与者键）——迁移生效后消除。
- 回滚：回退本任务提交；R10 缺陷回归（通道串用/误删），需与 T01–T06 一并评估。
