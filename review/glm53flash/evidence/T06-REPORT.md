任务：T06 / 对应 R09（撤销 issuer 信任立即降级展示）

基线与提交：
- 起点：T05 提交 90adf5e 之后；本任务提交见 git log T06。

实际修改文件：
- teamspeak-svc/src/main/java/com/tsvoice/client/ClientIdentityService.java
  （VerifiedIdentity 显式保存 issuerKeyId；getVerifiedMcUuid 查询时复核
  trustDirectory.isTrusted —— 解除信任后存量关联立即失效；unpinTrustedIssuer
  按 issuerKeyId 移除全部已验证关联并触发显示层通知；ClaimRevoked 移除映射时同样通知；
  setIdentityChangeListener/异步安全触发）
- teamspeak-svc/src/main/java/com/tsvoice/client/TeamspeakSvcClient.java
  （注册身份变更监听 → 主线程 syncAllGhostVisibility：Ghost 展示立即降级；
  降级不改 TS 参与者稳定 UUID，不停止其音频）
- review/p0-defects/ClientIdentityServiceRegression.java（扩展 R09 场景：unpin 立即降级
  同服务器/第二服务器、其他 issuer 映射保留、显示层通知、re-pin 后旧声明重放仍拒绝、
  符合 revision 策略的新声明可再提升、过期查询返回空；注册用反射调用使套件可对旧代码
  编译并按断言失败）

原缺陷的失败证据：
- T06-prefix-fail.log：3 项断言失败 —— unpin 后查询仍返回 MC UUID（R09 核心）、
  无显示层通知、re-pin 后旧声明重放被错误接受。编译通过、仅断言失败。

修复后自动化命令、原始日志、结果：
- RunClientIdentity.ps1 → 15 项既有断言（未放宽，setClient 经生产入口）+ 8 项 R09 全 PASS
  （T06-clientidentity.log）。
- RunAll.ps1 → 20 组 harness + core test 全 PASS（T06-runall.log）。
- gradle build → BUILD SUCCESSFUL（T06-teamspeak-svc-build.log）。

相关旧回归：身份四组套件（p0-defects 的 ClientIdentity/IdentityIssuing/BridgeMembership/
IdentityRevisionStore）全部继续通过。

真实环境、步骤、结果（没有则填未验证）：
- 未验证。E09（TS 与 SVC 原生玩家同一 MC 关联；撤销 issuer 的实际展示降级与音频连续性）
  需真实多客户端环境。

代码状态 / 自动化状态 / 集成状态 / 真实环境状态：
- 代码：完成（查询复核 + unpin/撤销主动通知 + 显示层刷新接线）。
- 自动化：完成。
- 集成：完成（显示层消费点为 manager 每帧解析 + Ghost 可见性同步，两条路径都收敛到
  查询时校验）。
- 真实环境：未验证。

与本计划偏差及原因：
- "当前 MC 自签自动全局可信"捷径未添加（计划明令禁止）；信任仍只来自显式 pin。

已知限制与回滚：
- 显示降级依赖下一次渲染/下一语音帧刷新（监听触发 syncAllGhostVisibility 立即处理
  可见性；皮肤纹理随下一帧解析降级）。
- 回滚：回退本任务提交；R09 缺陷回归。
