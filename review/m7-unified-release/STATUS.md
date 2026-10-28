# M7 统一发布执行状态（T14.1–T14.8）

日期：2026-10-29。基线：N7 终点 `2f0240c`。依据：
[M7 任务书](../../docs/superpowers/plans/2026-10-29-m7-unified-release-plan.md)（其
"上一轮全部验收通过"前提按 N7 工程门槛执行）。客户端真机验收：**用户要求暂缓**。

## 任务状态总表（实现 / 工程验证 / 客户端·真机验收）

| 任务 | 内容 | 实现状态 | 工程验证 | 真机验收 |
|---|---|---|---|---|
| T14.1 | 公共 payload 注册所有者 | ✅ `common/net/CapabilitiesPayloads`（同 ID/有界/注册一次性标志）；客户端接收器与服务端 hello 接收分离；主入口一次注册 | ✅ 生产 compileJava；wire/ID 不变（N6 golden 语义沿用）；重复注册防护（register() 由 main 唯一调用） | ❌ 真机双端互通未验证 |
| T14.2 | 中性元数据与依赖检查 | ✅ `fabric.mod.json` environment="*"、main=TeamspeakSvcMod、client=bootstrap.ClientEntrypoint；SVC 移出全局 depends→客户端明确校验（RuntimeDependencies，零类加载探测）；`breaks: voice-capabilities`；全部 Mixin 限定 client 环境 | ✅ RunM7 ArtifactChecks（environment/entries/depends/breaks/mixins 全部断言）；缺 SVC 客户端=明确日志停用（不触发目标类解析） | ❌ dedicated 真实启动未验证 |
| T14.3 | 环境安全 SVC 门面 | ✅ `compat/svc/VoicechatPluginEntrypoint`：客户端且依赖就绪→延迟委托 VoicechatPluginImpl（事件只注册一次）；dedicated→稳定插件 ID、零客户端事件 | ✅ 编译+门面逻辑审阅；物理环境判定经元数据 | ❌ dedicated±SVC 装载未验证 |
| T14.4 | 服务端配置与公告迁移 | ✅ `server/ServerConfig`（schema v1、模板检测、原子保存、legacy 备份迁移、未实现 kind 诊断）、`server/CapabilityAnnouncer`（nonce 回显、2/s 突发 4 限流、有界帧、无目标不回复） | ✅ ServerConfigTest F1–F8 + AnnouncerTest A1–A8（RunM7） | ❌ 真实公告往返未验证 |
| T14.5 | 生命周期与管理诊断 | ✅ `server/ServerRuntime`（每 MinecraftServer 实例、状态机 DISABLED/INVALID_CONFIG/ACTIVE/FAILED、幂等 close、先验证再提交的 reload、集成服务器默认门）、`server/ServerCommands`（/tsvoice-server status\|reload，26.3 权限模型） | ✅ AnnouncerTest A5–A8（状态现实性、失败重载停公告保文件、closed 不响应、集成门） | ❌ 单人 A→B 世界/dedicated 重启未验证 |
| T14.6 | 打包与冲突 | ✅ 单一 JAR；主入口无 SVC 引用；`breaks` 精确冲突声明；RunM7 内容检查（无 review/stub/身份材料） | ✅ ArtifactChecks 全过；构建产物见 DELIVERY | ❌ 旧公告共存实测未验证（breaks 元数据+客户端日志诊断） |
| T14.7 | 同一产物安装与功能验收 | — | ✅ **终点 HEAD 干净副本 RunAll 全绿**（RunM7 在干净检出自行构建产物后通过全部检查，[../engineering-closeout/evidence/m7-clean-copy-runall.log](../engineering-closeout/evidence/m7-clean-copy-runall.log)）；M7-E01–E12 真实安装矩阵**待真机**（用户暂缓；E04/E11 依赖真机音频） | ❌ 全部未验证 |
| T14.8 | 交付与接点 | ✅ DELIVERY.md、docs/deployment/server-features.md、continuous-development STATUS 更新 | ✅ | — |

## 关键实现决策（与任务书对应）

- 入口拆分：`com.tsvoice.TeamspeakSvcMod`（main，无 SVC/client 引用）→
  `bootstrap.ClientEntrypoint`（client，元数据检查后委托 `TeamspeakSvcClient.onInitializeClient()`，
  该方法改为 static 供委托）→ `compat.svc.VoicechatPluginEntrypoint`（voicechat 自定义入口门面）。
- 集成服务器（单人）由 SERVER_STARTING 统一创建 runtime 并默认 DISABLED
  （`integratedServerEnabled` 显式开启除外）——不设独立 server 入口例外。
- hello 连接身份 = UUID + ServerPlayer 实例哈希（重连区分限流）；DISCONNECT 清理。
- 26.3 权限模型：`PermissionSet`/`LevelBasedPermissionSet`（控制台=ALL，OP≥2=MODERATORS）。

## 未验证清单（不因实现完成而宣称通过）

1. 真实 dedicated server（无 SVC/有 SVC）与客户端的同 JAR 装载启动（M7-E01/E03）。
2. 真实 hello→公告→授权→客户端连接往返（M7-E02）。
3. 客户端缺 SVC 的实际游戏内表现（M7-E06；预期为日志停用而非崩溃）。
4. 单人世界 A→菜单→世界 B 的 runtime 重建（M7-E07；逻辑已由 A5–A8 覆盖）。
5. 旧 voice-capabilities 共装的 Loader 实际冲突表现（M7-E09；已用 breaks 声明）。
