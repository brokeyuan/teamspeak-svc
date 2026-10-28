基线 / 当前提交：
`b732169`（开工）→ `3976267`（N0–N7 全部提交；提交表见
review/engineering-closeout/DELIVERY.md）。

当前阶段 / 当前任务：
阶段 A（N0–N7 工程收尾）与阶段 B（M7 统一发布 T14.1–T14.6/T14.8）已完成；
真实安装矩阵（M7-E01–E12/T14.7）待用户恢复真机验收。下一段可继续的工作：
M7 真机矩阵补验（需用户环境）、随后沿队列 C（M9/P6 TS over MC TCP）推进。

已完成的生产路径：
- D01–D05 全部关闭（工程层）：独立 AudioChannel 播放 / 失效租约接管 / OFF
  processSoundPacket HEAD mixin 门控 / 策略拒绝原子撤销+全局单调写出代际 /
  clientmove 命令关联与 FAILED 持续阻断。
- T09/T10/T12 生产接线：书签 schema v3 + 列表/编辑屏 + VoiceStatusModel、
  频道页（cid 加入/锁定/stay-here/参与者 UUID 用户列表）、能力公告链
  （hello→公告→信任授权→候选选择）+ FailureKind 分类 + 退避 jitter。

本次实际测试与证据路径：
- review/RunAll.ps1 v2：core 先行 + 23 组 harness（含 m7-unified-release/RunM7）。
- M7 候选产物 teamspeak-svc-0.9.8+26.3.jar SHA-256
  2E8BFE430DE304138E240973455CDE084AB45363D76F3C178A536D2CF64ACE73
  （取代 N7 阶段哈希 5FA9DF88…，N7 记录保留为历史）。
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
等用户恢复真机验收后执行 M7-E01–E12 矩阵（review/m7-unified-release/STATUS.md
未验证清单）；工程侧可继续队列 C 的技术准备：TS3 over MC TCP 的最小数据报往返
原型（TcpTransport 之上的 per-connection 帧格式冻结前不做生产代码）。验证命令：
`powershell -NoProfile -ExecutionPolicy Bypass -File review/RunAll.ps1`（23 组全绿）。

本轮新增或修订的技术决策（M7）：
- 唯一 JAR：environment="*"；main（com.tsvoice.TeamspeakSvcMod，无 SVC 引用）/
  client（bootstrap.ClientEntrypoint 依赖校验后委托）/ voicechat（compat 门面，
  服务端只占稳定 ID）三入口；SVC 从全局 depends 移除，客户端以元数据检查明确校验。
- payload 注册唯一所有者：common/net/CapabilitiesPayloads，主入口一次注册。
- 服务端公告：server/ServerConfig（schema v1 + legacy 备份迁移 + 未实现 kind 诊断）、
  CapabilityAnnouncer（nonce 回显、2/s 突发 4 限流）、ServerRuntime（四状态、
  每 MinecraftServer 实例、集成服务器默认门）、/tsvoice-server status|reload。
- breaks: voice-capabilities（旧公告 mod 冲突声明）。

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
