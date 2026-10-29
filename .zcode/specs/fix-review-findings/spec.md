# teamspeak-svc 审查问题逐一修复 - 产品需求文档

## Overview
- **Summary**: 按 `CODE_REVIEW.md` 审查结论，逐一修复 teamspeak-svc 的高危、中危、低危问题并回填文档，范围含群组密码链路、命令/GUI、音频线程模型与 AEC、协议健壮性、安全部署、清理与文档。
- **Purpose**: 让已实现功能真正生效（群组密码、闪避、混响、AEC、配置桥），消除崩溃与资源泄漏风险，并使文档与代码一致。
- **Target Users**: 使用 teamspeak-svc 的 MC 玩家与维护该 mod 的开发者。

## Goals
- 群组密码在 SVC 群组切换后仍可用于建/进 TS 子频道。
- `/tsvoice` 全部命令可用；GUI 配置不丢失、不覆盖已存密码。
- AEC 有实际效果且不泄漏 native 资源；闪避/混响接通且无跨线程世界访问。
- 协议层对畸形输入优雅降级；敏感文件权限收紧。
- README/ROADMAP/spec 与代码一致。

## Non-Goals
- 不回移协议修复到 tsbridge，不抽共享协议模块。
- 不引入测试框架、不改构建体系（除版本号）。
- 不实现 whisper 经 TS、不做 TS 侧 3D 声源插件。
- 实机多人语音联调不在本环境执行。

## Background & Context
- 审查报告：[CODE_REVIEW.md](../../../teamspeak-svc/CODE_REVIEW.md)。
- 已确认根因：SVC `getGroup()` 每次返回新 `ClientGroupImpl`，而 `GroupChannelMapper` 用 `!=` 比较；`tsvoice.status` 6 占位符只传 4 参数；AEC 结果写回副本；`DuckingManager.tick`/`RayTracedReverb.tick` 无调用点；`TsAudio.refresh` 自赋值；密码框未回填且保存会覆盖为空。
- 已确认 SVC 26.2 语义：`ClientSoundEvent` 携带原始 PCM、cancel 即停止发送；`AudioPacketBuffer` 参数是阈值（默认 3，0=关闭，范围 0-16）。

## Functional Requirements
- **FR-1**: 群组变化按群组 id 判断；群组信息 payload 早到时不丢失；带密码群组在信息到达后使用密码建/进子频道。
- **FR-2**: `/tsvoice status`、`/tsvoice connect` 正常输出状态；新增 `/tsvoice say <消息>` literal 别名。
- **FR-3**: GUI 打开时回填密码并掩码显示；保存不覆盖未修改的密码；`AudioExtraScreen` 正确返回来源界面。
- **FR-4**: `manualDisconnect` 后不被 beacon/配置事件静默重连，除非用户再次连接或换服。
- **FR-5**: 音频增强配置（duckingLevel/aecDelayMs/nvidia 路径/networkToleranceMs）经 reload 生效。
- **FR-6**: AEC 在麦克风线程同步处理，结果写回发送数组，参考流 1:1 对齐；reconfigure/shutdown 释放 native APM。
- **FR-7**: 闪避与混响在客户端主线程 tick 驱动；混响世界查询只发生在主线程，OpenAL 调用只在 AL 上下文线程。
- **FR-8**: 跨线程访问（SkinManager、level 实体、速度表）改为主线程提交或并发快照。
- **FR-9**: 协议层对畸形压缩包、未知包类型、TCP 静默超时优雅处理。
- **FR-10**: 敏感文件 POSIX 600；日志不出现明文密码；配置原子写入。

## Non-Functional Requirements
- **NFR-1**: 20ms 音频帧路径不新增阻塞锁竞争与显著分配。
- **NFR-2**: 每个会话断开后 TS/native 资源完全释放。
- **NFR-3**: 修复后 `gradlew build` 成功（JDK 25）。
- **NFR-4**: 不改变既有音频/语音协议兼容性（TS3 协议栈线格式不变）。

## Constraints
- **Technical**: MC 26.3 / Fabric / Java 25；SVC 2.6.25 内部类 mixin；工作目录含 `&`，构建需用 `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build`。
- **Business**: 仅修 teamspeak-svc，tsbridge 不动。
- **Dependencies**: 真实 TS 服务器（人工联调）、NVIDIA/WebRTC native（降级路径验证）。

## Assumptions
- SVC 26.3 与 26.2 的 `getGroup()`/`ClientSoundEvent` 语义一致（调研基于 26.2，jar 为 26.3）。
- 本环境可完成构建与静态验证，实机语音测试由用户执行。

## Acceptance Criteria

