任务：T03 / 对应 R08（出站语音帧频道代际隔离与队列线性化）

基线与提交：
- 起点：T02 提交 e6608f2 之后；本任务提交见 git log T03。
- R08 在核对报告中为"静态风险，未真实 TS 复现"——本任务以前后两层可控队列测试证明隔离
  （真实 Ts3Connection + 阻塞 transport 的线性化测试 + manager 层代际盖章测试），
  替代无法在修复前用旧 API 表达的"失败测试"（旧代码根本没有代际参数可断言）。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Connection.java
  （OutgoingFrame 携带 routeEpoch；setVoiceRouteEpochSupplier/clearQueuedVoice；
  addOutgoingPacket 三参重载；writer 在最终写出点复核代际，不匹配/未绑定帧丢弃 fail-closed；
  resend 路径 sendRaw 补齐签名；协议序号/加密计数算法未动）
- teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3VoiceClient.java
  （sendVoice(opus,codec,routeEpoch) 重载；无代际旧签名委托 Long.MIN_VALUE=永不发出；
  setVoiceRouteEpochSupplier/clearQueuedVoice 转发）
- teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java
  （sendMic 从同一协调器快照取许可与 routeEpoch 并盖章；connect() 把 writer 的有效代际来源
  接线到协调器快照——未就绪返回 MIN_VALUE）
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/TsChannelRoute.java
  （requestMove：撤销许可 → 清排队旧帧 → 按 writer 顺序提交 clientmove；显式选择登记为
  LEGACY 模式合法目标；observeSelf：确认频道变化即清排队旧帧（服务器主动移动的本地隔离边界））
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java
  （setChannelTarget 的 null-policy 分支只更新 LEGACY 目标；锁定目标不可被 requestMove 隐式改靶）
- review/route-lifecycle/：QueuedVoiceRouteTest.java（新增）、ConnectionLinearizationTest.java（新增，
  真实 Ts3Connection/Ts3Crypt/Ts3Packet/TsTransport + 可阻塞 transport）、conn-stubs/（新增）、
  RunRouteLifecycle.ps1（接线两个新测试）

玩家入口 → 状态提交 → 网络/音频结果：
- sendMic → 快照许可+routeEpoch 盖章 → Ts3Connection 队列 → writer 写出前复核：
  频道切换边界后排队的旧代际帧不写出；已开始发送（在 transport 内）的帧不可撤回（计划明示限制）。
- requestMove（mapper/UI）→ invalidateRoute（epoch+1）→ clearQueuedVoice → clientmove 控制
  命令经同一 writer 边界先于后续语音发出。
- 服务器主动移动 → self 观察确认新频道 → confirmChannel 递增 epoch → 清排队旧帧。

修复后自动化命令、原始日志、结果：
- RunRouteLifecycle.ps1 → RouteLifecycle 39 + BookmarkRoute 21 + QueuedVoiceRoute 9 +
  ConnectionLinearization 8 项全 PASS（T03-routelifecycle.log）。
- RunAll.ps1 → 18 组 harness + core test 全 PASS（T03-runall.log）。
- gradle build → BUILD SUCCESSFUL（T03-teamspeak-svc-build.log）。

相关旧回归：全部通过（stub client 补齐新 API 签名，断言未放宽）。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。E05（真实 TS 故障联调：错误频道是否收到可辨识音频）需真实 TS 服务器与写线程故障注入。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（出站帧全链路绑定代际；线性化点在 writer 写出点）。
- 自动化：完成（真实连接层阻塞测试覆盖"已取出旧帧"场景——clearQueuedVoice 之外的复核防线）。
- 集成：完成（sendMic/requestMove/observeSelf 三条生产路径均经同一 writer 边界）。
- 真实环境：未验证（缺真实 TS）。

与本计划偏差及原因：
- "服务器主动移动"的隔离边界按计划以 self 移动观察提交为准；LEGACY 模式下管理员移动由
  mapper 拉回（撤许可→重join→重确认），FOLLOW 模式自动跟随新频道并递增代际——两种策略
  行为分别由 QueuedVoiceRouteTest 与 BookmarkRouteTest B3 覆盖。
- 修复过程发现并修正测试自身的扫描差一（marker 检索窗口），生产代码零改动；调试探针已移除。

已知限制与回滚：
- 在 transport 内已开始发送的帧无法撤回（协议限制，已在测试与报告记录）；管理员移动前
  已在途的帧不冒称可撤回。
- 回滚：回退本任务提交；sendVoice 旧签名恢复直发语义。
