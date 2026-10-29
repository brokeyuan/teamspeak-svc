# teamspeak-svc 改进路线图（ROADMAP）

> 当前版本：**0.9.8**（2026-10-26）。配套服务端：tsbridge ≥0.3.3（group-sync 模式）。
> 本文档是改进项的唯一权威清单；完成项移入"已交付"。

## 已交付能力（截至 0.9.0）

- TS3 原生语音协议（纯 Java）：握手/加密/重连/频道操作/文件传输（头像）
- 服务端信标免配置直连（group-sync）+ 服务端桥模式自动休眠（bridge）
- SVC 群组完全复刻：任意群组↔TS 子频道、密码、常驻群组镜像
- 双向身份显示：MC 名=TS 昵称；TS 用户 `[TS] 昵称`；跨服玩家=真实皮肤
- 单玩家音量/静音/名字（USERNAME_CACHE 补齐）
- 皮肤三方向：同服原生/跨服真皮/TS 内置品牌皮（mixin）
- TS 头像=MC 皮肤脸（FT 上传）
- 音频增强（缝合自 SVC-Extra，GPLv3）：路径追踪混响、Ducking、WebRTC AEC/NS/AGC、NVIDIA AI 降噪（含缺 dll 游戏内引导链接）
- v0.5：TCP 隧道传输（UDP 受限网络兜底，经 tsbridge 隧道端口，真机验证通过）、MC↔TS 文字互通（/tsvoice say + 聊天栏 [TS] 消息）、抖动缓冲调优、GUI 全开关（AEC/AGC/混响/闪避/传输/降噪）、混响射线可视化（/tsvoice rays）
- v0.9（审查修复批次）：
  - 群组密码链路修复（按群组 id 判断变化、早到 payload 缓存、密码不再每 tick 丢失）
  - `/tsvoice status` 格式串修复；新增 `/tsvoice say` 别名；GUI 密码回填/掩码/保存不再覆盖
  - 手动断开不再被信标/配置事件静默重连
  - AEC 改为麦克风线程同步处理（结果写回、参考帧 1:1、修复 native 泄漏与 dispose 竞态）
  - 闪避与混响接通客户端 tick；混响世界查询限定主线程、OpenAL 调用限定 AL 上下文线程
  - 跨线程访问治理（位置快照/遮挡缓存/皮肤注册主线程/并发容器）
  - 协议健壮性（QuickerLz 边界校验、TCP 超时区分、未知包类型丢弃、Ed25519 例外处理）
  - 敏感文件 POSIX 600 + 原子写入；WebRTC 原生库固定缓存目录
  - 抖动缓冲阈值语义修正（toleranceMs/20，0=关闭）
- v0.9.1（体验修正）：
  - 移除 TS 配置界面里重复的“音频增强”入口（保留 SVC 语音界面 Extra 按钮）
  - 混响湿声压低约 12dB（early ≤1.5、late ≤2.5、EAX 增益 0.25、RT60 ≤8s），消除人声发闷
  - 清理不再使用的 `tsvoice.gui.audio_open` 语言键
- v0.9.2（清晰度优先）：
  - 音频增强默认全部关闭（混响/Ducking/AEC/AGC/WebRTC 降噪），发送侧使用 SVC 自身降噪
  - 移除抖动缓冲覆盖 mixin（`AudioChannelMixin`）与 `networkToleranceMs`，接收侧回到 SVC 原生 `audio_packet_threshold`
  - 旧配置一次性迁移（`configVersion` 0→1）
  - 混响关闭时同时停用遮挡/水下滤波与遮挡缓存计算
- v0.9.3（接收链路根因修复）：
  - TS→MC 改为注入 SVC `SoundPacket`（编码 Opus + TS 发送者语音序号）到 `ClientVoicechat.processSoundPacket`，由 SVC 的 AudioChannel 完成抖动缓冲/重排/Opus FEC/PLC 解码
  - 移除裸 PCM API 通道路径（`sequenceNumber=-1`，绕过全部补偿）与每 Ghost 的独立 Opus 解码器
  - 连接层不再丢弃乱序语音包；新增 16 位序号回绕与发送者重连复位处理
