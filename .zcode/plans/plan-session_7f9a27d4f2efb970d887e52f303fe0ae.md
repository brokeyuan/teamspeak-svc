# TeamSpeak / SVC 五阶段修复与公开发布实施计划

## 目标与不可违背的产品原则

按用户指定的 01～05 顺序实施，覆盖 [UNIFIED-BACKLOG.md](C:/Users/Administrator/Documents/Workspace/Brokeyuan/mods/teamspeak&svcbridge/review/UNIFIED-BACKLOG.md) 的安全、协议、身份、路由、共享模块、测试与发布项。

1. **普通语音不需要身份验证。**纯官方 TS 用户无需 Minecraft、MC mod、配对或签名即可在支持的 direct、server-bridge、per-player、mixer、group-sync 路径双向语音和文字交流。
2. **身份验证只建立可信玩家映射。**它影响 MC UUID 关联、可信皮肤、原生状态和“已验证”展示，不决定发言、收听、群组成员权限或音频路由。
3. **任何兼容 MC 服务器均可接入。**使用各服务器独立生成的密钥，无中心注册、无单一受信服务器白名单；陌生 TS 用户和互不信任的不同 MC 服务器仍可正常语音。
4. **伪造/过期/撤销只降级，不静默。**无效声明不能提升为真实 MC 玩家、可信皮肤或权限，但不能因此拒绝普通语音。
5. **不自动执行外部变更。**不擅自轮换真实秘密、修改测试服配置、启动外部服务、提交或发布。真实设备和管理员证据不足时标 `blocked`，不伪造通过。

批准后创建 `.zcode/specs/unified-public-release-20261027/spec.md` 与 `tasks.md`，按本计划实施；完成后创建 `review.md`，由独立只读代理进行最终 Review。三份旧报告和旧规格保留为历史，不覆盖。

## 阶段 01：修复现有安全与协议 P0

### 范围
- **信标凭据**：取消跨 endpoint 密码继承；区分真实 TS 虚拟服务器、拨号地址与 TCP 隧道地址；首次信任或实际 endpoint 改变时明确授权，可记住信任。
- **无认证隧道**：新增版本化隧道握手，使用独立短期票据、抗重放、来源/会话/速率限制和双向空闲保活；默认 loopback，公开监听须显式配置。隧道票据不是 MC 身份认证，不要求 TS 用户安装 Minecraft。
- **TS3 分片**：服务端修正首/尾边界语义；客户端不直接复制，统一有界重组、乱序、孤立片、最大字节/分片/等待时间。
- **RSA**：固定 64 字节无符号编码；补握手必需参数、alpha、曲线点和异常边界，不改变 TS3 握手算法。
- **序号回绕**：16 位模序号与真实代际，覆盖 COMMAND/COMMAND_LOW 的 `65534→65535→0→1`、乱序和迟到旧包。
- 同阶段修复直接关联的 QuickLZ 输入边界、未知包类型、codec 拒绝、ServerQuery 通信日志脱敏和测试服/发布物秘密隔离。

### 关键实现
- 新增纯 Java 可测的 `Seq16`、`BoundedCommandAssembler`、checked QuickLZ reader 与握手校验；保留现有正确 AES/EAX 和 Ed25519 实现。
- 新增独立 v2 网络 payload/能力协商，保留旧 Beacon/GroupInfo 编解码，不通过首字节插入破坏现有客户端。
- 现有 51 项回归可复用，但“信标继承密码”等冲突测试改为新信任模型；先写失败测试再修代码。

### 验收
- 分片、乱序、回绕、压缩畸形输入、RSA 高位、ECDH 非法点均有自动测试，缓存/读取有上界。
- 未授权 endpoint 不收到旧密码；未认证隧道不能转发，纯收听不被单向空闲误断，stop 回收全部会话。
- Query 最高日志级别、payload 调试和发布扫描无秘密；真实秘密轮换只记录管理员结果，不擅自执行。

## 阶段 02：确立身份与信任协议（可选能力，不是语音门槛）

### 协议角色与命名空间
- 音频身份使用 `TsPeerId(tsVirtualServerUid, tsClientUid)`；`clid` 仅是当前会话 ID，不能作为持久身份。
- TS 虚拟服务器 UID 从权威 Query/语音协议 serverinfo 获取，不用 host、频道名或昵称替代。
- 可信映射为可选属性：`UNTRUSTED / VERIFIED_LOCAL / VERIFIED_REMOTE`；无声明者保持普通 TS Ghost、内置皮肤和正常音频。
- 分离 `audioSenderUuid` 与 `skinProfileUuid`：远程签发的相同 MC UUID 不得覆盖本服真实玩家状态或通道。

### 两条自愿证明路径
1. **装 mod 的 MC 客户端直连**：
   - MC 服务器发送一次性 challenge；客户端用现有 TS P-256 身份私钥签名，证明 MC 会话、TS 虚拟服务器、TS UID、nonce 和短有效期。
   - 服务器验证签名公钥推导的 TS UID 后签发映射 claim。复用签名原语，但不改变 TS3 原握手输入。
