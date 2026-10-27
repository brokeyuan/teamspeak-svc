# SVC ↔ TeamSpeak Bridge (v2.1)

一个 Fabric 服务端 mod，将 [Simple Voice Chat (SVC)](https://modrepo.de/minecraft/voicechat/overview) 的语音流双向桥接到 TeamSpeak 3 频道，实现 MC 玩家与 TS 用户之间的跨平台语音互通。

当前构建目标：**Minecraft 26.3 / Fabric Loader 0.20.1 / Java 25**（与 SVC 上游一致）。

## 当前状态（务必阅读）

| 功能 | 状态 |
|---|---|
| ServerQuery 管理（频道创建 / 文本消息 / 配对） | ✅ 可用 |
| SVC 永久桥接群组（"TeamSpeak互联"，跨重启持久） | ✅ 可用 |
| Ghost Player（TS 用户在 MC 侧显示为 `[TS] 昵称` 虚拟玩家） | ✅ 可用（v0.2.0 修复事件注册后实时生效） |
| 存在感同步（Bot 昵称 `MCBridge (N online)` + 频道描述，仅统计桥接群组成员） | ✅ 可用 |
| `/tsbridge` 命令树 + 权限 | ✅ 可用 |
| ServerQuery 断线重连后自动恢复全部组件 | ✅ 可用 |
| **音频（MC↔TS 语音流）** | ✅ 原生 TS3 语音客户端（对真实 TS3 服务器验证通过） |

### v0.3 真 1:1 模式

- **每个 MC 玩家连入 SVC 时，自动获得一个专属 TS 语音客户端**（昵称 = 玩家名，
  身份持久化在 `config/tsbridge/identities/<uuid>.txt`，跨重启同一 TS uid），
  自动加入桥接频道 —— TS 用户在频道里看到的就是玩家本人的独立客户端
  （可单独调音量/静音）；玩家离开 SVC 自动断开
- **MC→TS 零混音直通**：玩家的 Opus 帧直接经其专属客户端发送（SVC 帧即 TS 兼容的
  20ms Opus，无编解码开销）；TS→MC 依旧每个 TS 用户一个 Ghost（1:1）
- **回声/幽灵防护**：玩家镜像客户端的声音不会被 bot 注回 MC，也不会被误建
  `[TS]` Ghost 或误消费配对码
- 接收与存在感锚点仍由单 bot `MCBridge (N online)` 承担；`perPlayerClients: false`
  可回退为旧的单 bot 混音模式
- `/tsbridge status` 新增 `Mode` 与 `Per-player clients` 行

### v0.2.0 修复摘要

- Ghost Player 事件驱动失效：ServerQuery 现在注册 `event=server`，TS 用户加入/离开/移动即时生效
- `nativeVoiceEnabled` 配置项此前从未被读取，现在真正生效
- MC→TS 仅转发桥接群组成员的语音（不再把其他私人群组的内容广播到 TS）
- 语音客户端由单线程连接循环管理：首次启动失败持续重试、被踢/掉线自动重连、
  不再阻塞服务器主线程、不再产生孤儿连接
- TS 用户帧长可配置（10-100ms）不再导致 MC 侧无声，解码后按 960 采样重分块
- 混音器空闲 5 分钟回收玩家队列与原生 Opus 解码器（修复长期运行内存泄漏）
- 存在感昵称同步到原生语音 bot（TS 频道内真正可见的那个客户端）
- TS3 keyNonce 缓存索引与 TSLib 对齐 + 线程安全化；classic 握手 beta=54 长度守卫

### 音频传输说明（v2.2：已实现原生语音客户端）

- **Ts3NativeTransport（原生 TS3 语音客户端）**：完整移植 TS3AudioBot/TSLib 的客户端
  协议（`com.svcb.tsbridge.ts3voice` 包，纯 Java）：init1 握手（含 RSA 谜题）→
  license 加密协商（Ed25519 密钥派生 + ECDSA 证明 + AES-EAX 会话密钥）→ clientinit →
  语音收发。已对真实 TS3 服务器实测：握手/上线/命令通道/心跳/语音帧发送全部通过。
  身份（P-256 + PoW）持久化在 `config/tsbridge/identity.txt`，跨重启保持同一 TS uid。
- **Ts3AudioBotTransport（回退方案）**：仍不可用（官方 Web API 无音频流端点），仅兼容
  魔改 bot。原生传输默认开启，无需 TS3AudioBot。

> 验证方式：`java -cp "build/classes/java/main;<bcprov.jar>;<slf4j-api.jar>" >   com.svcb.tsbridge.ts3voice.Ts3VoiceSelfTest <host> 9987 <频道名> 8`

## 工作原理

```
┌────────────────────────────────────────────────────────────────────────┐
│                          TsBridge Mod (Fabric)                          │
│                                                                        │
│  ┌──────────────────────┐  SVC 群组/状态包   ┌────────────────────────┐ │
│  │  MC 玩家 (SVC)       │ ←──────────────── │  永久桥接群组           │ │
│  │  麦克风/扬声器        │  GhostPlayer注入   │  "TeamSpeak互联"       │ │
│  └────────┬─────────────┘                   └──────────┬─────────────┘ │
│           │                                            │               │
│  ┌────────▼─────────────┐                   ┌──────────▼───────────┐   │
│  │  McAudioMixer        │                   │  McAudioInjector     │   │
│  │  (多人混音→TS)        │                   │  (每 TS 用户一条通道) │   │
│  └────────┬─────────────┘                   └──────────▲───────────┘   │
│           │                                            │               │
│  ┌────────▼──────────────────────────────────────────┴─────────────┐ │
│  │  TsAudioTransport：Ts3NativeTransport（实验） ↓ Ts3AudioBot（弃用）│ │
│  └────────┬────────────────────────────────────────────────────────┘ │
│           │                                                          │
│  ┌────────▼────────────────────────────────────────────────────────┐ │
│  │  TsQueryClient (ServerQuery TCP 10011，断线自动重连+组件恢复)      │ │
│  │  - BridgeChannelManager（频道管理）   - PairingManager（配对）     │ │
│  │  - TsPresenceUpdater（MC→TS 同步）    - TsGhostPresenceListener   │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

- **永久群组**：通过 SVC 公开 API `groupBuilder()` 创建持久群组（固定 UUID），重启后复用；
  TS→MC 音频通道的 targets 即群组成员
- **MC → TS**：`McAudioMixer` 捕获多个 MC 玩家的 Opus 麦克风帧，单人时直接透传
  （零编解码），多人时解码 → 饱和加法混音 → 重编码，交给 transport 发送
- **TS → MC**：每个 TS 用户一条独立 `StaticAudioChannel`（channelId = Ghost UUID），
  targets 为桥接群组成员，语音包发送者即该 Ghost —— MC 客户端自动显示说话高亮
- **Ghost Player**：反射构造 SVC 真实网络包（`net.PlayerStatePacket` +
  `RemovePlayerStatePacket`，挂桥接群组）广播虚拟玩家；新玩家接入时补发状态；
  每 30 秒与 TS 频道成员对账自愈
- **身份配对**：`/tsbridge pair` 生成配对码 → TS 侧 `!pair <code>` 绑定；
  或开启 `autoPairing` 后 TS 用户进入桥接频道即自动绑定（配对码实时显示在频道描述）
- **可选身份声明**：安装 teamspeak-svc 的玩家可自愿完成 TS 身份挑战，服务端签发
  MC↔TS 映射声明。没有声明、声明过期/撤销的 TS 用户仍可正常语音，身份验证不是通信门槛

## 依赖

### 必需

- Minecraft 26.3
- Fabric Loader >= 0.19.3
- Fabric API
- [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)（服务端 + 客户端）
- [fabric-permissions-api-v0](https://modrinth.com/mod/fabric-permissions-api)（已 jar-in-jar 打包）

### 外部服务

- **TeamSpeak 3 Server**：需开启 ServerQuery（TCP 10011）

## 部署步骤

### 1. 构建

```powershell
cd "teamspeak&svcbridge"
.\gradlew.bat build
```

产物位于 `build/libs/tsbridge-0.3.4+26.3.jar`。注意：`gradle.properties` 中
`java_version=25`，构建机需安装 JDK 25（与 SVC 上游一致）。

### 2. 安装

将 jar 放入 Fabric 服务端 `mods/` 目录，确保 `mods/` 已有：
- `fabric-api-*.jar`
- `voicechat-*.jar`

### 3. 配置

首次启动服务端会生成 `config/tsbridge.json`，编辑后用 `/tsbridge reload` 热重载：

```json
{
  "tsHost": "127.0.0.1",
  "tsQueryPort": 10011,
  "tsServerPort": 9987,
  "tsVoicePort": 9987,
  "queryUsername": "serveradmin",
  "queryPassword": "你的ServerQuery密码",
  "botApiUrl": "",
  "botApiToken": "",
  "bridgeChannelName": "MC Bridge",
  "bridgeGroupName": "TeamSpeak互联",
  "pairCodeLength": 6,
  "pairCodeExpiryMinutes": 5,
  "autoPairing": false,
  "sendSilenceFrames": false,
  "nativeVoiceEnabled": true,
  "tcpTunnelPort": 0,
  "tcpTunnelBindAddress": "127.0.0.1",
  "debug": false
}
```

| 字段 | 说明 |
|---|---|
| `tsHost` | TeamSpeak 3 服务器地址 |
| `tsQueryPort` | ServerQuery 端口（默认 10011） |
| `tsServerPort` | 虚拟服务器端口（`selectVirtualServerByPort` 用） |
| `tsVoicePort` | TS3 语音 UDP 端口（原生传输用，默认 9987） |
| `queryUsername` / `queryPassword` | ServerQuery 登录凭证 |
| `botApiUrl` / `botApiToken` | **已弃用**：TS3AudioBot 回退地址（见上方诚实说明） |
| `bridgeChannelName` | TS 侧桥接频道名 |
| `bridgeGroupName` | SVC 侧永久桥接群组名（≤24 字符，无特殊字符） |
| `pairCodeLength` / `pairCodeExpiryMinutes` | 配对码长度与过期时间（分钟） |
| `autoPairing` | 自动配对：TS 用户进入桥接频道即绑定最早生成的配对码 |
| `sendSilenceFrames` | 无人说话时向 TS 发送静音帧 |
| `nativeVoiceEnabled` | 原生 TS3 语音传输开关（默认 true） |
| `tcpTunnelPort` | UDP-over-TCP 隧道端口；`0` 关闭。启用时必须设置 `TBRIDGE_TUNNEL_SECRET`（≥32 字符） |
| `tcpTunnelBindAddress` | 隧道监听地址，默认 `127.0.0.1`；公网开放需显式修改并同时配置密钥 |
| `debug` | 调试日志开关 |

> **TCP 隧道安全**：未配置 `TBRIDGE_TUNNEL_SECRET` 时隧道不会启动。客户端需在同一环境变量中配置
> 相同密钥；握手使用一次性短期票据，未认证、过期和重放票据不会转发。公网开放请谨慎设置绑定地址。
>
> **可选身份声明**：服务端自动生成 `config/tsbridge/identity-signing-keypair.txt`。声明只用于
> 可信玩家映射展示，不是语音通信、频道连接或配对的前置条件。

### 4. 启动与配对

1. 启动 MC 服务端，查看日志确认 `[tsbridge]` 各组件启动成功
2. MC 玩家加入 SVC 群组 "TeamSpeak互联"（群组列表中选择）
3. MC 玩家执行 `/tsbridge pair` 获取配对码
4. TS 用户进入桥接频道，发文本 `!pair <code>` 完成绑定
   （或开启 `autoPairing` 后进入频道即自动绑定）
5. Ghost Player 出现在 MC 侧群组中；TS 频道描述实时显示 MC 在线玩家

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/tsbridge pair` | 任何玩家 | 生成配对码（可配置有效期） |
| `/tsbridge unpair` | 任何玩家 | 解除自身配对 |
| `/tsbridge status` | 任何玩家 | 查看桥接器状态（ServerQuery / transport / 群组 / Ghost 等） |
| `/tsbridge reload` | `tsbridge.admin` | 热重载 `config/tsbridge.json` |
| `/tsbridge link <tsChannel>` | `tsbridge.admin` | 更新桥接频道名并立即生效 |

## 架构组件

| 组件 | 职责 |
|---|---|
| `TsBridgeMod` | 主入口 + 组件编排器（首次启动 / ServerQuery 重连恢复共用同一幂等入口） |
| `TsVoicechatPlugin` | SVC 插件入口，注册音频事件监听 |
| `TsQueryClient` | ServerQuery TCP 客户端（断线重连 + 重连恢复回调） |
| `BridgeChannelManager` | TS 侧桥接频道创建与管理 |
| `TsGhostPresenceListener` | TS 用户 join/moved/leave → Ghost 增删（clid 缓存 + 定期对账） |
| `GhostPlayerInjector` | 反射广播 SVC 玩家状态包（挂桥接群组） |
| `McAudioInjector` | 每个 TS 用户一条 StaticAudioChannel + AudioPlayer（空闲自结束） |
| `McAudioMixer` | MC→TS 混音（绝对时钟节拍，单人 Opus 透传） |
| `McToTsForwarder` | MC→TS 单路转发（mixer 不可用时的回退路径） |
| `Ts3NativeTransport` | 原生 TS3 语音客户端（实验性，默认关闭） |
| `Ts3AudioBotTransport` | TS3AudioBot 回退（已弃用，官方 API 无对应端点） |
| `TsPresenceUpdater` | MC 在线玩家同步到 TS 昵称/频道描述 |
| `PairingManager` | MC↔TS 手动/自动配对 |
| `BridgeConfig` / `PairingStore` | 配置与配对数据持久化 |

## 故障排查

- **`ServerQuery password is empty`**：`queryPassword` 未配置
- **ServerQuery 反复重连**：检查地址/端口/凭证；重连成功后组件会自动恢复，无需重启服务器
- **TS 用户加入频道但 MC 看不到 Ghost**：确认 `bridgeChannelName` 与 TS 频道名一致；
  `/tsbridge status` 查看 Ghost Players；对账任务每 30 秒自愈一次
- **MC 玩家听不到 TS 用户**：玩家必须加入 SVC 群组 "TeamSpeak互联"（targets 按群组成员过滤），
  且需要可用的音频 transport（见上方诚实说明）
- **TS 用户听不到 MC 玩家**：需要可用的音频 transport

## 技术说明

- **混音算法**：饱和加法，超出 16-bit PCM 范围时钳位；单人时透传原始 Opus 零编解码
- **AudioPlayer 生命周期**：SVC 的 `AudioPlayer` 把 supplier 返回 null 视为流结束，
  本 mod 的 supplier 阻塞取帧 500ms，超时自然结束播放器（flush 通道、客户端停止
  说话高亮），下一帧到来时重建，队列跨播放器生命周期保留
- **Ghost Player**：反射目标为 SVC 真实内部类（`voice.common.PlayerState`、
  `net.PlayerStatePacket`、`net.RemovePlayerStatePacket`、`net.NetManager.sendToClient`），
  反射失败时整体禁用 Ghost 功能并记录一次错误，不影响其他组件
- **降级设计**：每个组件启动/关闭独立隔离，任一失败不影响其他组件；
  transport 启动使用 `catch (Throwable)` 防止 Error 击穿启动链

## 安全与信任（v0.3.4）

- **TCP 隧道认证**：启用 	cpTunnelPort 必须设置环境变量 `TBRIDGE_TUNNEL_SECRET`（≥32 字符）。
  客户端用该秘密换取一次性、短期 HMAC 票据；重放票据和无效票据无法转发任何数据。
  隧道默认只绑定 `127.0.0.1`；公网监听必须把 `tcpTunnelBindAddress` 显式改为 `0.0.0.0` 等地址。
- **信标凭据隔离**：信标（Beacon）只下发 endpoint，不继承已保存密码；客户端首次使用或 endpoint
  变化时需要明确授权。TS 服务器密码按虚拟服务器隔离保存。
- **可选身份声明**：安装 teamspeak-svc 的玩家可自愿完成 TS 身份挑战，服务端用独立生成的
  EC P-256 密钥（`config/tsbridge/identity-signing-keypair.txt`，首次启动自动生成，原子私有写入）
  签发 24 小时有效的 MC↔TS 映射声明；玩家断开时广播撤销。
  没有声明、声明过期/撤销/伪造的 TS 用户只是降级为普通 TS Ghost，语音和文字完全不受影响；
  签名声明不授予任何群组或频道权限，也没有中心白名单——任何 MC 服务器都用自己生成的密钥。
- **日志脱敏**：ServerQuery 通信日志保持关闭，密码不会进入日志。
- **秘密管理**：本 mod 不会自动轮换任何秘密；轮换、发布由管理员自行执行。
