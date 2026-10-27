# SVC 客户端功能对照修复（Ghost 语义 + 音量/静音生效）Implementation Plan

## Repository Research

### 发现 1：Ghost 方向性错误（用户报告的核心问题）
- 现状：`TsAudioInjector.onTsFrame`（`teamspeak-svc/.../TsAudioInjector.java`）对**所有**说话的 TS 客户端建 `[TS]` Ghost——包括**其他装 mod 的 MC 玩家**（他们广播 `client_description=tsvc-uuid:<uuid>`，`Ts3VoiceClient.getMcUidOf` 可识别）。
- 后果：两个 MC 玩家经 TS 互通时，彼此在对方游戏里显示为 `[TS] xxx` 机器人；同服玩家甚至与自己原生的真实 PlayerState **重复出现**。
- SVC 原生语义（用户指出的正确方向）：MC 侧连来的 = 普通玩家；TS 侧连来的 = TS 用户。

### 发现 2：单玩家音量/静音失效的根因（SVC 音量机制链条）
- plugin 通道最终走原生播放：`ClientStaticAudioChannelImpl.play()` → `GroupSoundPacket(id,id,pcm,category)` → `ClientVoicechat.processSoundPacket` → 原生 `AudioChannel` → `AudioChannel.writeToSpeaker`（`common-client/.../AudioChannel.java:184-191`）：
  ```java
  if (USERNAME_CACHE.has(uuid))      → PLAYER_VOLUME_CONFIG.getVolume(uuid)  // 玩家个人音量（含静音=0）
  else if (category != null)         → CATEGORY_VOLUME_CONFIG
  else                               → OTHER_CATEGORY
  ```
- `USERNAME_CACHE` 只在**收到网络 PlayerState 包**时更新（`ClientPlayerStateManager.java:53,68`）。
- 我们的 Ghost 是反射直接改 states map（`ClientStateReflector.injectGhost`）——**从不触碰 USERNAME_CACHE** → `has(uuid)==false` → 个人音量永不应用 → **音量调节/静音无效**。群组/音量列表里的名字显示也依赖它。
- 附带确认（自动生效、无需修改的功能）：全局禁用（`processSoundPacket` 入口检查）、M 键麦克风静音（我们拦在激活后的发送路径）、本地录音（注入也走原生 AudioChannel）、输出静音、群组隔离（TS 频道天然）。

## Approach
- **Chosen**：按"说话者身份"分流注入（纯 TS 用户 / 跨服 MC 玩家 / 同服 MC 玩家三种通道键与显示策略），并在所有注入路径补 USERNAME_CACHE。不采用"全部伪装成普通玩家"（TS 用户需要 [TS] 标识区分）也不采用"全部 Ghost"（重复显示+音量语义破坏）。
- 分流依据：`getMcUuidOf(clid)`（description 广播）+ 本服 states 是否已含该 UUID。

| 说话者 | 音频通道 ID | states/显示 | 名字 | 皮肤 | 音量 |
|---|---|---|---|---|---|
| 纯 TS 用户 | 派生 UUID（现状） | 注入 `[TS] 昵称` | 昵称 | 内置 TS 皮 | USERNAME_CACHE 补齐后原生生效 |
| 跨服 MC 玩家 | **真实 MC UUID** | 注入 `MC名`（**无前缀**，如普通玩家） | MC 名 | cross-server 真皮管线 | 同上 |
| 同服 MC 玩家 | **真实 MC UUID** | **不注入**（原生已有） | 原生 | 原生 | 原生自动生效 |

## Files and Modules
- `teamspeak-svc/.../TsAudioInjector.java`：按身份分流（通道键/显示名/皮肤注册三路）；同服玩家跳过 states 注入
- `teamspeak-svc/.../ClientStateReflector.java`：`injectGhost` 增加"无前缀玩家模式"；新增 `hasLocalState(uuid)`；新增 `updateUsernameCache(uuid,name)`（反射 `UsernameCache.updateUsernameAndSave`）
- `teamspeak-svc/.../GhostSkins.java`：注册键从"派生 ghostUuid"改为传入的通道 UUID（真实或派生）；`skinFor` 判定集合同步
- `teamspeak-svc/.../TsVoiceManager.java`：`handleVoiceFrame` 传 mcUuid + 本服判定结果

## Implementation Steps
- [ ] 1. ClientStateReflector：无前缀注入模式 + hasLocalState + USERNAME_CACHE 反射更新
- [ ] 2. TsAudioInjector：三路分流重构（键统一为 channelUuid；同服跳过 states；全部路径补 cache）
- [ ] 3. GhostSkins：键适配（cross-server 直接用真实 UUID；内置皮仍按派生 UUID）
- [ ] 4. 构建 + 协议自测回归（Ts3VoiceSelfTest）
- [ ] 5. testserver 侧无改动；交付 jar

## Dependencies and Considerations
- 同服/跨服判定用反射 states map（ClientPlayerStateManager），失败时按跨服处理（保守：宁可重复显示也不丢音频）
- USERNAME_CACHE 反射（`VoicechatClient.USERNAME_CACHE` 静态字段 + `updateUsernameAndSave`）失败仅降级为音量不生效，不影响出声
- 皮肤 mixin 判定集合需与注入键保持一致（activeGhosts 含真实 UUID 与派生 UUID 两类）

## Validation
- [ ] 构建成功
- [ ] Ts3VoiceSelfTest 对真实 TS 服务器回归通过
- [ ] 用户联调：同服两 mod 玩家互看 = 普通玩家（无 [TS]、无重复）；TS 用户 = [TS] Ghost；音量界面调某人音量到 0 → 该人静音生效

## Risks
- SVC 2.6.25 内部类名与 26.2 源码不一致（UsernameCache 路径）→ 反射 try/catch 降级 + 一次性 warn
- 同服判定误判（states 延迟）→ 判定失败方向安全（重复显示可自愈：原生状态包到达后 cache/states 以原生为准）

## Result
- 构建成功：teamspeak-svc-0.3.2+26.3.jar
- Ts3VoiceSelfTest 对真实 TS 服务器回归 PASSED（连接/语音帧收发正常）
- 三分流实现：同服玩家(真实UUID/不注入状态) / 跨服玩家(真实UUID/无前缀注入/真皮管线) / TS用户([TS] Ghost/内置皮)
- USERNAME_CACHE 补齐（updateUsernameAndSave 反射）——单玩家音量/静音/名字显示生效
- 用户联调：待执行（同服互看=普通玩家、TS用户=[TS]、音量调0=静音三项验证）
