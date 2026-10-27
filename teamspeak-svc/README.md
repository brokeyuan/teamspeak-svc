# teamspeak-svc

**SVC 界面 + TeamSpeak 后端**：客户端 Fabric mod（MC 26.3 / Java 25）。保留 Simple Voice Chat (SVC) 作为游戏内语音前端（界面、按键、语音激活、群组、HUD），把语音后端整体替换为 TeamSpeak 3 —— 玩家的 MC 客户端直接以独立 TS3 语音客户端身份连入 TS，MC 服务器不再承载任何语音转发。

当前版本：**0.9.8**（配套 tsbridge ≥0.3.3；本 mod 仅在客户端安装）。

## 架构

```
每个玩家的 MC 客户端（装本 mod）
┌────────────────────────────────┐
│ SVC：界面 / 按键 / 语音激活 / 群组 │
│ 麦克风 → ClientSoundEvent 拦截    │──────┐
│ TS 声音 → SVC 播放管线（等响度）   │←─────┤
└────────────────────────────────┘      │
                              TeamSpeak 服务器（语音 UDP 9987，可用 TCP 隧道）
                                        │
                              TS 用户客户端（无感互通）
```

- **MC→TS**：SVC 产生原始 PCM → 本 mod 用 SVC 的 Opus 编码器编码为 20ms 帧 → 经玩家自己的 TS 客户端发出，并取消发往 MC 服务器的原始发送（`ClientSoundEvent.cancel()`）。
- **TS→MC**：TS 频道语音 → 以 SVC `SoundPacket`（编码 Opus + TS 语音序号）注入 SVC 的 `AudioChannel`，由 SVC 完成抖动缓冲、乱序重排、Opus FEC/PLC 丢包补偿与解码；TS 用户以本地 Ghost（`[TS] 昵称`）呈现在 SVC 界面并点亮说话指示。
- **群组映射**：加入 SVC 群组 → TS 客户端自动移动到 `<主频道>/<群组名>` 子频道（不存在则临时创建；带密码群组使用 tsbridge 下发的频道密码）；离开群组回主频道。
- 语音全走 TS：MC↔MC 与 MC↔TS 同源；MC 服务器的 SVC 后端不再转发音频。

## 部署

| 位置 | 需要安装 |
|---|---|
| MC 服务器 | **SVC（voicechat mod）**（仅作界面/会话锚点；可选 tsbridge 以启用信标免配置、群组密码、TCP 隧道） |
| 玩家客户端 | **SVC + fabric-api + 本 mod**（teamspeak-svc jar） |
| 外部 | 一台可访问的 TeamSpeak 3 服务器 |

> 本 mod 声明依赖 `voicechat >= 2.6.20`：未安装 SVC 时不会加载，避免 mixin 静默失效。

### 配置来源（优先级从高到低）

1. **本服信标**（服务器装 tsbridge 时下发，`useServerBeacon` 默认开）：首次或 endpoint 变化时必须由玩家确认；记住的 endpoint 才会自动采用，GUI 中灰置；`transport=tcp` 时使用隧道的 host/port。
2. **本服手动配置** `config/teamspeak-svc/servers.json`（按服务器地址键控）。
3. **全局配置** `config/teamspeak-svc.json`（兼容 v0.1 的单服写法）。

全局配置字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `tsHost` | `""` | TS 服务器地址（UDP 语音端口） |
| `tsVoicePort` | `9987` | TS 语音端口 |
| `channelName` | `"Minecraft"` | 主频道名（群组子频道为 `<主频道>/<群组名>`） |
| `autoConnect` | `true` | SVC 连接后自动连 TS |
| `transport` | `"udp"` | `udp`（默认）或 `tcp`（经 tsbridge 隧道，UDP 受限网络兜底） |
| `lowLatencyMode` | `true` | TS 会话期间把 SVC 播放缓冲压到 2 包（≈40ms）；关闭则用 SVC 原配置 |
| `serverPassword` | `""` | 全局默认服务器/主频道密码（每服可覆盖） |
| `chatBridgeEnabled` | `true` | TS 频道文字 ↔ MC 聊天栏 |
| `debug` | `false` | 预留调试开关 |
| `reverbEnabled` | `false` | 路径追踪混响（接收侧空间感；含遮挡/水下滤波） |
| `duckingEnabled` | `false` | TS 语音播放时压低游戏音量 |
| `duckingLevel` | `0.35` | 闪避深度（0.05–1.0，越大压得越多） |
| `echoCancelEnabled` | `false` | WebRTC 回声消除（外放防回声） |
| `denoiseMode` | `"OFF"` | 发送侧降噪：`OFF`=用 SVC 原生；也可选 `WEBRTC_*` / `NVIDIA_AI` |
| `agcEnabled` | `false` | WebRTC AGC 自动增益 |
| `aecDelayMs` | `120` | AEC 播放→麦克风路径延迟估计（毫秒） |
| `nvidiaDllPath` | `""` | NVAudioEffects.dll 路径覆盖（空=自动发现） |
| `nvidiaModelDir` | `""` | NVIDIA 模型目录覆盖（空=按 dll 位置推导） |

