# MANIFEST — 客户端验收候选（80dd40f）

日期：2026-10-29。状态：**候选已交付，等待用户客户端验收**。

## 候选产物

| 项 | 值 |
|---|---|
| 源码终点提交 | `80dd40f`（打包提交 `77fc8ea` 仅追加 JAR 本体） |
| JAR 路径 | `dist/client-acceptance/80dd40f/teamspeak-svc-0.9.8+26.3.jar`（仓库内已跟踪，客户端/服务端用同一文件） |
| SHA-256 | `8585708afc5e3740309411b28b46138fb0886b5e08552fe99e20f5ad6c4d6a1c` |
| 大小 | 39,396,995 字节（37.6 MiB） |
| 构建命令 | `cd teamspeak-svc && gradlew build --no-daemon`（JDK 25），退出码 0 |
| 工程门 | `review/RunAll.ps1` → CI-EQUIVALENT PASS（core test + 26 套件，27 条 PASS，exit 0） |

## 实际依赖与版本（已验证组合）

| 组件 | 版本 | 验证方式 |
|---|---|---|
| Minecraft | 26.3 | fabric.mod.json depends + testserver 实际启动 |
| Fabric Loader | 0.20.1 | testserver 实际启动 |
| Fabric API | 0.165.0+26.3 | testserver 实际启动 |
| Simple Voice Chat | 2.6.25+26.3（支持声明区间 2.6.20 ≤ v < 2.7.0，语义版本判定；区间外拒绝） | testserver 启动 + RealCodecRoundTripTest 用其 Opus 实现 |
| Java | 25（构建用 Temurin/Oracle 25.0.4.1；运行需 `java >= 25`） | gradle toolchain |
| voicechat-api | 2.6.20（compileOnly） | build.gradle |
| webrtc-java 0.14.0 / JNA 5.17.0 / BC 1.78.1 / ts3voice-core 0.1.0 | 由 JAR 内嵌（include） | build.gradle + JAR 内容检查 |

## 客户端安装（验收机）

1. 安装 Minecraft 26.3 + Fabric Loader 0.20.1。
2. `mods/` 放入：`fabric-api-0.165.0+26.3.jar`、`voicechat-fabric-2.6.25+26.3.jar`、
   本候选 `teamspeak-svc-0.9.8+26.3.jar`。
3. 启动一次 → `config/teamspeak-svc/` 自动生成；TS 身份文件 `identity.txt`
   首次连接时生成（音乐 Bot 用独立 `identity-music.txt`，自动生成）。

## 服务端安装（按模式，最小矩阵）

| 模式 | 服务端 mods/ |
|---|---|
| A：TS/UDP（跨服基础） | **无需本扩展**（客户端直连 TS） |
| B：TS/MC 通道 | fabric-api + 本 JAR；`config/teamspeak-svc/server.json` 里 `relay.enabled=true` + `relay.targetIds`（管理员 allowlist） |
| C：SVC/UDP | fabric-api + SVC（本 JAR 可不装；装了默认不改行为） |
| D：SVC/MC 通道 | fabric-api + SVC + 本 JAR；`relay.svcMcChannelEnabled=true` |
| B+音乐 Bot 第二会话 | 同 B，另 `relay.musicBotEnabled=true` |

**不装旧 Bridge（tsbridge/voice-capabilities）**；已有运行服务器未被本任务改动。

## 已支持 / 不支持 / 未验证

**已支持（有自动化回归 + 无界面 dedicated/真实组件证据）**：见
`review/client-acceptance-next/DELIVERY.md` §1。

**本轮明确不支持（勿在验收中期待）**：
- 跨 MC 点歌 / 远程控制他人 Bot（可信 sender 元数据链未完成 —— 手册标 N/A）。
- 在线曲库、搜索、MP3/OGG（仅授权本地 .wav）。
- SVC↔TS 媒体桥接（P7 未批准）、网页/手机。
- 双后端同时收听（产品默认单收听；无显式开关）。
- svc-extra 双装版本矩阵（仅 2.6.25 有启动证据；不扩大声明）。

**未验证（需要用户客户端/真实 TS 服，本轮如实未做）**：
真实 MC 网络上的 TS 握手/音频、实体设备听感、30 分钟稳定性、Extra 双装行为。

## 回滚

删除 mods 里的本 JAR 即恢复原版 SVC 行为（SVC 缺失时本扩展入口也不可进）；
`config/teamspeak-svc/` 保留不影响卸载。服务端 reload/停止均回收 relay 会话
（E4 回归覆盖）。