2. **普通官方 TS 用户自愿配对**：
   - 保留 `/tsbridge pair` + `!pair`，以 ServerQuery 的 invokerUniqueId 和实时会话作为证明；无需安装 MC 或 mod。
   - `autoPairing` 仅凭进频道消费最早配对码、公开频道描述中的配对码，不得产生可信映射。

### Claim、密钥、撤销与过期
- Claim 最小字段：`version、issuerKeyId、mcUuid、tsVirtualServerUid、tsClientUid、revision、issuedAt、expiresAt、signature`；使用长度明确的规范化二进制和 domain separation，不签拼接字符串/Gson 文本。
- 每个 MC 服务器独立生成并持久化签名密钥，`issuerKeyId=公钥指纹`，不与 TS 连接身份或玩家镜像身份共用私钥。
- 本服密钥按 MC 来源 TOFU/变更确认；远程密钥按本地策略显式信任。任意服务器可自签接入，但“自签”不自动等于全球可信。
- 撤销目录含单调 `directoryRevision` 和 tombstone；过期/撤销/信任库损坏只降级为普通 TS 身份，不影响语音。
- 密钥轮换由旧密钥签新密钥；丢失则生成新身份，不能静默继承旧信任。
- 旧配对迁移为 `LEGACY_UNVERIFIED`，重新证明后升级；不清空映射、不阻断音频。

### 立即纠正现有行为
- `client_description=tsvc-uuid:*` 和 `[MC]` 昵称不再能提升为真实 MC 玩家或可信皮肤。
- forwarder 回退不得要求已配对才能转发 MC 语音。
- 无效证明不会踢出、静音或限制陌生 TS 用户。

### 验收
- 无 MC/mod/配对/claim 的普通 TS 用户在各支持路径均可双向语音。
- 伪造 UUID、昵称、错误 TS UID/虚拟服务器、重放 nonce、过期和撤销都不能提升身份，但语音继续。
- 两个各自生成密钥的兼容 MC 服务器可接入同一 TS；互不互信时远程 claim 不生效，普通跨服语音仍正常。
- 可选配对、撤销、过期只改变可信展示，不成为通信门槛。

## 阶段 03：统一群组与音频路由

### 服务器命名空间与群组
- 路由键使用 `(tsVirtualServerUid, cid)`，群组/凭据/信任/头像均包含 TS 虚拟服务器命名空间；同 UID、cid 或频道名出现在不同 TS 时不串数据。
- 本地 SVC：OPEN 留主频道，NORMAL/ISOLATED 进子频道；TS 镜像群映射原子频道。无法完整保留的 NORMAL/proximity 语义在 UI/文档明确降级。
- 以 cid/UUID 持久化所有权，名称只用于显示；同名手工群不得被认领或删除，保留旧名称派生数据的迁移。

### 可信成员快照与权限隔离
- 在 MC 主线程一次性生成成员/密码/接收者不可变快照，后台只发包；区分 `UNKNOWN / EMPTY / MEMBERS`。
- UNKNOWN 时保留上次已验证快照或 fail-closed，绝不退化为全员广播。
- 语音与文字使用同一路由快照；目标频道未确认、移动拒绝、密码缺失或编码/发送失败时遵守明确的防双音频策略，不向旧频道泄密。
- 权限只由服务器/频道/群组成员关系决定，**签名 claim 不授予群组权限**。
- 每用户音频所有权、 mixer 最终 clamp、队列收敛、SVC 回调只入队、原生资源停止后回收；不因重构默认开启音频增强。

### 验收
- 本地/镜像群矩阵、同名冲突、成员 UNKNOWN、频道移动拒绝、不同 TS 同 cid/UID 均通过。
- 无配对普通 MC 玩家在 mixer 不可用、forwarder 回退时仍可发言。
- 成员读取失败不扩大受众；普通 TS 用户不被可信映射或路由改造静音。
- 默认 per-player 与 mixer 均验证双向音频、背压、停止和资源回收。

## 阶段 04：抽共享协议模块并建立 CI

### 构建边界
- 保留两个 standalone Loom 项目，不强行合成一个多模块。
- 新建无 Minecraft/Fabric 的 `ts3voice-core`：Seq16、assembler、QuickLZ、包/加解密、身份原语、transport、Listener、状态机。
- 新增版本化 `bridge-protocol`：能力协商、信标扩展、身份 proof/claim、撤销目录和线格式 golden vectors。
- 通过 `includeBuild`/composite build 或版本化制品复用；实际 mod jar 显式包含所需依赖并检查内容。

### 代码结构
- 协议不知道 GUI、世界、SVC 或配置存储；identity 持久化通过 callback/接口注入。
- SVC 私有反射集中到 client/server adapter；缓存 Method，不缓存旧 ClientVoicechat。
- 会话、路由、配置、音频图和资源 owner 渐进抽取，不大爆炸重写。

