# 首切片（P0–P4）交付证据包

日期：2026-10-28。切片范围：P0 缺陷修复 → P1 媒体归属/会话隔离 → P2 前端适配层 → P3 书签/频道/上传 → P4 能力发现。
spec：`.zcode/specs/p0-p4-first-slice-20261028/spec.md`；任务与逐项证据：`tasks.md`。

## 提交清单（19 + 收口）

| 提交 | 阶段 | 内容 |
|---|---|---|
| ac4039c | 基线 | git init + 基线快照（1164 文件，排除生成物/凭据） |
| c3b9164 | P0/F02 | 麦克风先取消后处理（MicRouteGate），失败测试先行 9 项 |
| b2ce9e6 | P0/F06 | UNKNOWN 按群过滤 + 快照 5s TTL，失败测试先行 3 项 |
| 8263c3e | P0/F04+F05 | 显式 issuer 信任 + 真实 expiry + 持久防重放，失败先行 10 项 |
| 483d6ee | P0/签发端 | IdentityRevisionStore 预留块持久化 + 逐条撤销广播，失败先行 2 项 |
| 6495bcd | P0 收口 | CI 接入 4 组 P0 回归 |
| cb7c8f0 | P1/F03 | 回调绑定 (source client, token) 双检 + SVC 断连解耦，失败先行 5 项 |
| 594e7b4 | P1/F09 | 有界发送队列 + 不可阻塞关闭，失败先行 4 项 |
| beede24 | P1 | 稳定参与者 UUID（serverUID 作用域）+ 所有权守卫通道关闭，old-probe 2 项 |
| b580b95 | P1 收口 | CI 接入 3 组 P1 回归 |
| 8dae2e5 | P2 | 前端适配层/自检门控/CLIENT_STOPPING/降级，19 项替身断言 |
| 6000b62 | P3/F08 | endpoint 隔离凭据 + 书签 v2 + 显式意图，失败先行 1 项（hunk 级） |
| 68d6edb | P3 | TsCommandTracker（return_code）+ TsChannelSnapshot + 群组分模式，15 项 |
| 66b7db8 | P3/F10 | clientftfid 关联 + marker 键升级，6 项 |
| 9792762 | P3 收口 | CI 接入命令关联套件 |
| 3c342bb | P4 | CapabilityWire（golden+malformed 22 项，extraCount 设计修正） |
| 2ee7fbb | P4 | SelectionPolicy/CapabilityDiscovery/服务端公告 mod（29 项） |
| 5db8c00/本收口 | chore | harness 状态文件忽略等 |

## 最终全量验证（CI 等价命令）

**一键入口：`review/RunAll.ps1`**（汇总 16 组 harness 回归 + core 测试，PASS/FAIL 逐行输出，任何失败即退出码 1；粘贴原始输出作为证据，不手抄声明）。

最近一次完整运行（Review R1 整改后，提交于 remediation 提交）：

```
PASS review/channel-fixes/RunProtocol.ps1
PASS review/channel-fixes/RunMapper.ps1
PASS review/client-fixes/RunTests.ps1
PASS review/client-fixes/RunManagerTests.ps1
PASS review/p0-defects/RunMicRedirector.ps1
PASS review/p0-defects/RunClientIdentity.ps1
PASS review/p0-defects/RunBridgeMembership.ps1
PASS review/p0-defects/RunIdentityIssuing.ps1
PASS review/p1-isolation/RunSessionIsolation.ps1
PASS review/p1-transport/RunTransportShutdown.ps1
PASS review/p1-playback/RunStableVolume.ps1
PASS review/p2-frontend/RunFrontend.ps1
PASS review/p3-config/RunBookmarkConfig.ps1
PASS review/p3-channel/RunCommandTracker.ps1
PASS review/p4-discovery/RunFallbackPolicy.ps1
PASS review/server-fixes/RunServerChecks.ps1
PASS core test (ts3voice-core clean test)
CI-EQUIVALENT RESULT: PASS (all suites + core test green)
```

| 步骤 | 命令 | 结果 |
|---|---|---|
| core 测试 | `cd ts3voice-core && ../teamspeak-svc/gradlew.bat clean test` | ✅ 108 protocol + 15 identity trust + 22 capability wire |
| 客户端构建 | `cd teamspeak-svc && ./gradlew.bat clean build` | ✅ |
| 旧桥构建 | `cd teamspeak&svcbridge && ./gradlew.bat clean build` | ✅ |
| 能力服务端构建 | `cd voice-capabilities-fabric && ./gradlew.bat clean build` | ✅（0.1.0+26.3.jar） |

