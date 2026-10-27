# teamspeak-svc 0.9.0 修复 - 独立评审

- [x] CP-R1: 构建可交付
  - **Type**: `rule`
  - **Covers**: AC-1
  - **Evidence**: `build-final.log` = `BUILD SUCCESSFUL in 16s` / `EXIT:0`；`build/libs/teamspeak-svc-0.9.0+26.3.jar` 存在，`fabric.mod.json` 可解析（version 0.9.0+26.3、authors、depends.voicechat>=2.6.20、suggests.modmenu）。

- [x] CP-R2: 群组密码链路
  - **Type**: `rule`
  - **Covers**: AC-2
  - **Evidence**: `GroupChannelMapper.java:48-53` 按 `getId()` 比较；`:34-45` pending 缓存；`:69-75` 仅群组变化时失效；无 `group != lastGroup`。窄竞态见 R1-F9（已修复）。

- [x] CP-R3: 命令与 GUI
  - **Type**: `rule`
  - **Covers**: AC-3
  - **Evidence**: `tsvoice.status` 6 占位符 / 6 实参；`ConfigScreen.java:164-171,236,284-285` 密码掩码/回填/保存；两个入口均 `setParent`。信标下密码框锁定见 R1-F3（已修复）。

- [x] CP-R4: 断开语义与配置生效
  - **Type**: `rule`
  - **Covers**: AC-4
  - **Evidence**: `userDisabled` 覆盖自动激活路径；`TsAudio.refresh` 五项读取；`AudioChannelMixin` 阈值 `toleranceMs/20`（0=关闭）。`manualConnect` 未连接时卡 sessionRunning 见 R1-F4（已修复）。

- [x] CP-R5: AEC 有效且无泄漏
  - **Type**: `rule`
  - **Covers**: AC-5
  - **Evidence**: `AecProcessor.java:86-106` 同步处理且写回原数组；参考 1:1；`AudioPipeline` 类锁 + `webrtc.close()`；旧类已删。构造失败 dispose 见 R1-F6（已修复）。

- [x] CP-R6: 闪避/混响接线与线程安全
  - **Type**: `rule`
  - **Covers**: AC-6
  - **Evidence**: END_CLIENT_TICK 唯一注册点；无 ForkJoin/join；世界查询主线程；OpenAL 仅 AL 线程。SVC states 并发写见 R1-F2（已修复）。

- [x] CP-R7: 协议健壮性
  - **Type**: `rule`
  - **Covers**: AC-7
  - **Evidence**: QuickerLz/TCP/未知包类型通过；R1-F1 修复后经 RFC 8032 自检 + 加法/倍点/逆元/标量乘一致性数值验证（`ED25519 ADD TESTS PASSED`）；R2/R3 复验通过。

- [x] CP-R8: 敏感数据与配置安全
  - **Type**: `rule`
  - **Covers**: AC-8
  - **Evidence**: `ConfigFileUtil` POSIX 600 + 原子写；`GroupInfoPayload.toString` 掩码；无明文密码日志。

- [x] CP-R9: 文档一致
  - **Type**: `rule`
  - **Covers**: AC-9
  - **Evidence**: README 0.9.0 命令/配置/产物一致；ROADMAP 0.9.0；v0.4-audio spec 回填。HUD 声明见 R1-F7（已修正文档）。

- [x] CP-R10: 修复质量
  - **Type**: `rubric`
  - **Covers**: AC-10
  - **Scale**: 1-5
  - **Anchors**: 1 = 引入新回归或崩溃；3 = 功能正确但改动面过大或缺少降级；5 = 功能正确、改动聚焦、保留降级路径且有验证证据
  - **Pass Threshold**: >= 4
  - **Evidence**: R1 3/5（引入 Ed25519 回归、残留跨线程写）；R2 4/5（两个 actionable 已修复，保留降级路径）；R3 4/5（四项加固落地、构建与字节码一致；扣分因无运行时自动化测试与残余极窄竞态）。

