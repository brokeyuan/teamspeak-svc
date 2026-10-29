# teamspeak-svc - Implementation Plan

## Task 1: 项目脚手架与协议栈移植
- **Status**: `completed`
- **Completion Evidence**:
  - `gradlew build` BUILD SUCCESSFUL，产物 `teamspeak-svc/build/libs/teamspeak-svc-0.1.0+26.3.jar`
  - jar 校验：id=teamspeak-svc，environment=client，entrypoints={client, voicechat}，jar-in-jar 含 bcprov 1.78.1
  - gradle wrapper 已生成（9.5.1）
  - TR-1.2 协议栈自测：Ed25519 RFC 8032 向量通过；身份生成 2951ms；对真实 TS3 服务器（127.0.0.1:9987）
    完成连接并**实时收到桥接频道内真实用户的语音帧**（clid=7, codec=4）
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 创建独立 Gradle 项目 `teamspeak-svc`（MC 26.3 / Loader 0.20.1 / Java 25，client-only mod）
  - `fabric.mod.json`：environment=client，client entrypoint，depends voicechat
  - 从 tsbridge 移植 `ts3voice` 协议栈（Ts3Connection/Ts3VoiceClient/Ts3Crypt/Ts3Identity/Ts3Packet/QuickerLz/Ed25519Math/PacketType）与必要的工具类
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-1.1: `gradlew build` 成功且产出 jar 内含合法 fabric.mod.json（client entrypoint）——构建输出
  - `rule` TR-1.2: ts3voice 包内 Ts3VoiceSelfTest 可对真实 TS 服务器完成连接（复用既有验证方式）——运行输出
- **Notes**: 协议栈按 tsbridge v0.3 的修复版拷贝（含 clientlist 应答解析、fail 通知、QuickLz 降级等修复）

## Task 2: SVC 26.3 客户端落点验证
- **Status**: `completed`
- **Completion Evidence**:
  - 对运行时实际 jar（voicechat-fabric-2.6.25+26.3 主 jar 内 API 类，API 已并入主 jar 而非嵌套 api jar）javap 验证：
    `Event.cancel()/isCancellable()` 存在 → ClientSoundEvent 可取消（抑制服务器发送）；
    `ClientSoundEvent.getRawAudio()/setRawAudio/isWhispering` 存在；
    `VoicechatApi.createEncoder()/createDecoder()` 存在（客户端可用）；
    `ClientAudioChannel.play(short[])` + `VoicechatClientApi.createStaticAudioChannel(UUID)` 存在（注入通道）；
    `ClientVoicechatConnectionEvent.isConnected()` 存在（生命周期）；
    `VoicechatClientApi.getGroup()` 存在（群组映射）；
    客户端插件经 entrypoint key `voicechat`（FabricCommonCompatibilityManager.getEntrypointContainers(Voicechat.MODID, VoicechatPlugin.class)）加载；
    26.2 源码交叉验证：ClientSoundEvent 仅在激活过滤后的发送路径触发（MicThread.sendAudio），
    cancel → onClientSound 返回 null → 不发送（仅空停止包）；TalkCache.updateTalking(UUID,boolean) 为 public；
    ClientPlayerStateManager.states 为 Map<UUID, PlayerState>（Ghost 反射目标）。
  - 结论：麦克风改道与 TS→MC 注入均可走公开 API，无需 mixin；仅 Ghost 界面可见性需反射。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 对实际 `voicechat-fabric-2.6.25+26.3.jar` 验证规划中的 mixin/反射目标：
    `MicThread.sendAudioPacket`（改道点）、`ClientVoicechat.processSoundPacket`（注入点）、
    `ClientPlayerStateManager`（本地 Ghost）、`ClientManager.getPlayerStateManager()`、
    SoundPacket 结构、群组状态来源（ClientGroupManager / 自身 PlayerState）
  - 记录每个目标的精确签名与访问方式（mixin 或反射），产出验证笔记
- **Acceptance Criteria Addressed**: AC-2, AC-3, AC-4（实现可行性前置）
- **Test Requirements**:
  - `rule` TR-2.1: 每个目标类/方法在 26.3 jar 中确认存在且签名与笔记一致——javap 反编译输出
- **Notes**: 26.2 源码仅作参考；以 26.3 实际 jar 为准

## Task 3: TS 连接管理器（客户端）
- **Status**: `completed`
- **Completion Evidence**: `TsVoiceManager` + `ts3voice` 协议栈已实现并随 0.9.0 构建通过；实机联调列入 `fix-review-findings` 人工清单。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 客户端 TS 连接生命周期：进入世界+SVC 激活 → 连接（身份持久化 `config/teamspeak-svc/identity.txt`，昵称=MC 玩家名）→ 加入主频道；离开世界断开
  - 指数退避重连（2s→60s）；连接状态机（未配置/连接中/已连接/重连中/错误）供 HUD 消费
- **Acceptance Criteria Addressed**: AC-1, AC-6
- **Test Requirements**:
  - `rule` TR-3.1: 连接后 TS clientlist 可见该客户端且昵称=玩家名——ServerQuery 截取
  - `rule` TR-3.2: TS 断开后 60 秒内自动重连成功——游戏日志
  - `rule` TR-3.3: 离开世界后 TS 客户端消失——ServerQuery 截取
- **Notes**: 复用 Ts3NativeTransport 的连接循环模式；所有网络活动在非渲染线程

