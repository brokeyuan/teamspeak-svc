# teamspeak-svc 首切片（P0–P4）- Independent Review

- [ ] CP-R1: P0 缺陷修复真实有效（F02/F04/F05/F06/签发端），每个修复有"旧代码失败 → 新代码通过"证据且未回退 4.1 既有修复
  - **Type**: `rule`
  - **Covers**: AC-1, AC-2, AC-3, AC-4, AC-10（部分）, NFR-1
  - **Evidence**: Pending

- [ ] CP-R2: P1 媒体归属与隔离达成（回调 token 绑定、阻塞可关闭、稳定 UUID + 所有权守卫、SVC 断连解耦）
  - **Type**: `rule`
  - **Covers**: AC-5, AC-6
  - **Evidence**: Pending

- [ ] CP-R3: P2 前端适配层交付且诚实标注（ABI 台账、单一 mic 所有权、hook 自检禁用 TS、未验证清单完整、无"已支持"声明）
  - **Type**: `rule`
  - **Covers**: AC-7, FR-P2-5
  - **Evidence**: Pending

- [ ] CP-R4: P3 书签/凭据/命令关联/上传关联交付（F08 两个半边、F10、分模式、无凭据扩散）
  - **Type**: `rule`
  - **Covers**: AC-8
  - **Evidence**: Pending

- [ ] CP-R5: P4 能力 wire/选择策略/发现状态机/服务端公告 mod 交付（wire 严格验证、失败门不绕过认证错误、上下文绑定）
  - **Type**: `rule`
  - **Covers**: AC-9
  - **Evidence**: Pending

- [ ] CP-R6: 全量 CI 等价验证（core 108+15+22、四构建、16 组 harness 回归全绿；新测试全部接入 ci.yml）
  - **Type**: `rule`
  - **Covers**: AC-10, NFR-4
  - **Evidence**: Pending

- [ ] CP-R7: 阶段留存与诚实标注（每阶段独立提交、未验证项全部标注且无夸大声明）
  - **Type**: `rule`
  - **Covers**: AC-11, NFR-3
  - **Evidence**: Pending

- [ ] CP-U1: 架构契合度（实现与实施计划目标架构的契合、克制的类职责、无越界重构、偏差记录合理）
  - **Type**: `rubric`
  - **Covers**: AC-12
  - **Scale**: 1-5
  - **Anchors**: 1 = 偏离计划架构、全局状态继续扩散；3 = 大体符合但存在明显越界重构或职责混杂；5 = 符合计划接口草案与 SOLID 调整方向，无越界重构，音频热路径无阻塞
  - **Pass Threshold**: >= 4
  - **Evidence**: Pending

## Review History

### Review R1
- **Result**: `fail`
- **Evidence**: 独立只读审查（fresh subagent，HEAD 2f35481，104 次工具调用）：CP-R1..R5 全 pass（P0 根因修复、NFR-1 无回退、P1/P3/P4 语义在源码确认）；core 测试 108+15+22 全过、4 构建全绿、ci.yml 完整。**CP-R6 fail**：16 组 harness 中 9 组在 HEAD 上失败（RunMicRedirector/RunSessionIsolation PowerShell 数组语法错误 `,,`，RunClientIdentity/RunTransportShutdown/RunProtocol/RunStableVolume/RunTests 编译清单缺 Ts3FileTransferBridge/Ts3FileTransfer，RunManagerTests stub onTsFrame 签名过期，RunMapper stub 缺 hasExplicitBookmarkIntent）——3 组破损由 6000b62/66b7db8 引入且未被察觉。**CP-R7 fail**：DELIVERY-EVIDENCE.md"16 组全绿"与实测矛盾；Task 18 实际仍 pending。CP-U1 = 4/5（类职责契合、无越界重构；TsVoiceManager 门面化未完成、注册表职责由 ClientStateReflector 承载）。
- **Findings**: F-1 actionable(高) 9 组回归失败；F-2 actionable(高) 证据声明矛盾；F-3..F-6 advisory。
- **Remediation**: Issue I-1、I-2 已物化至 tasks.md；修复后须以全新 reviewer 重审。
