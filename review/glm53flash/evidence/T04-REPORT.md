任务：T04 / 对应 R07（异步第二次消费的会话隔离）

基线与提交：
- 起点：T03 提交 c1e6e38 之后；本任务提交见 git log T04。
- R07 核对结论为"静态隔离缺口"（未真实竞争复现）；本轮以可控"排队→换会话→执行"窗口测试证明。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java
  （新增 CallbackContext(session, contextEpoch, source) 与 McContext 记录）
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java
  （contextEpoch 计数：onMcContextChanged/currentContextEpoch/currentSessionToken）
- teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java
  （onServerChanged 递增 MC 上下文代际；聊天回调捕获 CallbackContext，
  主线程执行时复核 会话+上下文+来源 client —— 聊天显示属 MC 关联输出）
- teamspeak-svc/src/main/java/com/tsvoice/client/ClientIdentityService.java
  （beginProof 绑定会话上下文，排队发送复核"自己捕获的绑定"（不是字段当前值）；
  challenge/claim 接收器在到达时刻捕获绑定，执行时复核；撤销墓碑保持全局应用；
  tsDisconnected 清绑定；isCurrentSession 辅助）
- teamspeak-svc/src/main/java/com/tsvoice/client/AvatarPipeline.java
  （后台任务四道会话门：任务启动/namespace 解析后/发送 client_flag_avatar 前/写 marker 前；
  aborted/started 任务计数作为诊断与回归观测）
- review/session-consumption/（新增）：RunSessionConsumption.ps1（双编译单元：manager+avatar 主套件、
  真实 ClientIdentityService+真实 Ts3Identity 的身份套件）、SessionConsumptionTest.java（C1–C4）、
  IdentityConsumptionTest.java（I1–I4）、stubs/、identity-stubs/
- review/RunAll.ps1（接入新套件）；既有 harness 编译面适配：
  RunClientIdentity.ps1+ClientIdentityServiceRegression（setClient 改经生产入口 beginProof——
  直设字段不再构成有效绑定；15 项断言未放宽）、RunFrontend/RunFallbackPolicy（CallbackContext
  的类型占位 stub）

原缺陷的失败证据（本轮复现，非引用历史）：
- I1 首跑失败："旧会话的排队 proof 请求仍然发送"——闭包读取 sessionContext 字段当前值而非
  任务自身捕获的绑定（生产缺陷，修复为捕获绑定）。
- I2 首跑失败：到达时刻不捕获绑定则换会话后旧 claim 写入新会话（生产缺陷，
  修复为接收器到达时刻捕获 CallbackContext）。
- 两次首跑日志在会话过程记录中，最终全绿见 T04-sessionconsumption.log。

修复后自动化命令、原始日志、结果：
- RunSessionConsumption.ps1 → SessionConsumption 10 项 + IdentityConsumption 7 项全 PASS
  （T04-sessionconsumption.log）。
- RunAll.ps1 → 19 组 harness + core test 全 PASS（T04-runall.log）。
- gradle build → BUILD SUCCESSFUL（T04-teamspeak-svc-build.log）。

相关旧回归：RunClientIdentity 15 项全 PASS（其 setClient 改经生产入口，断言不变）。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。快速重连/换服的真实竞争需真实网络环境（E12 长会话场景覆盖）。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（聊天/身份 challenge+claim+proof 排队、头像后台任务全部绑定；
  语音/会员回调为直接调用，已有 active(gen)+来源校验，无二次消费窗口）。
- 自动化：完成（两种调度结果均可确定性观测）。
- 集成：完成（三条排队路径 + 接收器到达捕获）。
- 真实环境：未验证。

与本计划偏差及原因：
- "命令完成回调及关闭回调"依赖 T05 的命令生命周期（生产中尚无异步命令 Future），
  在 T05 实现时按同一 CallbackContext 模式绑定；本任务不预造无调用方的 API。
- 撤销墓碑（ClaimRevoked）不随会话丢弃——服务器权威全局状态，计划要求高水位/墓碑
  不被清除，故保持全局应用。

已知限制与回滚：
- contextEpoch 只在 onServerChanged 递增；同一地址重进世界不递增（会话级隔离由
  session token 覆盖，语义正确）。
- 回滚：回退本任务提交；身份服务恢复读取字段当前值（R07 缺口回归，需与 T01–T03 一并评估）。
