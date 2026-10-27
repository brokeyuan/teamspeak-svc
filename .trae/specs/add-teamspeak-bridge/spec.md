# Simple Voice Chat ↔ TeamSpeak 桥接器 Spec

## Why
Simple Voice Chat (SVC) 仅能在 Minecraft 客户端之间通信，无法与非 MC 玩家（手机、桌面 TeamSpeak 用户）互通。需要一个桥接器，把 SVC 的语音流双向转发到 TeamSpeak 频道，从而实现跨平台语音。

## What Changes
- 新增独立项目 `teamspeak&svcbridge/`，作为一个 **Fabric 服务端 mod**，依赖 Simple Voice Chat API（`simple-voice-chat-26.2/api`）与 Fabric API。
- 通过 SVC 插件 API 捕获 MC 玩家麦克风包（`MicrophonePacketEvent`，已为 Opus 编码），并按 TS 客户端要求转发到 TeamSpeak 频道。
- 通过 SVC 的 `LocationalAudioChannel` / `StaticAudioChannel` + `AudioPlayer`，把来自 TeamSpeak 的 Opus 音频注入回 MC 玩家。
- 通过 TeamSpeak **ServerQuery**（TCP，使用 `com.github.theholywaffle:teamspeak3-api`）实现：
  - 桥接频道的创建与配置
  - MC 玩家与 TS 用户的身份配对（pairing code）
  - 用户移动 / 踢出 / 状态查询
  - 文本消息转发（可选）
- 通过可插拔的 **TS 音频传输层**（`TsAudioTransport` 接口）传输音频帧，默认实现为 **TS3AudioBot JSON-RPC** 客户端，需用户在 TS 服务器侧部署一个 TS3AudioBot 实例并配置其连接凭证。
- 提供 `fabric.mod.json`，在 `entrypoints.voicechat` 注册 `VoicechatPlugin` 实现，在 `entrypoints.server` 注册 `DedicatedServerModInitializer`。
- 提供完整配置文件（`config/tsbridge.json`，由 mod 自己读取，不依赖 Fabric Config API）：TS ServerQuery 凭证、TS3AudioBot API URL、桥接频道名、配对码长度、音频采样参数、调试开关等。
- 提供命令（Fabric Command API v2）：`/tsbridge pair <code>`、`/tsbridge unpair`、`/tsbridge status`、`/tsbridge reload`、`/tsbridge link <tsChannel>`。
- 权限使用 **fabric-permissions-api-v0**：`tsbridge.admin`。
- **BREAKING**：无（新项目，不修改既有 SVC 代码）。

## Impact
- Affected specs: 无（新增能力）
- Affected code:
  - 新建 `teamspeak&svcbridge/` 完整 Fabric Loom 子项目（`fabric.mod.json` + `build.gradle` + `gradle.properties`）
  - 仅以 `modImplementation` / `compileOnly` 依赖 SVC API（`voicechat_api` mod）与 Fabric API，不修改 `simple-voice-chat-26.2` 源码
  - 依赖 `com.github.theholywaffle:teamspeak3-api`（ServerQuery，通过 `include` 打入 jar）
  - 依赖 `org.java-websocket:Java-WebSocket`（与 TS3AudioBot 通信备选，通过 `include` 打入 jar）
  - 依赖 `me.lucko:fabric-permissions-api`（权限）
  - 最终产物为可放入 `mods/` 的 Fabric mod jar

## ADDED Requirements

### Requirement: SVC 麦克风音频捕获与转发
系统 SHALL 在 Fabric 服务端通过 `fabric.mod.json` 的 `voicechat` entrypoint 注册 SVC `VoicechatPlugin`，监听 `MicrophonePacketEvent`，捕获所有已连接 SVC 玩家的 Opus 麦克风数据帧，并将其按发送者 UUID 路由到对应的 TeamSpeak 音频通道。

#### Scenario: MC 玩家说话被转发到 TS
- **WHEN** 已配对的 MC 玩家发送麦克风包
- **THEN** 系统通过 `TsAudioTransport` 将该 Opus 帧转发到 TS3AudioBot，由其在 TS 频道播放
- **AND** 转发线程异步执行，不阻塞 SVC 主线程

#### Scenario: 未配对玩家语音不转发
- **WHEN** 未完成 MC↔TS 配对的玩家发送麦克风包
- **THEN** 系统丢弃该帧并记录 debug 级别日志（不报错）

### Requirement: TS 音频注入 SVC
系统 SHALL 通过 `TsAudioTransport` 接收来自 TeamSpeak 的 Opus 音频帧，并使用 SVC API 的 `LocationalAudioChannel`（位于触发玩家附近）或 `StaticAudioChannel`（广播给所有目标玩家）+ `AudioPlayer` 将音频注入到 MC 玩家。

