# TeamSpeak / SVC 五阶段实施队列

## Task 1：建立标准测试与证据底座
- **Status**: `completed`
- **Completion Evidence**：
  - 新建 `ts3voice-core` 纯 Java 模块，含 Seq16、PacketSequenceWindow、BoundedCommandAssembler 和依赖-free 测试入口；标准 `clean test --offline` 于 Java 25 / Gradle 9.5.1 下 BUILD SUCCESSFUL。
  - 协议测试 28 项断言通过：单包、2/3 片、完全倒序、跨回绕分片、旧代际、压缩标志、窗口、预算和超时恢复；失败测试会非零退出。
  - 已创建 spec.md/tasks.md；未修改真实测试服配置，未输出秘密。信任/路由测试入口及秘密扫描随 Task 3/6/8 继续建设。
- **Priority**: high
- **Depends On**: None
- **Description**：建立协议/信任/路由测试入口、golden vectors、fake clock/transport/latch、秘密扫描和验证记录；复用现有 51 项有效回归，改写与新决策冲突的预期。
- **Acceptance Criteria Addressed**：AC-1、AC-2、AC-3、AC-4、AC-5、AC-6、AC-8
- **Test Requirements**：
  - `rule` TR-1.1：标准测试能编译运行真实生产组件，失败非零退出，无恒真或吞掉子步骤失败。
  - `rule` TR-1.2：秘密扫描和验证记录不输出真实秘密；测试不依赖真实外部 TS/MC。
- **Notes**：构建优先复用已有离线缓存；不修改真实测试服配置。

## Task 2：修复 TS3 分片、RSA、序号回绕及关联协议边界
- **Status**: `completed`
- **Completion Evidence**：
  - `ts3voice-core clean test` 通过 83 项协议断言：单包、2/3 片、完全倒序、跨回绕、旧代际、压缩边界、RSA 固定宽度、票据与可选声明。
  - 两端 `Ts3Connection` 已改用共享 `PacketSequenceWindow` 与 `BoundedCommandAssembler`；旧双副本重组器删除；服务端未知包不再映射为 VOICE。
  - 两端 `Ts3Crypt` RSA 结果使用 64 字节无符号左填充，并复制到真实 Y 字段；`RsaRegression` 验证服务器回显逐字节不变。
  - `RunProtocol.ps1` 输出 11 项频道回归 PASS 和 `RSA and handshake regression checks passed`。
  - 客户端与服务端 `clean build --offline` 均 BUILD SUCCESSFUL，两个 jar 包含 `META-INF/jars/ts3voice-core-0.1.0.jar`。
- **Priority**: high
- **Depends On**: Task 1
- **Description**：实现有界重组、Seq16/代际、QuickLZ checked reader、RSA 固定长度、握手校验、乱序/重传、未知包/codec/转义处理；两端同步修复。
- **Acceptance Criteria Addressed**：AC-1
- **Test Requirements**：
  - `rule` TR-2.1：单包、2/3/5 片、乱序/孤立片、`65535→0`、大列表、QuickLZ 畸形输入和内存预算测试通过。
  - `rule` TR-2.2：RSA 高位/短值/零、曲线点、缺参数、未知包、codec、转义、弱网重传测试通过。
- **Notes**：不直接复制客户端重组器；不改变正确 TS3 加密握手。

## Task 3：修复信标凭据、无认证隧道与秘密暴露
- **Status**: `completed`
- **Completion Evidence**：
  - 客户端新增 `BeaconTrustScreen` 与 `/tsvoice trust`；未信任/变化 endpoint 只进入 `AWAITING_TRUST`，可选仅本次、记住、拒绝或禁用本服信标。
  - `TsVoiceManager` 不再把全局或其他 endpoint 密码用于信标；信标会话密码/频道密码为空。`RunManagerTests.ps1` 13/13 PASS，包括 endpoint 变更需重新授权、拒绝不连接。
  - TCP 隧道默认 `127.0.0.1`，公网绑定需显式 `tcpTunnelBindAddress`，启用必须提供 `TBRIDGE_TUNNEL_SECRET`；共享 `TunnelTicket`/`TunnelTicketStore` 提供短期一次性票据。`ServerRegression` 首次有效票据可转发，无票据与重放均不转发。
  - ServerQuery 通信日志已禁用；产品源码秘密扫描 185 个文件 0 命中。未自动轮换真实测试服秘密。
