# TeamSpeak / SVC 五阶段修复与公开发布需求

## 概述

- **目标**：以 `review/UNIFIED-BACKLOG.md` 为问题基线，先修安全与协议，再建立可选身份信任、统一路由、共享协议和 CI，最终完成公开发布验收。
- **用户**：使用 `teamspeak-svc` 的 MC 玩家、使用普通 TeamSpeak 客户端的用户、部署 `tsbridge` 的管理员及维护者。
- **源码基线**：`teamspeak-svc` 0.9.8、`tsbridge` 0.3.4，MC 26.3 / Java 25。

## 不可违背的产品原则

1. 普通 TS 用户无需 Minecraft、MC mod、配对或签名即可在支持的 direct、server-bridge、per-player、mixer、group-sync 路径双向语音和文字交流。
2. 身份验证只建立可信玩家映射，影响 MC UUID 关联、可信皮肤、原生状态和“已验证”展示；不决定发言、收听、群组权限或音频路由。
3. 任何兼容 MC 服务器均可使用独立生成的密钥接入，无中心注册或单一受信服务器白名单；互不互信的服务器及陌生 TS 用户仍能语音。
4. 伪造、过期、撤销或不互信只降级可信展示，不能静音或踢出普通用户。
5. 不自动轮换秘密、修改真实服务器配置、启动外部服务、提交或发布。缺真实设备或权限时记录阻塞，不伪造通过。

## 目标

- 修复信标凭据、无认证隧道、TS3 分片、RSA、序号回绕及直接相关协议问题。
- 建立签名声明、TS 会话证明、服务器公钥管理、撤销与过期机制，且与普通语音彻底解耦。
- 以独立 TS 虚拟服务器命名空间、可信成员快照和明确权限统一群组与音频路由。
- 抽共享协议模块，建立标准测试、CI、兼容矩阵、弱网与并发回归。
- 完成多 MC/TS 实例、真实音频设备、长会话和升级兼容的公开发布验收。

## 非目标

- 不要求所有 TS 用户安装 Minecraft、完成 MC 配对或持有可信声明。
- 不把身份签名用作通信、隧道传输或群组的强制门槛。
- 不重写正确的 TS3 密码学算法、上游 SVC，或为减少行数而大爆炸拆类。
- 不默认开启 AEC/NVIDIA/AGC/混响/Ducking，不宣称未经真实测量的音质/延迟收益。
- 不将两个独立 Loom 项目强行合成单一多模块，不把旧报告的“已修复”或构建成功当作新需求完成证明。

## 功能要求

- **FR-1 协议**：统一有界分片、16 位序号/代际、QuickLZ、RSA、握手输入、乱序/重传、codec 和转义；异常输入受控失败。
- **FR-2 传输与凭据安全**：信标首次/变更授权，密码按真实 TS 虚拟服务器隔离；隧道版本化握手、独立短期票据、抗重放、限额和双向保活；Query 日志脱敏。
- **FR-3 可选身份**：音频身份为 `(tsVirtualServerUid, tsClientUid)`；clid 不是持久身份。可信映射有 `UNTRUSTED / VERIFIED_LOCAL / VERIFIED_REMOTE`，无声明普通 TS Ghost 仍正常通信。
- **FR-4 证明与密钥**：支持 mod 客户端一次性 challenge 签名，以及普通官方 TS 用户自愿配对。Claim 含版本、issuerKeyId、MC UUID、TS 虚拟服务器、TS UID、revision、签发/过期时间和签名。每服独立签名密钥、TOFU/显式信任、撤销 tombstone、过期和轮换。
- **FR-5 路由**：以 `(tsVirtualServerUid,cid)` 命名空间管理路由/群组/凭据/信任/头像；本地 OPEN 留主频道，NORMAL/ISOLATED 进子频道，TS 镜像群映射原子频道；NORMAL/proximity 差异明示降级。
- **FR-6 权限**：主线程生成 `UNKNOWN / EMPTY / MEMBERS` 不可变快照，UNKNOWN 不扩大受众；语音与文字共用路由快照；签名不授予群组权限。
- **FR-7 生命周期与音频**：会话代际拥有 socket、线程、SVC channel、Opus/native 和 EFX；默认 1:1 与 mixer 单一路由、有界队列、最终 clamp、回调快速返回；旧路径不得要求配对才能发言。
- **FR-8 工程与体验**：共享纯 Java core、版本化 bridge-protocol、SVC adapter、标准测试/CI、不可变配置、明确错误、双语 UX、兼容升级、许可和发布追溯。

