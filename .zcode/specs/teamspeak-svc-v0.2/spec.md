# teamspeak-svc v0.2 三项增强 - Product Requirements Document (R2)

## Overview
- **Summary**: 客户端 mod `teamspeak-svc` + 服务端组件 `tsbridge` 三项增强：(1) 非 TS 服务器零干扰兼容；(2) 服务器信标自动下发 TS 信息（免确认直接连）+ ModMenu/GUI 配置；(3) 完全复刻 SVC 群组系统到 TS（名称/密码/普通-开放-独立类型），多服务器共享互通。
- **Purpose**: 零配置、零干扰、群组体验与原版 SVC 一致并完整映射到 TeamSpeak。
- **Target Users**: 玩家（零配置）、服主（信标+群组同步）、TS 社区。

## Goals
- 服务器下发 TS 信息后玩家**免确认直接自动连接**（用户决策：信任服务器，去掉弹窗）。
- 未下发且未手动启用的服务器上 mod 完全休眠，SVC 原生路径零影响。
- 配置界面：ModMenu 集成（整合包入口）+ `/tsvoice gui` 命令兜底，同一界面。
- **群组系统完全复刻**：玩家用 SVC 原版 UI 创建群组（名称/密码可选/类型 普通|开放|独立）→ 完整反馈到 TS：
  - **开放（OPEN）**：成员留在主频道，与所有人互通（语义精确匹配）；
  - **普通（NORMAL）/ 独立（ISOLATED）**：成员进入独立子频道（TS 频道隔离天然实现"群外听不到"；TS 模型无法表达"普通"的单向可听，近似为独立——已知限制，文档说明）；
  - **密码**：SVC 群组密码 → TS 子频道密码（玩家 B 在 SVC 输密码进群 → 能进对应 TS 加密频道；TS 侧未输密码者进不去）。
- 多 MC 服务器共享同一 TS 主频道 = 互通（服主不做隔离，用户决策）。

## Non-Goals
- 跨 TS 服务器桥接。
- "普通"类型在 TS 侧的单向可听语义（TS 无此能力，近似为独立）。
- ModMenu 之外的 GUI 框架（YACL/Cloth）集成。
- payload 加密签名。

## Background & Context
- SVC 客户端安全设计：`ClientGroup` 仅含 `hasPassword` 布尔，密码明文不下发客户端；但服务端 `voice.server.Group.getPassword()` 持有明文 → 密码分发走"tsbridge 反射读取 → 仅发给群组成员"路径（扩展信标 payload 通道）。
- 群组成员归属：tsbridge 可从 SVC `PlayerStateManager`（已有反射）读取 玩家→群组 映射。
- Fabric S2C payload（已验证 API）：`ServerPlayNetworking.send(player, CustomPacketPayload)`。
- tsbridge 0.3.1 已有 group-sync（TS 永久频道↔SVC 常驻群组）；本版扩展为成员定向的 `group_info` 下发。

## Functional Requirements
- **FR-1 服务器信标（免确认直连）**: tsbridge 配置 `publicTsHost` 非空时，玩家进服即收到 `{tsHost, tsVoicePort, channelName}`；客户端**直接自动连接**（无弹窗），HUD 标注来源 server。
- **FR-2 激活策略（非 TS 服务器兼容）**: 仅当 (a) 收到本服信标 或 (b) 玩家对本服手动启用 时激活；否则完全休眠（无连接/无拦麦/无 HUD）。
- **FR-3 配置 GUI**: ModMenu 入口（compileOnly 集成，未装 ModMenu 时仅命令）+ `/tsvoice gui`：地址/端口/频道/启用开关/使用下发值开关；保存至按服键控的 `servers.json` 即时生效。
- **FR-4 群组类型复刻**: 玩家加入 SVC 群组时按类型执行：
  **OPEN→成员留在主频道且不创建子频道**（开放=与所有人互通，子频道无人无意义）；
  NORMAL/ISOLATED→进同名子频道（不存在则建临时频道）；离开群组→回主频道。
- **FR-5 群组密码复刻**: tsbridge 周期检测群组（含成员、密码）并向**成员**定向下发 `group_info{id,name,password,type}`；客户端用它建/进带密码的 TS 子频道（channelcreate channel_password / clientmove cpw）。非成员无密码 → TS 侧进不去（与 SVC 侧门禁一致）。
- **FR-6 多服务器互通**: 多服下发同一 {host,port,channel} 时玩家同频道互通；各服 tsbridge 同步同一频道结构的常驻群组；TS 身份跨服复用。
- **FR-7 状态可观测**: HUD/status 区分来源（server/manual）与休眠原因。
- **FR-8 双向名称显示**: MC 玩家名即 TS 客户端昵称（已有，保持）；TS 用户昵称经客户端表解析后以 `[TS] <TS昵称>` 显示在 SVC 界面与说话指示中（v0.1 为 uid 占位，本版升级为真实昵称）。

