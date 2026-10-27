# 审查问题修复队列

## Task 1: 客户端音频状态与会话生命周期
- **Status**: `in_progress`
- **Priority**: high
- **Depends On**: None
- **Description**：R01/R02/R08；实际音频注入器回归，维护状态所有权。
- **Acceptance Criteria Addressed**：AC-1
- **Test Requirements**：`rule` TR-1：先复现旧客户端和真实状态误删，再验证修复及无群组可见性。

## Task 2: 频道协议、映射和发送隔离
- **Status**: `pending`
- **Priority**: high
- **Depends On**: None
- **Description**：R03/R04/R05/R06/R07/R09；协议频道表、映射授权、TS 原始目标传递。
- **Acceptance Criteria Addressed**：AC-2
- **Test Requirements**：`rule` TR-2：错误父频道、删除、快照、缺密码和群组变更均有行为回归测试。

## Task 3: 服务端连接、隧道、过滤和重载
- **Status**: `pending`
- **Priority**: high
- **Depends On**: None
- **Description**：R10/R11/R12/R13/R14；失败资源清理、停止竞态和拒绝不支持的热更新。
- **Acceptance Criteria Addressed**：AC-3
- **Test Requirements**：`rule` TR-3：失败连接关闭、离线连接不提交、停止隧道断开活动会话、权限未知拒发、重启字段不部分生效。

## Task 4: 构建入口和密码配置
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 2
- **Description**：R15/R16；修复 wrapper，独立主频道密码并兼容旧配置。
- **Acceptance Criteria Addressed**：AC-4
- **Test Requirements**：`rule` TR-4：含 & 路径 wrapper 可启动；两类密码参数分离；客户端和服务端编译通过。

## Task 5: 集成验证
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1, Task 2, Task 3, Task 4
- **Description**：统一执行所有回归与构建，记录修复映射和限制。
- **Acceptance Criteria Addressed**：AC-1, AC-2, AC-3, AC-4
- **Test Requirements**：`rule` TR-5：回归检查无失败；新鲜构建证据；独立审阅无未解决行动项。