- **Priority**: high
- **Depends On**: Task 1
- **Description**：区分真实 TS/隧道/拨号 endpoint；首次/变更授权与记住信任；凭据按虚拟服务器隔离；版本化隧道票据/抗重放/限额/保活；Query 日志脱敏；发布秘密隔离。
- **Acceptance Criteria Addressed**：AC-1、AC-2
- **Test Requirements**：
  - `rule` TR-3.1：跨 endpoint 不发送旧密码，授权/记住信任矩阵通过；普通语音不被身份验证阻断。
  - `rule` TR-3.2：未认证隧道不转发、限额/保活/stop 正确；日志/payload/制品扫描不含秘密。
- **Notes**：不擅自轮换真实秘密或改测试服配置；隧道票据不是 MC 身份门槛。

## Task 4：统一会话、SVC 通道和音频资源所有权
- **Status**: `completed`
- **Completion Evidence**：
  - 已新增 `BridgeMembershipService`：SVC 状态反射和不可变成员快照在 MC 主线程发布；`UNKNOWN` 保留上次已验证受众，`EMPTY` 明确目标为空，不回退全员。`RunServerChecks.ps1` 为 14/14 PASS。
  - `BeaconSender` 周期任务已改为在 MC 主线程读取 SVC 状态并发布快照。
  - `McAudioInjector` 的 targets 现在按快照差异刷新，成员未变化时不再执行 `clearTargets/addTarget`，避免发送线程观察到空目标窗口。
  - 可选身份声明按 `(TS 虚拟服务器 UID, TS client UID)` 命名空间存储，TS 昵称/description 不再自动映射真实玩家。
  - 此项不覆盖客户端音频 native 生命周期和配置 UX；这些在 Task 9。
- **Priority**: high
- **Depends On**: Task 2、Task 3
- **Description**：连接可取消、关闭代际、旧回调隔离、关闭不等待写锁；SVC AudioChannel/Ghost/皮肤时序；服务端权威在线快照；Opus/native/EFX 生命周期。
- **Acceptance Criteria Addressed**：AC-5
- **Test Requirements**：
  - `rule` TR-4.1：DNS/connect/stop/换服/旧回调/阻塞 I/O 的确定性交错测试无状态回退或孤儿资源。
  - `rule` TR-4.2：同 UUID 高低序号重连、皮肤交错、mixer stop、Opus/native/线程计数回基线。
- **Notes**：保留当前 ClientVoicechat 获取和 ownedStates 防误删机制。

## Task 5：实现可选身份、TS 会话证明、密钥目录与撤销过期
- **Status**: `completed`
- **Completion Evidence**：
  - `ts3voice-core` 新增 `SignedPlayerClaim`、`ServerTrustDirectory`、`IdentityProtocol`；核心回归已并入共享套件（最终 108 项 PASS），覆盖签发/验证、错误签发者、过期、撤销、线格式往返。
  - 客户端 TS 身份可对服务器 nonce 签名；服务端验证 TS UID 派生、签名、一次性 nonce 和 TS 虚拟服务器 UID 后签发声明。
  - 服务端签发密钥持久化为原子私有文件；声明 24 小时过期；MC 玩家断开时广播撤销。客户端只把有效声明用于 MC UUID 映射，未声明或无效声明继续按普通 TS Ghost 处理。
  - 已移除“昵称/description 自动映射真实玩家”；声明按修订号/过期/撤销维护，客户端在换服时清除身份映射。
  - 客户端断开会异步关闭网络并真正关闭自有 SVC AudioChannel；manager/GUI 锁不再等待阻塞 I/O。
  - fallback 转发不再要求配对；`RunServerChecks.ps1` 增加“unpaired fallback microphone is not gated by pairing” PASS。
  - 协议层现在对重复/旧序号命令包也发送 ACK，但只让首个通过窗口的包进入重组。
  - 客户端与服务端 `clean build --offline` 均 BUILD SUCCESSFUL。真实 TS/MC 端到端验证仍留给 Task 10。
