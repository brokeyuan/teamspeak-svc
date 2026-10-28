# glm53flash 轮次执行状态（T00–T13）

依据：[2026-10-28 改进报告与分阶段实施计划](../../docs/superpowers/plans/2026-10-28-glm53flash-improvement-report.md)（修订 1.1）。
本轮实施范围为 M0–M6；M7/T14 是后续必做的统一发布验收项，M8/M9/P7 不在本轮自动开始。

## 基线与环境

- **源码基线：**`09361ccf561829b7e3df3b0d524ddcddb9911d11`（开工时 HEAD 一致）。
- **日期：**2026-10-28。执行环境：Windows 10.0.26100 x64，Git Bash + PowerShell。
- **Java：**25.0.4.1 LTS（要求 Java 25，满足）。
- **模块版本：**teamspeak-svc 0.9.8（MC 26.3，voicechat-api 2.6.20，Loom 1.17.21，Gradle wrapper 9.5.1 已缓存）；ts3voice-core 0.1.0；voice-capabilities-fabric 0.1.0（MC 26.3）。
- **SVC 参考：**`review/lib/voicechat-api-2.6.20.jar`（API JAR）；`simple-voice-chat-26.2/` 为参考源码。**更正（2026-10-29 工程收尾 N0）：**实际 SVC 2.6.25 完整 mod JAR 一直在 `teamspeak-svc/libs/voicechat-fabric-2.6.25+26.3.jar`（Git 跟踪文件，SHA-256 `189605FD1C07D582DB3F243201F0435A48545F18481FB79847F1965011579407` 已复核）；本行旧表述"实际 SVC JAR 未定位到"作废，真实 ABI 依据随 N2（D01）补记。
- **真实环境：**未启动 MC、未连接真实 TS 服务器、无音频设备验收条件。按计划约束，环境缺失只阻塞对应验证列，不停止可完成的源码修复。

## 证据状态定义

| 列 | 含义 |
|---|---|
| 代码 | 生产路径已接入（不是仅有类/enum/纯函数） |
| 自动化 | 本轮实际运行的回归命令 + 原始日志（evidence/ 下），失败测试先于修复暴露缺陷 |
| 集成 | 完整调用链可达（玩家入口 → 状态提交 → 网络/音频结果） |
| 真实环境 | 真实 MC/SVC/TS/音频设备验收（E 矩阵对应行） |

初始状态一律为“未完成”；不得凭上一轮 PASS 填本轮 PASS。

## 本轮新跑基线（M0 实际执行结果）

- 命令：`powershell -NoProfile -ExecutionPolicy Bypass -File ./review/RunAll.ps1`，原始输出见 [evidence/M0-runall-baseline.log](evidence/M0-runall-baseline.log)。
- 结果：**16 组 harness 全部 PASS + ts3voice-core clean test PASS**（2026-10-28 本轮实跑，非引用历史记录）。
- 运行后 `git status` 与开工一致（仅 docs/ 与核对报告两个未跟踪项），harness 未污染受跟踪文件或用户配置。
- 历史核对（引用，非本轮新增）：[P0–P4 独立核对报告](../P0-P4-VERIFICATION-2026-10-28.md)，其 R01/R02/R05/R06/R09 探针在本轮未重跑，缺陷状态以该报告 + 本文任务结果为准。

## 任务状态总表

| 任务 | 内容 | 代码 | 自动化 | 集成 | 真实环境 |
|---|---|---|---|---|---|
| T00 | 建立基线与执行记录 | ✅（本文件+evidence） | ✅ 基线回归实跑通过 | — | — |
| T01 | R01 统一连接意图与麦克风归属 | ✅ | ✅ route-lifecycle 33 项 + RunAll 全绿 | ✅（CLI/GUI/自动激活经协调器；书签 GUI 在 T09） | ❌ E04/E14 未验证 |
| T02 | R02 共用确认路由 | ✅ | ✅ RouteLifecycle 39 + BookmarkRoute 21 项 | ✅（书签 GUI 在 T09） | ❌ E06/E15 未验证 |
| T03 | R08 频道切换队列线性化 | ✅ | ✅ QueuedVoice 9 + ConnLinearization 8 项 | ✅（三条生产路径同一 writer 边界） | ❌ E05 未验证 |
| T04 | R07 异步二次消费隔离 | ✅ | ✅ Session 10 + Identity 7 项 | ✅（聊天/身份/头像三条排队路径） | ❌ 真实竞争未验证 |
| T05 | R06 命令生命周期与上传确认 | ✅ | ✅ CommandLifecycle 13 + UploadConfirmation 9 项 | ✅（tracker 接线+marker 确认链） | ❌ E10 未验证 |
| T06 | R09 撤销信任与展示降级 | ✅ | ✅ R09 8 项新增 + 身份既有 15 项 | ✅（查询复核+显示通知） | ❌ E09 未验证 |
| T07 | R10 参与者标识与播放所有权 | ✅（更正 2026-10-29 N0：提交 `736b8f0`，原行误记 ❌） | ✅ playback-ownership 12 项 | ✅ 注入/清理/对账 | ❌ E09/E11 未验证 |
| T08 | R03 独立 SVC 音频前端（a/b/c） | ✅（租约接线+播放入口） | ✅ FrontendLease 14 项+租约会话断言 | 🔶 集成待验收（L3 真机未验证） | ❌ E01/E11 未验证 |
| T09 | 书签配置与界面入口 | ❌ 未开始（manager 动作层已具备） | ❌ | ❌ | ❌ |
| T10 | 频道树/用户列表/命令结果 | ❌ 未开始（TsChannelRoute/TsChannelSnapshot 已具备） | ❌ | ❌ | ❌ |
| T11 | R05 能力发现生命周期 | ✅ | ✅ Discovery 30 项 | 🔶 生产接线在 T12 | ❌ E08 未验证 |
| T12 | 公告接线与实际选择策略 | ❌ 未开始（discovery API 已就绪） | ❌ | ❌ | ❌ |
| T13 | P0–P4 产品闭环验收 | ❌ M6 不标完成（见 DELIVERY.md） | — | — | ❌ |

