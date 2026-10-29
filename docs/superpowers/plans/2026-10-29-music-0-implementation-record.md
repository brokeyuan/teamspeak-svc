# Music-0 实施记录（S4 起点，按总任务书第 5 节先行记录再实施）

日期：2026-10-29。基线 `1b598ac`（S0–S3 完成后）。

## 范围（本阶段）

授权本地音源（PCM/WAV/测试音）→ 独立队列/节拍/暂停取消 → headless Opus 编码
→ TS 音乐 codec 发送（`CODEC_OPUS_MUSIC=5`）。**不含** Music-1 的独立 Bot
身份/独立会话、Music-2 的完整页面与提供方适配、Music-3 的服务端 Music Host；
不含 P7（待负责人确认）。

## 技术决策

1. **编码方案（headless、不依赖客户端 SVC 私有实现）**：优先使用 SVC 官方 API
   `VoicechatApi.createEncoder()`（`de.maxhenkel.voicechat.api.opus.OpusEncoder`，
   48kHz 单声道 short[] PCM → Opus；`encode/reset/isClosed/close` 面，2.6.2x
   稳定）。API 不可用（无 SVC 客户端实例）时本阶段<b>不降级到裸 libopus 绑定</b>
   ——引入新原生依赖需单独记录版本/许可/装载证据（总任务书第 6 节），测试音/
   WAV 路径以注入自有编码器接口的方式保持可测。
2. **音源授权边界**：本阶段只接受 (a) 本地 WAV（PCM_S8/16/24/32、采样率重采样
   到 48k 单声道由 javax.sound 完成）与 (b) 内置测试音（正弦/扫频）。**不引入**
   任何在线搜索/转播；Music-2 的提供方适配另行验收（搜索结果 ≠ 可转播音源）。
3. **队列/节拍**：独立 `MusicEngine` 内置队列（有界，默认 512 帧 ≈ 10s @20ms；
   满时**背压**（停止预读），不丢帧 —— 有序播放优先；时钟可注入）；
   调度用专用 daemon 线程 + 绝对节拍（`nextFrameAt += 20ms`，防漂移），暂停=
   停止出队保留位置，取消=清队列并关闭音源；与会话解耦：会话关闭时队列取消，
   但音源所有权归 MusicQueue。
4. **发送路径**：编码后的 Opus 帧经 `Ts3VoiceClient.sendVoice(opus, CODEC_OPUS_MUSIC,
   routeEpoch)` 走既有路由/许可/代际链（与麦克风同一许可模型，R21 双条件）。
   **音乐不与麦克风争抢发送所有权**：Music-0 只在本地主持人明确"播放到 TS"时
   激活；玩家语音不中断（发送并行，两个源各自帧）。
5. **资源边界**：单实例（本阶段无多队列）；编码失败/音源结束 → 自动停并在
   状态接口反映原因；close 幂等；不启动外部服务、不读网络。
6. **测试面**：`review/music-0/RunMusicCore.ps1` —— WAV 解析/重采样（正弦
   fixture）、节拍调度（假时钟）、暂停/取消/边界（空队列/满队列）、编码接口
   契约（stub encoder 记录帧数）、与 routeEpoch 的绑定（许可撤销后不再发出）。

## 不做/留待（明确）

- 不做独立 TS 身份/第二会话（Music-1 范围，含 relay 的 MUSIC_BOT purpose ——
  wire 已预留 purpose 枚举，未开放授权）。
- 不做 MC relay 上的音乐通道（同上，等 Music-1 明确授权后开放第二个 purpose）。
- 不做听感/音量验收（客户端暂缓范围）。
- 不引入新库；如 Music-1 需要独立解码器（mp3/ogg）再按第 6 节记录。
