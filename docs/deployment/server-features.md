# teamspeak-svc 服务端功能说明（部署）

最后更新：2026-10-29（M7 统一发布）。候选 JAR：`teamspeak-svc-0.9.8+26.3.jar`
（SHA-256 `2E8BFE430DE304138E240973455CDE084AB45363D76F3C178A536D2CF64ACE73`）。

## 安装矩阵（唯一发布包，两端同哈希）

| 环境 | 需要安装 | 效果 |
|---|---|---|
| 玩家客户端 | Simple Voice Chat + teamspeak-svc | 完整 TS/SVC 体验：书签、频道页、独立 TS 音频、三操作、身份/头像 |
| 玩家客户端（缺 SVC） | — | 本扩展日志输出依赖原因并停用；原版游戏不受影响 |
| MC 服务端（无 SVC） | teamspeak-svc（可选） | 能力公告（管理员配置后）；客户端可直连 TS |
| MC 服务端（SVC） | teamspeak-svc（可选） | 原生 SVC 正常；本扩展门面只占用稳定插件 ID，不注册客户端事件 |
| MC 服务端（无本扩展） | — | 客户端仍可手动书签直连 TS（无公告） |
| 单人/集成服务器 | — | 服务端增强默认关闭（`integratedServerEnabled` 显式开启除外） |

**不要**同时安装旧 `voice-capabilities`（payload 冲突，元数据已声明 `breaks`）。

## 服务端配置（config/teamspeak-svc/server.json）

```json
{
  "schemaVersion": 1,
  "integratedServerEnabled": false,
  "capabilities": {
    "enabled": true,
    "serviceInstanceId": "<首次生成并持久保存>",
    "ttlSeconds": 30,
    "targets": [
      { "kind": "ts_endpoint", "host": "实际TS主机", "port": 9987 }
    ]
  }
}
```

- 全新安装默认关闭、目标为空；管理员配置真实 TS endpoint 后开启。
- 仅支持 `kind: "ts_endpoint"`；`ts_relay` / `svc_tcp` / `music` / `media_bridge`
  **本版本未实现** —— 配置它们会得到"该版本未实现"诊断，不会产生虚假公告。
- 上限沿用 wire：单帧 16 KiB、候选 8 条、TTL ≤ 60 秒、host ≤ 253、端口 1–65535。
- 管理：`/tsvoice-server status`（状态/原因/目标数/配置路径）、
  `/tsvoice-server reload`（先验证后提交；失败自动停公告并保留文件）。
- 状态机：`DISABLED / INVALID_CONFIG / ACTIVE / FAILED`——只有 ACTIVE 回复公告。

## 迁移（旧 voice-capabilities）

仅当 `server.json` 不存在时迁移旧 `config/voice-capabilities.json`：
备份为 `voice-capabilities.json.migrated-backup`，保留真实 endpoint/TTL；
`serviceInstanceId` 换成新持久 ID（客户端可能需要重新确认授权）；精确匹配
`voice.example.org:9987` 的未配置模板保持关闭。损坏输入不覆盖、公告停止并显示
配置错误；客户端直连与其他独立功能不受影响。

## 后续接入点（仅记录边界，均未实现）

- **TS_MC_CHANNEL（M9/P6 TS over MC TCP）**：payload 注册在
  `com.tsvoice.TeamspeakSvcMod`（common）；per-server 生命周期在
  `com.tsvoice.server.ServerRuntime`；per-MC-connection 所有权沿用 hello 限流的
  连接身份方案；配置来源为 `server.json` 的未来 targets kind（当前产生
  "未实现"诊断，不产生公告）。
- **SVC_MC_CHANNEL（M8/P5）与 Music（Music-0–3）**：同样以 ServerRuntime 为
  宿主挂载点；未冻结 wire 前不新增任何 capability 常量。

不安装本扩展的 MC 服务器不需要任何操作；客户端手动 TS 直连始终可用。
