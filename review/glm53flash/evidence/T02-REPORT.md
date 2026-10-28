任务：T02 / 对应 R02（共用确认路由 TsChannelRoute；书签可获确认发送许可）

基线与提交：
- 起点：T01 提交 a8ed3b2 之后的工作区；本任务提交见 git log T02。

实际修改文件：
- 新增 teamspeak-svc/src/main/java/com/tsvoice/client/connection/TsChannelRoute.java
  （共用确认路由服务：目标解析、在途请求、旧观察失效、歧义/拒绝暂停、统一 isReadyForVoice）
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java
  （新增 ChannelPolicy：TS_NATIVE_FOLLOW / LOCKED_TARGET / LEGACY_GROUP_MAPPING）
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java
  （新增 setChannelTarget/confirmChannel；confirmChannel 按 ChannelPolicy 批准；
  确认频道变化递增 routeEpoch；未就绪发布清除 authority/channelId/confirmed）
- teamspeak-svc/src/main/java/com/tsvoice/client/GroupChannelMapper.java
  （重构：只做 LEGACY 模式目标解析并提交共用路由；isReadyForVoice=共用路由+legacy 活动群组守卫；
  删除独占 routing 许可结构）
- teamspeak-svc/src/main/java/com/tsvoice/client/TsVoiceManager.java
  （sendMic/sendChannelText 读取 TsChannelRoute.isReadyForVoice；会话启动登记策略与书签初始频道；
  self-move 监听提交观察；周期对账 tick；连接失败/丢失清在途请求；CONNECTED 不再直接发布 ready）
- review/route-lifecycle/：BookmarkRouteTest.java（新增 B1–B6 场景）；RunRouteLifecycle.ps1
  （编译真实 mapper/路由服务；stub client 升级为可编排频道模拟）
- 既有 harness 适配（编译面）：RunMapper.ps1+MapperRegression.java（生产 API 建立会话）、
  RunMicRedirector.ps1+MicRedirectorRegression.java（健康路径前经 confirmChannel 授权）、
  RunManagerTests.ps1+ManagerTests.java、RunSessionIsolation.ps1、RunBookmarkConfig.ps1
  （stub client 补 channelSnapshot/getMyChannelId/joinChannel；未放宽任何断言）

玩家入口 → 状态提交 → 网络/音频结果：
- 书签连接 → PINNED_TS + 初始频道（书签 channel）经 TsChannelRoute 解析（唯一匹配才移动，
  歧义暂停）→ clientmove 请求（先 invalidateRoute 撤销许可）→ TS self 频道通知 →
  confirmChannel(FOLLOW) 批准 → route(authority+cid, ready) → sendMic/sendChannelText 放行。
- legacy 群组配置 → mapper 周期解析目标 → setLegacyTarget → 同一确认流；密码群组/未知成员保护不变。
- 服务器强制移动（未锁定）→ self 观察 → 自动跟随新频道并递增 routeEpoch（T03 隔离依据）；
  锁定/群组约束不符 → 暂停并显示原因。

原缺陷的失败证据：
- R02 探针（历史轮）显示书签模式 isReadyForVoice 恒 false；本轮由 BookmarkRouteTest 的生产路径
  场景替代探针：修复前该行为由 mapper.routing=null 且无替代确认服务造成（源码事实一致）。
  本轮 T02-runall.log 首次运行曾暴露 mapper 目标切换后被旧在途请求阻塞的真实缺陷
  （OPEN 群组回主频道无法确认），由 setLegacyTarget 取代旧在途请求修复 —— 属修复过程发现的
  集成缺陷，非回归放水。

修复后自动化命令、原始日志、结果：
- RunRouteLifecycle.ps1 → RouteLifecycle 39 项 + BookmarkRoute 21 项全 PASS（T02-routelifecycle.log）。
- RunAll.ps1 → 18 组 harness + core test 全 PASS（T02-runall.log；IdentityIssuing 曾出现一次
  Windows 文件重命名偶发 AccessDeniedException，清理 fixture 后复跑通过，与代码无关）。
- gradle build → BUILD SUCCESSFUL（T02-teamspeak-svc-build.log）。

相关旧回归：
- MapperRegression 13 项（legacy 群组/密码/镜像/OPEN 语义）全 PASS；其余 16 组全 PASS。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。缺真实 TS 服务器（E06/E15 对应行：错误密码/权限/管理员移动）。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（语音与频道文字同一判定；三种频道策略落地；书签默认频道≠锁定）。
- 自动化：完成（原始日志在 evidence/）。
- 集成：部分 —— 书签 GUI 入口本身在 T09；本任务已覆盖 manualConnect(bookmarkId) 完整控制流。
- 真实环境：未验证（缺真实 TS 服务器与多客户端布局）。

与本计划偏差及原因：
- LOCKED_TARGET 的书签 schema 字段（channelPolicy）按计划属 T09；T02 已实现锁定语义与生产 API
  （setChannelTarget），测试经该 API 驱动。
- 在途移动请求加 10 秒超时（计划 3.4"通知因果不明确则重新确认"的可实现下限），T10 UI 可显式重试。

已知限制与回滚：
- TS 错误应答（密码错/权限不足）尚未接入 onMoveRejected 的实际协议路径（T05 命令生命周期接线）；
  当前拒绝语义由 joinChannel 抛异常路径覆盖。
- 回滚：回退本任务提交；mapper 恢复自持许可，书签模式回到 R02 缺陷状态（已知不可接受，故回滚
  应与 T01 一并评估）。
