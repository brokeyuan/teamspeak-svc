* [ ] `teamspeak&svcbridge/` 目录已创建，包含完整 Fabric Loom 子项目（build.gradle、settings.gradle、gradle.properties、fabric.mod.json）

* [ ] `build.gradle` 应用 `net.fabricmc.fabric-loom`，依赖 SVC `voicechat_api`、Fabric API、`fabric-permissions-api-v0`，并通过 `include` 打入 `teamspeak3-api` 与 `Java-WebSocket`

* [ ] `fabric.mod.json` 声明 `entrypoints.server=[TsBridgeMod]`、`entrypoints.voicechat=[TsVoicechatPlugin]`、`depends: { voicechat_api, fabricloader, fabric-api, fabric-permissions-api-v0 }`

* [ ] `BridgeConfig` 可正确加载/保存 `config/tsbridge.json`，包含 spec 中列出的全部字段

* [ ] `PairingStore` 线程安全，可持久化到 `config/tsbridge/pairings.json`，提供 bind/unbind/getTsIdentity/getMcUuid

* [ ] `TsQueryClient` 可连接 ServerQuery 并自动重连，配置错误时抛出明确异常

* [ ] `BridgeChannelManager` 启动时检查/创建桥接频道，并将 TS3AudioBot 移入

* [ ] `TsVoicechatPlugin` 正确实现 `VoicechatPlugin` 接口，`getPluginId` 返回 `svctsbridge`，并通过 `fabric.mod.json` 的 `voicechat` entrypoint 加载

* [ ] `MicrophonePacketEvent` 监听器捕获 Opus 帧，按 sender UUID 路由到 `McToTsForwarder`

* [ ] `PlayerConnectedEvent` / `PlayerDisconnectedEvent` 用于维护在线 MC 玩家表

* [ ] `McAudioInjector` 使用 `LocationalAudioChannel` + `AudioPlayer` 将 TS 音频注入到目标玩家附近

* [ ] `TsAudioTransport` 接口已定义，包含 start/stop/sendMcAudio/setOnTsAudioListener

* [ ] `Ts3AudioBotTransport` 默认实现通过 HTTP/JSON-RPC 推送音频、通过 WebSocket/长轮询订阅 TS 音频

* [ ] `McToTsForwarder` 异步消费队列，失败重试 + 背压丢弃

* [ ] `AudioCodec` 处理 Opus↔PCM 转换，采样率对齐（48kHz mono 20ms = 960 samples）

* [ ] `PairingManager` 生成 6 位配对码，5 分钟过期，绑定后立即生效双向转发

* [ ] TS 用户在桥接频道发送 `!pair <code>` 可触发配对

* [ ] 命令通过 `CommandRegistrationCallback` + Fabric Command API v2 注册，支持 `pair`/`unpair`/`status`/`reload`/`link`

* [ ] `reload`/`link` 命令通过 `fabric-permissions-api-v0` 校验 `tsbridge.admin` 权限

* [ ] `ServerLifecycleEvents.SERVER_STARTED` 按顺序启动各组件，`SERVER_STOPPING` 逆序关闭，每个组件独立 try/catch

* [ ] 任一组件启动失败时降级运行，SVC 部分仍可用

* [ ] `gradle :teamspeak&svcbridge:build` 可成功产出可部署 Fabric mod jar

* [ ] 本地 Fabric 测试服可加载 mod，`/tsbridge status` 输出正常（不要求真实 TS 连接）

* [ ] 未修改 `simple-voice-chat-26.2/` 任何源码

