任务：T01 / 对应 R01（统一连接意图与麦克风归属；新增 PAUSED/切 SVC/全关三操作）

基线与提交：
- 起点提交 09361cc（HEAD 与计划基线一致）；本任务完成后提交（见 git log T01）。
- 实际执行环境：Java 25.0.4.1，Gradle 9.5.1 wrapper，Windows 10.0.26100。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java
  （新增 SelectionSnapshot/RouteSnapshot(扩展)/CoordinatorSnapshot；FailureKind 保持唯一枚举）
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java
  （IntentKind 增加 PAUSED；candidates 对 PAUSED 不自动连接；FailureKind 改为 VoiceState.FailureKind
  的常量别名类，删除同义枚举漂移）
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java
  （唯一提交点：select/selectAuto/disconnectTeamSpeak/switchToSvc/disableAllVoice/snapshot/
  invalidateRoute；beginSession/endSession/publishRoute 保留并从同一快照派生）
- teamspeak-svc/src/main/java/com/tsvoice/client/audio/MicRouteGate.java
  （改为协调器快照的兼容视图；删除独立写入口 setOwner）
- teamspeak-svc/src/main/java/com/tsvoice/client/MicRedirector.java
  （每帧只读一次协调器快照；PAUSED/OFF/未解析=取消并丢弃；SVC 选中需跨过发言边界才放行）
- teamspeak-svc/src/main/java/com/tsvoice/client/backend/SvcBackendAdapter.java
  （新增 voiceMuted 覆盖标志：仅本扩展门控，不改用户 SVC 静音设置）
- teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java
  （manualConnect/manualConnect(bookmarkId) 先 select(PINNED_TS) 再 evaluateActivation；
  manualDisconnect→disconnectTeamSpeak→PAUSED；新增 switchToSvc/disableAllVoice；
  evaluateActivation 尊重 PAUSED/OFF/PINNED；startSession 连接前发布未就绪路由；
  sendMic 复核选择；handleVoiceFrame 门控 OFF/PAUSED 入站播放；删除 userDisabled/refreshRouteOwnership）
- teamspeak-svc/src/main/resources/assets/teamspeak-svc/lang/zh_cn.json、en_us.json
  （ts_disconnected 措辞改为"不会自动切换到 SVC"）
- review/route-lifecycle/RunRouteLifecycle.ps1、RouteLifecycleTest.java（新增回归）
- review/RunAll.ps1（接入新套件）；review/p0-defects/RunMicRedirector.ps1、
  review/client-fixes/RunManagerTests.ps1、review/p1-isolation/RunSessionIsolation.ps1、
  review/p3-config/RunBookmarkConfig.ps1、review/p2-frontend/RunFrontend.ps1
  （编译清单加入 SelectionPolicy.java；ManagerTests 经生产入口补选择提交，断言未放宽）

玩家入口 → 状态提交 → 网络/音频结果：
- /tsvoice connect、GUI 连接、自动激活 → BackendCoordinator.select(PINNED_TS)/selectAuto(TS3)
  → startSession 发布未就绪路由 → 后台连接；成功 publishRoute(TS3,true)，失败保持 TS 选择进入重连。
- /tsvoice disconnect、GUI 断开 → disconnectTeamSpeak → PAUSED（selectedBackend=空）→ TS 会话关闭，
  麦克风帧取消并丢弃；公告/换服/SVC 事件不自动恢复。
- /tsvoice（旧 manualConnect 调用方）语义不变；switchToSvc → PINNED_SVC，发言边界后恢复原版发送；
  disableAllVoice → OFF，TS+原生 SVC 收发同时停止（TS 播放经 handleVoiceFrame 门控 + removeAll）。

原缺陷的失败证据：
- review/glm53flash/evidence/T01-prefix-fail.log（修复前套件输出）
- T01-prefix-fail-summary.txt：5 项断言失败，全部为 R01 缺陷族：
  D 断开后帧泄漏 SVC；E(R01) 重连后 gate 卡 SVC_NATIVE；F 重连会话帧未达 TS；
  G1 断开+换服泄漏 SVC；H 换服自动重启会话（断开不被尊重）。
  编译通过、仅断言失败 —— 符合"失败测试先于修复暴露缺陷"要求。

修复后自动化命令、原始日志、结果：
- powershell -NoProfile -ExecutionPolicy Bypass -File ./review/route-lifecycle/RunRouteLifecycle.ps1
  → T01-routelifecycle.log：33 项检查全部 PASS（A–L 十二组场景）。
- powershell -NoProfile -ExecutionPolicy Bypass -File ./review/RunAll.ps1
  → T01-runall.log：17 组 harness（含新增 route-lifecycle）+ ts3voice-core clean test 全部 PASS。
- ./teamspeak-svc/gradlew.bat -p ./teamspeak-svc build --no-daemon
  → T01-teamspeak-svc-build.log：完整构建结果（真实工具链编译验证）。

相关旧回归：
- MicRedirectorRegression（P0）、SessionIsolation、ManagerTests、BookmarkConfig、Frontend 全部继续通过；
  未放宽任何断言（ManagerTests 仅新增生产路径选择提交作为 sendMic 前置）。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。缺真实 MC 客户端 + SVC 2.6.25 + TS 服务器 + 音频设备（E04/E14 对应行）。
- 全关的原生 SVC 播放静音（SvcBackendAdapter.voiceMuted 的前端消费）属版本敏感入口，与 T08 一起核对；
  本任务已提供门控接缝与 OFF 门控测试（入站 TS 播放已覆盖）。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（所有用户入口统一经协调器提交；MicRedirector 单快照读取）。
- 自动化：完成（本任务套件 + 全量回归全绿，原始日志在 evidence/）。
- 集成：部分 —— CLI/GUI/自动激活路径已在 harness 内验证真实 manager 控制流；
  书签 GUI 入口本身在 T09 交付（manualConnect(bookmarkId) 已具备并被本套件覆盖）。
- 真实环境：未验证（条件缺失，已列入 ACCEPTANCE.md E04/E14）。

与本计划偏差及原因：
- Plan §3.2 的 confirmChannel 未在本任务实现 —— 它属 T02 的确认路由服务，避免提交无调用方的 API。
- MicRedirector 发言边界用"最后被 TS/暂停消费帧的时间戳 + 静默窗口"实现，窗口 250ms 可配置；
  计划允许"使用现有检测的静默边界"，PTT 场景由 ClientSoundEvent 停止即静默达成，未侵入 SVC 检测器。
- OFF 对原生 SVC 播放的停止暂以 voiceMuted 门控 + T08 前端核对实现，未做 SVC 内部反射静音
  （计划明示版本敏感入口与 T08 一起核对）。

已知限制与回滚：
- PAUSED 持久化到用户显式选择为止；CLIENT_STOPPING 走 manualDisconnect（游戏退出场景，无影响）。
- 回滚：回退本任务单个提交即可；MicRouteGate 视图与协调器快照一并回退，
  不遗留半状态（旧 userDisabled 语义在回退后恢复）。
