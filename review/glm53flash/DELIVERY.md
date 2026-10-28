# glm53flash 轮次交付报告（T00–T08 + T11）

日期：2026-10-28。基线 `09361cc` → 终点提交见 git log。本报告对应计划 T13（M6 验收）的
诚实结论：**核心切片未全部通过——M6 不标完成**；已交付 11/14 个任务的可复查实现与
自动化证据，M4（T09/T10 GUI）与 T12（公告 payload 接线）未开始（见"剩余工作"）。

## 已完成任务与提交

| 任务 | 提交 | 自动化 | 集成 | 真实环境 |
|---|---|---|---|---|
| T00 基线与记录 | 44a0b97 | ✅ 基线 16 组全绿 | — | — |
| T01/R01 统一连接意图+PAUSED | a8ed3b2 | ✅ 33 项 | ✅ CLI/GUI/自动 | ❌ E04/E14 |
| T02/R02 共用确认路由 | e6608f2 | ✅ 60 项 | ✅（书签 GUI 在 T09） | ❌ E06/E15 |
| T03/R08 队列线性化 | c1e6e38 | ✅ 22 项 | ✅ writer 边界 | ❌ E05 |
| T04/R07 异步二次消费 | d868731 | ✅ 17 项 | ✅ 三条排队路径 | ❌ 真实竞争 |
| T05/R06 命令生命周期 | 90adf5e | ✅ 22 项 | ✅ marker 确认链 | ❌ E10 |
| T06/R09 撤销信任降级 | 2750c25 | ✅ 8 项新增 | ✅ 查询+显示通知 | ❌ E09 |
| T07/R10 参与者键+所有权 | 736b8f0 | ✅ 12 项 | ✅ 注入/清理/对账 | ❌ E09/E11 |
| T08/R03 独立前端接线 | a295ed6 | ✅ 14 项 | 🔶 集成待验收 | ❌ E01/E11 |
| T11/R05 发现生命周期 | 32e8e77 | ✅ 30 项 | 🔶 接线在 T12 | ❌ E08 |

每任务四列状态与偏差、回滚见 [evidence/Txx-REPORT.md](.)；原始日志全部在 evidence/。

## 核对报告 R01–R10 的最终状态

R01 ✅ T01；R02 ✅ T02；R03 🔶 T08（代码完成，真机待验收）；R04 🔶 部分完成
（命令 tracker/授权路由/选择策略状态机已进生产；GUI 屏与 payload hello 未接线）；
R05 ✅ T11（状态机；接线在 T12）；R06 ✅ T05；R07 ✅ T04；R08 ✅ T03；
R09 ✅ T06；R10 ✅ T07。

## 计划内硬约束的落实

- 统一协调器提交点（§3.1/3.2）：T01 建立并贯穿——所有意图入口经 BackendCoordinator；
  MicRouteGate 降级为快照兼容视图。
- 三个语音操作（D1）：disconnectTeamSpeak→PAUSED / switchToSvc / disableAllVoice 均
  为独立操作并有自动化（E14 的真实设备验收未做）。
- 无公告不改变本地/全局自动目标（D2）：T11 状态机 + T12 将接线的优先级序
  （§3.3）——当前 manager 优先级未改（见剩余工作）。
- 频道策略（D8）：TS_NATIVE_FOLLOW/LOCKED_TARGET/LEGACY_GROUP_MAPPING 全部落地。
- 保留既有有效修复：全程未回退；harness 断言只收紧未放宽。

## 自动化总入口

`powershell -NoProfile -ExecutionPolicy Bypass -File .\review\RunAll.ps1`
→ 22 组 harness + ts3voice-core clean test 全绿（T08-runall.log）；
`gradlew -p teamspeak-svc build` 成功（T08-build.log）。

## 剩余工作（按计划顺序，未开始）

1. **T09/T10（M4 GUI）**：书签 schema 扩展（nickname/identityRef/channelPolicy）、
   TsBookmarksScreen/TsBookmarkEditScreen/TsChannelsScreen、VoiceStatusModel。
   锚点：manager 已具备 manualConnect(bookmarkId)/三个语音操作/TsChannelRoute.statusReason()；
   屏幕需真实 MC 客户端渲染验收（E07）。
2. **T12（M5 接线）**：CapabilitiesPayloads（外层 readByteArray 限 MAX_FRAME_BYTES）、
   TeamspeakSvcClient 注册 + hello 发送（JOIN 时）、discovery 生命周期接入 manager
   （onServerChanged→begin；公告接收→onAnnouncement）、§3.3 优先级接入
   resolveSessionConfig（授权公告 > 本地自动 > 全局）、重连循环接 SelectionPolicy
   backoff。锚点：discovery API 就绪（T11）、CapabilityMod/payload ID 在
   voice-capabilities-fabric。
3. **T13（M6）**：上述完成后按 §6.2 真实矩阵执行；未建立真机基线前不得标 M6。

## 真实环境验收（全部未验证）

ACCEPTANCE.md E01–E15 全部"未验证"+缺失条件；统一发布（M7/T14）与 B/D/P7 不在
本轮范围。当前无任何真机双向通话证据——**不得宣称产品闭环完成**。

## 已知限制汇总（细节见各任务报告）

1. R03 真机播放（null-connection 下行注入）未验证；M3 集成待验收。
2. 音量迁移依赖真实 SVC 配置 ABI；未核对前为尽力而为+日志。
3. REMOTE_VERIFIED 读回验证待目标 TS 协议核实。
4. tsvoice 命令帮助与 GUI 文案的完整化随 T09 交付。
5. IdentityIssuing 套件存在 Windows 文件重命名偶发（AccessDeniedException），
   清理 fixture 重跑即过（与代码无关，已在 T02/T05 记录）。
