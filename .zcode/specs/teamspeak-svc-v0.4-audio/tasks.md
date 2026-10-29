# teamspeak-svc v0.4 音频增强缝合 - Implementation Plan

> 搬运源：`github.com/liuyuexiaoyu1/SVC-Extra`（GPL-3.0）。所有缝合文件入包 `com.tsvoice.client.audio.*`，
> 文件头保留来源与许可声明；mod 许可 MIT→GPL-3.0。

## Task 1: 依赖与许可变更
- **Status**: `completed`
- **Completion Evidence**:
  - BUILD SUCCESSFUL；jar 38.9MB 含 4 平台 webrtc natives + 主 jar；fabric.mod.json license=GPL-3.0
- **Priority**: high
- **Depends On**: None
- **Description**:
  - build.gradle：`dev.onvoid.webrtc:webrtc-java:0.14.0`（implementation+include）+ 4 平台 classifier natives（win-x86_64/linux-x86_64/linux-aarch64/macos-aarch64）
  - fabric.mod.json + README：license → GPL-3.0，致谢 SVC-Extra/Channel
- **Acceptance Criteria Addressed**: NFR-1, NFR-2, NFR-4
- **Test Requirements**:
  - `rule` TR-1.1: 构建成功且 jar 内含 webrtc natives（4 平台）——jar 内容清单
  - `rule` TR-1.2: fabric.mod.json license=GPL-3.0——文件检查

## Task 2: WebRTC 封装（native 加载 + 降级）
- **Status**: `completed`
- **Completion Evidence**:
  - WebRtcNative 缝合（jar-in-jar 提取加载 + 失败降级路径保留）；真机加载验证待用户（Windows）
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - `WebRtcNative`（改包名）：平台检测 + native 提取加载（jar-in-jar → 临时目录 System.load）；失败置 unavailable + 一次 WARN
  - 封装 NS/AGC/AEC3 句柄管理（参考 SVC-Extra WebRtcNative + AudioPipeline 的用法）
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-2.1: Windows 加载成功日志 `WebRTC native loaded`；强制失败路径降级 OFF 不崩溃——日志
- **Notes**: webrtc-java 的 API（NoiseSuppressor/AudioProcessing?）以 0.14.0 实际类为准，SVC-Extra 的调用代码为参照

## Task 3: 路径追踪混响（P1）
- **Status**: `completed`
- **Completion Evidence**:
  - RayTracedReverb + rt/HitPoint + rt/BlockSoundProperty + AlSpeakerMixin(openSync/writeSync 挂点) 缝合编译通过
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 搬 `RayTracedReverb` + `rt/HitPoint` + `rt/BlockSoundProperty`（改包名+来源头）；ForkJoin 异步射线；EFX aux 挂载
  - 新 mixin `AlSpeakerMixin`（openSync TAIL 挂 EFX / writeSync HEAD markDucking+推AEC参考+水下滤波），mixin json 登记
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-3.1: 不同环境 EFX 参数变化（调试日志）——日志
  - `rule` TR-3.2: reverbEnabled=false 时零 EFX 调用——日志/代码路径

## Task 4: Ducking 闪避
- **Status**: `completed`
- **Completion Evidence**:
  - DuckingManager + SoundEngine/SoundManager accessor 缝合编译通过
- **Priority**: high
- **Depends On**: Task 3（同挂点触发）
- **Description**:
  - 搬 `DuckingManager` + `SoundEngineAccessor`/`SoundManagerAccessor`（accessor mixin）
  - 退出/关闭时精确还原音量
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `rule` TR-4.1: 触发→压低→600ms 后恢复原始值（日志记录 original/restore）——日志
  - `rule` TR-4.2: 关闭开关后音量无残留——日志

## Task 5: AEC + 降噪/AGC（发送侧）
- **Status**: `completed`
- **Completion Evidence**:
  - AecScheduler/AecSyncBuffer/AecReferenceMixer + MicProcessorMixin(preprocess/useDenoiser) + AgcProcessor 缝合；AudioPipeline 重写适配 TsAudio；NVIDIA 分支摘除
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - 搬 `AecReferenceMixer`/`AecScheduler`/`AecSyncBuffer`；`MicProcessorMixin`（preprocess 挂点：WebRTC AEC3+NS+AGC+HPF 处理链；useDenoiser 覆盖开关）
  - 全链路受 echoCancelEnabled / denoiseMode 配置门控；native 不可用自动 OFF
- **Acceptance Criteria Addressed**: AC-3, AC-4, AC-5
- **Test Requirements**:
  - `rule` TR-5.1: echoCancel=true 时 preprocess 链路包含 AEC 处理（调试日志计数）——日志
  - `rule` TR-5.2: denoiseMode=OFF 时不调用 WebRTC NS——日志
  - AC-3/AC-4 真机双端联调交用户（外放回声/底噪对比）

## Task 6: 配置 + 热切换 + 收尾
- **Status**: `completed`
- **Completion Evidence**:
  - ModConfig 7 新字段 + /tsvoice reload 热切换（TsAudio.refresh + AudioPipeline.reconfigure）；Ts3VoiceSelfTest PASSED；0.4.0 jar 产出
- **Priority**: medium
- **Depends On**: Task 3, 4, 5
- **Description**:
  - ModConfig 增字段（FR-5 默认值）；/tsvoice reload 热生效；README（新功能/许可变更/平台支持）
  - 版本 0.4.0
- **Acceptance Criteria Addressed**: FR-5, AC-6, NFR-3
- **Test Requirements**:
  - `rule` TR-6.1: 关全部新开关 → Ts3VoiceSelfTest PASSED + 会话冒烟一致——自测输出
  - `rule` TR-6.2: reload 后开关即时生效——日志
- **Notes**: 用户端联调清单（AC-1/2/3/4/7）随 jar 交付
