# TS Bridge v2 质量检查清单

## 架构合规
* [ ] `Ts3NativeTransport` 实现 `TsAudioTransport` 接口，start/stop/sendMcAudio/setOnTsAudioListener/isStarted 全部覆盖
* [ ] `Ts3AudioBotTransport` 标记为 `@Deprecated`，但保留为降级回退方案
* [ ] 移除 `build.gradle` 中的 `org.java-websocket:Java-WebSocket` 依赖（仅当确认 `Ts3AudioBotTransport` 不再需要时）
* [ ] `BridgeConfig.java` 新增 `tsVoicePort`（默认 9987），删除 `botApiUrl`/`botApiToken`
* [ ] `fabric.mod.json` 依赖列表更新（若移除 websocket 则同步移除）

## 永久群组
* [ ] `TsBridgeMod.SERVER_STARTED` 中调用 `VoicechatServerApi.createStaticAudioChannel(channelId, level)` 创建永久频道
* [ ] 频道 UUID 固定派生自 `UUID.nameUUIDFromBytes("tsbridge-permanent".getBytes())`
* [ ] 频道名称通过 `BridgeConfig.bridgeChannelName` 配置
* [ ] 频道在 SERVER_STOPPING 时关闭（flush + release）
* [ ] 频道在 mod 中持有强引用，不被 GC 销毁

## Ghost Player 注入
* [ ] 每个在线 TS 用户（在桥接频道中）对应一个 Ghost Player
* [ ] Ghost Player 名称格式：`[TS] <tsUsername>`
* [ ] Ghost Player 通过反射构造 `PlayerStatePacket` 注入到 SVC 客户端
* [ ] TS 用户加入时（ClientJoinEvent / ClientMovedEvent）注入 Ghost Player
* [ ] TS 用户离开时（ClientLeaveEvent / 移出频道事件）移除 Ghost Player
* [ ] TS 用户说话时 Ghost Player 的 `speaking` 状态为 true，停止说话 500ms 后恢复 false
* [ ] Ghost Player 的 UUID 固定派生自 `"ts-ghost-" + tsUniqueId`
* [ ] 所有 Ghost Player 资源在 SERVER_STOPPING 时释放

## MC→TS 存在感同步
* [ ] MC 玩家加入/离开 `StaticAudioChannel` 时触发 bot 昵称更新
* [ ] bot 昵称格式：`MCBridge (N online)`
* [ ] 频道描述列出在线 MC 玩家名（最多 16 人）
* [ ] 频道描述更新带 3 秒 debounce
* [ ] ServerQuery 不可用时昵称/描述更新静默跳过不报错

## 原生 TS3 音频客户端
* [ ] `Ts3NativeTransport.start()` 通过 ServerQuery 获取语音 token
* [ ] 实现 TS3 语音协议握手（init/initack/clientinit/clientinitiv）
* [ ] `sendMcAudio()` 将 Opus 帧封装为 TS3 语音包并通过 UDP 发送
* [ ] UDP 接收线程持续监听 TS 服务器语音包，解析后回调 `onTsAudioListener`
* [ ] 连接断开时自动重连（指数退避）
* [ ] 异常不抛到调用方（所有方法内部 try/catch）

## 混音
* [ ] `McAudioMixer` 提供 `mixFrame(senderUuid, opusData)` 用于收集每帧
* [ ] 混音线程按固定间隔（20ms）执行混音 + 发送
* [ ] 单人时透传 Opus（不编解码）
* [ ] 多人时 OpusDecode → 饱和加法 → OpusEncode
* [ ] 无声时发送静音帧或跳过（可配置）
* [ ] OpusEncoder 在 reconfiguration 后正确重置

## 命令增强
* [ ] `/tsbridge status` 新增输出：Ghost Players 数、混音器状态、MC 玩家列表、TS 用户列表
* [ ] `/tsbridge pair` 支持无参数自动配对模式（可选开启）
* [ ] `/tsbridge channel` 新命令：手动管理 ghost player（kick/unban，admin 权限）

## 容错与生命周期
* [ ] `Ts3NativeTransport` 启动失败时回退到 `Ts3AudioBotTransport`（若已配置）
* [ ] 两个 transport 都不可用时，mod 其余功能正常（仅音频不可用）
* [ ] SERVER_STOPPING 逆序关闭：forwarder → mixer → native transport → audio bot transport → ghost injector → static channel → query → pairings
* [ ] 每个组件独立 try/catch（同 v1 风格）

## 构建验证
* [ ] `gradle :teamspeak&svcbridge:build` 产出 jar
* [ ] 本地 Fabric 测试服加载 mod 不崩溃
* [ ] `/tsbridge status` 输出完整（含新字段）
* [ ] 未修改 `simple-voice-chat-26.2/` 源码
* [ ] 未修改 `voicechat-discord-main/` 源码
* [ ] `PairingManager.TsPairingListener` 覆盖所有 TS3Listener 方法（包括 `onChannelMoved`）

## 文档
* [ ] 更新 README.md：移除 TS3AudioBot 部署步骤，新增原生 TS3 语音配置说明
* [ ] 说明 `botApiUrl`/`botApiToken` 字段变更（标记 deprecated）
