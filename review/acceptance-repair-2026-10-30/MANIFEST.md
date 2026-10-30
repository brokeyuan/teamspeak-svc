# MANIFEST — 验收修复候选（4a56e70 / f2546d7）

日期：2026-10-30。状态：**候选已交付，等待用户客户端验收**。
本轮：G0–G7（任务书 `docs/superpowers/plans/2026-10-30-known-defects-and-acceptance-repair.md`）。
上一轮候选（`80dd40f`，SHA `8585708a…`）保留未覆盖。

## 候选产物

| 项 | 值 |
|---|---|
| 功能源码终点 | `e0be650`（G6 收口） |
| 本轮最终提交 | `4a56e70`（e0be650 之后仅 review harness 修正，源码树相同） |
| 打包提交 | `f2546d7`（仅追加 JAR 本体） |
| JAR 路径 | `dist/client-acceptance/4a56e70/teamspeak-svc-0.9.8+26.3.jar`（客户端/服务端同一文件） |
| SHA-256 | `7DDE538B8A0EFA707AA326363211A4B91356D7BF891BBB2D31B55829396D1DE9` |
| 大小 | 39,421,864 字节（37.6 MiB） |
| 构建 | 干净副本 `gradlew clean build --no-daemon`（JDK 25），exit 0 |
| 工程门 | `review/RunAll.ps1` → CI-EQUIVALENT PASS（core test + 26 harness，exit 0；`evidence/g7-runall-final.log`） |

## 实际依赖与版本（已验证组合）

| 组件 | 版本 | 验证 |
|---|---|---|
| Minecraft | 26.3 | fabric.mod.json depends + 隔离 dedicated 实启 |
| Fabric Loader | 0.20.1 | 隔离 dedicated 实启 |
| Fabric API | 0.165.0+26.3 | 隔离 dedicated 实启 |
| Simple Voice Chat | 2.6.25+26.3（支持区间 `[2.6.20, 2.7.0)`；区间外拒绝） | 隔离 dedicated + 真实 Opus 回归 |
| Java | 25（构建 JDK 25.0.4.1；运行 `java >= 25`） | gradle/隔离实启 |
| 内嵌依赖 | ts3voice-core 0.1.0、webrtc-java 0.14.0（含平台原生）、JNA 5.17.0、BC 1.78.1 | build.gradle include + JAR 内容检查 |

**不装旧 Bridge**：`teamspeak&svcbridge`（tsbridge）与 `voice-capabilities-fabric`
均不是安装条件；隔离矩阵已验证不含它们。

## 安装

### 客户端
1. Minecraft 26.3 + Fabric Loader 0.20.1。
2. `mods/`：fabric-api-0.165.0+26.3.jar、voicechat-fabric-2.6.25+26.3.jar、本 JAR。
3. 首次启动生成 `config/teamspeak-svc/`（`identity.txt` 玩家身份、`identity-music.txt`
   音乐 Bot 独立身份均自动生成；**不要外发，含私钥**）。
4. **若测试实例装过上一候选（80dd40f），先删除旧同 ID JAR 再放本候选**。

### 服务端（四模式）
| 模式 | mods/ | 配置 |
|---|---|---|
| A：TS/UDP | 无需本扩展 | — |
| B：TS/MC 通道 | fabric-api + 本 JAR | `config/teamspeak-svc/server.json`: `relay.enabled=true`、`relay.targetIds.<id>={host,port}` |
| B+音乐 Bot 第二会话 | 同上 | 另加 `relay.musicBotEnabled=true` |
| C：SVC/UDP | fabric-api + SVC（本 JAR 可不装） | — |
| D：SVC/MC 通道 | fabric-api + SVC + 本 JAR | `relay.svcMcChannelEnabled=true` |

`relay.musicBotEnabled` 默认 false；开放后仍受 targetId allowlist、每连接/
全服会话额度、速率/字节限额约束；PLAYER_VOICE 与 MUSIC_BOT 各最多一个会话。

## 已支持 / 不支持 / 未验证

**已支持（自动化回归 + 隔离 dedicated 证据，见 DELIVERY §1）**：
四模式部署基础、TS 中继（媒体屏障/控制隔离/代际/限额/异步建立）、单后端收听、
PAUSED/OFF 语义、音乐 Bot 独立会话与可撤销房间许可、真实 Opus 编解码、游戏内
音乐页与命令、本机收听音量/静音。

**不支持（验收标 N/A）**：远程点歌/跨服控制 Bot（可信 sender 元数据链未做）、
在线曲库/搜索、MP3/OGG、SVC↔TS 媒体桥接（P7 未批准）、网页/手机、双后端同时
收听、svc-extra 任意版本双装（仅 2.6.25 有证据）。

**待用户验证（本轮自动化无法覆盖）**：真实 TS 服通话/听感、实体设备、
MC Bot 中继模式的端到端（Netty+实服）、30 分钟稳定性、Extra 双装行为。

## 回滚
删除 mods 内本 JAR 即恢复原版 SVC；`config/teamspeak-svc/` 可保留。
服务端 relay/music 开关默认关闭，故障时可独立停用对应可选能力。
