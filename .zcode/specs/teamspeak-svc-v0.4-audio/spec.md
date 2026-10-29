# teamspeak-svc v0.4 音频增强缝合（SVC-Extra 优点）- Product Requirements Document

## Overview
- **Summary**: 把 SVC-Extra（GPL-3.0，源自 Channel 项目）的四项音频增强缝合进 teamspeak-svc：路径追踪混响、Ducking 闪避、WebRTC 回声消除（AEC）、WebRTC 降噪+AGC。全部作用于"TS 后端"音频路径（发送侧 = 麦克风→TS；接收侧 = TS 注入→扬声器）。
- **Purpose**: 补偿等响度架构的体验短板：混响给 TS 用户声音带环境空间感；AEC 防外放回声循环（TS 架构特有风险）；降噪/AGC 提升发送质量；Ducking 改善游戏内听感。
- **Target Users**: teamspeak-svc 玩家（尤其外放用户、洞穴/大空间场景）。

## Goals
- **路径追踪混响**：TS 音频按本地环境（方块材质/空间几何/水下）实时生成 OpenAL EFX 混响——补偿"无距离衰减"的空间感缺失。
- **Ducking**：TS 音频播放时平滑压低 MC 游戏音量（说话期间压暗、最后帧后 600ms 保持、500ms 平滑恢复）。
- **AEC 回声消除**：播放的 TS 音频作为参考信号，麦克风发送侧经 WebRTC AEC3 同步处理（外放防循环）。
- **WebRTC 降噪 + AGC**（可选替代 SVC 原生降噪）：发送侧 WebRTC NS + 自动增益（固定目标电平 -3dBFS）+ 高通滤波。
- 全部功能开关化（默认开混响/Ducking，AEC/降噪默认关由用户开启），运行期可热切换。

## Non-Goals
- ~~NVIDIA Broadcast AI 降噪（硬件/SDK 绑定）~~ —— v0.8 已实现（NvidiaDenoiser + NvAudioEffects 绑定），保留为可选档位。
- TCP 双协议传输（与 TS 后端无关）。
- 音乐播放共享（MusicPlayer/MusicReceiver，另一功能域）。
- 服务器侧任何改动（纯客户端）。

## Background & Context
- 源项目：`liuyuexiaoyu1/SVC-Extra`（GPL-3.0），代码源自 `USS-Shenzhou/Channel`。
- 挂点已验证可行：`ALSpeakerBase.openSync/writeSync`（接收侧：我们注入的 GroupSoundPacket 走同一 speaker 管线，混响/Ducking/AEC 参考信号自动覆盖 TS 音频）；`MicrophoneProcessor.preprocess/useDenoiser`（发送侧：SVC 采集→处理→我们拦截→TS，顺序天然正确）。
- native：`dev.onvoid.webrtc:webrtc-java:0.14.0`（Apache-2.0，与 GPL 兼容）Maven Central 现成平台 classifier（windows/linux/macos × x64/aarch64），`include` jar-in-jar。
- 混响/Ducking 纯 Java（LWJGL OpenAL EFX / MC SoundEngine 音量），无 native。
- 与现有 mixin（GameProfileUtils 皮肤）无交集，无 mixin 冲突。

## Functional Requirements
- **FR-1 混响**: TS 音频播放的 OpenAL source 挂 EFX aux slot；每 500ms 重算射线（Fibonacci 球面两半球各 300 条、≤5 弹射、64 格），由方块 SoundType 材质推导 Density/Diffusion/RT60/GainHF/早晚反射等 EFX 参数；水下自动特性切换；射线查询在客户端主线程按 tick 预算分批执行（v0.9 起，不再跨线程访问世界），OpenAL 参数在 AL 上下文线程应用。
- **FR-2 Ducking**: TS 音频活动时把 MC SoundEngine 各分类音量按 smoothstep 压至配置比例（默认 35%），最后一帧后保持 600ms、再用 500ms 平滑恢复；玩家手动调整音量滑条时以新值为基准，原始音量正确还原。
- **FR-3 AEC**: 播放侧把混音后的 TS 音频推入参考缓冲；发送侧麦克风帧在麦克风线程内同步与 1:1 对齐的参考信号经 WebRTC AEC3 处理并写回发送缓冲（v0.9 起；参考缺失时补静音）。
- **FR-4 降噪+AGC**: 发送侧可选 WebRTC NS（低/中/高/激进）+ AGC（固定目标电平 -3dBFS）+ 高通，替换 SVC 原生 denoiser 开关行为；关闭时完全回退 SVC 原生。AEC 模式下降噪由 APM 内置 NS（WebRTC 档位）或 NVIDIA 处理器承担。
- **FR-5 配置**: ModConfig 新增 `reverbEnabled`（0.9.2 起默认 false）、`duckingEnabled`（0.9.2 起默认 false）、`duckingLevel`（默认 0.35）、`echoCancelEnabled`（默认 false）、`denoiseMode`（OFF/原生/WEBRTC_*，0.9.2 起默认 OFF = 使用 SVC 原生降噪）、`aecDelayMs`（默认 120）+ AGC 开关；`/tsvoice reload` 热生效；GUI 已提供（ConfigScreen + AudioExtraScreen，入口在 SVC 语音界面 Extra 按钮）。0.9.2 起移除 `networkToleranceMs` 与抖动缓冲覆盖 mixin，接收侧回到 SVC 原生 `audio_packet_threshold`。
- **FR-6 降级**: webrtc-java native 加载失败（不支持平台）→ AEC/降噪自动 OFF 并提示一次，混响/Ducking（纯 Java）不受影响；mod 整体不因此崩溃。