### Review R1 整改记录（诚实标注）

R1 实测发现 9/16 组回归在 HEAD 失败（3 组由 6000b62/66b7db8 引入的 runner 破损：PowerShell 数组语法、编译清单缺 Ts3FileTransfer/Ts3FileTransferBridge、stub onTsFrame 6 参签名过期、ClientStateTests 仍用 ghostUuidFor），与本文件早先"退出码全 0"的声明矛盾——该声明当时不成立，已按以下整改后复跑：

1. RunMicRedirector/RunSessionIsolation 数组语法修复；RunManagerTests/RunMicRedirector/RunSessionIsolation stub onTsFrame 升级 8 参（authority 前缀）。
2. Ts3FileTransfer.java/Ts3FileTransferBridge.java 加入 RunProtocol/RunClientIdentity/RunTransportShutdown/RunStableVolume/RunTests 编译清单。
3. ClientStateTests 改用 participantUuidFor（v2 API）；RunMapper 内嵌 TsVoiceManager stub 补 hasExplicitBookmarkIntent。
4. Ts3FileTransfer 日志与入口类解耦（独立 slf4j Logger——消除了 harness 对 TeamspeakSvcClient 的编译依赖，亦为更干净的设计）。
5. 新增 review/RunAll.ps1 一键 CI 等价验证；RunAll 自身修复了三个环境陷阱：工作区路径含 `&` 时 Set-Location/子进程调用被截断（root 改由 $PSScriptRoot 推导）、Start-Process 退出码传递（core 步骤改 marker 文件判定）、汇总误用 $LASTEXITCODE（改 $failed 计数）。

## 兼容范围（收窄声明）

- **已验证（替身级）**：MC 26.3 / SVC 2.6.25（javap ABI 台账）/ Fabric Loader 0.20.1 / Java 25 / Gradle 9.5.1。
- **真实验收矩阵未执行**——以下项不得宣称"已支持"：
  1. **U1/U2（P2 核心门）**：无 SVC 服务器的手动 TS 双向通话；独立播放通道（`AudioChannel` 直建 + null InitializationData 的 run() 安全性未核）。**已知缺口**：`processSoundPacket` 无连接时直接返回，注入路径在无 SVC 时无声音——P2 停线条件适用，禁止发布"无 SVC 服务器可用"声明。
  2. U3 mic 所有权交接（真机）、U4 hook 自检失败（非 2.6.25 运行时）、U5 退出编排（真机）、U6 设备热切换。
  3. P3：TS 频道浏览 UI/书签屏幕（数据层就绪，屏幕未实现——真实窗口/焦点/键位验证要求）；真实 TS 服务器上的上传/权限拒绝。
  4. P4：hello/announce 真实往返、客户端 payload 接线与授权弹窗 UI（决策逻辑就绪并测试）、防火墙故障注入演练。
  5. 真实设备音频质量（PTT/激活/AEC/个人音量）、30 分钟长流、跨服场景。

## 关键语义变化（升级注意）

1. TS 选中但未就绪期间麦克风 fail-closed（原为泄漏到 SVC）；恢复 SVC 原生需手动断开 TS。
2. SVC 断开不再销毁 TS 会话（P1 解耦）。
3. TS 命令发送变异步入队；传输层错误经连接超时路径上报（黑洞下 stop 限时返回）。
4. 身份：未知 issuer 一律 Ghost；重放防护持久化（identity-trust.json）；签发端升级需同时部署（revision 块预留）。
5. Ghost UUID 方案更换（tsvoice-participant-v2，serverUID 作用域）——旧音量缓存不迁移；per-player 桥的服务端需同步适配。
6. 凭据不再跨 endpoint 继承（F08）；书签 v2 为增量文件，旧配置原样保留。

## 上游协作记录

- CapabilityWire 额外字段设计修正（extraCount）与包位置修正（capability 子包）为本地缺陷，golden 测试当场暴露并修复。
- SVC 2.6.25 字节码证据（MicThread null-connection 支持、AudioChannel 四参 ABI）已记录于 Svc2625FrontendAdapter 类注释，可作为上游扩展提案素材（D02）。
