任务：T05 / 对应 R06（命令生命周期与上传确认）

基线与提交：
- 起点：T04 提交 d868731 之后；本任务提交见 git log T05。
- 本任务提前完成命令追踪器的生产接线（T10 复用）；不等 GUI 才修头像。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsCommandTracker.java
  （每个 Ts3VoiceClient 一个实例；新增异步入口 executeAsync(command,timeout) ——
  CompletableFuture 完成于 return_code 关联的应答/错误；共享守护看护器在超时时
  释放登记并异常完成；cancelSession 立即以取消完成全部在途；在途 return_code 不复用；
  旧的阻塞 execute 保留并标注"测试专用，生产禁止"）
- teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3VoiceClient.java
  （构造器实例化 tracker；disconnect()/fail() 先 cancelSession + 取消本 client 的
  ft 登记（会话关闭先撤销等待方）；ft 应答分发携带来源；commandTracker() 访问器）
- teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java
  （证据语义重写：EOF 只产 TRANSPORT_FINISHED；CONFIRMED 从类型系统移除；
  REMOTE_VERIFIED 为预留状态（目标服务器读回协议未证实前不产出）；
  登记键改为 来源 client+clientftfid；onFtReply 携带来源；cancelClient 会话级取消）
- teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java
  （onFtReply(row,source) / cancelClient(source)）
- teamspeak-svc/src/main/java/com/tsvoice/client/AvatarPipeline.java
  （marker 只在 client_flag_avatar 经命令关联器取得成功结果后写入（AVATAR_APPLIED
  语义）；全链路异步；写入前再次校验会话；marker IO 异常按"未生效"处理；
  confirmAvatarApplication 提取为包内可见 seam 供回归直接驱动）
- review/upload-confirmation/（新增）：RunUploadConfirmation.ps1、CommandLifecycleTest.java（13 项）、
  UploadConfirmationTest.java（9 项，含本地 EOF 数据服务器与真实 AvatarPipeline marker 验证）
- 既有 harness 适配：p3-channel FileTransferRegression（新 API/新证据语义，断言收紧：
  CONFIRMED 必须不存在）、CommandTrackerTest（unassociated 行改为 tracker 边界投递——
  真实 fail() 现在会取消在途命令，属生产行为）、session-consumption stub 补
  parseParams/commandTracker

原缺陷的失败证据（本轮复现）：
- U2 首跑 EOF→UNKNOWN 暴露测试服务器读全等关的语义差（fixture 修正）；
- I1/L3 首跑（前序轮）暴露闭包字段读取与 stub parseParams 空实现——均为 harness/缺陷复现路径，
  生产修复见 T04 报告；本轮 R06 核心断言：EOF 不得产生存储确认（CONFIRMED 类型级移除 +
  AvatarPipeline 仅在命令确认后写 marker，U4 直接验证拒绝不写/确认才写）。

修复后自动化命令、原始日志、结果：
- RunUploadConfirmation.ps1 → CommandLifecycle 13 项 + UploadConfirmation 9 项全 PASS
  （T05-uploadconfirmation.log）。
- RunAll.ps1 → 20 组 harness + core test 全 PASS（T05-runall.log）。
- gradle build → BUILD SUCCESSFUL（T05-teamspeak-svc-build.log）。

相关旧回归：RunCommandTracker（p3-channel）全 PASS；其余 19 组全 PASS。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。E10（真实 TS 上传 EOF/拒绝/超时、断开旧会话）需真实 TS 服务器文件传输权限；
  REMOTE_VERIFIED 需要"远端文件信息/大小或哈希读回"协议核实后实现——当前按计划保留 UNKNOWN
  允许可选头像暂缓，不写永久成功 marker。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（tracker 生产接线；ft 来源隔离；证据五态；头像命令确认后写 marker）。
- 自动化：完成（22 项新回归 + 全量绿）。
- 集成：完成（AvatarPipeline → tracker → marker 的真实控制流由 U4 直接驱动）。
- 真实环境：未验证（头像生效的真实服务器状态核对属 E10）。

与本计划偏差及原因：
- REMOTE_VERIFIED 的读回验证未实现（计划允许：未证实前保留 UNKNOWN）。
- AVATAR_APPLIED 不在传输层枚举中（它是应用层结论），由 AvatarPipeline 的
  "命令确认 + 会话校验 + marker 写入"组合表达，U1 钉死该契约。
- 串行兼容模式未实现：当前 return_code 是 TS 标准参数，实测服务器（E06/E10）确认前
  不添加非标准机制；无关联错误保持"会话级日志，不冒充请求结果"。

已知限制与回滚：
- fail() 现在取消在途命令——p3-channel 的旧投递方式（未连接假客户端）需经 tracker 边界；
  该 harness 已相应调整（断言未放宽）。
- 回滚：回退本任务提交；CONFIRMED 语义回归（R06 缺陷重现），需与 T01–T04 一并评估。
