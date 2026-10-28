# 工程收尾轮执行状态（N0–N7）

日期：2026-10-29。基线 `b732169` → 终点 HEAD 见 git log。依据：
[工程收尾任务书](../../docs/superpowers/plans/2026-10-29-engineering-closeout-plan.md) 与
[持续推进总任务书](../../docs/superpowers/plans/2026-10-29-continuous-development-master-plan.md)。
客户端真机验收统一状态：**用户要求暂缓**（见 DEFERRED-CLIENT-ACCEPTANCE.md），不填通过。

## 基线与环境

- 开工 HEAD：`b7321693deb3067315249ce3761f5956696a7ba1`（与任务书编写基线一致）。
- Java 25.0.4.1 LTS；Gradle wrapper 9.5.1；Windows 10.0.26100 x64。
- 目标依赖 `teamspeak-svc/libs/voicechat-fabric-2.6.25+26.3.jar`
  SHA-256 `189605FD1C07D582DB3F243201F0435A48545F18481FB79847F1965011579407`（开工即核对一致）。
- 详细基线与上一轮记录纠错：[N0-BASELINE.md](N0-BASELINE.md)。

## 任务状态总表（实现 / 工程验证 / 客户端验收）

| 任务 | 内容 | 实现状态 | 工程验证 | 客户端验收 |
|---|---|---|---|---|
| N0 | 基线纠错 + 统一测试入口 | ✅ RunAll v2（core 先行 + 21→22 组 harness）、STATUS/DELIVERY 更正 | ✅ 干净副本全绿（[n0-clean-copy-runall.log](evidence/n0-clean-copy-runall.log)）；历史两组失败判定为本机 JDK HttpClient 环境故障（HttpClientSmoke + 复测通过） | — |
| N1 | D04/D05 频道受众与移动生命周期 | ✅ MovePhase 五态、clientmove 经命令 tracker、全局单调写出代际、失败持续阻断、acceptCurrentChannel | ✅ RouteMoveLifecycleTest M1–M8（146 项 route-lifecycle 全绿）；RouteEpochRevocationTest（真实 Ts3Connection writer 消费生产路由状态）；既有 B/Q/L 段不回退 | ❌ E05/E06/E15 未验证（用户暂缓） |
| N2 | D01/D02 独立前端与采集所有权 | ✅ 自有 AudioChannel 播放（null-connection 早退门已确证并绕开）、失效租约先释放再创建、SVC 建立/断开交接、frontendMicAlive 诊断 | ✅ FrontendLeaseTest 39 项（忠实 null-connection 门桩、恢复链、交接、播放生命周期、资源失败不伪报）；ABI.md 修订；compileJava+RunAll 全绿 | ❌ E01/E11 未验证（真机听感暂缓） |
| N3 | D03 OFF 原生 SVC 接收覆盖 | ✅ ClientVoicechatMixin（processSoundPacket HEAD、意图直读门控）、disableAllVoice 清空原生通道队列、PAUSED 不压制 | ✅ OffGateTest O1–O6（gate 生命周期、真实反射清空、PAUSED 非 OFF、OFF→TS 恢复、换服保持、会话停止）；mixin 目标/签名经 javap 核对 | ❌ E14 未验证（真实设备静音效果暂缓） |
| N4 | T09 书签与原界面入口 | ✅ schema v3（nickname/identityRef/channelPolicy+v2 备份迁移）、TsBookmarksScreen/TsBookmarkEditScreen、VoiceStatusModel、ConfigScreen 麦克风受众行+三操作、删除活动书签进入 PAUSED | ✅ GuiFlowTest G1–G7（迁移、锁定端到端、三操作转换、删除保护、endpoint 隔离凭据、未知 id 报错）；生产 compileJava 通过 | ❌ E07 未验证（布局/可点击性/快捷键暂缓） |
| N5 | T10 频道页与用户控制 | ✅ TsChannelsScreen（快照仅客户端线程读、cid 加入、锁定/跟随切换、在当前频道继续、参与者稳定 UUID 用户列表）、路由策略入口 | ✅ GuiFlowTest C 段（cid 加入、错误密码、权限拒绝、stay-here 恢复、创建≠移动、页面关闭后迟到回复、旧会话销毁） | ❌ E07/E15 未验证 |
| N6 | T12 公告与实际选择策略接线 | ✅ net/CapabilitiesPayloads（同 ID、双向有界）、JOIN hello（canSend 门控）、公告主线程消费、信标信任模型授权、候选进入 resolveSessionConfig（不继承凭据）、FailureKind 分类+终止性重试边界、退避 jitter | ✅ GuiFlowTest D 段（hello nonce/界限、授权链、选择、错 nonce/重放/旧上下文/超长/零 TTL 负例、全局回退保留、失败分类、jitter 界）；22 组 harness + core 全绿 | ❌ E08/E13 未验证（真实公告服务端交互暂缓） |
| N7 | 工程交付 | ✅ 本文档组 + 提交范围 | ✅ 终点 HEAD 干净副本 RunAll（[n7-clean-copy-runall.log](evidence/n7-clean-copy-runall.log)）+ `gradlew build`（[n7-build.log](evidence/n7-build.log)），产物 `teamspeak-svc-0.9.8+26.3.jar` SHA-256 `5FA9DF880E53C35CFFE04D12996A58CC9DC00A01DE677AA1E6A5B6FA4C1C6447` | ❌ 全部 E/U 项维持未验证 |

