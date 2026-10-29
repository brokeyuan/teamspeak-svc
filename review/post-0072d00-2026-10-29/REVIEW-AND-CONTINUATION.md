# 0072d00 交付复核与连续执行接点

> **后续执行范围更新：**用户要求本轮结束后做客户端验收，下一轮按[验收候选任务书 F0–F7](../../docs/superpowers/plans/2026-10-29-client-acceptance-candidate-plan.md)实施并交付候选/手册后交接。本报告继续提供缺陷依据，原“再继续 Music-2/3”的顺序不作为本轮自动扩展指令。

日期：2026-10-29。复核 HEAD：`0072d00a047c89eaf56ad6614129807c7a2c3b6e`。本轮只读业务代码，检查在 git archive 临时副本执行；未启动客户端或用户服务器，未清理工作区已有修改。

## 结论与准确进度

本轮修复具有实质进展，可以保留：Mixin 路径已纠正、候选 JAR 九个配置类均存在；SVC 原生 UDP 委托和客户端 receiver 已补；TS raw/envelope 桥与 requestId 隔离已有实现；MusicEngine 的序列背压与暂停/关闭有对应修复；Bot purpose 登记已预留。

但“E0–E9 已全部完成、T31–T38 全部关闭”仍过宽。**E4 生产链未完成，E5 有产品规则偏差，E7 仍是接口/测试接线而非可用独立 Bot，E8 明确只完成基础。** 应继续原任务书，不重新推翻已修复模块，也不直接越过这些缺口只添加命令。

## 本次证据范围

- 候选哈希与交付一致：`01B23D6A23BB1881CCEAAE4DAB7989D008138800E4E762C83675B835F2D7A03F`；Mixin 九条完整类名均有 JAR entry，条件 plugin 存在。
- 已读取对方 RunAll 和 dedicated 无/有 SVC 启动摘要；这属于对方证据，本轮未重启服务器。现有有 SVC 启动组合还包含旧 tsbridge，后续最小部署矩阵应补“不装旧 Bridge”的组合，不能将它写成必装条件。
- 独立复跑 TS relay、SVC MC channel、Music-0、route-lifecycle 四组，均退出 0。本轮未独立复跑完整 RunAll 或 Gradle build。
- 新增三个无客户端探针，均复现下列边界；实际 JAR 的 ClientVoicechatConnection.class 字节码也已核对。证据及探针源码文本在 `evidence/`。
- 当前 RunAll 数组确有 26 个 harness，交付日志有 26 条 PASS；脚本最后一行仍硬编码 23。属于汇总文本未更新，不据此否定实际 26 条通过，应改为按实际数组计数。

## 1. E4：服务端局部测试通过，不等于生产代际闭合

位置：`client/TsVoiceManager.openMcRelaySession()`；`client/ts3voice/TsTransport/TsMcChannelTransport/Ts3Connection`；`server/relay/TsRelayRuntime/TsRelaySession`；`TeamspeakSvcMod`。

当前客户端 sender 仍构造：

```java
new TsRelayWire.DataFrame(session, 0, 0, datagram)
```

仍从可变全局 mcRelaySessionId 取 session；未发现客户端实际发出 ROUTE_BARRIER 的生产路径。服务端 advanceRouteBarrier 的确实现了单调检查，但只在测试主动喂 barrier 时工作；真实客户端既未带真实 epoch，也未推送 barrier。因此 T33 只关闭了一部分。

此外，协商 maxBytesPerSecond 没有实际计费/拒绝路径；只有 C2S pps bucket。DNS/建 socket 仍在 OPEN receiver 调用的同步链，DATA 仍直接 UDP send，缺少既定有界发送/MC 待提交任务背压。DELIVERY 已把部分背压列为后续问题，不能同时把整个 E4 标完成。

**继续要求：**按原任务书 E4 完成入队数据报的不可变 session/epoch/traffic 传递；sender 绑定创建时 session；路由失效和频道确认真正发 barrier；服务端最终 writer 检查并清旧媒体队列。全部流量计 bytes/pps，队列/任务总字节有界，I/O 不阻塞 MC 主线程。测试真实生产 sender→payload→runtime→阻塞 writer，不能只直接调用 server.onRouteBarrier。

## 2. E2：阻塞 UDP read 切 MC 会抛异常，实际 SVC 读线程将结束

位置：`client/backend/SvcMcChannelSocket.read()/activateMcChannel()`。

read() 已进入 UDP receive 时，activateMcChannel() 关闭该 UDP Socket，readUdp 的 SocketException 会直接向外传播。探针结果：

```text
result during UDP-to-MC switch=java.net.SocketException: Socket closed
closed=false, mcMode=true
```

从实际 SVC 2.6.25 JAR 提取 ClientVoicechatConnection.class 核对：run() 的整个读取循环受外层 Exception catch 包围；捕获后记录错误并 return，不是 catch 后继续读。故这不是可直接忽略的“模式切换噪声”。本轮未启动实际游戏观察断线/超时 UI，但生产异常路径和上游退出行为均有依据。