## Review History

### 0.9.8 换服后频道不同步修复（用户截图反馈）
- **Result**: `pass`（构建通过；根因有代码证据）
- **Root Cause**: 生产连接路径只发 `clientlist -uid`，**从未发 `channellist`**（仅自测工具发送）；新会话的频道名→cid 表为空/不完整，`GroupChannelMapper` 查不到主频道 `MINECRAFT` 就每 2s 打一条日志并 return，客户端停在 TS 默认频道（CS2）。截图活动日志里 Brokeyuan 有“离开并前往 MINECRAFT”，YZQ 没有对应记录，符合同一会话下主频道解析失败。
- **Changes**: 连接成功后主动 `channellist`；主频道缺失时每 10s 刷新 `channellist` + `channelfind` 兜底；空主频道名保护；`/tsvoice status` 增加 `sent=`。
- **Evidence**: `build-0.9.8.log` = `BUILD SUCCESSFUL` / `EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.8+26.3.jar`。

### 0.9.7 彻底移除定位音频（用户要求）
- **Result**: `pass`（删除性改动 + 构建通过）
- **Changes**: 删除 `OcclusionCache`、多普勒（AL_VELOCITY）、`PlayerVelocityTracker` 位置/速度追踪；新增 `OnlinePlayers` 仅维护在线玩家 UUID/名字用于同服识别；TS 音频保持 `GroupSoundPacket` 等响度，无距离衰减/定位/多普勒。
- **Evidence**: `build-0.9.7.log` = `BUILD SUCCESSFUL` / `EXIT:0`；`grep` 确认无 `PlayerVelocityTracker/OcclusionCache/LocationSoundPacket/AL_VELOCITY/getMcUuidForChannel/ghostPositions` 引用；产物 `build/libs/teamspeak-svc-0.9.7+26.3.jar`。

### 0.9.6 诊断增强（同频道无声排查）
- **Result**: `pass`（仅诊断计数/日志）
- **Changes**: `/tsvoice status` 增加 `injected=`；未知 clid 语音帧与 SVC `ClientVoicechat` 未就绪各告警一次。
- **Evidence**: `build-0.9.6.log` = `BUILD SUCCESSFUL` / `EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.6+26.3.jar`。
- **说明**: SVC 的“音量增益 0%”= 1.0 倍正常音量，不是静音；同频道无声的 48 格限制来自本 mod 的 `LocationSoundPacket` 路径，0.9.5 已移除。

### 0.9.5 同服玩家身份/听不见修复（用户截图反馈）
- **Result**: `pass`（定向修复；构建通过）
- **Root Cause**:
  - 先于本客户端进入 TS 的玩家只有 `clientlist`（无 `client_description`），`mcUuid` 永远为 null → 派生 Ghost 重复出现在 SVC 列表；语音被路由到 Ghost 的独立 UUID（真实玩家的音量/静音设置不生效）。
  - 同服玩家使用 `LocationSoundPacket`（48 格定位音频），距离 >48 格或跨维度时 SVC 直接丢弃 → 只有 MC 玩家的频道里听不见。注意 SVC 的“音量增益 0%”是 1.0 倍（正常音量），不是静音。
  - `[TS] ` + `[MC] name` 造成双前缀。
- **Changes**: TS 昵称 `[MC] 名字` 反查本服在线玩家 UUID（覆盖先入服玩家）；UUID 晚到时迁移派生 Ghost；TS 音频统一 `GroupSoundPacket` 等响度；去掉双前缀；在线玩家表每 tick 从 `ClientPacketListener` 刷新（含其他维度）。
- **Evidence**: `build-0.9.5.log` = `BUILD SUCCESSFUL` / `EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.5+26.3.jar`，`version=0.9.5+26.3`。实机验证：同服两玩家同频道应互相可闻，SVC 列表不再出现重复 `[TS]` 条目。