## Non-Functional Requirements
- **NFR-1 零新增运行时依赖**（ModMenu 为 compileOnly 可选集成）。
- **NFR-2 休眠零开销**：未激活时无线程/socket/拦截。
- **NFR-3 兼容保留**：全局 `teamspeak-svc.json` 手动模式继续有效。
- **NFR-4 可构建**：两项目构建产出可部署 jar。

## Constraints
- **Technical**: MC 26.3 / Fabric 0.20.1 / Java 25 / SVC 2.6.25；ModMenu 21.x API（compileOnly）。
- **Business**: `publicTsHost` 必须玩家可路由（公网）；密码明文仅经本服 MC 通道发给群组成员（与 SVC 安全模型同级）。
- **Dependencies**: tsbridge ≥0.3.2、teamspeak-svc ≥0.2.0。

## Assumptions
- 信任服务器下发的 TS 地址（用户决策，无确认弹窗）。
- TS 临时频道语义：最后一人离开自动删除（与 SVC 临时群组一致）。

## Acceptance Criteria

### AC-1: 信标免确认直连
- **Type**: `rule`
- **Given**: tsbridge 配置 publicTsHost、客户端装 mod
- **When**: 玩家进入服务器
- **Then**: 5 秒内自动连接 TS（无任何弹窗），HUD 显示 `TS ✔ (server)`
- **Pass Condition**: 客户端日志 beacon received + connected；无弹窗
- **Evidence**: 客户端日志

### AC-2: 非 TS 服务器零干扰
- **Type**: `rule`
- **Given**: 无信标且未手动启用
- **When**: 正常游戏使用 SVC
- **Then**: 无 TS 会话/无拦麦/无 HUD；SVC 原生语音正常
- **Pass Condition**: 日志与 SVC 原生语音联调
- **Evidence**: 日志 + 联调

### AC-3: ModMenu/GUI 配置
- **Type**: `rule`
- **Given**: 客户端（装/不装 ModMenu 两种情况）
- **When**: 从 ModMenu 配置入口或 `/tsvoice gui` 打开
- **Then**: 界面可编辑并保存生效；未装 ModMenu 时命令可用
- **Pass Condition**: 保存后 status 反映新值
- **Evidence**: status 输出 + 截图

### AC-4: 群组类型映射
- **Type**: `rule`
- **Given**: 玩家分别加入 OPEN / NORMAL / ISOLATED 群组
- **When**: 群组变化
- **Then**: OPEN→TS 客户端在主频道；NORMAL/ISOLATED→在同名子频道；离群→回主频道
- **Pass Condition**: TS clientlist 频道归属逐项一致
- **Evidence**: TS clientlist 对账

### AC-5: 群组密码复刻
- **Type**: `rule`
- **Given**: 玩家 A 建带密码 SVC 群组；玩家 B 用密码加入；TS 侧用户 C 无密码
- **When**: A/B 加入群组、C 尝试进 TS 子频道
- **Then**: A、B 的 TS 客户端进入加密子频道；C 无法进入（TS 提示需要密码）
- **Pass Condition**: TS 频道属性含密码 + 三方行为符合
- **Evidence**: TS channellist + 联调记录

### AC-6: 多服务器互通
- **Type**: `rule`
- **Given**: 两服下发相同 {host,port,channel}
- **When**: 两服各一玩家说话
- **Then**: 同频道互通；两服 SVC 群组列表一致
- **Pass Condition**: 跨服联调（协议层先自测，双服 e2e 用户执行）
- **Evidence**: 联调记录

### AC-7: 双向名称显示
- **Type**: `rule`
- **Given**: 装客户端 mod 的玩家 + TS 用户同频道
- **When**: 双方在线
- **Then**: TS 侧出现以 MC 玩家名命名的客户端；MC 侧 SVC 界面出现 `[TS] <TS昵称>`（真实昵称，非 uid）
- **Pass Condition**: 两侧名称显示正确
- **Evidence**: TS 客户端截图 + MC 界面截图

### AC-9: 手动配置兼容
- **Type**: `rule`
- **Given**: 无信标服务器，玩家经 GUI 手动配置启用
- **When**: 保存后
- **Then**: 与 v0.1 行为一致
- **Pass Condition**: 连接与语音正常
- **Evidence**: 日志

### AC-10: 配置体验
- **Type**: `rubric`
- **Dimension**: 零配置顺滑度（信标直连 + GUI 整体）
- **Scale**: 1-5
- **Anchors**: 1 = 仍需手改文件；3 = 需手动开关一次；5 = 全程无感
- **Pass Threshold**: >= 4
- **Evidence**: 用户实测反馈

## Open Questions
- [ ] `group_info` 下发频率与成员变更延迟（周期 2-5 秒，实现时定）
- [ ] TS 频道密码字符限制与 SVC 密码字符集差异（实现时对齐/转义）