## 五项重点自查（6.3）

| 重点 | 结论 | 证据 |
|---|---|---|
| 旧请求撞新会话 | 已封闭：移动请求结果绑定创建时的 PendingMove 实例与会话 token；被新请求取代的迟到回复按代际丢弃；旧会话回调整体失效 | RouteMoveLifecycleTest M8（A→B 迟到回复丢弃）；session-consumption 套件（T04 会话隔离）；endSession/beginSession 状态重置 |
| 已出队旧帧 | 已封闭：写出点复核 + 全局单调 routeEpoch（跨会话不复位）；策略拒绝本身原子撤销许可并递增代际；真实 writer 队列消费生产路由状态 | RouteEpochRevocationTest R（真实 Ts3Connection：旧 epoch 队列帧全部丢弃、旧会话帧永不匹配新代际）；ConnectionLinearizationTest L（在途帧不可撤回为已记录限制） |
| 输入线程死亡 | 已封闭：失效租约先释放再创建（不再创建即关闭的抖动）；4 轮连续设备死亡恢复始终唯一存活采集源；SVC 建立/断开与周期 tick 双路交接 | FrontendLeaseTest R1（恢复链）+ R2（借用/自有交接）；manager 会话循环每轮 reacquire |
| OFF 残余队列 | 已封闭（工程层）：原生 SVC 表内通道 closeAndKill+clear（真实反射路径）；TS 播放资源由 OFF 会话停止释放；新帧由 mixin HEAD 门控拦截（运行期应用待真机确认） | OffGateTest O2（排队原生播放清空）+ O6（TS 会话停止）；RouteLifecycleTest J（TS 播放资源释放）；mixin ABI 见 [standalone-frontend/ABI.md](../standalone-frontend/ABI.md) 与 N3 报告 |
| 无公告但用户配置 TS | 已封闭：公告缺失只淘汰公告候选——本服/全局/手动书签路径原样保留；信标信任模型不因公告机制改变 | GuiFlowTest D4（全局回退不变）；resolveSessionConfig 优先级顺序（书签>信标>本服>公告>全局）与既有 B/E13 语义 |

## 保留的既有产品边界（未回退）

单一协调器意图入口；PAUSED 不隐含切换 SVC；TS_NATIVE_FOLLOW 接受正常管理员移动；
锁定/群组映射各自受众约束；无 SVC 服务端仍可手动 TS；凭据按 endpoint 隔离、绝不
跨 endpoint 继承；身份私钥不进书签/日志；原版 SVC 操作体验与音频增强未动。

## 阶段后续

N7 工程门槛已达成（见 DELIVERY.md 的未解决事项清单）——按总任务书进入 M7
统一发布工程实施；M6/M7 的完整安装矩阵与最终验收仍待用户恢复真机验收。
