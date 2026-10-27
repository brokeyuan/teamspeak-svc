# Tasks

## Phase 1: 修复编译 + 代码清理

- [ ] Task 1: 修复编译错误
  - [ ] SubTask 1.1: 在 `PairingManager.TsPairingListener` 中补充 `onChannelMoved(ChannelMovedEvent)` 方法（空实现，跟上库版本更新）
  - [ ] SubTask 1.2: 运行 `gradle :teamspeak&svcbridge:build` 验证编译通过

- [ ] Task 2: 构建配置更新
  - [ ] SubTask 2.1: `BridgeConfig.java` 新增 `tsVoicePort` 字段（默认 9987），标记 `botApiUrl` / `botApiToken` 为 `@Deprecated`
  - [ ] SubTask 2.2: `build.gradle` 移除 `org.java-websocket:Java-WebSocket` 的 `include`（保留 `implementation` 以便 Ts3AudioBotTransport 降级方案仍可用，或完全移除待 Task 7 决定）
  - [ ] SubTask 2.3: 更新 `fabric.mod.json` 的 `description` 反映 v2 变更

## Phase 2: 永久群组 + Ghost Player（核心体验变更）

参考：Discord bridge 的 `GroupManager`（群组事件追踪）、voicechat-moreservergether 的 `GhostPlayerStateInjector`（假玩家反射注入）。

- [ ] Task 3: 永久 StaticAudioChannel
  - [ ] SubTask 3.1: 在 `TsBridgeMod` 中添加 `staticAudioChannel` 字段（`volatile StaticAudioChannel`）
  - [ ] SubTask 3.2: 在 `SERVER_STARTED` 中，`TsQueryClient` 连接成功后，调用 `TsVoicechatPlugin.api.createStaticAudioChannel(channelId, level)` 创建永久频道
  - [ ] SubTask 3.3: 频道 UUID = `UUID.nameUUIDFromBytes("tsbridge-permanent".getBytes(StandardCharsets.UTF_8))`
  - [ ] SubTask 3.4: 频道名称从 `BridgeConfig.bridgeChannelName` 读取
  - [ ] SubTask 3.5: 在 `SERVER_STOPPING` 中调用 `staticAudioChannel.flush()` 并置 null

- [ ] Task 4: Ghost Player 注入器
  - [ ] SubTask 4.1: 创建 `GhostPlayerInjector.java`（包 `com.svcb.tsbridge.ghost`）
  - [ ] SubTask 4.2: 实现 `injectPlayer(UUID ghostUuid, String displayName, StaticAudioChannel channel)` — 用反射构造 `PlayerStatePacket` 并广播
  - [ ] SubTask 4.3: 实现 `updateSpeaking(UUID ghostUuid, boolean speaking)` — 更新 Ghost Player 说话状态
  - [ ] SubTask 4.4: 实现 `removePlayer(UUID ghostUuid)` — 发送离线包移除 Ghost Player
  - [ ] SubTask 4.5: 实现 `shutdownAll()` — 清理所有 Ghost Player
  - [ ] SubTask 4.6: 在 `TsBridgeMod` 中注册 ServerQuery 事件监听（`ClientJoinEvent` / `ClientLeaveEvent` / `ClientMovedEvent`）→ 触发 Ghost Player 增删

## Phase 3: MC→TS 混音（参考 Discord bridge）

参考：Discord bridge 的 `combine_audio_parts`（Rust `audio_util.rs`）— 多位玩家 PCM 饱和加法混音。

- [ ] Task 5: 多人混音器
  - [ ] SubTask 5.1: 创建 `McAudioMixer.java`（包 `com.svcb.tsbridge.audio`）
  - [ ] SubTask 5.2: 维护 `Map<UUID, LinkedBlockingQueue<short[]>>` 收集每个 MC 玩家的 PCM 帧
  - [ ] SubTask 5.3: `McAudioMixerThread` daemon 线程：每 20ms 从所有队列 poll PCM → 饱和加法 → 发送混音结果
  - [ ] SubTask 5.4: 单人时短路：直接透传 Opus 不编解码（检查 `queues.size() == 1`）
  - [ ] SubTask 5.5: 无声时发送静音帧或跳过（可通过 `BridgeConfig` 新增 `sendSilenceFrames` 开关控制）
  - [ ] SubTask 5.6: `McToTsDispatcher.onMicrophonePacket` 修改：将 Opus 帧送给 mixer 而非 forwarder
  - [ ] SubTask 5.7: `McToTsForwarder` 保留但职责简化为：从 mixer 取混音结果 → 调用 `TsAudioTransport.sendMcAudio`

## Phase 4: 原生 TS3 音频客户端（核心架构变更）

参考：Discord bridge 的 Rust serenity/songbird 音频层思路 — 但用纯 Java 实现 TS3 语音协议。

- [ ] Task 6: Ts3NativeTransport 核心
  - [ ] SubTask 6.1: 创建 `Ts3NativeTransport.java`，实现 `TsAudioTransport` 接口
  - [ ] SubTask 6.2: `start()` — 通过 `TsQueryClient` 获取语音 token → UDP Socket 连接 → TS3 协议握手（init + initack + clientinit + clientinitiv）
  - [ ] SubTask 6.3: 实现 UDP 接收线程（`DatagramSocket.receive`），解析 TS3 语音包头 → 提取 Opus payload + sender clientId → 回调 `onTsAudioListener`
  - [ ] SubTask 6.4: `sendMcAudio()` — 封装 TS3 语音包（packetID + clientId + codec + opus payload）→ `DatagramSocket.send`
  - [ ] SubTask 6.5: `stop()` — 发送 disconnect 包 → 关闭 socket → 清理资源
  - [ ] SubTask 6.6: 断线检测 + 指数退避重连（参考 `TsQueryClient.startReconnectLoop()`）