**继续要求：**本对象未真正关闭、且确因内部模式切换关闭旧 UDP 时，在 Socket 内部处理该受控唤醒，回到新模式读取；真正断连或其他 I/O 错误仍按契约退出。补“已经阻塞的 read→宣告→MC 帧被同一读线程取得”、反向禁用/重载所需的显式会话重建、close 唤醒测试。

## 3. E5：PAUSED 无选中后端，却重新放行 SVC 收听

位置：`client/backend/SvcBackendAdapter.isNativeReceiveSuppressed()`；`review/route-lifecycle/SingleBackendListenTest`。

当前 Optional.empty 被 orElse(SVC) 解释成 SVC，探针结果：

```text
intent=PAUSED, backend=Optional.empty, suppress native SVC=false
```

最新产品规则和 E5 任务明确要求：默认只收听选中后端，PAUSED 没有活动后端时不自动恢复另一路收听。现有测试却继续断言 PAUSED 放行原生接收。**PAUSED 与 OFF 是不同用户操作，不表示 PAUSED 可以擅自收听未选中的 SVC。**

**继续要求：**按实际 selectedBackend 与明确意图派生接收许可；PAUSED/无后端不隐含 SVC，只有明确选择 SVC 才恢复。同步清理不再拥有的播放队列，保持用户原静音/音量设置。修正测试的业务断言，而非保留旧行为来满足测试。

## 4. E7：编码中的旧帧在 cancel 返回后仍调用 sender

位置：`client/music/MusicSession.encodeAndSend()`。

状态只在编码前检查一次；编码完成后没有复核 state/播放代际/连接与频道许可，直接读取当前 frameSenderRef 并发送。阻塞 encoder、调用 cancel、再释放 encoder 的探针结果：

```text
state after cancel=CONNECTED, sender callbacks after cancel=1
```

MusicEngine 在调用 sink 前的 generation 检查不能保护 sink 内编码期间的变化。后续接真实 TS sender 时，这个窗口不能保留。该探针使用当前 MusicSession 与注入编码器/发送回调，不声称当前已经有真实 Bot 发包。

**继续要求：**编码开始时捕获音乐 session、playback generation、encoder、source route 和 sender；编码后及最终网络入队时复核，不能给旧帧换绑新 sender。覆盖 cancel/pause/replace/房间移动/断连/OFF 发生在编码与发送之间的场景。

## 5. MusicSession 的真实完成边界

目前仅有 ConnectionBundle、connectForTest、startPlaybackForTest；Dependencies.createIdentity 未被调用，未发现生产实例化入口。acquireEncoder() 恒抛 EncodingUnavailableException。公共 startPlayback() 因此没有可用的生产编码器工厂；测试编码字节也不是 Opus 实际 encode→decode。

这是明确的“音乐会话接口与单测完成，生产实现待接线”，不能描述成独立身份/真实编码/发送许可全链已可用。报告所列下一步 Music-1 connect 是合理方向，但 E7/T38 状态应保留部分完成。

**继续要求：**实现强类型生产连接/encoder 工厂，独立 identity/client/transport，真正的音乐 channel/route 许可与资源所有权；移除仅测试对象冒充生产连接的表述。优先通过已允许的客户端官方 API 适配证明最小路径，同时保留并验证无客户端 SVC 的共享编码方案。真实 codec 的纯 encode→decode 不需要实体麦克风或扬声器，不应整项归入“客户端暂缓”。

## 6. 继续执行的顺序，不再另起总体设计

统一开工文件仍为 `docs/superpowers/plans/2026-10-29-next-round-execution.md`：

1. **E2 补读线程换态；E4 完成生产代际/字节额度/有界任务；E5 修正暂停收听。** E1 已修复的包名不重做，保留相应回归。
2. **E7 补编码后最终许可与真实生产工厂。** 不复用玩家会话，不以合成编码器测试声称 Opus 已可用。
3. **E8 继续完整 Music-1。** 不只增加命令：独立 Bot 连接到确认的 serverUID/cid、start/pause/resume/next/stop/queue 的最小界面/命令入口、点歌者及明确状态；房间/owner/requestId/可信 actor 来源与限额；离开语音默认停本机 Bot。
4. **开放受控 MUSIC_BOT 中继。** 使用含义明确的 TS relay 配置，例如 `relay.musicBotEnabled=false`，不要命名为 svcMcChannelMusicEnabled 将 TS Bot 与未批准的 SVC 音乐/媒体桥接混淆。分 purpose/session 的限额、生命周期与玩家独立，配置默认拒绝是正确基础。
5. **E9 真实工程收口。** 当前候选最终哈希、实际命令退出码和测试数量回填同一权威状态；DELIVERY 的“进行中”、旧终点和 STATUS 内重复历史当前段应整理。无/有 SVC 且不装旧 Bridge 的最小 dedicated 矩阵、reload/停止资源检查有条件即做；原客户端暂缓矩阵继续保留。

各子项独立提交并在完成后继续下一项；Music-1 可操作闭环后再继续 Music-2/3，P7 与网页/手机仍不自动实施。当前会话不执行这些生产修改。