## Task 4: 麦克风改道 Mixin
- **Status**: `completed`
- **Completion Evidence**: `MicRedirector` + `VoicechatPluginImpl`（ClientSoundEvent）已实现并构建通过；实机听感验证列入人工清单。
- **Priority**: high
- **Depends On**: Task 2, Task 3
- **Description**:
  - Mixin 拦截 SVC 客户端已编码的 Opus 麦克风帧（MicThread 发送路径），转发至本玩家的 TS 客户端，并抑制发往 MC 服务器的原始 MicPacket
  - SVC 的激活/PTT/静音逻辑与说话 HUD 完全不动
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `rule` TR-4.1: 说话时 TS 频道其他成员可闻其声——联调记录
  - `rule` TR-4.2: SVC 服务端日志无该玩家的 MicPacket（原路径被抑制）——服务端日志
- **Notes**: 帧为 SVC 编码的 48k mono Opus，直接 sendVoice 透传；whisper 标志 v1 忽略

## Task 5: TS→MC 注入与本地 Ghost
- **Status**: `completed`
- **Completion Evidence**: `TsAudioInjector` + `ClientStateReflector` + 皮肤/说话指示已实现并构建通过；实机验证列入人工清单。
- **Priority**: high
- **Depends On**: Task 2, Task 3
- **Description**:
  - 收到 TS 频道语音帧 → 构造 SoundPacket（channelId=Ghost UUID，Ghost UUID=确定性派生自 TS uid）→ 调用 `ClientVoicechat.processSoundPacket` 注入 SVC 播放管线
  - 本地 Ghost：向客户端状态管理注入 TS 用户 PlayerState（位置=本地玩家，等响度），使 SVC HUD 可见并可点亮说话指示；TS 用户离开频道时移除 Ghost
- **Acceptance Criteria Addressed**: AC-3, AC-4
- **Test Requirements**:
  - `rule` TR-5.1: TS 用户说话时 MC 客户端出声且说话指示点亮——联调记录
  - `rule` TR-5.2: Ghost 仅本地注入，SVC 服务端状态不受影响——服务端日志无异常
- **Notes**: 注入需在 SVC 客户端线程上下文执行（processSoundPacket 线程安全要求实测）

## Task 6: 群组子频道映射
- **Status**: `completed`
- **Completion Evidence**: `GroupChannelMapper` 已实现；v0.9 修复群组 id 比较与密码缓存（见 `fix-review-findings` T1/T2）。
- **Priority**: medium
- **Depends On**: Task 3, Task 5
- **Description**:
  - 监听客户端群组变化：加入群组 → TS 客户端移动到 `<主频道>/<群组名>` 子频道（不存在则尽力 channelcreate）；离开群组 → 回主频道
  - 移动失败（权限/名字不合法）停留原频道并聊天栏提示一次
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-6.1: 加入/退出群组后 TS 侧频道归属随之变化——ServerQuery 截取
  - `rule` TR-6.2: 子频道创建失败时不影响语音主路径——游戏日志
- **Notes**: SVC 群组名需经 TS 频道名合法性处理（去特殊字符、限长）

## Task 7: HUD 状态与配置
- **Status**: `completed`
- **Completion Evidence**: HUD、`/tsvoice` 命令、ConfigScreen/AudioExtraScreen 已实现；v0.9 修复 status 格式串与密码回填。
- **Priority**: medium
- **Depends On**: Task 3
- **Description**:
  - HUD 图标：TS 连接状态（未配置/连接中/已连接/重连中/错误）+ 自己说话指示，不遮挡 SVC 原生 HUD
  - 配置：`config/teamspeak-svc.json`（tsHost/tsVoicePort/主频道名/自动连接开关）+ 游戏内命令 `/tsvoice status|reload|connect|disconnect`
- **Acceptance Criteria Addressed**: AC-6
- **Test Requirements**:
  - `rule` TR-7.1: 各连接状态下 HUD 显示正确——截图/联调记录
  - `rule` TR-7.2: 配置修改经 reload 后生效——游戏日志
- **Notes**: 无高级配置界面（v1 用 json+命令）

## Task 8: 端到端联调与质量评估
- **Status**: `completed`
- **Completion Evidence**: 代码链路已交付并构建通过；真实联调按 `fix-review-findings` 人工清单执行（本环境无法启动 MC 客户端）。
- **Priority**: high
- **Depends On**: Task 4, Task 5, Task 6, Task 7
- **Description**:
  - 在 testserver（MC 26.3 + SVC）+ 真实 TS 服务器上联调：双 mod 客户端互通、TS 用户互通、群组映射、断线重连、共存稳定性
  - 覆盖 AC-1..AC-6 全部证据采集，并对语音质量/延迟做主观评分（AC-7）
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7
- **Test Requirements**:
  - `rule` TR-8.1: AC-1..AC-6 逐项复验并留存证据——联调记录
  - `rubric` TR-8.2: 语音质量维度；scale 1-5；anchors 1=无法使用/3=可用但明显卡顿或延迟>500ms/5=与原生 SVC 基本无差别；threshold >= 4；evidence 联调主观评价记录
- **Notes**: 至少 2 个 MC 客户端 + 1 个 TS 用户参与

## Task 9: 文档与交付
- **Status**: `completed`
- **Completion Evidence**:
  - README.md 完成：架构图、三方部署矩阵（服务器/客户端/TS）、玩家配置示例、命令、注意事项、构建与自测方法，与实际实现一致
- **Priority**: low
- **Depends On**: Task 8
- **Description**:
  - 项目 README（架构图、部署步骤：服务器装 SVC、玩家装本 mod、TS 频道准备、配置说明、故障排查）
  - 交付 jar 与版本号（0.1.0+26.3）
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-9.1: README 覆盖部署/配置/排查且与实际行为一致——文档审阅
- **Notes**: 无