## 非功能要求

- **NFR-1 兼容**：保留旧 payload 读取和基本语音能力，通过独立 v2 payload/能力协商扩展；不直接插入字段破坏旧 codec。
- **NFR-2 降级**：无证明、无效证明、无可信远程密钥、可选 native/EFX 失败均不影响普通干声。
- **NFR-3 实时纪律**：主线程生成世界/玩家快照；网络线程不读可变世界；SVC 回调只入队；音频路径不做磁盘/模型/UI；性能结论有测量。
- **NFR-4 隐私**：秘密不进日志、报告或发布物；私密路由 fail-closed；管理员执行真实轮换/发布。
- **NFR-5 构建**：两个 standalone root 经 composite/版本化制品复用 core；实际 mod jar 显式包含所需依赖，core 不依赖 MC/Fabric。

## 约束、依赖与假设

- 现有 51 项回归可复用，但与“信标继承密码”“UNKNOWN=empty”“回退需配对”冲突的测试须按本规格改写。
- TS 虚拟服务器 UID 从权威 serverinfo 获取，不使用 host/昵称替代；拨号、隧道、文件上传地址分别建模。
- 签名私钥只在服务端，客户端保存公钥/信任配置，不传输 TS 身份私钥；签名原语可复用，但不改变 TS3 握手。
- 旧配对迁移为 `LEGACY_UNVERIFIED`，重新证明后提升，不清空用户映射或阻断音频。
- 真实 TS/MC、OpenAL/NVIDIA、双端、性能和管理权限不属于可假定存在的环境；无法获得时相关验收标 `blocked`，记录 Unblock Condition。
- 不覆盖三份旧报告或旧规格；本目录为本次权威需求与执行记录。

## 验收标准

### AC-1：安全与协议 P0
- **Type**: `rule`
- **Given**：分片/乱序/回绕/迟到包、QuickLZ 畸形输入、RSA 高位、非法握手、信标 endpoint 改变、未认证隧道及 debug 登录。
- **When**：客户端/服务端处理协议、凭据和传输。
- **Then**：分片/解密/重组正确且资源有界；RSA 回显不变；密码不跨虚拟服务器；未认证隧道不转发；秘密不进入日志/发布物。
- **Pass Condition**：共享 golden vectors、协议/授权/隧道/日志测试全部通过；真实握手/大列表证据或明确环境阻塞。
- **Evidence**：标准测试输出、脱敏日志、制品扫描、真实 TS 记录。

### AC-2：普通语音不依赖身份验证
- **Type**: `rule`
- **Given**：纯官方 TS 用户无 MC/mod/配对/claim；未配对 MC 玩家；mixer 不可用而 forwarder 可用的回退。
- **When**：用户在 direct、server-bridge、per-player、mixer、group-sync 及回退路径通信。
- **Then**：普通双向语音/文字和 TS Ghost 正常，身份验证不是发言、收听或群组权限门槛。
- **Pass Condition**：所有支持路径均有无证明通信测试；回退不再按配对过滤；无效身份不导致静音。
- **Evidence**：路由/回退回归、真实多客户端集成日志与听感记录。

### AC-3：可选身份、会话证明和密钥生命周期
- **Type**: `rule`
- **Given**：mod 客户端 challenge、普通 TS 自愿配对、伪造 UUID/昵称、错误 TS UID/虚拟服务器、重放、过期、撤销、密钥轮换和互不互信服务器。
- **When**：建立或更新可信玩家映射。
- **Then**：只有有效证明可提升映射；无效/过期/撤销只降级为普通 TS 身份；任意兼容服务器可自签接入，不自动全球可信。
- **Pass Condition**：签名/会话/密钥/撤销/迁移矩阵通过；伪造不能获得真实玩家通道、可信皮肤或权限；语音不受影响。
- **Evidence**：协议与存储测试、签名负例、混合服务器/客户端集成记录。