- v0.9.4（低延迟目标）：
  - 新增 `lowLatencyMode`（默认开）：TS 会话期间把 SVC 扬声器缓冲从 5 包（≈100ms）压到 2 包（≈40ms）
  - 发送侧维持 SVC 原生 RNNoise 降噪 + Speex AGC（Extra 全关时不做二次处理）
  - `/tsvoice status` 显示 `OFF (SVC native RNNoise)`
- v0.9.5（同服玩家身份/听不见修复）：
  - 先于本客户端进入 TS 的玩家拿不到 `client_description`：改用 TS 昵称 `[MC] 玩家名` 反查本服在线玩家 UUID
  - MC UUID 晚到时把已建的派生 Ghost 迁移到真实 UUID（消除 SVC 列表重复条目与幽灵音量配置隔离）
  - TS 音频统一 `GroupSoundPacket` 等响度，修复同服玩家距离 >48 格/跨维度时听不见
  - 去掉 `[TS] [MC]` 双前缀
- v0.9.6（诊断增强）：
  - `/tsvoice status` 增加 `injected=`（成功注入 SVC 播放管线的帧数），与 `receivedFrames` 对比可判断“没收到帧”还是“注入失败”
  - 未知 clid 的语音帧、SVC `ClientVoicechat` 未就绪会各告警一次
- v0.9.8（换服后频道不同步修复）：
  - 生产路径连接成功后主动发送 `channellist`（此前只有自测工具发送，导致新会话频道名→cid 表为空，客户端停在 TS 默认频道）
  - 主频道查不到时每 10s 主动刷新 `channellist` 并用 `channelfind` 兜底
  - `/tsvoice status` 增加 `sent=`（成功发往 TS 的麦克风帧数）
- v0.9.7（彻底移除定位音频）：
  - 删除 `OcclusionCache`（逐源遮挡滤波）、多普勒（AL_VELOCITY）、`PlayerVelocityTracker` 位置/速度追踪
  - 新增 `OnlinePlayers`（仅在线玩家 UUID/名字快照）用于同服识别
  - TS 音频统一等响度，无距离衰减/定位/多普勒

## 开发项（规划中）

| 优先级 | 项 | 说明 | 量级 | 备注 |
|---|---|---|---|---|
| ⭐⭐⭐ | 音乐 bot 豁免适配（前置小开发） | 识别音乐 bot（配置 `musicBotName`/uid）：跳过 Ducking 触发与说话高亮；Ghost 名显示 `🎵 <bot名>` | 小 | 见下方"音乐机器人"部署项的前置 |
| ⭐ | whisper 私语 | TS3 whisper-list 实现（无视频道定向说话） | 大 | 按社群需求再排 |

## 部署/运维项（非开发）

### TS 音乐机器人（音乐共享方案，定案：直接部署，不自研）

**方案**：部署 TS3AudioBot（开源，https://github.com/Splamy/TS3AudioBot）——bot 在 TS 频道播音，MC 玩家（mod 即 TS 客户端）与 TS 用户同源共听，零开发。

闭环（多数已就绪）：
1. 管理员建半永久子频道"音乐台"（Query 一条命令）
2. tsbridge group-sync 自动镜像为 SVC 常驻群组 ✅（已支持）
3. MC 玩家加"音乐台"群组 → mod 自动进音乐频道 ✅（已支持）
4. 音量列表单独调 bot 音量/静音 ✅（已支持）；AEC 对音乐正常防回声 ✅

部署要点：与 TS 同机；YouTube 播放需 ffmpeg；首次连接自动生成身份。
**前置**：先完成上表"音乐 bot 豁免适配"（否则 bot 持续播放会一直触发 Ducking 压低游戏音量、说话高亮常亮）。

## 搁置/不做

| 项 | 原因 |
|---|---|
| 绝对响度/介质增益（SVC-Extra） | 依赖音源位置；TS 音频无位置（pos=null），两个分支都走不到。待将来给 TS 音频挂"虚拟位置"再评估 |
| 自研音乐播放器（SVC-Extra MusicPlayer 路线） | 被"TS 音乐机器人"方案取代（TS 用户也能听、零开发） |
| TCP 改造 SVC 自身网络（SVC-Extra McChannel 路线） | 与 TS 后端无关（语音不走 SVC 网络） |
| 距离衰减/3D 声像 | 架构性不支持（TS 无坐标）；混响已部分补偿空间感 |
