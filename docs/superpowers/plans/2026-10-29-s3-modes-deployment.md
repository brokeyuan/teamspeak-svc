# 四模式部署与诊断（S3）

> 状态说明：本表描述工程层已验证的行为与配置面；真实客户端验收（用户主动暂缓）
> 与真实 TS 服务互操作不在本表"已验证"范围。MC 通道两模式的端到端（真实 netty
> 载荷 + 真实 TS 服务器）见 STATUS.md 的暂缓记录。

## 模式一览

| 模式 | transport 配置 | 客户端条件 | 服务端条件 | 麦克风目标 | 默认 |
|---|---|---|---|---|---|
| TS 直连 UDP | `TS_UDP`（旧 `udp`） | TS 服务器可达（客户端解析 DNS） | 无要求 | TS | 是（历史默认） |
| TS 隧道 TCP | `TS_TUNNEL_V1`（旧 `tcp=true` 只迁移到这里） | 同上 + 隧道端点可达 | 旧 tsbridge 隧道（如部署） | TS | 否 |
| TS MC 通道 | `TS_MC_CHANNEL` | 本扩展统一 JAR；服务器注册 relay 通道 | 服务端装 teamspeak-svc，`relay.enabled=true` + allowlist 目标；客户端**不做** TS DNS | TS | 否（默认关） |
| SVC 原生 | （SVC 自身 UDP） | 装 SVC | 原生 SVC 服务端 | SVC | 未选 TS 时 |
| SVC MC 通道 | （服务端宣告 `MC_CHANNEL`） | 装 SVC + 本扩展；`svc-extra` 在场时让渡 | SVC 服务端 + 本扩展 `relay.svcMcChannelEnabled=true`；需要 SVC 在场 | SVC | 否（默认关） |

选择优先级（AUTO 语义）：显式书签 > 本服信标 > 本服手动 > 已授权公告 > 全局自动；
`PAUSED/OFF/PINNED_*` 用户意图不被任何自动来源覆盖。

## 配置迁移规则（向后兼容）

- 书签 `transportPolicy`：`null`/未知 → `AUTO`；`"udp"` → `TS_UDP`；
  `"tcp"`/`"tunnel"` → `TS_TUNNEL_V1`（**绝不**自动升级为 MC 通道）；
  `"mc_channel"`/`"mc-channel"`/`"relay"` → `TS_MC_CHANNEL`（显式选择）。
- 旧 `servers.json` 的 `transport`（`udp`/`tcp`）按同一映射；`AUTO` 在信标场景
  按"隧道可用性"回落旧 `wantTcp` 行为（不改变既有默认路径）。
- 服务端 `server.json`：`relay` 节缺省 = 全部关闭（TS relay 与 SVC MC 通道都
  关）；旧行为（公告）不受影响。

## 服务端配置参考（server.json）

```jsonc
{
  "schemaVersion": 1,
  "integratedServerEnabled": false,          // 集成服务器默认关（S0.3/R22 门）
  "capabilities": { "enabled": false, "targets": [ ... ] },  // M7 公告（不变）
  "relay": {
    "enabled": false,                         // TS_MC_CHANNEL 服务端总开关
    "targetIds": { "ts-main": { "host": "ts.example.org", "port": 9987 } },
    "maxDatagramBytes": 4096,                 // ≤4096（wire 硬上限）
    "maxDatagramsPerSecond": 256,
    "maxBytesPerSecond": 524288,
    "maxSessions": 64,                        // ≤64
    "svcMcChannelEnabled": false              // SVC_MC_CHANNEL 服务端适配
  }
}
```

管理员诊断：

- `/tsvoice-server status`：公告状态 + `relay=on targets=N sessions=M`（或 `off`）。
- 客户端 `/tsvoice status`：状态机、失败类别（认证/密码/权限不当作 UDP 不通）、
  收发帧计数；`/tsvoice trust-announce` 打开公告授权界面。

## 错误类别（S0.7/R26 语义）

| FailureKind | 触发 | 行为 |
|---|---|---|
| `NETWORK_TIMEOUT` | socket 层超时/拒绝/IO | 可重试（退避 jitter） |
| `DNS_FAILED` | UnknownHost | 可重试 |
| `AUTH_DENIED` / `CHANNEL_PASSWORD` / `PERMISSION_DENIED` / `BANNED` / `SERVER_FULL` / `UNSUPPORTED_PROTOCOL` | 服务器结构化拒绝或消息匹配 | 终止重试等待用户 |
| `PROTOCOL_REJECTED` | 服务器明确拒绝（未收录 token） | 终止重试 |
| `INTERNAL` | 内部错误/未知异常 | 可重试但**不当** UDP 不通（不触发跨网切换） |
| `FRONTEND_UNAVAILABLE` | SVC hook 自检失败 | 终止（原版 SVC 不受影响） |
| `CANCELLED` | 用户/会话取消 | 不重试 |

MC 通道特有错误（稳定码）：目标不在 allowlist（`ERR_UNKNOWN_TARGET/3`）、会话/
全局上限（`ERR_LIMIT_SESSIONS/6`）、OPEN 限流（`ERR_LIMIT_OPEN_RATE/5`）、目标
不可达（`ERR_TARGET_UNAVAILABLE/9`）。客户端在服务器未注册中继通道时显式失败
（"this server does not support the TS relay channel"），不静默回退 UDP。

## 资源边界（工程保护初值，服务端配置可调小）

TS 中继：单数据报 ≤4096 B；外层帧 ≤16 KiB；每连接 1 个 PLAYER_VOICE 会话；
全服 ≤64；OPEN 2/s burst 4；每会话 256 datagrams/s 与 512 KiB/s（全部流量计费）；
空闲 120s 回收；在途 OPEN 5s 超时（迟到 OPENED 不复活）。
SVC MC 通道：voice 外层 ≤8 KiB；客户端接收队列 1024 帧（满丢最旧计数）；
会话地址唯一（单调计数器），断连回收，重连新地址。
