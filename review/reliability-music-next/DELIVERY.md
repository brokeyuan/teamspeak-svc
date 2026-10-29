# E0–E9 轮次交付报告（2026-10-29）

基线 `81c687c` → 本轮结束 `06c35d8`（8 个独立提交，E1–E8 各一）。

## 已实现（每项：反例 → 最小修复 → 回归 → 独立提交）

| 项 | 提交 | 内容 | 测试 |
|---|---|---|---|
| E1/T31 | dfbfd5f | mixin config package 修正为 `com.tsvoice.client.mixin`；新增 `TsVoiceMixinPlugin`（SVC 目标 server mixin 仅在 voicechat 在位时应用）；RunM7 升级为逐条 FQN→JAR entry 断言（负例 JAR 验证会 FAIL） | RunM7 全 PASS；负例 8/8 缺失被拒 |
| E2/T36 | d6a9b9e | SvcMcChannelSocket 改为双模（open() 创建真实 DatagramSocket，宣告前=原生 UDP 委托，4096 接收边界；宣告后单向切 MC）；宣告/初始化顺序无关（pending 兑现）；S2C receiver/config 接线；服务端登记按 server instance+connection、地址回收不复用、createUnresolved 合成地址、svc-extra 让渡 | SvcMcChannelLoopbackTest 28 断言（含真实 UDP 回环）+ 原套件 |
| E3/T32+T34 | 5ecb27b | `TsRelayWire.classifyUpstreamDatagram` 唯一外层编码点；生产 sink 按分型包装 DATA_S2C/透传控制帧（raw 不再被当 TSRL 拒）；服务端 pendingOpens 按 connection#requestId，会话终结释放登记；客户端 PENDING_OPENS 按 requestId+context+transport 绑定 | RelayProductionBridgeTest 11 断言（真实 echo 穿生产桥） |
| E4/T33+T35 | 49ee583 | 会话级单调 routeBarrier（倒退拒绝）；协商 pps token bucket 真实拒绝（突发+回填）；服务端 UDP 接收与客户端 receive 统一协商 4096；receive(maxLen) 不足明确 IOException（不裁剪） | RelayBoundaryRegressionTest 16 断言 |
| E5 | 39aacb5 | `isNativeReceiveSuppressed` 改为按协调器选中后端判定（默认单后端收听；OFF 仍压制；PAUSED 不隐含 OFF）；TS 选中时 SVC 原生下行压制；切回 SVC 恢复；用户音量/静音不动 | SingleBackendListenTest 11 断言 + OffGate O3/O4 断言更新为已确认产品规则 |
| E6/T37 | 2e9b08d | MusicEngine：容量判定先于 readFrame（无损背压，容量 1/2/512 下 1..6 完整有序恰好一次）；close 终态（play 拒绝）；PAUSED 阻塞等待（ThreadMXBean CPU 探针 ~0ms/300ms）；playback generation（取消/替换后旧代在途帧不进新播放，值域 100..149 vs 1..3 验证）；source 四种终态恰好关一次；sink 在锁外（控制操作即时返回） | MusicEngineBoundaryTest 23 断言 |
| E7/T38 | 2a4d615 | 新 `client/music/MusicSession`：独立身份面/连接生命周期/编码器/发送桥；绝不触碰玩家 endSession/currentClient；`OpusEncoderAdapter` 自有接口（SVC API 是实现之一，不是前提）；无编码器明确 FAILED；48kHz 单声道 20ms + CODEC_OPUS_MUSIC=5；发送许可=引擎代际+会话状态门 | MusicSessionTest 16 断言（W1 字节级接线验证）；实施记录追加修订附录 |
| E8 基础 | 06c35d8 | `Purpose.MUSIC_BOT(2)` wire 预留；登记按 connection#purpose（PLAYER_VOICE 与 MUSIC_BOT 互不覆盖；断连按连接回收全部 purpose；PLAYER_VOICE 单会话约束按 purpose 判定）；MUSIC_BOT 默认 ERR_NOT_AUTHORIZED（受控开放） | RelayMusicBotPurposeTest 6 断言 |

## 实际测试（本轮全部真实执行，非 stub-through）

- RunTsMcRelay.ps1：**64 PASS**（RelayRuntimeTest + ProductionBridge + Boundary + MusicBotPurpose 四类，含真实 UDP echo 回环）。
- RunSvcMcChannel.ps1：**40 PASS**（原 R1–R8 + Loopback/UDP 保护 28 断言，真实 loopback UDP）。
- RunMusicCore.ps1：**47 PASS**（引擎 + 边界 + 独立会话三类；真实线程/CPU 探针）。
- RunRouteLifecycle.ps1：**exit 0**（含新 SingleBackendListenTest；OffGate 断言按 E5 规则更新）。
- RunM7.ps1：**PASS**（含逐条 mixin FQN→JAR entry 断言）。
- ts3voice-core `gradlew test`：**108 protocol checks PASS**。
- teamspeak-svc `gradlew compileJava/build`：exit 0。

## 构建产物

- 候选 JAR：`teamspeak-svc/build/libs/teamspeak-svc-0.9.8+26.3.jar`
  SHA-256 `01b23d6a23bb1881cceaae4dab7989d008138800e4e762c83675b835f2d7a03f`
  （以 06c35d8 源码构建；交付前以最终提交重算）。

## Dedicated 装载验证（E1，真实执行）

testserver（Fabric 0.20.1 / MC 26.3 / GraalVM 25）两种矩阵均真实启动：

- **无 SVC**（fabric-api + teamspeak-svc）：MIXIN Subsystem 启动、`common init complete`、
  runtime 启动、Done (0.269s)；mixin error/failure 行 = 0；带 relay 配置启动同样正常。
- **有 SVC**（voicechat 2.6.25 + tsbridge + teamspeak-svc）：SVC facade 注册、
  `Voice chat server started at port 65524`、Done (0.252s)；ERROR 行 = 0。
- 验证后 testserver mods 目录恢复原状（用户进程 19696 经用户确认后重启验证并停止，
  未保留本扩展 JAR 于服务器）。
- 客户端安装矩阵继续暂缓（用户既定范围）。

## 暂缓/未验证（如实）

- 真实 MC 网络（netty 收发）与真实 TS 服务器经中继的端到端握手/音频 —— 客户端暂缓。
- SVC-Extra 双端 wire 互操作实测 —— 需目标版本环境。
- 音乐真实听感 / SVC API OpusEncoder 实机 encode→decode —— 客户端暂缓；
  本轮以编码器适配+帧字节验证为已验证边界。
- Music-1 Bot UI（加入房间/队列控制/点歌者界面/可信请求来源）—— E8 基础已落地，
  UI 与控制面在下一队列。

## 本轮环境阻塞记录

- RunAll 全量在本机后台执行中（结果回填 STATUS）；此前各套件均分跑通过。
- 无其他阻塞；testserver 已恢复。

## 剩余问题与下一条具体动作

1. RunAll 全量结果回填（进行中，日志 `$TEMP/tsvoice-ci-logs`）。
2. Music-1 继续：Bot 加入明确 serverUID/cid 房间（独立身份 connect）→
   开启/暂停/继续/下一首/停止/队列入口 → host 离开默认停 Bot。
   入口文件：`client/music/MusicSession`（startPlayback 生产 connect() 接线）、
   `TsRelayRuntime`（MUSIC_BOT 授权开关配置节）。
3. SendS2C 背压有界化复核（E4 任务书中"MC/Netty 待提交任务纳入背压"一条在
   sink 分类点已分类，Fabric send 的有界化需在 Music-1/后续轮结合真实网络验证）。
