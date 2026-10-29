# Tasks

- [x] Task 1: 搭建 Fabric Loom 子项目骨架
  - [x] SubTask 1.1: 在 `teamspeak&svcbridge/` 下创建 `build.gradle`（应用 `net.fabricmc.fabric-loom`）、`settings.gradle`、`gradle.properties`
  - [x] SubTask 1.2: 配置依赖：`modImplementation` Fabric API、`modImplementation`/`compileOnly` SVC `voicechat_api`、`include` `teamspeak3-api` 与 `Java-WebSocket`、`modImplementation` `fabric-permissions-api-v0`
  - [x] SubTask 1.3: 创建 `src/main/resources/fabric.mod.json`：`id=tsbridge`、`entrypoints.server=[TsBridgeMod]`、`entrypoints.voicechat=[TsVoicechatPlugin]`、`depends: { voicechat_api, fabricloader, fabric-api, fabric-permissions-api-v0 }`
  - [x] SubTask 1.4: 创建 `src/main/java/com/svcb/tsbridge/` 包结构与 `TsBridgeMod implements DedicatedServerModInitializer` 骨架

- [x] Task 2: 实现配置与持久化层
  - [x] SubTask 2.1: `BridgeConfig` 类：读取/保存 `config/tsbridge.json`（用 Gson/Fabric Loader 的 `ConfigDirectory`），字段：TS host/port、query 凭证、botApiUrl、channelName、pairCodeLength、broadcastRange、debug
  - [x] SubTask 2.2: `PairingStore` 类：加载/保存 `config/tsbridge/pairings.json`，提供 `bind(mcUuid, tsUniqueId)` / `unbind(mcUuid)` / `getTsIdentity(mcUuid)` / `getMcUuid(tsUniqueId)` 方法，线程安全
  - [x] SubTask 2.3: `TsBridgeMod.onInitializeServer()` 仅注册 `ServerLifecycleEvents.SERVER_STARTED/STOPPING`，重 IO 放到 `SERVER_STARTED` 回调

- [x] Task 3: 实现 TeamSpeak ServerQuery 客户端
  - [x] SubTask 3.1: `TsQueryClient` 类：封装 `TS3Api` 连接、登录、频道查询/创建、用户移动、按 uniqueId 查 client
  - [x] SubTask 3.2: 实现断线自动重连（指数退避，最大间隔 60s）
  - [x] SubTask 3.3: `BridgeChannelManager`：启动时检查/创建配置的桥接频道，将 TS3AudioBot 移入；提供 `getClientsInBridgeChannel()` 方法
  - [x] SubTask 3.4: `TsQueryClient.connect()` 在配置错误时抛出明确异常并记录日志

- [x] Task 4: 实现 SVC 插件集成
  - [x] SubTask 4.1: `TsVoicechatPlugin implements VoicechatPlugin`，`getPluginId()` 返回 `svctsbridge`，作为 `fabric.mod.json` 的 `voicechat` entrypoint
  - [x] SubTask 4.2: 在 `registerEvents` 注册 `MicrophonePacketEvent` 监听器，捕获 Opus 帧并投递到 `McToTsDispatcher`（Task 5 占位）
  - [x] SubTask 4.3: 注册 `PlayerConnectedEvent` / `PlayerDisconnectedEvent` 用于维护 `OnlinePlayerRegistry`
  - [x] SubTask 4.4: 提供 `McAudioInjector`：用 `VoicechatServerApi#createLocationalAudioChannel` + `createAudioPlayer` 将 TS 音频注入到目标玩家附近