### 0.9.4 低延迟目标（用户目标）
- **Result**: `pass`（定向改动；`getBufferSize` 已用 javap 确认存在于 SVC 26.3）
- **Changes**: 新增 `lowLatencyMode`（默认 true）：TS 会话期间把 SVC 扬声器缓冲从 `outputBufferSize`（默认 5 包≈100ms）压到 2 包（≈40ms）；关闭时完全使用 SVC 配置。发送侧保持 SVC 原生 RNNoise + Speex AGC；`/tsvoice status` 显示 `OFF (SVC native RNNoise)`。
- **Evidence**: `build-0.9.4.log` = `BUILD SUCCESSFUL` / `EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.4+26.3.jar`，`version=0.9.4+26.3`；`javap` 确认 `ALSpeakerBase.getBufferSize()` 存在。实机延迟/降噪听感待 `manual-checklist.md`。

### 0.9.3 接收链路根因修复（用户反馈）
- **Result**: `pass`（定向修复 + 运行时反射签名已用 javap 对照 SVC 26.3）
- **Root Cause**: 旧实现 `decoder.decode(opus)` → `ClientStaticAudioChannel.play(pcm)`；SVC 的 `ClientAudioChannelImpl.play` 会构造 `sequenceNumber=-1` 的裸 PCM `SoundPacket`，`AudioChannel` 判定 `isFromClientAudioChannel()` 后直接写扬声器，绕过抖动缓冲、序列重排与 `decoder.decode(data, packetsToCompensate+1)` 的 Opus FEC/PLC；同时 `Ts3Connection.acceptVoice` 直接丢弃乱序包。任何 UDP 丢包/乱序都变成咔哒/断音。
- **Changes**: TS 编码 Opus 包 + 发送者 16 位语音序号 → `GroupSoundPacket` / `LocationSoundPacket` → `ClientVoicechat.processSoundPacket`；连接层不再丢弃乱序包；新增序号回绕与发送者重连复位；移除裸 PCM 通道与每 Ghost 解码器。
- **Evidence**: `build-0.9.3.log` = `BUILD SUCCESSFUL` / `EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.3+26.3.jar`，`version=0.9.3+26.3`；`javap` 确认 SVC 26.3 中 `processSoundPacket(SoundPacket)` 与两个包构造器签名一致；源码已无 `createStaticAudioChannel/createDecoder/acceptVoice`。实机听感待 `manual-checklist.md` 验证（重点：丢包/多人同时说话）。

### 0.9.2 清晰度优先修正（用户反馈）
- **Result**: `pass`（仅默认值/配置迁移，无评审阻断项）
- **Changes**: 音频增强默认全关（混响/Ducking/AEC/AGC/WebRTC 降噪），发送侧使用 SVC 原生降噪；移除抖动缓冲覆盖（`AudioChannelMixin` + `networkToleranceMs`），接收侧回到 SVC 原生配置；旧配置一次性迁移（`configVersion` 0→1）；混响关闭时跳过遮挡/水下滤波与缓存计算。
- **Evidence**: `build-0.9.2.log` = `BUILD SUCCESSFUL` / `EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.2+26.3.jar`，`version=0.9.2+26.3`；jar 内 mixins 无 `AudioChannelMixin`，源码无 `networkToleranceMs` 引用；`ModConfig` 默认 `reverbEnabled=false`、`duckingEnabled=false`、`denoiseMode=OFF`。听感仍待 `manual-checklist.md` 实机确认。

