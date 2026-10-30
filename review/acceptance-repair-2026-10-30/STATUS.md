# STATUS.md — 当前状态（2026-10-30）

基线 / 当前提交：
`bdea14e`（PR #1 合并）→ 验收修复轮 G0–G7：`c58d067`(G0 基线) → `2edd110`(G1
P1-03) → `3adc6c9`(G2 P1-02) → `c57c3de`(G3 P1-01/P2-04) → `186efff`(G4 F2 异步
与有界调度) → `6d06525`(G5 Bot 第二会话) → `e0be650`(G6 音乐入口+个人音量) →
`4a56e70`(harness 修正) → `f2546d7`(候选 JAR 打包) → `04db889`(候选交付文档；
该提交夹带回退五个 p0 共享替身，引入 CI 确定缺陷)。

CI 修复轮（PR #2，分支 `codex/acceptance-repair-20261030`，均只动 review/
测试基础设施，不触生产源码，功能终点仍为 e0be650）：
- `0420a8f` 恢复被 04db889 回退的共享替身 canSend 契约；RunMicRedirector
  自生成 ClientPlayNetworking（按 type 白名单、默认拒绝），套件不再依赖
  其他套件残留文件。
- `1a6b4f7` RunAll 注释去除残留硬编码计数（计数已是 $suites.Count 动态）。
- `fa05e7c` RelayAsyncOpenTest G4a 完整 RunAll 下读到 worker finally 清理前
  中间态的时序竞争（带期限等待，生产语义不变）。
- `0df498b` RunServerChecks 两处干净 checkout 缺陷：gson 改用入库
  review/lib/gson.jar（缺失时按 GRADLE_USER_HOME→USERPROFILE 回退）；
  套件内自建 core classes，不再依赖外部 gradle 产物。
- `ac0fccb` RunM7 产物哈希改用 .NET SHA256 API，摆脱 CI 上被内嵌 gradlew
  重置 PSModulePath 后不可自动加载的 Get-FileHash。

当前阶段 / 当前任务：
**候选已交付；CI 修复轮完整 CI 全绿（push run 41029108863 与 PR run
41029118278，全部步骤 success）；等待用户客户端验收。**
候选：`dist/client-acceptance/4a56e70/teamspeak-svc-0.9.8+26.3.jar`
SHA-256 `7DDE538B8A0EFA707AA326363211A4B91356D7BF891BBB2D31B55829396D1DE9`
（39,421,864 字节；功能终点 e0be650；干净副本 gradlew clean build exit 0；
RunAll core+26 harness 全 PASS exit 0；隔离 dedicated 无SVC/有SVC 双矩阵
启动/停止 exit 0，不含旧 Bridge；CI 修复轮构建与候选 JAR 哈希逐字节一致）。
验收材料：`review/acceptance-repair-2026-10-30/{CLIENT-ACCEPTANCE,RESULTS,
MANIFEST,DELIVERY}.md`。冒烟顺序 A00/A01/A02/A05。

本轮修复（publish-audit-2026-10-30 全部 P1/P2 闭环 + F2/F5 缺口）：
- P1-03：麦克风 harness canSend 契约 + 4 生产分支用例（G1）。
- P1-02：TsDatagramKind 包头分类；barrier 只约束媒体；VOICE_WHISPER 归媒体；
  控制与媒体统一计费（G2）。
- P1-01/P2-04：MusicSendPermit（四相+单调代际+可撤销）+ MusicBotAssembly 生产
  装配；attach≠握手成功；频道确认绑定授权房间；移出即撤销+停播（G3）。
- F2 缺口：OpenEstablisher（OPEN 四段拆分，有界 4 worker/64 待办，重复拒、
  迟到 discard、断连 revoke）；MusicBotManager.start 异步化（G4）。
- F5 缺口：客户端 MUSIC_BOT 会话接线（purpose 参数化 OPEN/关联，Bot 分发不触
  玩家全局；UDP/MC 显式模式）（G5）；MusicScreen 游戏内音乐页 + ConfigScreen
  入口 + 本机收听音量/静音实际生效（G6）。
- 历史 F0–F7 已修复项（Mixin 包名、raw/envelope 桥、requestId 关联、MusicEngine
  序列背压等）保留原回归，未重做。

工程门（本轮干净副本实测 + 远端 CI）：
- core `clean test`：145 检查 PASS。
- `RunAll.ps1`：CI-EQUIVALENT PASS（core + 26 harness，exit 0；ac0fccb 干净
  副本复跑一致；失败注入曾正确非零退出）。
- mod `clean build`：exit 0 → 候选 JAR（哈希见上）。
- RunM7：Mixin FQN/元数据/禁止内容 PASS（哈希 7DDE538B…）。
- 隔离 dedicated（Fabric API + 本 JAR，无旧 Bridge）：无 SVC / 有 SVC（+
  voicechat 2.6.25）两矩阵 Done→Stopping exit 0，0 mixin error。
- 远端 CI：ac0fccb push run 41029108863 与 PR run 41029118278 均 success，
  全部 26 步 success（含 mic redirect、Server regressions、RunAll），无
  skipped 冒充通过。
- 证据：`review/acceptance-repair-2026-10-30/evidence/`（基线探针、逐套件日志、
  隔离矩阵、RunAll 最终门）。

待用户验证（客户端暂缓矩阵，未伪报）：
真实 TS 服通话/听感、实体设备、Bot MC 中继端到端（Netty+实服）、30 分钟稳定、
Extra 双装。

仍有缺口（下一轮工程项，不阻塞验收）：
- MC 提交侧无独立有界 drain 队列（服务端限额已生效；客户端在途帧上界由
  Fabric 内部管理）。
- 远程点歌/跨服控制的可信 sender 元数据链未实现（明确不支持，非暂缓）。

结束规则（任务书 G8）：
**候选已交付，等待用户客户端验收；停止功能扩展。** 用户验收反馈后先修验收
缺陷，再讨论后续路线。Music-2/3、在线曲库、远程点歌、媒体桥接、WebRTC 均不
自动进入。
