# 独立 Review — unified-public-release-20261027

- **Review 日期**：2026-10-28
- **方式**：独立只读代理审计 tasks.md（Task 1–9）证据与仓库实况的一致性，并对四条产品原则做代码级核查；发现 F1 后已修正并复验。
- **Review 范围**：`ts3voice-core`、`teamspeak-svc`、`tsbridge` 源码与构建、回归脚本、CI、许可与发布文件。

## 总体结论

**离线可验证范围：PASS。** Task 1–9 的证据声明与仓库实况一致，四条产品原则在代码层成立；1 处证据计数偏差（F1）已修正。
**公开发布验收：未宣称完成。** Task 10 的真实环境矩阵（多实例、真实设备、长会话、升级）缺环境，全部按 blocked 记录，Unblock Condition 见下文。

## 逐项验证结果（独立代理，file:line 证据）

1. **PASS** `ts3voice-core` 十个协议类齐全；`ProtocolTestMain` 81 个 `check()` 调用点（含循环共 108 项断言），失败即抛 `AssertionError`；`src/main` 零 Minecraft/Fabric 依赖。
2. **PASS** 身份证明绑定玩家 UUID：`IdentityProtocol.proofBytes` 写入 playerUuid；客户端 `ClientIdentityService.handleChallenge` 用其签名；服务端 `IdentityProofService.verifyProof` 强制 `player.getUUID()==proof.playerUuid()` 且 serverUid/tsClientUid/publicKey 与 pending 挑战逐项相等。
3. **PASS** 普通语音不被身份门禁：`getVerifiedMcUuid` 全库唯一调用点 `TsVoiceManager.java:644`，仅用于展示映射；无 claim 走 `"[TS]"` Ghost 正常收发（`TsAudioInjector`）、内置皮肤（`GhostSkins`）；`ServerTrustDirectory` 无网络代码，信任纯本地，无中心白名单。
4. **PASS** 隧道安全：`TcpTunnelServer` 先 `verifyAndConsume` 一次性票据再启动转发；默认绑定 `127.0.0.1`；无 `TBRIDGE_TUNNEL_SECRET` 则隧道禁用；客户端强制 secret≥32 字符并主动发票据。
5. **PASS** 成员快照 fail-closed：`UNKNOWN` 保留 lastVerified、`EMPTY` 权威置空，绝不回退全员。
6. **PASS** 构建固定：双 wrapper 均含 `distributionSha256Sum`；Loom 固定 `1.17.21`（非 SNAPSHOT）。
7. **PASS** 许可文件齐全：客户端 GPL-3.0-or-later、服务端 MIT、核心 MIT OR GPL-3.0-or-later 双许可；服务端作者占位符已修正。
8. **PASS** CI 覆盖 core 测试、双端构建与全部五组回归脚本。
9. **PASS** 回归脚本 `javac+java`，非零退出即失败，无自测恒真入口。
10. **PASS** 两个发布 jar 存在且 SHA-256 与 tasks.md 一致，均内嵌 `ts3voice-core-0.1.0.jar`。

## Actionable Findings 及处置

- **F1（已修复）**：tasks.md 多处声称服务端回归 16/16，实际 `ServerRegression` 为 12 个 `test()` 调用点（含一个 4 阶段循环）= **14 项**。机制真实且 fail-closed，仅计数失实。处置：tasks.md 三处已更正为 14/14，并在本 Review 复验。

## Blocked 矩阵（Task 10，不计 finding）

| 验收项 | Blocked By | Unblock Condition |
|---|---|---|
| 双 MC + 普通 TS + tsbridge 多实例端到端（direct/server-bridge/group-sync） | 无真实 TS 服务器与多 MC 客户端 | 管理员提供可连的真实 TS3 服务器与 ≥2 个 MC 客户端 |
| 真实音频设备、设备切换、外放 AEC | 无 OpenAL/真实麦克风扬声器环境 | 真机测试记录（听感 + 日志） |
| NVIDIA NVAFX ABI/稳定性 | 环境无 `NVAudioEffects.dll`/RTX | 安装 NVIDIA Broadcast 的 RTX 环境；在此之前保持 dll-检测门槛，不盲改 |
| 30 分钟以上弱网长会话、升级迁移矩阵 | 同多实例环境 | 长会话日志 + 资源计数前后对照 |
| 已混合版本（v1/v2 客户端）实测 | 同多实例环境 | 旧版客户端 jar 可供安装 |

## 遗留开放项（移交发布前确认）

- 服务端 `THIRD-PARTY-NOTICES.md` 已标注：`com.svcb.tsbridge.ts3voice` 移植自 TS3AudioBot/TSLib，**上游许可证与 MIT 声明的兼容性需在公开发布前由所有者核实**。

## 最终门槛状态

- 所有实现任务（Task 1–9）：completed，证据可复现（命令与哈希见 tasks.md）。
- Task 10：blocked（真实环境）。**本 Review 不构成公开发布验收完成的结论**；真实环境矩阵补齐并复评通过后，方可宣称发布验收完成。