## Non-Functional Requirements
- **NFR-1 许可变更**: 因缝合 GPL-3.0 代码，mod 许可 MIT → **GPL-3.0**（fabric.mod.json 与 README 同步，注明衍生自 SVC-Extra/Channel 及其许可证）。
- **NFR-2 体积**: webrtc-java natives include 后 jar 允许增大（预计 +15~25MB），仅 include 主流平台（windows-x86_64、linux-x86_64、linux-aarch64、macos-aarch64）。
- **NFR-3 零回归**: 全部开关关闭时行为与 0.3.3 完全一致（含协议自测）。
- **NFR-4 可构建**: `gradlew build` 通过。

## Constraints
- **Technical**: MC 26.3 / SVC 2.6.25 mixin 目标（ALSpeakerBase、MicrophoneProcessor 为 SVC 内部未混淆类，remap=false）；GPL 义务（保留来源声明）。
- **Dependencies**: webrtc-java 0.14.0（Apache-2.0）；LWJGL EFX（MC 自带）。

## Assumptions
- SVC 2.6.25 的 ALSpeakerBase/MicrophoneProcessor 方法名与 SVC-Extra 所适配版本一致（SVC-Extra 面向 26.2/SVC 同代；require=0 兜底缺失场景）。
- EFX 为用户声卡驱动支持（绝大多数支持；不支持时混响静默无效不崩溃）。

## Acceptance Criteria

### AC-1: 混响生效
- **Type**: `rule`
- **Given**: 客户端在有大空间/洞穴差别的两个位置，reverbEnabled=true，TS 用户说话
- **When**: 移动位置
- **Then**: EFX 参数随环境变化（日志/调试输出参数变化），听觉有可辨差异
- **Pass Condition**: 参数随环境变化且不崩溃
- **Evidence**: 调试日志 + 用户听感

### AC-2: Ducking 生效与还原
- **Type**: `rule`
- **Given**: duckingEnabled=true，MC 游戏音量正常
- **When**: TS 音频播放 → 停止 600ms 后
- **Then**: 游戏音量压至配置比例并平滑恢复至原始值（精确还原，误差 0）
- **Pass Condition**: 音量曲线符合 + 退出/关闭后无残留压低
- **Evidence**: 日志（原始/压低值）+ 听感

### AC-3: AEC 消回声
- **Type**: `rule`
- **Given**: 外放扬声器、echoCancelEnabled=true、另一端有回声路径
- **When**: 对方说话（我方麦克风能拾到外放声）
- **Then**: 对方听不到自己的回声（或显著衰减）
- **Pass Condition**: 双端联测回声消失/显著减弱
- **Evidence**: 联调记录

### AC-4: 降噪/AGC 可选生效
- **Type**: `rule**
- **Given**: denoiseMode=WEBRTC_*
- **When**: 麦克风输入持续底噪
- **Then**: 接收端底噪显著降低；denoiseMode=OFF/原生时行为与 SVC 原生一致
- **Pass Condition**: 对比录音底噪
- **Evidence**: 录音对比

### AC-5: 降级安全
- **Type**: `rule`
- **Given**: 模拟 native 加载失败（或非支持平台）
- **When**: 启用 AEC/降噪
- **Then**: 自动回退 OFF + 一次提示；混响/Ducking 正常；无崩溃
- **Pass Condition**: 日志降级记录 + mod 运行正常
- **Evidence**: 日志

### AC-6: 零回归
- **Type**: `rule`
- **Given**: 全部新开关关闭
- **When**: 完整语音会话（连接/群组/说话/信标）
- **Then**: 与 0.3.3 行为一致，协议自测 PASSED
- **Pass Condition**: Ts3VoiceSelfTest PASSED + 功能冒烟
- **Evidence**: 自测输出

### AC-7: 音质综合
- **Type**: `rubric`
- **Dimension**: 缝合后整体语音体验（空间感/回声/底噪/游戏音量平衡）
- **Scale**: 1-5
- **Anchors**: 1 = 明显劣化；3 = 无变化；5 = 空间感明显+外放无回声+底噪干净
- **Pass Threshold**: >= 4
- **Evidence**: 用户实测评价

## Open Questions
- [ ] macOS-x86_64 是否 include（用户群无此平台则省体积）
- [ ] AGC 目标电平默认值（-3dBFS 参考 WebRTC 常用值，实测调整）