每服配置（`servers.json` 的条目）：`enabled`、`host`、`port`、`channel`、`serverPassword`、`channelPassword`、`transport`、`useServerBeacon`、`trustedBeaconHost`、`trustedBeaconPort`。

> **音频默认值（0.9.2+）**：Extra 面板的增强项默认全部关闭，发送侧使用 SVC 自身的降噪；
> 接收侧抖动缓冲也交回 SVC 原生配置（本 mod 不再覆盖 `audio_packet_threshold`）。
> 旧版配置首次加载会自动迁移一次（`configVersion` 0→1），如需混响/闪避/降噪请手动在 Extra 面板开启。

- 连接后 TS 频道里会出现以 `[MC] 玩家名` 命名的客户端；TS 身份持久化于 `config/teamspeak-svc/identity.txt`（跨启动同一 uid，POSIX 下权限 600）。
- 连接状态可用 `/tsvoice status` 查看（本 mod 不添加独立 HUD 覆盖层）。

## 游戏内命令

| 命令 | 作用 |
|---|---|
| `/tsvoice status` | 连接状态、配置来源、降噪模式、Ghost 数、收到帧数 |
| `/tsvoice gui` | 打开配置界面（本服开关/信标/地址/端口/频道/密码/传输） |
| `/tsvoice trust` | 当服务器下发了未确认的 TS endpoint 时，重新打开授权界面 |
| `/tsvoice connect` | 手动连接 |
| `/tsvoice disconnect` | 手动断开（在换服或再次连接前不会被自动重连） |
| `/tsvoice reload` | 重新加载配置并重建音频管线 |
| `/tsvoice <消息>` | 发送 TS 频道文字 |
| `/tsvoice say <消息>` | 同上（兼容文档写法） |
| `/tsvoice rays` | 开关混响射线可视化（调试） |

说话方式完全跟随 SVC 设置（语音激活 / PTT / 静音）。

## 注意事项

- 所有参与 TS 语音的玩家都需要安装本 mod（未装者与 TS 侧不互通，仍走 SVC 原生路径）。
- 无距离衰减/定位音频/多普勒：所有 TS 音频（包括同服 MC 玩家）统一等响度播放，与 TS 客户端一致。
- whisper 私语 v1 不经 TS（所有语音发往当前频道）。
- 若某个 MC 服务器自带 SVC 且你不想走 TS：`/tsvoice disconnect`，或在该服配置里停用 mod。
- **信标信任**：服务器下发的 TS endpoint 首次使用或变化时必须确认。可选择“仅本次信任 / 记住 / 拒绝 / 本服禁用信标”。已保存给其他 TS endpoint 的密码不会发送给新 endpoint。
- **TCP 隧道**：`transport=tcp` 时客户端连接信标下发的隧道端口；启动游戏前需设置环境变量 `TBRIDGE_TUNNEL_SECRET`（与服务端一致，≥32 字符）。票据一次性、短期有效，隧道账号与 MC 身份无关。
- **身份验证完全可选**：不装本 mod、不做 MC 配对、没有签名声明的 TS 用户都能正常语音和文字交流。签名声明只用于把 TS 用户映射为可信 MC 玩家（皮肤/状态展示），声明过期、撤销或伪造只会降级为普通 TS Ghost，绝不静音或踢出。
- TS 服务器地址若在公网，确认 UDP 9987 已放行/转发（或配置 TCP 隧道）。

## 构建

```bat
gradlew.bat build
```

产物：`build/libs/teamspeak-svc-0.9.8+26.3.jar`（JDK 25）。

协议栈自测（可选，验证与真实 TS 服务器的互通）：

```bat
java -cp "build/classes/java/main;<bouncycastle.jar>;<slf4j-api.jar>" com.tsvoice.client.ts3voice.Ts3VoiceSelfTest <host> 9987 <频道名> 8 [tcp]
```

## 许可证

GPL-3.0。音频增强部分移植自 [SVC-Extra](https://github.com/liuyuexiaoyu1/SVC-Extra)（GPLv3），其包含来自 [Channel](https://github.com/USS-Shenzhou/Channel) 的代码；TS3 协议实现参考 TSLib 的公开实现。