### AC-1: 构建可交付
- **Type**: `rule`
- **Given**: 完成全部代码修改
- **When**: 执行 Gradle build
- **Then**: 构建成功并产出 `teamspeak-svc-0.9.0+26.3.jar`
- **Pass Condition**: exit code 0，jar 内 `fabric.mod.json` 合法
- **Evidence**: 构建输出 + jar 内容检查

### AC-2: 群组密码链路
- **Type**: `rule`
- **Given**: 玩家在带密码 SVC 群组，SVC `getGroup()` 每次返回新对象
- **When**: `GroupChannelMapper.tick` 连续运行且 tsbridge 已下发 GroupInfoPayload
- **Then**: 不再每轮清空密码；6 秒窗口内使用密码建/进子频道
- **Pass Condition**: 代码中群组比较按 id；`lastGroupInfo` 仅在群组 id 变化时失效；pending payload 可被消费
- **Evidence**: 代码审查 + 模拟多次 getGroup 返回不同实例的单元级验证

### AC-3: 命令与 GUI
- **Type**: `rule`
- **Given**: 已保存密码的服务器配置
- **When**: 打开 GUI 并点击完成/连接；执行 `/tsvoice status`
- **Then**: 密码不被清空并掩码显示；status 正常输出不抛异常
- **Pass Condition**: 代码路径检查 + 运行时格式化参数匹配
- **Evidence**: 代码审查 + grep 占位符/参数数量

### AC-4: 断开语义与配置生效
- **Type**: `rule`
- **Given**: 用户执行 `/tsvoice disconnect` 后修改音频设置
- **When**: beacon/配置事件触发 `evaluateActivation`
- **Then**: 不自动重连；`duckingLevel`/`aecDelayMs`/`networkToleranceMs`/NVIDIA 路径经 reload 生效
- **Pass Condition**: `userDisabled` 逻辑存在且事件路径不绕过；`TsAudio.refresh` 读取对应 config 字段
- **Evidence**: 代码审查 + grep

### AC-5: AEC 有效且无泄漏
- **Type**: `rule`
- **Given**: `echoCancelEnabled=true`
- **When**: 麦克风帧进入处理管线
- **Then**: APM 输出写回原数组；参考帧按 10ms 1:1 消费；shutdown 调用 `close()/dispose()`
- **Pass Condition**: 无异步队列写回副本；无未 close 的 WebRtcNative；AEC 线程与相关死类移除
- **Evidence**: 代码审查 + 静态检查

### AC-6: 闪避/混响接线且线程安全
- **Type**: `rule`
- **Given**: 默认配置（两功能开启）
- **When**: 客户端 tick
- **Then**: 两个 tick 各只有一个主线程调用点；无 ForkJoin 线程访问 ClientLevel；OpenAL 参数应用在 AL 上下文线程
- **Pass Condition**: grep 无 `RAY_POOL`/`parallelStream` 世界访问；tick 注册存在；遮挡查询在主线程
- **Evidence**: 代码审查 + grep

### AC-7: 协议健壮性
- **Type**: `rule`
- **Given**: 畸形压缩命令 / 未知包类型 / TCP 静默
- **When**: 协议层收到输入
- **Then**: 抛 `Ts3VoiceException` 并被连接层优雅丢弃，连接不中断；未知类型丢弃；TCP 超时日志明确
- **Pass Condition**: 长度/边界校验存在；catch 覆盖 RuntimeException；未知类型返回 null
- **Evidence**: 代码审查 + 针对性构造输入的人工验证说明

### AC-8: 敏感数据与配置安全
- **Type**: `rule`
- **Given**: POSIX 系统
- **When**: 身份/配置落盘
- **Then**: 文件权限 600；日志与 payload toString 不含明文密码；写入为原子替换
- **Pass Condition**: 代码路径存在且无明文日志
- **Evidence**: 代码审查 + grep

### AC-9: 文档一致
- **Type**: `rule`
- **Given**: 修复完成
- **When**: 对照 README/ROADMAP/spec 与代码
- **Then**: 版本、命令、配置字段、默认值一致
- **Pass Condition**: 文档声明的命令与配置项在代码中全部存在
- **Evidence**: 文档 diff + grep

### AC-10: 修复质量
- **Type**: `rubric`
- **Dimension**: 改动正确性、最小侵入性、可维护性、回归风险
- **Scale**: 1-5
- **Anchors**: 1 = 引入新回归或崩溃；3 = 功能正确但改动面过大或缺少降级；5 = 功能正确、改动聚焦、保留降级路径且有验证证据
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审报告

## Open Questions
- [ ] 实机联调（真实 TS 服务器 + 双客户端）需用户手动执行。
- [ ] SVC 26.3 内部类签名与 26.2 的差异需在构建/运行日志确认。
