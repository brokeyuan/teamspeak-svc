任务：T11 / 对应 R05（能力发现生命周期：nonce/无公告窗口/TTL）

基线与提交：
- 起点：T08 提交 a295ed6 之后；本任务提交见 git log T11。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/connection/CapabilityDiscovery.java
  （begin()：窗口从调用起计时——无公告也正常结束（修"从未收到公告 windowExpired 恒 false"）；
  每轮生成 requestNonce，响应按 contextEpoch+nonce 双重校验（修"nonce 校验未实现"）；
  TTL 绑定接收时刻记录 expiresAtNanos，快照/查询按当前时钟剔除过期候选
  （修"过期公告仍被视为可用授权候选"）；TTL=0 不产生候选；
  新增 receivedCandidates()——实际接收未授权快照，授权弹窗数据源；
  authorize 子集校验：外部任意列表/已过期候选被拒绝（"授权绑定实际已接收的快照"）；
  过期判断使用注入单调时钟）
- review/p4-discovery/DiscoveryFlowTest.java（扩展至 30 项：无公告窗口、错 nonce、
  任意列表授权拒绝、TTL 过期剔除、TTL=0、过期授权拒绝；既有场景全部保留）

原缺陷的失败证据：
- R05 探针（历史轮）三项：无公告 2 秒 windowExpired=false；TTL=1s 公告 120 秒后仍被推荐；
  nonce 校验缺失。本轮以新断言覆盖同一缺陷面（windowExpired 无公告超时、TTL 过期剔除、
  错 nonce 丢弃），修复前这些断言按旧 API 无法表达（begin/nonce/TTL 语义不存在）——
  属 API 级缺陷，修复后由 30 项套件钉死。

修复后自动化命令、原始日志、结果：
- RunFallbackPolicy.ps1 → 30 项全 PASS（T11-discovery.log）。

相关旧回归：FallbackPolicyTest 18 项全 PASS（选择策略未动）。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。E08（有效/无/错 nonce/过期公告、切 MC）需可控公告服务端（T12 接线后）。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（发现类生命周期闭环）。
- 自动化：完成。
- 集成：未开始 —— 生产调用方（payload 接收注册、hello 发送、选择策略驱动）属 T12；
  本任务不预造接线。
- 真实环境：未验证。

与本计划偏差及原因：
- 授权 API 收敛为"快照绑定 + 子集校验"两个入口（计划语义的实现细化）：
  authorize 必须引用 receivedCandidates() 的成员且未过期，外部构造的候选被拒绝。
- TTL 保持在 Capabilities 级（wire 现状），未加候选级 TTL。

已知限制与回滚：
- 授权快照的"有界刷新/新 nonce 续期"语义（计划 T11 第 4 条）依赖 T12 的请求循环；
  本任务覆盖窗口/TTL/nonce 的状态机部分。
- 回滚：回退本任务提交；R05 三缺口回归。