T08 特别约束：无实际 SVC 2.6.25 JAR 与音频设备时，M3 只能标“集成待验收”，不能宣称完成；若连代码级 ABI 确认都不可行，按计划第 9.3 条记录具体缺口。

## 轮次终点状态

详见 [DELIVERY.md](DELIVERY.md)：11/14 任务完成（T00–T08+T11），全部带失败先行证据与
原始日志；剩余 T09/T10（GUI）、T12（接线）、T13（闭环）未开始，M6 不标完成。

## 功能差距矩阵（开工时点）

| 发现 | 证据状态（开工） | 对应任务 |
|---|---|---|
| R01 手动断开→重连后麦克风仍归 SVC | 本轮 harness 断言复现（T01-prefix-fail，5 项）→ 已修复 | T01 ✅ |
| R02 显式书签永远拿不到发送许可 | 本轮生产路径场景覆盖 → 已修复 | T02 ✅ |
| R08 出站帧无频道代际 | 本轮两层可控队列测试证明隔离 → 已修复 | T03 ✅ |
| R07 异步二次消费隔离缺口 | 本轮排队→换会话→执行窗口复现并修复 | T04 ✅ |
| R06 EOF 即 CONFIRMED、头像命令未确认 | 类型级移除+命令确认后写 marker → 已修复 | T05 ✅ |
| R09 撤销 issuer 信任后缓存仍生效 | 本轮断言复现并修复（unpin 即时降级+通知） | T06 ✅ |
| R10 可信 MC 分支复用 MC UUID、主线程关流 | 本轮断言复现并修复（参与者键+有界退役） | T07 ✅ |
| R03 独立前端未接线 | 启动条件解除+租约接线+播放入口 → 代码完成，真机待验收 | T08 🔶 |
| R04 书签/频道 GUI、命令 tracker、payload 均未进生产流程 | 源码确认，交付缺口 | T09/T10/T12 |
| R05 发现类缺 nonce/无公告超时/TTL | 状态机重写+30 项断言 → 已修复（接线在 T12） | T11 ✅ |
| 统一服务端发布（M7） | 客户端与公告工程分离 | M7/T14（本轮外） |
| B/D/媒体桥接 | 未交付 | M8/M9/M10（本轮外） |

## 已保留的有效修复（不得回退）

先取消再编码的单帧异常路径；身份声明 expiry/显式信任/防重放高水位；IdentityRevisionStore；UNKNOWN 群组过滤（groupId 快照+5s TTL）；独立写线程/5 帧 120ms 有界队列/先关 Socket 关闭序；serverUID+TS UID 稳定参与者 UUID 与 ownedObject 守卫；同 endpoint 凭据继承限制；入站非 Opus 帧拒绝；SVC 断开不停 TS 管理线程。

## 记录纪律

- 每任务按计划第 9 节模板在 evidence/ 留报告；提交信息带任务编号。
- 未验证项如实标注“未验证 + 缺什么条件”；环境失败不得记为代码已修。
- 历史文档只增不改；更正以新说明形式追加。

## 工程收尾轮更正与接续（2026-10-29 N0 追加）

- **交付计数更正：**DELIVERY 开头"11/14 任务"与任务表 T00–T08+T11（共 10 项）不符；"22 组 harness + core"实为 RunAll 当时 20 组 harness + core（final-runall 日志 22 行含 core 行与汇总行）。以任务表为准。
- **D01–D05 登记：**delivery-audit 2026-10-29 确认五类未闭合问题全部保持未关闭，分别由收尾轮 N1（D04/D05）、N2（D01/D02）、N3（D03）修复，见 [engineering-closeout/](../engineering-closeout/)。
- **统一测试入口更新：**`review/RunAll.ps1` v2 改为 ts3voice-core clean test 先行（5 组 harness 依赖其 build/classes）、纳入 upload-confirmation（共 21 组 harness + core）、每组独立日志、任一失败非零退出。
- **环境故障分类：**上一轮 session-consumption / upload-confirmation 两组失败经无依赖 HttpClientSmoke 判定为本机 JDK HttpClient 初始化环境故障；本轮环境已复测两组均通过（证据 [engineering-closeout/logs/](../engineering-closeout/logs/)）。非产品缺陷。
- **客户验收：**E01–E15 全部维持未验证，按用户要求暂缓；后续轮继续沿用此状态直至用户恢复验收。