#### Scenario: TS 用户说话被 MC 玩家听到
- **WHEN** TS3AudioBot 推送一帧 Opus 音频到桥接器
- **THEN** 系统使用 `OpusDecoder` 解码为 PCM，再用 SVC `OpusEncoder` 重新编码（或直接透传若编码兼容）
- **AND** 通过 `AudioPlayer` 在目标 MC 玩家附近的 `LocationalAudioChannel` 播放

#### Scenario: TS 音频无人接收
- **WHEN** 接收到 TS 音频帧但没有 MC 玩家在监听范围内
- **THEN** 系统丢弃该帧并记录 debug 日志

### Requirement: MC↔TS 身份配对
系统 SHALL 提供基于配对码的身份绑定机制，将 MC 玩家 UUID 与 TS 客户端唯一 ID（`clientUniqueIdentifier`）一一对应。

#### Scenario: 配对流程
- **WHEN** MC 玩家执行 `/tsbridge pair <code>`
- **AND** TS 用户在 TS 频道中通过 ServerQuery 验证同一配对码
- **THEN** 系统将两者绑定并持久化到 `config/tsbridge/pairings.json`
- **AND** 双向音频转发立即生效

#### Scenario: 解除配对
- **WHEN** MC 玩家执行 `/tsbridge unpair`
- **THEN** 系统移除绑定记录并停止双向音频转发

### Requirement: TeamSpeak ServerQuery 管理
系统 SHALL 通过 `teamspeak3-api` 库建立 ServerQuery 连接，自动创建/复用桥接频道，并维护一个常驻 ServerQuery 会话。

#### Scenario: 启动时自动建频道
- **WHEN** mod 初始化且 ServerQuery 连接成功
- **THEN** 系统检查配置的桥接频道是否存在，不存在则创建
- **AND** 将 TS3AudioBot 移动到该频道

#### Scenario: ServerQuery 断线重连
- **WHEN** ServerQuery 连接断开
- **THEN** 系统以指数退避策略自动重连，并在重连后恢复频道状态

### Requirement: TS3AudioBot 集成（默认音频传输）
系统 SHALL 提供默认 `Ts3AudioBotTransport` 实现，通过 TS3AudioBot 的 JSON-RPC API 推送/拉取 Opus 音频帧。

#### Scenario: 推送 MC 音频到 TS
- **WHEN** 桥接器收到 MC 麦克风帧
- **THEN** 通过 HTTP POST `/bot/use/<botId>/pcm/send` 或等价 API 将 PCM/Opus 数据发送给 TS3AudioBot
- **AND** 失败时入队重试，超过阈值后丢弃并告警

#### Scenario: 拉取 TS 音频
- **WHEN** 桥接器启动
- **THEN** 通过 WebSocket / 长轮询订阅 TS3AudioBot 的音频流事件
- **AND** 收到帧后回调 `onTsAudioFrame(...)` 注入 SVC

### Requirement: Fabric 命令与权限
系统 SHALL 通过 Fabric Command API v2 注册命令，并通过 `fabric-permissions-api-v0` 校验权限。

#### Scenario: 命令注册
- **WHEN** `CommandRegistrationCallback.EVENT` 触发
- **THEN** 注册 `/tsbridge` 命令树，包含子命令 `pair`、`unpair`、`status`、`reload`、`link`
- **AND** `pair`/`unpair` 任何玩家可执行（仅作用于自身）
- **AND** `reload`/`link` 需要 `tsbridge.admin` 权限（通过 `fabric-permissions-api` 校验）

#### Scenario: 配置项
- **WHEN** 首次启动
- **THEN** 生成 `config/tsbridge.json`，包含：TS 主机/端口、ServerQuery 用户名/密码、TS3AudioBot API URL、桥接频道名、配对码长度、音频广播范围、调试开关
- **AND** `/tsbridge reload` 可热重载配置（除 ServerQuery 连接外）

### Requirement: Fabric 生命周期与容错
系统 SHALL 在 `DedicatedServerModInitializer#onInitializeServer` 中注册事件回调，在 `ServerLifecycleEvents.SERVER_STARTED` 时按顺序启动各组件，在 `ServerLifecycleEvents.SERVER_STOPPING` 时按逆序安全关闭，且任一组件失败不阻塞其他组件。

#### Scenario: 启动顺序
- **WHEN** `onInitializeServer()` 触发（仅注册事件回调，不做重 IO）
- **AND** `ServerLifecycleEvents.SERVER_STARTED` 触发
- **THEN** 顺序：加载配置 → 连接 ServerQuery → 创建/校验桥接频道 → 连接 TS3AudioBot → 注册音频转发线程
- **AND** SVC 插件通过 `fabric.mod.json` entrypoint 自动加载
- **AND** 任一环节失败记录错误并降级（如 TS 不可用时 SVC 仍正常工作）

#### Scenario: 关闭顺序
- **WHEN** `ServerLifecycleEvents.SERVER_STOPPING` 触发
- **THEN** 逆序：停止音频线程 → 断开 TS3AudioBot → 断开 ServerQuery → 持久化配对数据

## MODIFIED Requirements
无

## REMOVED Requirements
无