- [x] Task 5: 定义 TS 音频传输接口与默认实现
  - [x] SubTask 5.1: `TsAudioTransport` 接口：`start()` / `stop()` / `sendMcAudio(mcUuid, opusData, sequence)` / `setOnTsAudioListener(Consumer<TsAudioFrame>)`
  - [x] SubTask 5.2: `TsAudioFrame` 数据类：`tsUniqueId`、`opusData`、`sequenceNumber`、`timestampMs`（record）
  - [x] SubTask 5.3: `Ts3AudioBotTransport` 实现：基于 HTTP/JSON-RPC 调用 TS3AudioBot API 推送音频
  - [x] SubTask 5.4: TS3AudioBot 音频订阅：通过 WebSocket 接收 TS 音频帧，回调 listener，断线自动重连
  - [x] SubTask 5.5: `McToTsForwarder` 线程：从队列消费 MC 音频帧，调用 `TsAudioTransport#sendMcAudio`，背压丢弃计数
  - [x] SubTask 5.6: 替换 `McToTsDispatcher` 占位为真实转发（投递到 forwarder 队列，仅转发已配对玩家）

- [x] Task 6: 实现音频编解码适配
  - [x] SubTask 6.1: 创建 `AudioCodec` 工具类，按需用 SVC `OpusDecoder`/`OpusEncoder` 转换（TS3AudioBot 期望 PCM 时解码，SVC 注入时重编码）
  - [x] SubTask 6.2: 校验采样率一致性（SVC = 48kHz mono 20ms = 960 samples），与 TS3AudioBot 配置对齐，不一致时重采样（线性插值即可，简化实现）

- [x] Task 7: 实现配对流程
  - [x] SubTask 7.1: `PairingManager`：生成短配对码（默认 6 位），维护 `pendingPairings` Map（code → mcUuid + 过期时间，默认 5 分钟）
  - [x] SubTask 7.2: ServerQuery 端事件订阅：TS 用户在桥接频道发送 `!pair <code>` 文本消息时，原子消费配对码并调用 `PairingStore.bind`
  - [x] SubTask 7.3: 配对成功后向 MC 玩家发消息（`ServerPlayer.sendSystemMessage`）+ 向 TS 用户发 ServerQuery 文本提示

- [x] Task 8: 实现 Fabric 命令与权限
  - [x] SubTask 8.1: `TsBridgeCommands` 注册到 `CommandRegistrationCallback`，子命令 `pair` / `unpair` / `status` / `reload` / `link`
  - [x] SubTask 8.2: `reload` / `link` 通过 `.requires(s -> Permissions.check(s, "tsbridge.admin"))` 校验
  - [x] SubTask 8.3: `status` 显示：ServerQuery 状态、TS3AudioBot 状态、已配对用户数、音频帧计数

- [x] Task 9: 生命周期整合与容错
  - [x] SubTask 9.1: `ServerLifecycleEvents.SERVER_STARTED` 按顺序启动：config → PairingStore → TsQueryClient → BridgeChannelManager → PairingManager → TsAudioTransport → forwarder 线程
  - [x] SubTask 9.2: `ServerLifecycleEvents.SERVER_STOPPING` 逆序关闭：forwarder → tsAudioTransport → McAudioInjector → tsQueryClient → pairings
  - [x] SubTask 9.3: 任一组件启动失败时记录 ERROR 并降级运行（每个组件独立 try/catch）

- [ ] Task 10: 文档与构建验证
  - [ ] SubTask 10.1: 在 `teamspeak&svcbridge/README.md`（如用户已有则跳过）记录部署步骤、TS3AudioBot 配置要求、配对流程示意
  - [ ] SubTask 10.2: 运行 `gradle :teamspeak&svcbridge:build` 验证可产出可部署 Fabric mod jar
  - [ ] SubTask 10.3: 在本地 Fabric 测试服验证 mod 可加载、命令可执行、`/tsbridge status` 输出正常（不要求真实 TS 连接）

# Task Dependencies
- Task 2 依赖 Task 1
- Task 3、Task 4 可并行，均依赖 Task 2
- Task 5 依赖 Task 2（接口部分）与 Task 4（listener 注入）
- Task 6 依赖 Task 4、Task 5
- Task 7 依赖 Task 3、Task 2
- Task 8 依赖 Task 2、Task 7
- Task 9 依赖 Task 3、Task 4、Task 5、Task 7
- Task 10 依赖 Task 9
