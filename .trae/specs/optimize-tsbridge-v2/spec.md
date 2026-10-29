# TS Bridge v2 — 永久群组 + 双向存在感 Spec

## Why
当前 TS Bridge v1 存在以下问题：
- 音频架构依赖 **TS3AudioBot 外部中间层**，导致双重编解码（Opus→PCM→Opus）、HTTP/WS 中转延迟、部署复杂度高（需三节点协同）
- 使用 `LocationalAudioChannel` 绑定到单个 MC 玩家位置，不是群组体验
- MC 玩家在 TS 侧无存在感（全部混在一个 bot 里），TS 用户在 MC 侧无存在感（看不到虚拟玩家）
- 配对机制依赖 TS 用户手动发 `!pair` 文本命令，移动端体验差
- 无法创建永久桥接频道（SVC 群组随最后一个玩家离开而销毁）

参考 [voicechat-discord-main](file:///d:/ServerFile/Workspace/Fabric%20mod/teamspeak&svcbridge/voicechat-discord-main/) 的成熟架构和 [voicechat-moreservergether](file:///d:/ServerFile/Workspace/Fabric%20mod/voicechat/Voicechat-moreservergether/) 的 ghost player 注入技术，进行架构重构。

## What Changes

### 核心变更
1. **永久 SVC 桥接群组**：在 `SERVER_STARTED` 时创建一个 `StaticAudioChannel`（名称"TeamSpeak互联"），持久存在，有人无人都不销毁
2. **TS→MC Ghost Player 注入**：每个在桥接 TS 频道中的用户，在 SVC 群组 UI 中显示为一个虚拟玩家（带 TS 标识），使用反射构造 `PlayerStatePacket` 注入
3. **MC→TS 存在感同步**：TS bot 昵称动态更新在线 MC 玩家数（如 `MCBridge (3 online)`），频道描述列出在线 MC 玩家名
4. **原生 TS3 音频客户端**：移除 TS3AudioBot 依赖（`Ts3AudioBotTransport` 标记为 deprecated），新增 `Ts3NativeTransport` 基于 Java `DatagramSocket` 直接与 TS3 语音端口（UDP 9987）通信，参考 Discord bridge 的 Rust 层但用纯 Java 实现以简化部署
5. **MC→TS 多人混音**：参考 Discord bridge 的 `combine_audio_parts` 混音算法，将所有在线 MC 玩家的 Opus 解码后混音为一路 PCM → 编码为 Opus → 发送到 TS
6. **删除已废弃的 LocationalAudioChannel 路径**：全部改为 `StaticAudioChannel`

### 保留内容
- `TsQueryClient`（ServerQuery 频道管理 + 文本事件监听）
- `BridgeChannelManager`（频道确保 + 消息发送）
- `PairingManager` + `PairingStore`（配对码机制保留，但新增自动配对模式）
- `TsBridgeCommands` 命令树（增强 `status` 输出）
- `BridgeConfig`（移除 `botApiUrl`/`botApiToken`，新增 TS3 语音端口配置）
- 容错生命周期（每个组件独立 try/catch，降级运行）

### BREAKING
- 移除 `tsAudioTransport` 对 TS3AudioBot 的硬依赖
- `BridgeConfig` 删除 `botApiUrl` / `botApiToken` 字段
- `McAudioInjector` 不再使用 `LocationalAudioChannel`，改用 `StaticAudioChannel`
- `fabric.mod.json` 移除 `Java-WebSocket` 的 `include` 依赖

## Impact
- Affected specs: 覆盖 `add-teamspeak-bridge` 的 Task 5（音频传输层）和 Task 4.4（音频注入方式）
- Affected code:
  - **重写**：`Ts3AudioBotTransport.java` → `Ts3NativeTransport.java`（全新文件）
  - **重写**：`McAudioInjector.java`（`LocationalAudioChannel` → `StaticAudioChannel` + Ghost Player）
  - **修改**：`BridgeConfig.java`（移除 botApi 字段，新增 tsVoicePort）
  - **修改**：`TsBridgeMod.java`（启动流程调整，新增 native transport 和 ghost injector 初始化）
  - **修改**：`McToTsForwarder.java`（新增混音逻辑，参考 Discord bridge）
  - **修改**：`build.gradle`（移除 `Java-WebSocket`，可选新增 native 音频依赖）
  - **新增**：`package com.svcb.tsbridge.ghost.GhostPlayerInjector`（Ghost Player 注入）
  - **新增**：`package com.svcb.tsbridge.audio.McAudioMixer`（多人混音）
  - **不变**：`TsQueryClient.java`、`BridgeChannelManager.java`、`PairingManager.java`、`PairingStore.java`、`TsBridgeCommands.java`、`OnlinePlayerRegistry.java`、`McToTsDispatcher.java`

## ADDED Requirements

### Requirement: 永久 SVC 桥接群组
系统 SHALL 在 `ServerLifecycleEvents.SERVER_STARTED` 时通过 `VoicechatServerApi.createStaticAudioChannel()` 创建一个持久 `StaticAudioChannel`，名称为"TeamSpeak互联"（可通过 `BridgeConfig.bridgeChannelName` 配置）。该频道永不销毁（即使无玩家）。

#### Scenario: 频道创建
- **WHEN** SERVER_STARTED 触发
- **THEN** 创建 `StaticAudioChannel`（UUID 固定派生自 `"tsbridge-permanent"`）
- **AND** 频道对所有在线 MC 玩家可见

#### Scenario: 频道持久化
- **WHEN** 所有 MC 玩家离开桥接频道
- **THEN** 频道依然存在（`StaticAudioChannel` 由 mod 持有引用，不被 GC）

#### Scenario: 频道不可销毁
- **WHEN** 任何 MC 玩家尝试删除桥接频道
- **THEN** 操作被忽略（StaticAudioChannel 不受标准群组生命周期管理）

### Requirement: TS→MC Ghost Player 注入
系统 SHALL 对每个在线且位于桥接 TS 频道中的 TS 用户，在 SVC 群组 UI 中注入一个虚拟玩家（Ghost Player），显示格式为 `[TS] <tsUsername>`。使用反射构造 `PlayerStatePacket` 实现（参考 voicechat-moreservergether 的 `GhostPlayerStateInjector`）。

#### Scenario: TS 用户加入
- **WHEN** TS 用户加入桥接 TS 频道（通过 ServerQuery `ClientJoinEvent` 或 `ClientMovedEvent` 检测）
- **THEN** 系统向该桥接 `StaticAudioChannel` 注入一个 Ghost Player
- **AND** Ghost Player 名称显示为 `[TS] <tsUsername>`
- **AND** Ghost Player 的 `disabled` 状态为 false（表示可接收音频）

#### Scenario: TS 用户离开
- **WHEN** TS 用户离开桥接 TS 频道（通过 `ClientLeaveEvent` 或移出事件检测）
- **THEN** 系统从 `StaticAudioChannel` 移除对应的 Ghost Player
- **AND** 清理该 TS 用户的所有注入资源（AudioPlayer、OpusDecoder、队列）

#### Scenario: TS 用户说话时 Ghost Player 高亮
- **WHEN** TS 用户正在说话（接收到该用户的 Opus 帧）
- **THEN** Ghost Player 的 `speaking` 状态设为 true
- **WHEN** TS 用户停止说话超过 500ms
- **THEN** `speaking` 状态恢复为 false

### Requirement: MC→TS 存在感同步
系统 SHALL 通过动态更新 TS bot 昵称和频道描述，向 TS 用户展示当前连接的 MC 玩家信息。

#### Scenario: Bot 昵称更新
- **WHEN** MC 玩家进入/离开桥接 `StaticAudioChannel`（通过 SVC `PlayerConnectedEvent` / `PlayerDisconnectedEvent` 或频道成员变化检测）
- **THEN** 系统通过 ServerQuery 更新 bot 昵称为 `MCBridge (N online)`
- **AND** N 为当前在线 MC 玩家数

#### Scenario: 频道描述更新
- **WHEN** 在线 MC 玩家列表变更
- **THEN** 系统通过 ServerQuery 更新桥接 TS 频道描述为在线 MC 玩家名列表（每行一个，最多显示 16 人，超出显示 "... and N more"）
- **AND** 去抖（debounce）：3 秒内多次变更合并为一次更新

### Requirement: 原生 TS3 音频客户端
系统 SHALL 实现 `Ts3NativeTransport`，通过 Java `DatagramSocket` 直连 TS3 服务器的语音端口（UDP，默认 9987），实现 TS3 语音协议的 Opus 音频收发。不依赖 TS3AudioBot 或任何外部进程。

#### Scenario: 连接建立
- **WHEN** transport.start() 被调用
- **THEN** 系统通过 ServerQuery 获取或分配一个临时 TS3 语音客户端 token
- **AND** 通过 TS3 协议握手（init + initack + clientinit + clientinitiv）建立 UDP 语音连接
- **AND** 加入配置的桥接频道

#### Scenario: MC→TS 音频发送
- **WHEN** `sendMcAudio` 被调用（来自 `McToTsForwarder` 混音后的帧）
- **THEN** 系统将 Opus 帧按 TS3 语音包格式（packetID、sequence、opus payload）封装并 UDP 发送
- **AND** 异步非阻塞发送，失败时记录日志不抛异常

#### Scenario: TS→MC 音频接收
- **WHEN** UDP socket 接收到来自 TS 服务器的语音包
- **THEN** 系统解析发送者 clientId → 查询 uniqueId → 回调 `onTsAudioListener`
- **AND** 数据包中包含 Opus 编码的音频载荷

#### Scenario: Transport 降级（TS3NativeTransport 不可用时）
- **WHEN** `Ts3NativeTransport` 启动失败（如网络不可达、协议握手失败）
- **THEN** 系统回退到 `Ts3AudioBotTransport`（若用户配置了 TS3AudioBot）
- **AND** 若两者都不可用，标记音频传输为不可用但 mod 其余功能正常（降级运行）

### Requirement: MC 多人混音
系统 SHALL 实现 `McAudioMixer`，将多个 MC 玩家的 Opus 音频帧解码为 PCM 后混音为一个 PCM 流，再编码为 Opus 发送到 TS。混音算法使用饱和加法（参考 Discord bridge 的 `combine_audio_parts`）。

#### Scenario: 单人混音（无开销）
- **WHEN** 只有 1 个 MC 玩家在线
- **THEN** 跳过混音，直接透传该玩家的 Opus 帧到 TS（避免无谓的编解码）

#### Scenario: 多人混音
- **WHEN** ≥ 2 个 MC 玩家同时在线
- **THEN** 每个玩家独立 Opus 解码为 PCM short[960]
- **AND** 逐采样点饱和加法求和（clamp 到 i16 范围）
- **AND** 混音结果编码为 Opus 发送到 TS

#### Scenario: 无声时跳过
- **WHEN** 所有在线 MC 玩家均未发送音频帧（队列为空）
- **THEN** 发送静音帧或跳过发送（节省带宽和 CPU）

### Requirement: 群组内成员可见性
系统 SHALL 确保在 `StaticAudioChannel` 中的所有 MC 玩家能听到所有 TS 用户和所有其他 MC 玩家的声音，TS 用户能听到所有 MC 玩家的声音。

#### Scenario: MC 玩家听到 TS 用户
- **WHEN** TS 用户发送音频帧
- **THEN** 系统将 TS 音频通过独立 `AudioPlayer` 注入到 `StaticAudioChannel`
- **AND** 频道内所有 MC 玩家都能接收该音频

#### Scenario: TS 用户听到所有 MC 玩家
- **WHEN** ≥ 1 个 MC 玩家发送音频帧
- **THEN** 系统混音后通过原生 TS3 客户端发送
- **AND** TS 频道内所有 TS 用户都能听到混音结果

### Requirement: 增强 `/tsbridge status` 输出
系统 SHALL 增强 status 命令，显示 Ghost Player 数量、混音器状态、在线 MC 玩家列表、TS 用户列表。

#### Scenario: status 命令输出
- **WHEN** `/tsbridge status` 执行
- **THEN** 输出包含：ServerQuery 状态、TS Native 音频状态、桥接频道 ID、已配对用户数、Ghost Players 数、在线 MC 玩家列表、在线 TS 用户列表、混音器活跃状态、音频帧统计

## MODIFIED Requirements

### Requirement: 音频编解码适配（修改自 Task 6）
系统 SHALL 优化编解码路径：MC→TS 方向仅在多人时执行 Opus→PCM 解码混音+重编码，单人时透传 Opus（零解码）。TS→MC 方向尝试直接注入 Opus（通过 SVC 的多播 API），仅在 SVC API 不支持直接 Opus 注入时才回退到 Opus→PCM→Opus 双编码。

### Requirement: MC↔TS 身份配对（增强自 Task 7）
新增自动配对模式：当 MC 玩家加入 `StaticAudioChannel` 时，系统自动生成配对码并显示在 TS 频道描述中；TS 用户无需手动发 `!pair`，而是加入桥接频道即自动绑定（可选开启）。

## REMOVED Requirements

### Requirement: TS3AudioBot 集成（从默认实现降为可选回退）
`Ts3AudioBotTransport` 从默认实现降级为可选回退方案（仅在 `Ts3NativeTransport` 不可用且用户配置了 TS3AudioBot 时使用）。配置文件中的 `botApiUrl`/`botApiToken` 字段标记为 deprecated。