- **Priority**: high
- **Depends On**: Task 4
- **Description**：实现 TsPeerId、独立 v2 proof/claim、mod challenge 签名、普通 TS 自愿配对、服务器独立密钥、TOFU/显式信任、撤销 tombstone、过期/轮换与旧映射迁移；描述/昵称不再提升身份。
- **Acceptance Criteria Addressed**：AC-2、AC-3
- **Test Requirements**：
  - `rule` TR-5.1：伪造/错误 TS UID 或虚拟服务器/重放/过期/撤销均不提升身份，普通语音不受影响。
  - `rule` TR-5.2：两个独立密钥服务器均可接入，无中心白名单；远程不互信只降级展示，语音正常。
  - `rule` TR-5.3：普通 TS 自愿配对无需 Minecraft/mod；autoPairing/公开配对码不产生可信证明；旧数据迁移不阻断音频。
- **Notes**：签名不授予群组权限；音频 sender 与 skin profile 分离。

## Task 6：统一虚拟服务器命名空间、群组与私密音频路由
- **Status**: `completed`
- **Completion Evidence**：
  - 可选身份声明以 `(TS 虚拟服务器 UID, TS client UID)` 命名空间校验和存储；昵称/description 不再提升身份。
  - `BridgeMembershipService` 发布主线程不可变成员快照；`UNKNOWN` 保留上次已验证受众，`EMPTY` 目标为空。服务端回归 14/14 PASS。
  - `/tsvoice <消息>` 与 `/tsvoice say` 改用与麦克风一致的已确认路由；路由未确认时报 `tsvoice.route_not_ready`，不向旧频道泄露。
  - 本地 SVC `OPEN` 群组保留主频道；TS 镜像群组即使类型为 `OPEN` 仍映射原子频道。`RunMapper.ps1` 13/13 PASS。
- **Priority**: high
- **Depends On**: Task 4、Task 5
- **Description**：以 `(tsVirtualServerUid,cid)` 管理路由/群组/凭据/信任/头像；本地/镜像群矩阵；主线程 UNKNOWN/EMPTY/MEMBERS 快照；语音文字一致；所有权/在线快照；回退免配对。
- **Acceptance Criteria Addressed**：AC-2、AC-4
- **Test Requirements**：
  - `rule` TR-6.1：三类本地群、镜像群、同 cid/UID 不同 TS、同名手工群、密码缺失和移动拒绝测试通过。
  - `rule` TR-6.2：UNKNOWN 不扩大受众，语音/文字共用快照；无配对用户在 mixer/forwarder 回退仍可通信。
- **Notes**：签名 claim 不参与群组成员或权限计算。

## Task 7：完成双模音频、上传与头像命名空间
- **Status**: `completed`
- **Completion Evidence**：
  - 默认 per-player 模式增加每客户端有界出站队列和独立发送线程；SVC 回调只入队。单 bot mixer 保持唯一混音路由并保留单人透传。
  - mixer 停止先 join 混音线程再关闭 Opus 资源；突发积压丢弃旧帧并保留低延迟水位；多人混音改为全量求和后一次 clamp。
  - `McAudioInjector` 改为每用户锁，避免所有 TS 用户串行；targets 使用成员快照差异刷新。
  - `Ts3FileTransfer.Result` 区分 `CONFIRMED/UNKNOWN/FAILED`；只有确认结果写入头像 marker。头像 marker 按 TS namespace 哈希 + MC UUID 隔离，临时文件原子替换。
  - 客户端与服务端 `clean build --offline` 均 BUILD SUCCESSFUL；真实 TS/MC 双端联调仍留给 Task 10。
- **Priority**: high
- **Depends On**: Task 6
- **Description**：默认 per-player/mixer 单一路由、每用户所有权、最终 clamp、队列收敛、回调只入队；上传 SENT/CONFIRMED/UNKNOWN/FAILED；头像按虚拟服务器/uid/MC UUID；普通 TS 无需证明。
- **Acceptance Criteria Addressed**：AC-2、AC-5
- **Test Requirements**：
  - `rule` TR-7.1：两模式多人/突发/失败/停止测试通过，资源回基线，未配对回退可发言。
  - `rule` TR-7.2：上传短读/超时/并发/旧回复、换 TS/uid/皮肤测试通过，UNKNOWN 不写永久成功。
