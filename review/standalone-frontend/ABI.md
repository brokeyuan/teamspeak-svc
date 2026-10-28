# SVC 2.6.25 前端 ABI 与证据分级（T08a）

适配实现：`teamspeak-svc/src/main/java/com/tsvoice/client/frontend/Svc2625FrontendAdapter.java`。
本文件记录哪些入口已经过字节码核对、哪些只是"类存在"、哪些需要真机验证 —— 三层不可混淆。

## 证据分级模型

| 层级 | 含义 | 观测位置 |
|---|---|---|
| L1 hook 存在 | inspect() 反射核对构造器/方法签名 | `FrontendCapabilities`（micThreadUsable/playbackInjectionUsable） |
| L2 资源已创建 | 租约持有 + 通道对象快照 | `TsVoiceManager.frontendLeaseHeld()`、`TsPlaybackRegistry`、`Svc2625FrontendAdapter.injectedFrames()` |
| L3 音频已验证 | 真实设备/真实 TS 双向通话 | E01/E11（真机矩阵，见 ACCEPTANCE.md）——**当前全部未验证** |

## 已核对（javap，历史轮对 libs 内实际 JAR）

- `MicThread(ClientVoicechat, ClientVoicechatConnection, Consumer<MicroException>)`：
  run 循环与 sendAudioPacket 显式判空 connection —— null 连接时采集/PTT/语音激活
  照常，仅跳过 SVC 发送（隔离采集的前提）。
- `ClientVoicechat.getMicThread()` / `closeMicThread()`：借用原生 mic 的入口。
- `ClientVoicechat.processSoundPacket(SoundPacket)`：TS 下行注入入口
  （经 SVC 抖动缓冲/解码/个人音量/输出设备）——T08c 的 play() 委托点。
- `AudioChannel(ClientVoicechat, InitializationData, SoundManager, UUID)`：
  构造器仅保存 InitializationData（26.2 参考源码为 3 参的 ABI 差异）。

## 未验证（不得宣称可用）

1. **null-connection 下行注入**：processSoundPacket 在 ClientVoicechatConnection 为 null
   时的行为（NPE、静默丢弃还是正常播放）未真机验证 —— 这是"无 SVC 服务器的独立播放"
   的核心前提。
2. **隔离 mic 的设备流**：MicThread 在 null-connection 下对输入设备的选择/切换/失焦行为。
3. **停止事件探测路径**：CLIENT_STOPPING 时 MicThread.close 的实际清理语义。
4. **音量迁移**：SVC 音量配置（getVolumes）的实际形态未核对 —— 迁移为尽力而为+日志。
5. 个人音量/静音/设备选择在参与者键下的真机一致性。

## 本轮交付状态（T08）

- T08a：本文件 + FrontendCapabilities 的 L1 核对（真机缺失时 inspect 失败 →
  FRONTEND_UNAVAILABLE，绝不伪报 READY）。
- T08b：管理器租约接线（startSession 先取租约；stopSession 释放；SVC 断开后按
  所有权交接重取；acquire 先保留所有权再创建资源，失败原子释放）。
  —— 自动化：route-lifecycle（C/D 场景断言租约持有/释放）+ standalone-frontend（14 项）。
- T08c：play() 注入入口（版本敏感代码收敛到适配器）；注入器委托。
  —— 自动化：F5/F6（管线注入计数 + 无效输入拒绝）。
- **M3 状态：集成待验收** —— 真实 SVC 2.6.25 JAR 不在工作区（仅 API JAR），无音频设备
  与真实 TS 服务器；L3 验证（E01）未执行，M6 不能因 M3 标记通过。
