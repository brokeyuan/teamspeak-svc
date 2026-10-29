# G0 基线（2026-10-30）

- 实施位置：独立 worktree `C:\Users\Administrator/Documents\Workspace/Brokeyuan/mods/teamspeak-svc-repair`，
  分支 `codex/acceptance-repair-20261030`，起点 = origin/main `bdea14e`（PR #1 合并提交）。
- 原工作区（teamspeak&svcbridge）保持 `5ec6f2b` + 未提交文档；其未提交内容已核对：
  三个已跟踪文档与 origin/main **逐字节一致**（仅行尾表示差异），docs/README/publish-audit 均
  已在远端跟踪。唯一远端没有的是本任务书文档，已复制入 worktree 随本轮提交。
  原工作区未做 reset/clean/pull。
- Java：25.0.4.1（见 g0-env.txt）。远端 main 即最新；PR #1 合并提交 bdea14e 已确认。

## 原始失败证据（修复前基线，均如实保留）

1. **探针复现**：`RunProbes.ps1` exit 0 = 缺陷复现成功（不是产品通过）。
   - MusicWriterProbe：Bot epoch=1 被真实 writer 默认 supplier 拒绝，dropped +1；
     对照组显式绑定 supplier 后同代际通过（P1-01）。
   - RelayControlProbe：barrier=200 后 `send(byte[])`（epoch0）被拒；`send(data,200)` 通过（P1-02）。
   - 日志：g0-probes-baseline.log。
2. **麦克风 harness 干净副本编译失败**：RunMicRedirector.ps1 exit 1，两处
   `TsVoiceManager` 对 `canSend` 找不到符号（1287/1452）。日志：g0-mic-baseline.log。
   与远端已确认失败 https://github.com/brokeyuan/teamspeak-svc/actions/runs/40918992098 一致
   （core/客户端构建通过、麦克风 harness 失败）——不是本机 Java 问题。
3. 环境已知限制：本机 HttpClient loopback 故障导致 session-consumption /
   upload-confirmation / RunM7 自动构建失败（复核已归类），与 P1-01/02/03 无关。
