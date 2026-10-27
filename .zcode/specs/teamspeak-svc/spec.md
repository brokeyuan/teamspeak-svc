# teamspeak-svc - Product Requirements Document

## Overview
- **Summary**: 客户端 Fabric mod（MC 26.3）：保留 Simple Voice Chat (SVC) 作为语音前端（界面、按键、语音激活、群组、HUD），把 SVC 的语音后端整体替换为 TeamSpeak 服务器。玩家 MC 客户端直连 TS（每人独立持久 TS 身份），MC 服务器不再承载任何语音转发。
- **Purpose**: 语音基础设施完全交给 TeamSpeak：对 MC 服务器零语音负载，TS 用户与 MC 玩家在同一 TS 频道天然互通，体验与原 SVC 一致。
- **Target Users**: 使用 TeamSpeak 的 MC 服务器社区（服务器装 SVC 作界面锚点；玩家客户端装本 mod）。

## Goals
- SVC 的说话方式（语音激活/PTT/静音）与界面原样保留，玩家无学习成本。
- 玩家的 MC 客户端成为一个 TS3 语音客户端：昵称 = MC 玩家名，身份跨启动持久。
- MC↔MC 与 MC↔TS 语音全部经 TS 后端；MC 服务器的 SVC 后端不再转发音频。
- TS 用户在 SVC 界面中以本地 Ghost 形式可见、可看到说话指示。

## Non-Goals
- 距离/位置衰减（等响度；Ghost 位置固定于本地玩家）。
- 服务端组件（本 mod 不含服务端逻辑；tsbridge 项目承担服务端桥接形态）。
- SVC 私语（whisper）语义经 TS 传递（v1 全部发往频道）。
- TS3 服务器管理功能（频道权限管理等；频道创建为尽力而为）。
- 未安装本 mod 的玩家与 TS 语音互通。

## Background & Context
- 复用 tsbridge 项目中已对真实 TS3 服务器验证的 `ts3voice` 纯 Java 协议栈（init1 握手/license 加密协商/clientinit/语音收发/断线重连）。
- SVC 26.x 客户端管线（26.2 源码调研，26.3 需实测）：
  - 麦克风：`MicThread.sendAudioPacket` → `connection.sendToServer(MicPacket)`，Opus 48kHz mono 20ms 帧 —— 与 TS3 OpusVoice 直接兼容，**零转码**；
  - 播放：`ClientVoicechat.processSoundPacket(SoundPacket)` 按 channelId（发送者 UUID）建 `AudioChannel` 解码播放 —— TS 音频注入点；
  - 界面状态：`ClientPlayerStateManager` 维护客户端本地玩家状态 —— 本地 Ghost 注入点。
- 前置事实：MC 26.3 未混淆，mixin 目标稳定；SVC 客户端必须与装有 SVC 的服务器完成握手后才激活（因此 MC 服务器需安装 SVC 本体作界面锚点，但其音频路径不再被使用）。

## Functional Requirements
- **FR-1 连接生命周期**: 玩家进入世界且 SVC 客户端激活后，自动以配置的 TS 服务器/频道建立语音连接（昵称 = MC 玩家名，TS 身份持久化于客户端 config）；离开世界断开；连接失败按指数退避自动重连并显示状态。
- **FR-2 麦克风改道**: SVC 语音激活/PTT 生效期间产生的已编码 Opus 帧改发至 TS 频道，并抑制发往 MC 服务器的原始发送；SVC 的按键、激活类型、静音逻辑完全不动。
- **FR-3 TS 音频注入**: 收到的 TS 频道语音注入 SVC 客户端播放管线；说话者以客户端本地 Ghost 呈现（UUID 由 TS uid 确定性派生），Ghost 位置固定为本地玩家（等响度）。
- **FR-4 Ghost 可见性**: 本地 Ghost 出现在 SVC 玩家状态界面并随说话点亮（复用 SVC 客户端说话指示机制）；Ghost 仅存在于本地客户端，不依赖服务器广播。
- **FR-5 群组子频道映射**: 加入/离开 SVC 群组时，TS 客户端移动到 `<主频道>/<群组名>` 子频道（尽力创建，失败停留主频道并提示）；未在群组时回到主频道。
- **FR-6 HUD 与配置**: HUD 显示 TS 连接状态（未配置/连接中/已连接/重连中/错误）与说话指示；配置项（tsHost、tsVoicePort、主频道名、自动连接）存于客户端配置，提供游戏内命令查看/重载。
- **FR-7 共存与降级**: 与 SVC 客户端共存零冲突；SVC 未连接（服务器无 SVC）时本 mod 静默不激活；TS 不可达时 SVC 界面照常、仅无 TS 语音。

## Non-Functional Requirements
- **NFR-1 延迟**: 端到端语音延迟主要由 TS 网络决定，mod 附加开销 ≤ 30ms。
- **NFR-2 零原生依赖**: 纯 Java 实现，不引入新的 native 库（Opus 帧直接复用 SVC 编码结果，无需自带编解码器）。
- **NFR-3 资源可控**: 每个连接的线程/内存在断开后完全释放；单客户端内存增量 ≤ 50MB。
- **NFR-4 可构建**: 独立 Gradle 项目，`gradlew build` 产出可放入 `mods/` 的客户端 jar。

