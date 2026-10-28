# P2 独立前端 — 真实验收矩阵（未验证清单）

> 状态：**全部未验证**。本切片仅完成替身级验证（review/p2-frontend/RunFrontend.ps1，19 项断言）与
> 字节码级 ABI 核对（javap，见 Svc2625FrontendAdapter 类注释）。按实施计划规则 10，以下任何一项
> 在真实环境执行并留证之前，**不得宣称"无 SVC 服务器可用"**；构建通过不等于功能验收。
> 每项标注所需环境与复现步骤；执行后在本文件记录日期、环境与结果。

## ABI 已核对（字节码级，非运行时）

| 项 | 证据 |
|---|---|
| `MicThread(ClientVoicechat, ClientVoicechatConnection, Consumer<MicrophoneException>)` 三参构造 | javap MicThread |
| run 循环与 sendAudioPacket 显式判空 connection（null 连接=只采集不发送） | javap -c 字节码 offset 62/141、454-466 |
| `ClientVoicechat.getMicThread()/closeMicThread()` 可借用原生 mic | javap ClientVoicechat |
| `AudioChannel(ClientVoicechat, InitializationData, SoundManager, UUID)` 四参构造（26.2 参考源码为 3 参的 ABI 差异）；构造器仅保存 InitializationData 不解引用 | javap AudioChannel |
| `ClientVoicechat.processSoundPacket` 在 connection==null 时直接返回（收包门槛仍在） | 评估文档 §3.1 + javap |

## 未验证项

### U1 — 无 SVC 服务器的手动 TS 双向通话（P2 核心验证门）
- **环境**：MC 26.3 客户端 + 无任何 voicechat/tsbridge 的 MC 服务器 + 真实音频设备 + 可达的 TS3 服务器。
- **复现步骤**：
  1. 安装 teamspeak-svc，配置手动 TS 书签（host/port），MC 服无 SVC；
  2. 按 PTT/语音激活说话，另一 TS 用户应可听到；
  3. TS 用户说话，本端应经 Ghost 播放（音量/静音/说话指示可用）。
- **当前实现的落点**：mic 侧走 `MicThread(client, null, …)`（字节码已证实支持）；**播放侧未实现独立入口** ——
  `ClientVoicechat.processSoundPacket` 在无连接时直接返回，因此 `TsAudioInjector` 的
  GroupSoundPacket 注入路径在无 SVC 连接时**不产声**。这是 U1 当前必然失败的已知缺口，
  需要 FrontendLease.play 接入直接创建的 `AudioChannel`（四参构造器 + null InitializationData，
  其播放循环安全性未验证，见 U2）。P2 停线条件条款适用：在 U2 通过前不得声称独立前端完成。
- **结果**：未执行。

### U2 — 无 SVC 连接下的独立播放通道（AudioChannel 直接创建）
- **环境**：同 U1。
- **复现步骤**：FrontendLease 接管播放后，在无 SVC 连接时为 TS 流创建
  `new AudioChannel(clientVoicechat, null, soundManager, participantUuid)` 并 addToQueue；
  验证解码/抖动缓冲/扬声器输出与个人音量命中。
- **风险**：AudioChannel.run() 播放循环对 null InitializationData 的行为未验证
  （构造器只保存不解引用，run 未核）；若不安全需最小 hook 或上游扩展（D02 停线条件）。
- **结果**：未执行。

### U3 — mic 所有权交接（原生 mic 存在/接管/归还）
- **环境**：MC 26.3 + SVC 2.6.25 正常连接的 SVC 服务器。
- **复现步骤**：SVC 原生 mic 存在时借用手动 TS（FrontendLease 借用路径）；SVC 断开/重建时
  观察租约归还与重新申请（不得同时开两个设备或递归触发连接事件）。
- **结果**：未执行。

### U4 — hook 自检失败场景（真实缺类）
- **环境**：SVC 版本 ≠ 2.6.25（如 2.6.20/2.7.x）的运行时。
- **复现步骤**：加载 mod，观察日志出现 "TS DISABLED — frontend self-check failed"，
  TS 不启动，原版 SVC 可正常使用。
- **结果**：未执行（替身级已覆盖：FrontendCompatibilityTest/OwnershipTest 的禁用路径）。

### U5 — CLIENT_STOPPING / 世界退出资源编排
- **环境**：正常游戏会话。
- **复现步骤**：TS 连接中退出游戏/返回主菜单；验证无 mic/decoder/播放线程残留
  （jstack/线程计数回到基线）。
- **结果**：未执行。

### U6 — 设备热切换与 SVC 网络突然就绪
- **环境**：真实音频设备。
- **复现步骤**：TS 会话中拔插 mic/切换默认设备；SVC 服务器中途可达；验证音频连续性、
  唯一 mic 所有者、无双发。
- **结果**：未执行。

## 依赖的真实环境清单
- 可达的 TS3 服务器（含可写的频道权限）；
- 无语音组件的 MC 服务器实例；
- 正常的 SVC 服务器实例（U3/U6）；
- Windows 桌面 + 真实麦克风/扬声器（U1/U3/U6）；
- 替代 SVC 版本 JAR（U4）。
