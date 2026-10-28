# N0 基线记录（工程收尾轮）

日期：2026-10-29。执行 AI：GLM 5.3 Flash（ZCode 会话）。

## 环境

| 项 | 值 | 核对方式 |
|---|---|---|
| Git HEAD（开工） | `b7321693deb3067315249ce3761f5956696a7ba1` | `git rev-parse HEAD` |
| Java | 25.0.4.1 LTS（`C:\Program Files\Java\jdk-25.0.4.1`） | `java -version` |
| OS | Windows 10.0.26100 x64 | 环境自报 |
| Gradle wrapper | 9.5.1（teamspeak-svc/gradle/wrapper） | wrapper properties |
| 目标 SVC 依赖 | `teamspeak-svc/libs/voicechat-fabric-2.6.25+26.3.jar` | 文件存在，Git 跟踪 |
| 目标 SVC JAR SHA-256 | `189605FD1C07D582DB3F243201F0435A48545F18481FB79847F1965011579407` | `Get-FileHash -Algorithm SHA256`，与 [delivery-audit REVIEW §3 D01](../delivery-audit-2026-10-29/REVIEW.md) 记录一致 |

开工时未提交变更（仅本收尾轮新增内容）：`docs/`、`review/P0-P4-VERIFICATION-2026-10-28.md`、`review/delivery-audit-2026-10-29/`、本目录。

## 上一轮记录纠错（对应任务书 N0 第 3 条）

| 旧表述 | 实际情况 | 处理 |
|---|---|---|
| STATUS/T08-REPORT/ABI.md："实际 SVC 2.6.25 完整 mod JAR 未在工作区定位到" | JAR 一直在 `teamspeak-svc/libs/` 且为 Git 跟踪文件，哈希见上表 | STATUS.md 已更正；ABI 修正随 N2（D01）进行 |
| STATUS 表 T07 全为 ❌ | T07 已提交（`736b8f0`），回归 `review/playback-ownership/` 12 项存在 | STATUS.md 更新为已交付行 |
| DELIVERY "已交付 11/14 任务" | 表列 T00–T08 + T11 共 10 项 | DELIVERY.md 修订注明实际计数 |
| T08 报告 "22 组 harness + core" / final-runall 22 行 | RunAll 当时实际 20 组 harness + core（22 行含 core 行与汇总行） | 已在新 RunAll 注释与本记录说明 |
| review/RunAll.ps1 未含 upload-confirmation | 该套件独立存在且在此前环境中失败 | 新 RunAll 已纳入（共 21 组 harness + core 先行） |

## D01–D05 登记状态（开工时点，全部未关闭）

| 编号 | 摘要 | 状态 | 修复任务 |
|---|---|---|---|
| D01 | 无 SVC 会话时 TS 下行被实际 SVC `ClientVoicechat.processSoundPacket` 的 null-connection 门丢弃；适配器误报注入成功 | 未关闭 | N2 |
| D02 | 死亡麦克风租约占住 `activeLease`，恢复循环反复创建即关闭新 mic；`onSvcDisconnected` 未调用前端断连钩子 | 未关闭 | N2 |
| D03 | `disableAllVoice()`（OFF）不覆盖原生 SVC 接收/播放 | 未关闭 | N3 |
| D04 | 锁定频道被管理员移走后 `confirmChannel()` 仅返回 false，旧排队帧仍持当前 epoch 可写出新频道 | 未关闭 | N1 |
| D05 | clientmove 拒绝/超时/歧义目标未形成持续发送阻断，`onMoveRejected()` 无生产调用路径 | 未关闭 | N1 |

## 环境分类：两组历史失败套件

- 历史失败组：`review/session-consumption/`、`review/upload-confirmation/`（delivery-audit 2026-10-29 §4）。
- 故障特征：`AvatarPipeline` 静态初始化 JDK `HttpClient` 后回环请求报 `UnixDomainSockets.connect / Invalid argument`。
- 本轮判定：[`HttpClientSmoke.java`](HttpClientSmoke.java)（无项目依赖，复刻 AvatarPipeline 的 HttpClient 构建 + 回环请求）在本机 **通过**（exit 0，见 [logs/n0-httpclientsmoke.log](logs/n0-httpclientsmoke.log)）。
- 复测结果：两组套件在本机直接重跑 **均通过**（exit 0，见 [logs/n0-retest-session-consumption.log](logs/n0-retest-session-consumption.log)、[logs/n0-retest-upload-confirmation.log](logs/n0-retest-upload-confirmation.log)）。
- 结论：上一轮失败为本机 JDK HttpClient 初始化环境故障，非产品缺陷；该环境故障在本轮会话已不复现。产品结论以正常环境下套件真实通过为准。

## 统一测试入口（本轮修订）

- `review/RunAll.ps1` v2：ts3voice-core `clean test` **先行**（5 组 harness 链接 `ts3voice-core/build/classes/java/main`，干净检出必须先编译 core），随后 21 组 harness（原 20 组 + upload-confirmation）；每组独立日志于 `$env:TEMP\tsvoice-ci-logs\`，失败输出尾部摘录，任一失败进程退出码非零；脚本不向工作区写入任何文件。
- 干净副本验证：`git archive HEAD` 至临时目录 + 拷入新 RunAll 后运行，日志 [logs/n0-clean-copy-runall.log](logs/n0-clean-copy-runall.log)。结果：**PASS（core 先行 + 21 组 harness 全绿，进程退出码 0）**，确认干净检出可一键运行、不依赖旧 build 产物。

## 客户端验收

本轮统一状态：**用户要求暂缓**。E01–E15、U01–U06 全部保留原编号，不填通过；见 [DEFERRED-CLIENT-ACCEPTANCE.md](DEFERRED-CLIENT-ACCEPTANCE.md)（N7 产出）。