## Constraints
- **Technical**: MC 26.3 / Fabric Loader 0.20.1 / Java 25；SVC 2.6.25+26.3 客户端 mixin 目标需在实现前对实际 jar 验证。
- **Business**: 仅支持装了本 mod 的客户端参与 TS 语音（v1）。
- **Dependencies**: MC 客户端与服务器需安装 SVC（界面锚点）；外部 TeamSpeak 3 服务器（语音 UDP）。

## Assumptions
- 所有语音参与者均安装本 mod（未装者仅走 SVC 原生路径，与 TS 侧不互通，v1 接受）。
- TS 服务器的频道权限允许普通客户端加入频道（子频道创建为尽力而为，可由管理员预建）。
- 语音内容合规性由 TS 服务器管理方负责。

## Acceptance Criteria

### AC-1: 连接生命周期
- **Type**: `rule`
- **Given**: 客户端已配置 TS 地址并进入已启用 SVC 的服务器世界
- **When**: SVC 客户端激活（握手完成）
- **Then**: ≤10 秒内该玩家以 MC 玩家名出现在 TS 配置频道中；退出世界后其 TS 客户端断开
- **Pass Condition**: TS 侧 `clientlist` 可见该客户端且昵称与玩家名一致；离开后消失
- **Evidence**: TS ServerQuery clientlist 截取 + 游戏日志

### AC-2: MC→TS 语音改道
- **Type**: `rule`
- **Given**: 玩家已连入 TS 并按 SVC 按键说话
- **When**: SVC 产生已编码 Opus 帧
- **Then**: 帧经玩家自己的 TS 客户端发出（TS 频道成员可听到），且 MC 服务器的 SVC 不再收到该玩家的麦克风包
- **Pass Condition**: TS 侧其他客户端可闻；SVC 服务端日志/抓包无该玩家 MicPacket
- **Evidence**: 联调测试记录 + 服务端日志

### AC-3: TS→MC 注入
- **Type**: `rule`
- **Given**: TS 用户在桥接频道说话
- **When**: 客户端收到该 TS 用户的语音帧
- **Then**: MC 客户端可闻其声，SVC 界面显示对应本地 Ghost 说话
- **Pass Condition**: 联调中 MC 客户端出声且 HUD 说话指示点亮
- **Evidence**: 联调测试记录

### AC-4: MC↔MC 经 TS 互通
- **Type**: `rule`
- **Given**: 两个均安装本 mod 的客户端加入同一 TS 频道
- **When**: A 说话
- **Then**: B 可闻，且该语音不经过 MC 服务器转发
- **Pass Condition**: 联调互通；服务端无音频中转证据
- **Evidence**: 联调测试记录

### AC-5: 群组子频道映射
- **Type**: `rule`
- **Given**: 玩家加入/离开 SVC 群组（组名 ≤ TS 频道名限制）
- **When**: 群组变化事件触发
- **Then**: 其 TS 客户端移动到 `<主频道>/<群组名>` 子频道（不存在则尽力创建）或回主频道
- **Pass Condition**: TS 侧频道归属与 SVC 群组状态一致
- **Evidence**: TS ServerQuery channellist/clientlist 截取

### AC-6: 共存与降级
- **Type**: `rule`
- **Given**: 客户端同时安装 SVC 与本 mod；TS 服务器可随时不可达
- **When**: 正常游戏 / TS 断开 / 加入无 SVC 的服务器
- **Then**: 游戏与 SVC 界面零异常；TS 断开后自动重连；无 SVC 时 mod 静默
- **Pass Condition**: 游戏日志无本 mod 引发的报错；断线后 60 秒内自动重连成功
- **Evidence**: 游戏日志 + 重连日志

### AC-7: 语音质量与延迟
- **Type**: `rubric`
- **Dimension**: 真实联调中的可懂度、连续性、延迟与稳定性
- **Scale**: 1-5
- **Anchors**: 1 = 无法使用（断续/严重延迟）；3 = 可用但明显卡顿或延迟 >500ms；5 = 与原生 SVC 体验基本无差别（延迟 ≤300ms、无可感卡顿）
- **Pass Threshold**: >= 4
- **Evidence**: 联调主观评价记录（测试者、时长、现象）

### AC-8: 工程化交付
- **Type**: `rule`
- **Given**: 独立项目目录 `teamspeak-svc`
- **When**: 执行 `gradlew build`
- **Then**: 产出客户端 mod jar，可放入 `mods/` 被 Fabric 26.3 加载
- **Pass Condition**: 构建成功且 jar 内含合法 fabric.mod.json（client entrypoint）
- **Evidence**: 构建输出 + jar 内容检查

## Open Questions
- [ ] TS3 3D 声音定位（需 TS 侧插件）——非目标，未来再议
- [ ] whisper 语义经 TS 实现（TS whisper-list）——v1 后评估
