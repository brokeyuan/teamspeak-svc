# M7 统一发布交付报告（T14）

日期：2026-10-29。基线：N7 终点 `2f0240c` → M7 提交（见下）。

## 提交与验证

| 项 | 结果 |
|---|---|
| RunAll（23 组 harness + core，含 RunM7） | **PASS，进程退出码 0**（[../engineering-closeout/evidence/m7-runall.log](../engineering-closeout/evidence/m7-runall.log)） |
| `gradlew build`（teamspeak-svc，嵌套 core/依赖库） | BUILD SUCCESSFUL |
| M7 候选产物 | `teamspeak-svc/build/libs/teamspeak-svc-0.9.8+26.3.jar`（39,252,040 字节） |
| 候选 SHA-256 | `2E8BFE430DE304138E240973455CDE084AB45363D76F3C178A536D2CF64ACE73` |
| ArtifactChecks | 必需入口存在；无 review/stub/身份材料；environment="*"；三入口指向正确；SVC 已从全局 depends 移除；`breaks: voice-capabilities`；mixins 全部 client 限定 |

**M7 候选哈希取代 N7 报告中的 N7 构建哈希**（N7 记录保留为历史）；后续两端验证
统一使用本哈希候选 JAR。

## 安装说明（真实实现的能力）

- **客户端**：SVC + 本 JAR（保留完整 TS/SVC 体验）。缺 SVC 时客户端日志给出
  明确依赖原因并停用本扩展（不崩溃、不影响原版）。
- **服务端（仅公告）**：Fabric + 本 JAR；配置 `config/teamspeak-svc/server.json`
  （`capabilities.enabled=true` + `targets:[{"kind":"ts_endpoint","host":"…","port":9987}]`）；
  管理 `/tsvoice-server status|reload`。不需要 SVC，不需要旧 voice-capabilities。
- **服务端（SVC+本扩展）**：原生 SVC 正常；本扩展门面在服务端只占用稳定插件 ID，
  不注册任何客户端事件。
- **配置迁移**：仅当新文件不存在时迁移旧 `config/voice-capabilities.json`
  （备份 `.migrated-backup`；模板示例保持关闭；serviceInstanceId 换新 → 客户端
  可能需重新确认授权）。损坏文件不覆盖，公告报 INVALID_CONFIG，其余功能不受影响。
- **回滚**：revert M7 提交或用上一轮 JAR；服务端停用公告=配置 enabled=false 或
  移除本 JAR（客户端直连 TS 不受任何影响）。旧公告 mod 请移除（payload 冲突已用
  `breaks` 声明 + 客户端日志诊断）。

## 未验证（待真机，按用户要求暂缓）

M7-E01–E12 真实安装矩阵全部未验证（详见 STATUS.md 未验证清单）；M7 完成条件中
"真实 dedicated 启动、同 JAR 两端装载、真实公告往返"必须由用户恢复验收确认。
M9/P6（TS over MC TCP）、M8/P5（SVC-Extra 传输）与音乐功能不在本轮，接点记录在
[server-features.md](../../docs/deployment/server-features.md)。