- **Notes**：不默认开启音频增强；真实文件确认按环境记录。

## Task 8：抽共享协议模块并建立 CI 与兼容/弱网/并发回归
- **Status**: `completed`
- **Completion Evidence**：
  - 已建立纯 Java `ts3voice-core`（零第三方依赖），两端通过 composite build 复用；协议、序号、QuickLZ、票据和身份 wire format 已去重。两个 mod jar 均含 `META-INF/jars/ts3voice-core-0.1.0.jar`。
  - 共享协议测试 108/108 PASS：新增确定性弱网回归（乱序重传恰好产出一条命令、超时重同步、迟到旧包拒绝）与 16 线程并发票据消费（恰有一次通过、nonce 恰存一条），全部无 sleep 碰运气。
  - 构建固定：两个 mod 的 wrapper 均加 `distributionSha256Sum=bafc141b…7b031f`（官方 gradle-9.5.1-bin.zip 校验和）；Loom 由 `1.17-SNAPSHOT` 固化为 release `1.17.21`（在线拉取一次后离线 `clean build` 均 BUILD SUCCESSFUL）；所有第三方依赖版本已在 `gradle.properties` 显式固定；`ts3voice-core` 无外部依赖故无需锁文件。
  - `.github/workflows/ci.yml` 补充 `RunMapper.ps1` 步骤；CI 现执行 core 测试、双端构建和全部五组隔离回归。
  - 五组隔离回归全部 PASS：客户端协议 11 项 + RSA、路由 13 项、客户端状态 14 项、客户端 manager 13 项、服务端 14 项。
  - 最终制品 SHA-256（Task 9 元数据修正后的最终值）：`teamspeak-svc-0.9.8+26.3.jar` = `9672baee7fa821daa55354c73ed5ce7411acc99522be645d1d464b18054649da`；`tsbridge-0.3.4+26.3.jar` = `0b4ee35de262a303d9d2b35a4b4ec77c82e4c67ea8a84269852759c57a176d86`（clean 构建哈希一致）。
  - 剩余限制：真实多实例 v1/v2 混合矩阵与 30 分钟弱网长会话需要真实 TS/MC 环境，记录在 Task 10（Blocked By：真实环境）。线格式 golden vectors 与旧 payload 兼容（Beacon 未变、身份 payload 仅新增且旧客户端安全忽略）已离线覆盖。
- **Priority**: high
- **Depends On**: Task 2、Task 5、Task 6、Task 7
- **Description**：独立纯 Java ts3voice-core 与版本化 bridge-protocol；standalone composite/版本化依赖；SVC adapter；标准测试、CI、兼容矩阵、弱网并发回归、固定构建依赖。
- **Acceptance Criteria Addressed**：AC-1、AC-3、AC-4、AC-6
- **Test Requirements**：
  - `rule` TR-8.1：core 无 MC/Fabric，双端无协议修复双副本，实际 jar 依赖正确且可启动。
  - `rule` TR-8.2：协议 golden vectors、并发/弱网、v1/v2 混合和升级迁移测试通过；CI 失败阻断。
- **Notes**：不强行合成一个 Loom 多模块；v2 不接受旧描述为证明；真实环境矩阵移至 Task 10 并标 blocked。