### AC-4：命名空间、群组和权限隔离
- **Type**: `rule`
- **Given**：本地三类群、TS 镜像群、同 cid/UID 不同虚拟服务器、密码缺失、移动拒绝、成员 UNKNOWN、同名手工群。
- **When**：路由语音/文字、下发密码、同步群组或 target。
- **Then**：遵守映射矩阵；不串命名空间；UNKNOWN 不扩大受众；不误删手工群；签名不授予权限。
- **Pass Condition**：路由/成员/所有权/回退回归通过，语音与文字共用快照，无旧频道泄漏。
- **Evidence**：自动测试、真实群组联调、权限/受众日志。

### AC-5：生命周期、双模音频与上传
- **Type**: `rule`
- **Given**：DNS/connect/stop/换服/旧回调、默认 1:1/mixer、多人/突发、上传短读/超时/并发、换 TS/uid。
- **When**：会话、音频与文件传输启动、运行和关闭。
- **Then**：无状态回退或孤儿资源；每用户所有权、队列可收敛、最终 clamp；上传区分 SENT/CONFIRMED/UNKNOWN/FAILED，缓存按虚拟服务器/uid/MC UUID 隔离。
- **Pass Condition**：确定性交错、资源计数、双模路由和上传测试通过；UNKNOWN 不写永久成功；真实确认按环境记录。
- **Evidence**：生命周期/资源/音频/上传测试、真实 TS 与资源日志。

### AC-6：共享模块、CI 与升级兼容
- **Type**: `rule`
- **Given**：两个 standalone Loom 项目、共享 core/bridge-protocol、支持的 SVC 版本、v1/v2 混合端和旧配置/配对/群组/身份。
- **When**：构建、测试、升级和运行。
- **Then**：core 无 MC/Fabric，实际 jar 含所需依赖；旧基本通信可用，v2 不接受旧描述为证明；升级不阻断普通语音。
- **Pass Condition**：CI 执行标准协议/并发/弱网/兼容测试且无恒真或部分失败仍 PASS；构建、制品、依赖/许可扫描通过。
- **Evidence**：CI/Gradle 输出、依赖图、JAR 检查、混合版本测试和迁移记录。

### AC-7：真实音频质量与性能
- **Type**: `rubric`
- **Dimension**：普通干声可靠性、AEC 参考与近端保真、EFX/native 降级、实时线程阻塞、队列/GC/underrun 和资源稳定性。
- **Scale**：1-5
- **Anchors**：1 = 仅函数调用或存在明显故障；3 = 基础路径可用但多人/设备变化/真实测量不足；5 = 多设备多人证据充分，AEC ERLE/近端损伤、帧耗时、队列、GC、underrun 和资源均有记录。
- **Pass Threshold**：>= 4，且普通干声无故障；无真实设备证据时阻塞，不用 fake 冒充。
- **Evidence**：真实 OpenAL/NVIDIA/外放/多实例测量、JFR/日志和长会话记录。

### AC-8：公开发布验收与独立 Review
- **Type**: `rule`
- **Given**：完整实现队列、多个 MC/TS 实例、30 分钟以上会话、升级矩阵、性能数据、文档和发布物。
- **When**：准备公开发布。
- **Then**：普通 TS 用户与已验证用户混合通信，无资源持续增长；发布物无秘密/缓存/旧 JAR，许可和版本说明完整；所有 AC/TR 有证据。
- **Pass Condition**：所有实现任务完成或经用户批准取消；真实环境缺失项明确 blocked；最新独立 Review=pass 且无 actionable finding，才能宣称发布验收完成。
- **Evidence**：多实例/长会话/升级/设备记录、SBOM/NOTICE/SHA、spec/tasks/review 和独立 Review 结果。

## 未决事项

- 真实 NVIDIA 头文件/DLL、OpenAL/外放、双 MC/普通 TS 多实例、长会话和管理员权限的可用时间由环境决定。
- 远程公钥信任策略由接收方本地配置决定，不引入中心白名单。
- 实际支持的 SVC 版本范围由兼容测试确定，不预设所有未来版本兼容。
