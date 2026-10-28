基线 / 当前提交：
`b732169`（开工）→ `3976267`（N0–N7 全部提交；提交表见
review/engineering-closeout/DELIVERY.md）。

当前阶段 / 当前任务：
阶段 A（N0–N7 工程收尾）已完成；进入阶段 B（M7 统一客户端与可选服务端发布）。
M7 任务书：docs/superpowers/plans/2026-10-29-m7-unified-release-plan.md
（其"上一轮全部验收通过"开工前提已被 N7 工程门槛替换）。

已完成的生产路径：
- D01–D05 全部关闭（工程层）：独立 AudioChannel 播放 / 失效租约接管 / OFF
  processSoundPacket HEAD mixin 门控 / 策略拒绝原子撤销+全局单调写出代际 /
  clientmove 命令关联与 FAILED 持续阻断。
- T09/T10/T12 生产接线：书签 schema v3 + 列表/编辑屏 + VoiceStatusModel、
  频道页（cid 加入/锁定/stay-here/参与者 UUID 用户列表）、能力公告链
  （hello→公告→信任授权→候选选择）+ FailureKind 分类 + 退避 jitter。

本次实际测试与证据路径：
- review/RunAll.ps1 v2：core 先行 + 22 组 harness；终点 HEAD 干净副本全绿
  （review/engineering-closeout/evidence/n7-clean-copy-runall.log）。
- gradlew build：BUILD SUCCESSFUL；候选产物 teamspeak-svc-0.9.8+26.3.jar
  SHA-256 5FA9DF880E53C35CFFE04D12996A58CC9DC00A01DE677AA1E6A5B6FA4C1C6447。
- 新增回归：RouteMoveLifecycleTest(M1–M8)、RouteEpochRevocationTest(R)、
  FrontendLeaseTest(39 项)、OffGateTest(O1–O6)、GuiFlowTest(G/C/D 段)。

客户端暂缓项：
E01–E15 / U01–U06 全部"用户主动暂缓"，原编号与步骤保留在
review/engineering-closeout/DEFERRED-CLIENT-ACCEPTANCE.md。

局部阻塞及不受影响的任务：
- M6 产品闭环与 M7 完整安装矩阵依赖真机（暂缓），不阻塞 M7 的构建/装载隔离/
  服务端生命周期等工程工作。
- 公告候选的用户显式授权界面未交付（未信任候选保持未授权+日志）——归入 M7
  界面工作。
- 频道页列表超 8 条无分页（已记录）。

下一条具体动作（文件、方法、验证命令）：
M7：按 teamspeak-svc 同 JAR 交付可选服务端——fabric.mod.json environment
调整与入口拆分（TeamspeakSvcClient 客户端入口 / 新增服务端入口）、公告 payload
服务端注册并入（复用 net/CapabilitiesPayloads 语义与 voice-capabilities-fabric
的 CapabilityMod 逻辑）、配置与生命周期（ServerPlayNetworking/ServerLifecycleEvents）、
`gradlew build` 产出与既有客户端行为 diff 校验；验证命令：
`powershell -NoProfile -ExecutionPolicy Bypass -File review/RunAll.ps1` +
`gradlew -p teamspeak-svc build`（同一候选 JAR 哈希记录到 M7 报告）。

本轮新增或修订的技术决策：
- routeEpoch 全局单调（跨会话不复位）＝ 会话绑定的写出许可（N1）。
- clientmove 全部经命令 tracker（return_code 关联），移动结果与 self 频道确认
  分离；失败进入 FAILED 持续阻断，恢复=新请求确认或用户 acceptCurrentChannel（N1）。
- TS 下行播放不经 processSoundPacket：适配器自有 AudioChannel（null
  InitializationData，2.6.25 字节码零读取已核）；诊断分级
  attempts/enqueued/resourceFailures（N2）。
- OFF = 协调器意图直读门控（mixin HEAD）+ 原生通道队列清空；无第二份状态（N3）。
- 删除"正在使用"的书签 → PAUSED（不静默换目标）（N4）。
- 公告授权沿用信标信任模型：仅预信任 endpoint 自动授权；同 nonce 更新公告合法、
  字节级重放拒绝（N6）。
- 连接失败统一 FailureKind；认证/密码/权限/封禁/版本终止重试（N6）。
