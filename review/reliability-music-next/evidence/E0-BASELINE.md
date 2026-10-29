# E0 基线（2026-10-29）

- 实际 HEAD：`81c687cdfb3b6f067be3c655c23152c6960581c9`（S4 STATUS 提交，与任务书参考基线一致；无更新提交）。
- 工作区未提交内容：47 项（`baseline-uncommitted.txt`）——docs/superpowers 任务书、docs/product 成品 README、
  review/post-81c687c-2026-10-29 复核证据、review/post-m7-2026-10-29，及 gui-integration/route-lifecycle 两个
  stub 集合的既有修改。全部保留，不 reset/clean。
- 依赖版本：MC 26.3 / Fabric loader 0.20.1 / Fabric API 0.165.0+26.3 / voicechat-api 2.6.20（compileOnly）/
  loom 1.17.21 / Java 25 / mod_version 0.9.8；ts3voice-core 0.1.0（java-library）。
- 候选 JAR：`teamspeak-svc/build/libs/teamspeak-svc-0.9.8+26.3.jar`
  SHA-256 `7a75fb4415912f1fe0f6d849ffd2faeb4556aedbb8f9077caaa0a7eb3af98d60`
  （= 81c687c 复核中的缺陷候选，T31 未修复的证据状态）。

## RunAll 真实清单（梳理结果）

- Phase 1：ts3voice-core `gradlew clean test`（同时产出 5 个链接 core classes 的 harness 所需构建）。
- Phase 2：**26 个 harness 套件**（脚本注释写 23，实际数组 26 项——channel-fixes 2、client-fixes 2、
  p0-defects 4、p1-isolation/p1-transport/p1-playback、p2-frontend、p3-config/p3-channel、p4-discovery、
  route-lifecycle、session-consumption、playback-ownership、standalone-frontend、server-fixes、
  upload-confirmation、gui-integration、ts-mc-relay、svc-mc-channel、music-0、m7-unified-release）。
  最终 PASS 行的 "(core test + all 23 harness suites green)" 计数文本过期，待 E9 顺带修正。
- 顺序：core 先行 → 各套件按数组顺序独立 powershell 进程；任一失败 → 退出 1。
- 环境要求：Windows PowerShell、JDK 25（自动探测 `C:\Program Files\Java\jdk-25*`）、loopback UDP 可用。
- 已知限制：个别环境 HttpClient/loopback 初始化曾失败（STATUS 既有记录）；本轮按套件分跑并如实记录。

## 复核反例 → 本轮正式回归计划

81c687c 复核的 probe 保留为历史证据（post-81c687c-2026-10-29/evidence，不修改）。本轮把它们转成
“修复后应满足”的正式断言，并加入统一入口：

- RelayBoundaryProbe 6 项反例 → `review/ts-mc-relay`（T32 回包封装 / T33 barrier·限额 / T35 长度 /
  T34 OPEN 冲突）扩展断言：生产编码桥消费真实 UDP 回包、barrier 之下拒绝、限额真实拒绝、
  长度统一 4096 不截断、pendingOpens 按 connection 隔离。
- MusicBoundaryProbe 2 项反例 → `review/music-0` 扩展断言：容量判定先于 readFrame（无漏帧）、
  close 后 play 拒绝（终态）。
- Mixin 产物检查 → E1 在 `review/m7-unified-release/RunM7.ps1` 升级为逐条完整限定名→JAR entry 断言
  （当前该脚本只查数量）。

## 探针源码核对（fix 方向）

- `RelayBoundaryProbe`：生产 sink 以 `TsRelayWire.decode(b)==null` 拒绝 raw UDP → T32 契约统一；
  barrier=100 后 epoch=99 仍转发 → T33 代际；pps=1 四包全过 → T33 限额；3000 字节收 2048 → T35；
  两个 connection 同 requestId=1 第二个失败 → T34。
- `MusicBoundaryProbe`：capacity=2、源 1–6 输出 [1,2,4,6]（3、5 被满队列吞噬）→ 容量判定先于
  readFrame；close 后 play 复活 → 终态保护。