### Review R1
- **Result**: `fail`
- **Evidence**: 独立 reviewer 逐文件核验 + javap 对照 SVC 2.6.25 与 MC 26.3 符号 + 构建产物检查。CP-R1..R6/R8/R9 通过；CP-R7 失败（F1）；CP-R10 3/5。
- **Findings**:
  - **R1-F1**（actionable，中）：`Ed25519Math.add` 对“同 x、y 相反”返回恒等元，结果错误（正确为 (0, P-1)）；真正的 a==-b 未被该分支覆盖，但通用公式可正确处理。
  - **R1-F2**（actionable，中）：SVC `ClientPlayerStateManager.states` 是普通 HashMap，被 TS 接收线程/会话线程/语音线程写入，主线程 UI 并发读。
  - R1-F3（advisory，低）：信标锁定下密码框不可编辑，与 manual-checklist 不符。
  - R1-F4（advisory，中低）：SVC 未连接时 `manualConnect` 留下 `sessionRunning=true`，进服后不自动连接。
  - R1-F5（advisory，低）：`stopSession` 不释放 Ghost/解码器/AEC 参考。
  - R1-F6（advisory，低）：`AecProcessor`/`WebRtcNative` 构造中 `applyConfig` 失败泄漏 APM。
  - R1-F7（advisory，低）：README 声称 HUD，实际无 HUD 渲染。
  - R1-F8（advisory，中高，超范围）：`Ts3Identity.leadingZeroBits` 位序与 TS3 标准（MSB 前导零）疑似不一致，需真实服务器验证后再改。
  - R1-F9（advisory，低）：群组切换与 `onGroupInfo` 的窄竞态可覆盖刚写入的密码。
  - R1-F10（advisory，低）：残余清理项（自测类入 jar、死语言键、混响未用字段、BlockSoundProperty 分配、TsTransport javadoc）。
- **Remediation**: R1-F1..F7、F9、部分 F10 已修复；F8 留作独立验证项。

### Review R2
- **Result**: `pass`
- **Evidence**: 独立 reviewer 复核 R1-F1..F10：F1（Ed25519）数值验证通过；F2（SVC states）全部反射写主线程化 + 并发快照；F3-F7/F9 验收点成立；F10 部分清理完成。`build-remediation.log` BUILD SUCCESSFUL / EXIT:0。CP-R10 = 4/5。
- **Findings**: 无 actionable；advisory R2-F1（群组 pending 交接窄竞态）、R2-F2（knownStateIds clear/addAll 窗口）、R2-F3（会话代际竞态）、R2-F4（identity 位序，沿用 R1-F8）、R2-F5（未用字段）、R2-F6（createGhost decoder 泄漏）。

### Review R3
- **Result**: `pass`
- **Evidence**: 针对 R2 advisory 的四项加固（群组 pending 每 tick 消费、knownStateIds 原子替换、sessionGeneration 代际、decoder 关闭）逐行 + javap 复核；`build-r3.log` BUILD SUCCESSFUL / EXIT:0；class 与 jar SHA256 一致；AEC/混响/协议路径未回归。CP-R10 = 4/5。
- **Findings**: 无 actionable；advisory R3-F1（pending 消费条件仍可收窄）、R3-F2（connect 成功提交的 TOCTOU）、R2-F4/R2-F5 沿用。
- **Post-R3 hardening（静态验证）**: 已在 R3 后修复 R3-F1（pending 按 id 不匹配时消费）、R3-F2（代际检查与 client/state 提交在同一把类锁内），并补上“运行中配置变更触发会话重建”（evaluateActivation）。最终构建 `build-final2.log` 通过。上述为 advisory 加固，不改变 R3 的 pass 结论。

### 0.9.1 体验修正（评审后用户反馈）
- **Result**: `pass`（仅 UX/调参，无评审阻断项）
- **Changes**: 移除 TS 配置界面重复的“音频增强”入口（入口保留在 SVC 语音界面 Extra）；混响湿声压低约 12dB（early ≤1.5、late ≤2.5、EAX 增益 0.25、RT60 ≤8s）；删除 `tsvoice.gui.audio_open` 死键。
- **Evidence**: `build-0.9.1.log` = `BUILD SUCCESSFUL` / `EXIT:0`；产物 `build/libs/teamspeak-svc-0.9.1+26.3.jar`，`fabric.mod.json` version=`0.9.1+26.3`，jar 内无 `audio_open` 键，自测类已排除。实机听感仍待 `manual-checklist.md` 验证。
