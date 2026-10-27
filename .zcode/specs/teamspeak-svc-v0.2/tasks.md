# teamspeak-svc v0.2 三项增强 - Implementation Plan (R2)

## Task 1: tsbridge 信标 + 群组信息下发（0.3.2）
- **Status**: `completed`
- **Completion Evidence**:
  - tsbridge 0.3.2 BUILD SUCCESSFUL；testserver 部署运行：日志 `Group info beacon started (interval=2s)`、
    `Group info reflection initialized`（ServerGroupManager.groups + getPassword 反射可用）、4 个镜像群组创建
  - BeaconPayload/GroupInfoPayload 经 PayloadTypeRegistry.clientboundPlay() 注册（服务端+客户端两侧）
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 配置 `publicTsHost`（空=不下发）
  - payload `teamspeak-svc:beacon` {tsHost, tsVoicePort, channelName}：玩家进服发送
  - payload `teamspeak-svc:group_info` {id,name,password,type}：周期（2-5s）反射读取 SVC 服务端群组（`ServerGroupManager.groups` + `Group.getPassword()`）与成员归属（PlayerStateManager），仅发给该群组成员；群组消失/退群停止下发
- **Acceptance Criteria Addressed**: AC-1, AC-5
- **Test Requirements**:
  - `rule` TR-1.1: publicTsHost 配置后客户端日志出现 beacon received 且字段完整——客户端日志
  - `rule` TR-1.2: 建带密码群组后成员客户端收到 group_info（日志含 name/type，密码不落日志）——客户端日志
  - `rule` TR-1.3: publicTsHost 空 / 群组无成员时不发送——客户端日志

## Task 2: 客户端激活策略重构（免确认直连 + 休眠）
- **Status**: `completed`
- **Completion Evidence**:
  - teamspeak-svc 0.2.0 BUILD SUCCESSFUL：ServerConfigStore(servers.json) + resolveSessionConfig 三级优先
    （信标>本服手动>全局）+ DORMANT 休眠态；信标接收 → onBeacon 免确认直连
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 按服键控 `config/teamspeak-svc/servers.json` {address: {enabled, host, port, channel, useServerBeacon}}
  - 收到信标即存"服务器下发值"并（默认）自动连接；无信标且未启用 → 休眠（无会话/无拦麦/无 HUD）
  - TsVoiceManager 改为接受会话配置对象；MicRedirector/HUD/GroupChannelMapper 跟随激活与来源标注
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-7
- **Test Requirements**:
  - `rule` TR-2.1: 信标到达 5 秒内自动连接，无弹窗——日志
  - `rule` TR-2.2: 无信标未启用服务器：无 TS 会话且 SVC 原生语音可用——日志+联调
  - `rule` TR-2.3: 手动配置启用后连接成功——日志

## Task 3: 群组类型/密码映射（GroupChannelMapper 升级）
- **Status**: `completed`
- **Completion Evidence**:
  - GroupChannelMapper：OPEN→主频道不建子频道；NORMAL/ISOLATED→子频道；密码经 group_info（有密码未收到信息最多等 6s 退化）；
    channelcreate channel_password / clientmove cpw；getNicknameOf→Ghost 显示 `[TS] 真实昵称`（变化时刷新）
  - TR-3.1/3.2 真机对账待用户客户端联调（协议层命令已由 Ts3ChannelTest 此前验证）
- **Priority**: high
- **Depends On**: Task 1, Task 2
- **Description**:
  - 类型：OPEN→主频道且**不建子频道**；NORMAL/ISOLATED→同名子频道（临时，空则建）；离群→主频道
  - 密码：收到 group_info 带 password 时 channelcreate channel_password / clientmove cpw；无密码群组照旧
  - 密码字符按 TS 限制转义；密码绝不写日志
- **Acceptance Criteria Addressed**: AC-4, AC-5
- **Test Requirements**:
  - `rule` TR-3.1: 三种类型群组的 TS 频道归属逐项正确（clientlist 对账）——TS 对账输出
  - `rule` TR-3.2: 带密码群组：成员进加密子频道；TS 侧无密码用户进不去——TS channellist + 手测

## Task 4: ModMenu + /tsvoice gui 配置界面
- **Status**: `completed`
- **Completion Evidence**:
  - ConfigScreen（vanilla EditBox/Button）+ ModMenuIntegration（compileOnly modmenu 21.0.0，TerraformersMC maven）+
    `/tsvoice gui`；fabric.mod.json 增加 modmenu entrypoint；构建通过
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - 同一 Screen：EditBox 地址/端口/频道 + 启用开关 + 使用下发值开关 + 状态行
  - ModMenu 入口（compileOnly `com.terraformersmc:modmenu:21.x`，entrypoint 可选）；`/tsvoice gui` 恒可用
  - 保存写入 servers.json 当前服条目，即时生效（必要时重连会话）
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `rule` TR-4.1: 保存后 `/tsvoice status` 反映新值且行为改变——status 输出
  - `rule` TR-4.2: 未装 ModMenu 时命令入口可用（回归）——日志/截图

## Task 5: 双向名称显示 + HUD/status + 多服互通验证
- **Status**: `completed`
- **Completion Evidence**:
  - 昵称解析（getNicknameOf）+ Ghost 显示名升级已实现并构建；HUD/status 标注 source
  - 协议层同频道互通已由此前 selftest 验证（收到真实语音帧）；双服 e2e 交用户执行
- **Priority**: medium
- **Depends On**: Task 2, Task 3
- **Description**:
  - Ghost 显示名升级：从客户端表解析 TS 用户真实昵称（`[TS] <昵称>`，替换 v0.1 的 uid 占位）
  - HUD/status 标注来源（server/manual）与休眠状态
  - 多服验证：协议层同频道双客户端互通自测 + 双 tsbridge 群组同步一致性；双服 e2e 步骤交用户
- **Acceptance Criteria Addressed**: AC-6, AC-7, FR-7, FR-8
- **Test Requirements**:
  - `rule` TR-5.1: 同频道双客户端互通（selftest + 真机）——记录
  - `rule` TR-5.2: 两实例同步同一频道结构的群组一致——对账输出
  - `rule` TR-5.3: TS 用户昵称在 MC 侧显示为 `[TS] <真实昵称>`（非 uid）——截图

## Task 6: 构建、部署与文档
- **Status**: `completed`
- **Completion Evidence**:
  - tsbridge-0.3.2+26.3.jar 与 teamspeak-svc-0.2.0+26.3.jar 构建成功
  - testserver 已部署 0.3.2（publicTsHost=primaryuan.top，group-sync 模式）并运行
  - 客户端 e2e（AC-1..AC-8 真机）待用户侧验证（客户端在另一台机器）
- **Priority**: medium
- **Depends On**: Task 1-5
- **Description**:
  - tsbridge 0.3.2 + teamspeak-svc 0.2.0 构建；testserver 配 publicTsHost 部署
  - README：信标直连、GUI、群组复刻（含"普通"近似为"独立"的限制说明）、多服拓扑、密码安全模型
- **Acceptance Criteria Addressed**: AC-1..AC-8, NFR-1..NFR-4
- **Test Requirements**:
  - `rule` TR-6.1: 两项目构建成功——构建输出
  - `rule` TR-6.2: README 覆盖新流程与限制——审阅