### CI 与兼容矩阵
- 将可复用隔离回归迁标准 `src/test`；协议 fuzz/golden vectors、确定性 latch/时钟/弱网/阻塞 socket/资源计数进入 CI。
- 覆盖协议回绕、分片、RSA、隧道、凭据、可选身份、撤销、群组、语音/文字路由、mixer stop、DSP 失败降级。
- 覆盖支持的 SVC 版本、v2 客户端+v1 服务端、v1 客户端+v2 服务端及混合客户端；保留旧通信能力，v2 不再接受旧描述为证明。
- 固定 JDK/Gradle、Loom release、依赖锁/verification、wrapper SHA；CI 能处理 Windows 含 `&` 路径。

### 验收
- core 无 MC/Fabric 依赖，双端无双份协议修复；共享 golden vectors 和兼容矩阵通过。
- 弱网、并发、断连、重放、撤销竞态、长序号测试可重复，无 sleep 碰运气。
- CI 失败阻断交付；不存在自测恒真、部分 FAIL 仍整体 PASS 的入口。

## 阶段 05：完成公开发布验收

### 多实例与真实设备
- 至少双 MC 客户端、多个 MC 服务器/TS 虚拟服务器、普通官方 TS 客户端和 tsbridge，覆盖 direct/server-bridge/group-sync。
- 普通 TS 用户无 MC 身份验证参与双向语音；已验证/未验证用户混合并存。
- 真实音频设备测试麦克风、扬声器、普通 SVC 与 TS 流、设备切换、无 EFX、native 失败回退。
- AEC/NVAFX 只有在取得真实 ABI/设备证据后才宣称稳定；否则标实验或 blocked，不伪造 ERLE、近端损伤或兼容性结论。

### 长会话与升级
- 30 分钟以上双向语音，覆盖命令序号回绕、断线/重连、换服、群组切换、服务器撤销/密钥轮换和 weak network。
- 验证旧配置、旧配对、旧群组所有权、旧身份、混合版本升级；旧映射迁移不阻断通信。
- 检查线程、socket、Opus/native、OpenAL 和 SVC channel 资源无持续增长。

### 发布工程
- 干净环境构建、制品 SHA-256、依赖/SBOM、GPL/MIT/第三方 LICENSE/NOTICE；README 更新模式、配置、信任、可选身份、降级和兼容说明。
- 发布扫描不包含秘密、测试服、世界、缓存或旧 JAR；真实秘密轮换和发布由管理员决定。
- 性能以 P50/P95/P99、队列深度、GC、underrun、资源计数前后对照，不承诺未测量收益。

### 最终门槛
- 所有实现任务完成或经用户明确取消；每个验收项有证据，缺环境项有明确 blocked/Unblock Condition。
- 独立 Review 复核所有 AC/TR；actionable finding 必须转为待办并修复。
- 最新 Review=pass 才能宣称公开发布验收完成；不能只凭构建成功或旧 51 项 PASS。

## 执行队列与依赖

1. **T01 规格与测试底座**：标准测试、golden vectors、fake clock/transport/latch、秘密扫描、证据模板。
2. **T02 P0 协议修复**：分片/序号/RSA/QuickLZ/乱序/codec/转义。
3. **T03 P0 信任边界**：信标授权与凭据、隧道认证/限额、Query 脱敏、秘密隔离。
4. **T04 会话与资源所有权**：关闭代际、旧回调、SVC channel、服务端 bot、Opus/native。
5. **T05 身份协议与密钥目录**：proof/claim、TOFU/信任、撤销/过期、可选配对、旧数据迁移。
6. **T06 群组与路由**：虚拟服务器命名空间、可信成员快照、本地/镜像群、语音/文字一致路由。
7. **T07 双模音频与上传**：per-player/mixer、回退免配对、上传状态、头像命名空间、真实身份展示。
8. **T08 共享 core 与 CI**：协议/bridge-protocol、adapter、标准测试、兼容/弱网/并发矩阵。
9. **T09 客户端音频与配置体验**：AudioGraph、EFX/native 生命周期、AEC/ducking、配置快照/UX；默认增强保持关闭，NVAFX 无证据不盲改。
10. **T10 公开发布验收**：多实例、真实设备、长会话、升级、性能、许可/文档和独立 Review。

依赖顺序为 T01→T02/T03→T04→T05/T06→T07→T08/T09→T10；安全修复和测试可并行，阶段 02 的身份工作不得阻塞阶段 01 的 P0 交付。每个任务先补回归、完成自验并记录证据，再进入下一任务。

## 验证与 Review

- 自动测试覆盖所有可离线验证的协议、信任、路由、配置、生命周期和资源规则。
- 真实 TS/MC、OpenAL/NVIDIA、性能和管理员操作单独记录；无法执行时对应验收标 `blocked`，不冒充完成。
- 独立 Review 重点检查“普通 TS 用户始终可通信”“签名不授予权限”“无中心白名单”“无效身份只降级不静音”，以及所有 P0 和升级兼容要求。
- 不主动提交、推送或发布；批准后只修改产品源码、测试、构建和规格文档，外部真实配置变更仍由管理员执行。