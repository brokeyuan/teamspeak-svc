任务：T08 / 对应 R03（独立 SVC 音频前端：租约接线 + 播放入口 + ABI 记录）

基线与提交：
- 起点：T07 提交 736b8f0 之后；本任务提交见 git log T08。
- 环境约束：工作区无实际 SVC 2.6.25 mod JAR（仅 voicechat-api-2.6.20 API JAR 与 26.2 参考源码），
  无音频设备/真实 TS —— 按计划"环境缺失只阻塞对应验证"，L3（真机）全部未验证。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/frontend/SvcFrontendAdapter.java
  （接口新增 play(StreamKey, channelUuid, opus, sequence) —— 计划签名的 StreamKey 保留，
  另传 SVC 侧通道 UUID（通道派生是注入器领域，见偏差说明））
- teamspeak-svc/src/main/java/com/tsvoice/client/frontend/Svc2625FrontendAdapter.java
  （acquire 重排：预留标志 CAS 先占所有权 → 再创建 mic → 失败原子释放（修"先创建后 CAS"）；
  play() 注入入口（版本敏感代码自注入器收敛至此）；injectedFrames 为"音频已验证层"诊断；
  setMicSource 测试 seam（生产固定反射实现）；证据分级 javadoc）
- teamspeak-svc/src/main/java/com/tsvoice/client/TsAudioInjector.java
  （注入改经适配器 play()；本地反射块删除 —— 版本敏感代码不再散落）
- teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java
  （T08b：startSession 先取前端租约再启动会话线程（FRONTEND_UNAVAILABLE 显式报错）；
  stopSession 释放；会话循环按 micAlive 重取（与管理器锁串行，修竞争）；
  **移除 svcConnected 作为 TS 启动条件**（4 处中的启动相关 3 处：evaluateActivation 等待分支、
  manualConnect 等待分支、startSession 门）——前端自检（frontendUnavailable）决定可用性；
  frontendLeaseHeld() 诊断）
- review/standalone-frontend/（新增）：RunStandaloneFrontend.ps1、FrontendLeaseTest.java（14 项）、
  ABI.md（T08a 证据分级 + 已核对/未验证清单）、SVC client stubs
- 既有 harness 适配：9 个 runner 编译清单补前端类；4 个会话运行型套件注入 live mic 源；
  ClientStateTests 的注入入口探针改经适配器（断言语义不变）

R03 缺口的对应关系（核对报告 §R03）：
- "启动条件尚未解除" → 已解除（svcConnected 不再是 TS 启动条件；前端自检决定）。
- "独立 mic 租约没有接线" → 已接线（startSession/stopSession/会话循环三处）。
- "独立播放入口没有实现" → play() 已实现并接通（版本敏感入口收敛）；
  **null-connection 下行注入的真机行为未验证**（ABI.md 未验证清单第 1 条）——
  因此 M3 状态为"集成待验收"，P2 不能标完成。

修复后自动化命令、原始日志、结果：
- RunStandaloneFrontend.ps1 → F1–F6 共 14 项 PASS（T08-frontendlease.log）。
- RunRouteLifecycle.ps1 → 41 项 PASS（含新增租约持有/释放断言）×3 次稳定。
- RunAll.ps1 → 22 组 harness + core test 全 PASS（T08-runall.log）。
- gradle build → BUILD SUCCESSFUL（T08-teamspeak-svc-build.log）。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。E01（无 SVC 服务端双向通话）、E11（设备切换/禁用/native 失败）需真实客户端+设备；
  按计划"M3 只能'集成待验收'，M6 不能通过"。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（租约接线、播放入口、启动条件解除、所有权交接）。
- 自动化：完成（租约语义 14 项 + 会话集成断言）。
- 集成：部分 —— 采集链（SVC 事件→MicRedirector→TS）与播放链（TS→adapter.play→SVC 管线）
  在 harness 内可达；真机链路未验证。
- 真实环境：未验证。

与本计划偏差及原因：
- play() 签名比计划多一个 channelUuid 参数：通道派生（participantUuidFor）属于注入器领域，
  适配器不应反向依赖参与者命名空间；StreamKey 仍随帧传递用于所有权诊断。
- 音量迁移/停止事件探测按计划记录为未验证（ABI.md），未实现对应反射猜测之外的行为。

已知限制与回滚：
- 真实 SVC 2.6.25 JAR 到位前，inspect 在生产环境的通过性未证实（仅 API JAR 编译面）。
- 回滚：回退本任务提交；svcConnected 启动门恢复（R03 回归）。
