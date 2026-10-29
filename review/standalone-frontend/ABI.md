# SVC 2.6.25 前端 ABI 与证据分级（T08a / N2 修订）

适配实现：`teamspeak-svc/src/main/java/com/tsvoice/client/frontend/Svc2625FrontendAdapter.java`。
本文件记录哪些入口已经过字节码核对、哪些只是"类存在"、哪些需要真机验证 —— 三层不可混淆。

**修订（2026-10-29 N2/D01）**：旧版本将 processSoundPacket 的 null-connection 行为列为
"未验证"，实际其字节码早退门已由 delivery-audit 2026-10-29 用实际 JAR 反汇编确证
（`evidence/ClientVoicechat.javap.txt` 偏移 0–7：getfield connection → ifnonnull → return）。
该行为**已知且确定**：null 连接静默丢弃、不创建 AudioChannel。因此 TS 下行播放改为
适配器直接拥有 AudioChannel，不再委托该入口。

## 证据分级模型

| 层级 | 含义 | 观测位置 |
|---|---|---|
| L1 hook 存在 | inspect() 反射核对构造器/方法签名 | `FrontendCapabilities`（micThreadUsable/playbackInjectionUsable） |
| L2 投递动作完成 | 包已加入自有 AudioChannel 队列（attempt/enqueued/resourceFailure 分级计数） | `Svc2625FrontendAdapter.playbackDiagnostics()`、`TsVoiceManager.frontendLeaseHeld()/frontendMicAlive()`、`TsPlaybackRegistry` |
| L3 音频已验证 | 真实设备/真实 TS 双向通话 | E01/E11（真机矩阵，见 ACCEPTANCE.md）——**按用户要求暂缓，未验证** |

## 已核对（javap，对 libs 内实际 JAR `189605FD…79407`）

- `MicThread(ClientVoicechat, ClientVoicechatConnection, Consumer<MicrophoneException>)`：
  run 循环与 sendAudioPacket 显式判空 connection —— null 连接时采集/PTT/语音激活
  照常（`sendAudio` 仍触发 `ClientPluginManager.onClientSound` → ClientSoundEvent →
  MicRedirector），仅跳过 SVC 发送（隔离采集的前提）。
- `ClientVoicechat.getMicThread()` / `closeMicThread()` / `getSoundManager()`。
- `ClientVoicechat.processSoundPacket(SoundPacket)`：**偏移 0–7 的 null-connection
  早退门已确证** —— connection 为 null 时静默 return，不创建 AudioChannel、不排队。
  TS 下行播放**不再使用该入口**。
- `AudioChannel(ClientVoicechat, InitializationData, SoundManager, UUID)`：
  构造器仅保存 InitializationData；**2.6.25 字节码中该字段存储后从未读取（0 次
  getfield）**，运行期实际依赖 client（getRecorder/getTalkCache/setRecording）、
  soundManager（isClosed/isClosing）、CLIENT_CONFIG 与 Minecraft 静态上下文 ——
  均不经过网络会话。独立播放以 null InitializationData 创建自有通道，不伪造
  Secret。公共面：`addToQueue(SoundPacket)` / `isClosed()` / `canKill()` /
  `closeAndKill()`；实例为守护线程（字节码 iconst_1; setDaemon）。

## 未验证（不得宣称可用）

1. **真机听感**：自有 AudioChannel 的端到端音频输出（扬声器、音量曲线、设备切换）
   —— E01/E11 真机矩阵，用户要求暂缓。L2 只证明包进入 SVC 解码/播放对象队列。
2. **隔离 mic 的设备流**：MicThread 在 null-connection 下对输入设备的选择/切换/失焦行为。
3. **停止事件探测路径**：CLIENT_STOPPING 时 MicThread.close 的实际清理语义。
4. **音量迁移**：SVC 音量配置（getVolumes）的实际形态未核对 —— 迁移为尽力而为+日志。
5. 个人音量/静音/设备选择在参与者键下的真机一致性。
6. 双通道边界：自有通道与原生 SVC 通道（uuid 空间分离：参与者 UUID 派生）在真机上
   的相互影响未观测。

## 本轮交付状态（N2）

- T08a/N2 ABI 修订：null-connection 早退门从"未知风险"改为"已确证事实"；独立播放
  改为自有 AudioChannel 路径（create → addToQueue → start，与原生创建顺序一致）。
- T08b/N2 租约：失效租约先释放再创建（D02 探针修复）；SVC 断开钩子接入 manager
  （onSvcDisconnected → 前端交接）；SVC 建立与会话周期复核借用/自有交接（避免双采集）。
  —— 自动化：standalone-frontend 39 项（R1 恢复链/R2 交接/F1–F6）+ route-lifecycle。
- T08c/N2 播放：play() 直接拥有每条流的 AudioChannel；所有权快照供注入器/注册表
  复用（retire 双路关闭：自有通道 + SVC 表内历史对象）。
  —— 自动化：F5（不经原生门、通道创建/复用/退役、诊断分级）+ F5b（资源失败不伪报）。
- **客户端音频验收：按用户要求暂缓** —— L2 证据齐备；L3（E01）待用户恢复验收。