## Task 9：完善客户端音频、配置 UX 与发布工程
- **Status**: `completed`
- **Completion Evidence**：
  - 不可变会话配置：`TsVoiceManager.SessionConfig` 为不可变 record，`activeConfig/lastAppliedConfig` 以完整配置（含密码）比较决定是否重连，运行期不再读可变全局；`TsAudio` 为 volatile 运行期视图，`/tsvoice reload` 原子刷新。密码三态（per-server 覆盖全局、显式空清除继承）由 `RunManagerTests.ps1` 13/13 PASS 覆盖。
  - EFX/native 降级：`AudioPipeline` 对 AEC/NVIDIA/WebRTC/AGC 初始化与运行期失败全部捕获并回退 SVC 原生处理；NVIDIA dll 缺失时向玩家发送一次性可点击指引；`isNsActive()` 决定是否接管 SVC 原生降噪。音频增强默认全关并有 configVersion=1 一次性迁移；损坏配置自动备份后以默认值恢复（`ConfigFileUtil.backupBroken`）。普通干声路径不依赖任何增强组件。
  - NVFX：本环境无 NVAudioEffects.dll 与真实 RTX 设备，未做任何盲改；`NvidiaDenoiser` 保持“检测到 dll 才启用”的证据门槛。**Blocked By**：真实 NVIDIA Broadcast 设备与 ABI 证据（Unblock Condition：管理员提供安装 NVIDIA Broadcast 的 RTX 环境）。
  - 许可与发布工程：新增 `teamspeak-svc/LICENSE`（GPL-3.0-or-later 声明 + 指引）、`teamspeak&svcbridge/LICENSE`（MIT 全文）、两份 `THIRD-PARTY-NOTICES.md`（依赖、许可证、打包方式、NVIDIA 不分发声明）、`ts3voice-core/LICENSE.md`（MIT OR GPL-3.0-or-later 双许可）与 `LICENSE-MIT`；服务端 `fabric.mod.json` 作者占位符 `your-name` 修正为 `Brokeyuan` 并重新构建。
  - README：客户端补充 TCP 隧道 `TBRIDGE_TUNNEL_SECRET`、可选身份与降级语义；服务端新增“安全与信任（v0.3.4）”章节（隧道认证/绑定、信标隔离、身份声明生命周期、日志脱敏、不自动轮换秘密）。
  - 秘密扫描：产品源码 + CI 共 126 个文件 0 命中（模式：password/secret/token 赋值、私钥块、AWS/Slack token）。
  - 最终制品 SHA-256：`teamspeak-svc-0.9.8+26.3.jar` = `9672baee…54649da`（未变更）；`tsbridge-0.3.4+26.3.jar` = `0b4ee35de262a303d9d2b35a4b4ec77c82e4c67ea8a84269852759c57a176d86`。
  - 待办移交 Task 10：`THIRD-PARTY-NOTICES.md`（服务端）已标记 TS3AudioBot/TSLib 上游许可证需在公开发布前核实，与 MIT 声明的兼容性需用户确认。
- **Priority**: medium
- **Depends On**: Task 4、Task 8
- **Description**：不可变 AudioGraph、EFX/native 降级、AEC/ducking/低延迟；不可变配置/密码三态/状态 UX；NVAFX 先核实 ABI；构建/许可/SBOM/文档/制品检查。
- **Acceptance Criteria Addressed**：AC-5、AC-6、AC-7、AC-8
- **Test Requirements**：
  - `rule` TR-9.1：无 EFX/native 失败/缓存损坏/配置恢复/UX/构建与许可测试通过，普通干声保持可用。
  - `rubric` TR-9.2：真实音频质量与性能维度；scale 1-5；anchors 1=仅调用/故障，3=基础可用但多人/设备/测量不足，5=ERLE/近端/队列/GC/underrun/资源证据完整；threshold >=4；证据为真实设备和 profiling，缺环境则 blocked。
- **Notes**：默认增强保持关闭；无 NVAFX 证据不盲改；不承诺未测量收益。TR-9.2 rubric 项 **Blocked By**：真实 OpenAL/NVIDIA 设备与 profiling 环境。

## Task 10：公开发布验收与独立 Review
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1、Task 2、Task 3、Task 4、Task 5、Task 6、Task 7、Task 8、Task 9
- **Description**：多 MC/TS 实例、普通官方 TS、真实音频设备、30 分钟以上长会话、升级兼容、秘密/许可/制品检查；创建 review.md 并由独立只读代理复核，修复 actionable findings。
- **Acceptance Criteria Addressed**：AC-1、AC-2、AC-3、AC-4、AC-5、AC-6、AC-7、AC-8
- **Test Requirements**：
  - `rule` TR-10.1：所有实现项 completed 或用户批准 cancelled，所有 rule AC/TR 有通过证据，无未解决 actionable finding。
  - `rubric` TR-10.2：公开发布就绪维度；scale 1-5；anchors 1=仅构建成功，3=离线验证充分但真实环境不完整，5=多实例/设备/长会话/升级/文档/独立审查证据完整；threshold >=4；证据为验收矩阵和独立 Review。
- **Notes**：真实环境缺失时记录 Blocked By/Unblock Condition；最新独立 Review=pass 才能宣称公开发布验收完成。