- [ ] Task 7: Transport 降级链
  - [ ] SubTask 7.1: `TsBridgeMod` 启动流程改为：先尝试 `Ts3NativeTransport` → 失败则尝试 `Ts3AudioBotTransport`（若配置了 `botApiUrl`）→ 若都失败，音频传输标记为不可用
  - [ ] SubTask 7.2: `Ts3AudioBotTransport` 保留不动，仅标记 `@Deprecated`
  - [ ] SubTask 7.3: `/tsbridge status` 显示当前使用的 transport 类型

## Phase 5: MC↔TS 存在感同步

- [ ] Task 8: MC→TS 存在感（Bot 昵称 + 频道描述）
  - [ ] SubTask 8.1: 创建 `TsPresenceUpdater.java`（包 `com.svcb.tsbridge.ts.presence`）
  - [ ] SubTask 8.2: 监听 SVC `PlayerConnectedEvent` / `PlayerDisconnectedEvent`（或 StaticAudioChannel 成员变化）
  - [ ] SubTask 8.3: `updateBotNickname(int onlineCount)` — 通过 ServerQuery 更新 bot 昵称为 `MCBridge (N online)`
  - [ ] SubTask 8.4: `updateChannelDescription(List<String> playerNames)` — 通过 ServerQuery 更新频道描述
  - [ ] SubTask 8.5: 3 秒 debounce（用 `ScheduledExecutorService` 或简单的 lastUpdateTime 检查）

## Phase 6: 命令增强 + 配置调整

- [ ] Task 9: 命令增强
  - [ ] SubTask 9.1: `/tsbridge status` 增强 — 输出 Ghost Player 数、混音器状态、当前 transport 类型、在线 MC 玩家名列表、在线 TS 用户列表
  - [ ] SubTask 9.2: `/tsbridge ghost list` 新命令（admin 权限）— 列出当前所有 Ghost Player
  - [ ] SubTask 9.3: `/tsbridge ghost kick <name>` 新命令（admin 权限）— 手动移除指定 Ghost Player

- [ ] Task 10: 配置更新
  - [ ] SubTask 10.1: 默认 `config/tsbridge.json` 新增 `tsVoicePort: 9987`
  - [ ] SubTask 10.2: 默认 `config/tsbridge.json` 新增 `sendSilenceFrames: false`
  - [ ] SubTask 10.3: 默认 `config/tsbridge.json` 新增 `autoPairing: false`（自动配对开关）
  - [ ] SubTask 10.4: `botApiUrl` / `botApiToken` 保留但标记 deprecated（JSON 注释说明）

## Phase 7: McAudioInjector 重构

- [ ] Task 11: McAudioInjector 适配 StaticAudioChannel
  - [ ] SubTask 11.1: 将 `playTsAudio` 从 `LocationalAudioChannel` 切换为 `StaticAudioChannel`
  - [ ] SubTask 11.2: 移除位置跟随逻辑（`channel.updateLocation(...)` ），改为固定到静态频道
  - [ ] SubTask 11.3: 每个 TS 用户保持独立的 `AudioPlayer` + `OpusDecoder` + 队列架构
  - [ ] SubTask 11.4: 移除 `LocationalAudioChannel` 相关 import 和逻辑

## Phase 8: 生命周期整合 + 构建验证

- [ ] Task 12: 生命周期整合
  - [ ] SubTask 12.1: `SERVER_STARTED` 启动顺序：config → PairingStore → TsQueryClient → BridgeChannelManager → StaticAudioChannel → GhostPlayerInjector → TsPresenceUpdater → PairingManager → McAudioMixer → Ts3NativeTransport（↓Ts3AudioBotTransport）→ McToTsForwarder
  - [ ] SubTask 12.2: `SERVER_STOPPING` 逆序：forwarder → mixer → native transport → audio bot transport → presence updater → ghost injector → static channel → query → pairings
  - [ ] SubTask 12.3: 每个组件独立 try/catch

- [ ] Task 13: 构建与验证
  - [ ] SubTask 13.1: `gradle :teamspeak&svcbridge:build` 产出自包含 jar
  - [ ] SubTask 13.2: 本地 Fabric 测试服验证：mod 加载不崩溃、`/tsbridge status` 输出完整、Ghost Player 可见、频道持久化

# Task Dependencies

```
Phase 1 (Task 1-2)     ← 无依赖，先修编译和配置
Phase 2 (Task 3-4)     ← 依赖 Task 2（BridgeConfig 字段）
Phase 3 (Task 5)       ← 依赖 Task 2（mixer 需要独立于 transport）
Phase 4 (Task 6-7)     ← 依赖 Task 2（Ts3NativeTransport 需 config）+ 依赖 Task 3（需要 TsQueryClient）
Phase 5 (Task 8)       ← 依赖 Task 3（需要 TsQueryClient + StaticAudioChannel）
Phase 6 (Task 9-10)    ← 依赖 Task 4、Task 5、Task 7（status 要展示新增组件）
Phase 7 (Task 11)      ← 依赖 Task 3（需要 StaticAudioChannel 已存在）
Phase 8 (Task 12-13)   ← 依赖 Task 1-11
```

并行建议：
- Task 3 + Task 6 可并行（StaticAudioChannel 和 Ts3NativeTransport 无相互依赖）
- Task 4 + Task 5 可并行（GhostPlayer 和 Mixer 独立）
- Task 8 可与 Task 11 并行
