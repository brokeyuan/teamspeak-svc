# TeamSpeak / SVC 统一修复与重构 Backlog

> **状态**：待实施。本文是统一工作清单，不表示任何新条目已修复。
>
> **基线**：`ARCHITECTURE-REVIEW-CURRENT.md`（下称 C）；补充 `ARCHITECTURE-REVIEW-2026-10-27.md`（下称 D）和 `ARCHITECTURE-REVIEW.md`（下称 Z）。
>
> **源码基线**：`teamspeak-svc` 0.9.8、`tsbridge` 0.3.4，MC 26.3 / Java 25。两份原报告和 C 保持不变，本文取代三份报告作为后续实施的优先依据。
>
> **合并原则**：同一根因只保留一个工作项；每项给出证据、改动边界和可验证的完成条件。不得把“条目存在”当成“已在真实设备复现”。

## 1. 如何使用本清单

### 1.1 优先级

| 级别 | 含义 | 处理原则 |
|---|---|---|
| P0 | 发布阻断、安全边界或核心协议错误 | 先修并补回归，再扩大部署 |
| P1 | 高影响功能、并发、生命周期或数据隔离问题 | 下一阶段必须完成 |
| P2 | 可维护性、条件性错误、局部性能与体验问题 | 在对应模块修复时一并处理 |
| P3 | 清理、可读性和低风险工程改进 | 不阻碍高优先级工作，不单独大规模改动 |

### 1.2 证据标签

- **[C]**：当前源码/调用链可确认的问题；不代表已完成真实 TS/MC 端到端复现。
- **[P]**：离线探针或已有自动回归验证过相关行为；验收仍以完整测试范围为准。
- **[R]**：条件性风险，需要特定模式、时序、驱动、设备或外部版本才触发。
- **[V]**：需要真实 TS/MC、NVIDIA/OpenAL 或协议资料进一步验证，不能按已确认故障实施。
- **[G]**：指南性架构/可读性建议，不作为独立缺陷宣称。

建议角色和前置依赖见 2.1；它们不是已分配人员。所有项初始状态为 **TODO**；开始后必须在本文或任务系统记录 `DOING / BLOCKED / DONE`、提交哈希和验证结果。

### 1.3 已验证基线与限制

- 两个模块以 Java 25、Gradle 9.5.1 执行 `clean build --no-daemon --offline`，均 **BUILD SUCCESSFUL**。
- 五组现有隔离回归 **51/51 PASS**；但没有标准 `src/test` 和 CI，Gradle 为 `test NO-SOURCE`。
- 离线探针验证过 RSA 高位覆盖、QuickLZ 越过声明长度、非曲线点导入。
- 未完成真实 TS/MC 双端、真实 NVIDIA/OpenAL、长会话和性能基准验证。
- 性能项的代码路径可以确认，但“降低多少延迟/CPU”“是否发生 native 崩溃”不得由静态审查直接下结论。

---

## 2. 执行路线

| 阶段 | 包含工作项 | 阶段出口 |
|---|---|---|
| 0：保护与协议止血 | B-001～B-006、B-010～B-012 | 秘密与公开隧道风险受控；分片、序号、RSA、压缩边界自动测试通过 |
| 1：生命周期与路由正确性 | B-007～B-009、B-013～B-015、B-020～B-025 | 关闭、重连、换服、路由、上传无跨会话/旧频道副作用 |
| 2：服务端音频与成员 | B-016～B-019 | 默认 1:1 和 mixer 两模式均无孤儿资源；积压与目标受众可验证 |
| 3：客户端音频 | B-026～B-034、B-046 | 原生失败可降级，EFX 按上下文管理，AEC 有真实效果指标 |
| 4：配置、身份与 UX | B-035～B-045、B-051～B-054 | 不丢配置、不冒认身份、错误可见、所有窗口可用 |
| 5：结构、兼容与工程收口 | B-047～B-050、B-055～B-064 | 共享协议与 adapter 落地；CI、兼容矩阵、制品与许可可追溯 |

依赖不是严格的线性瀑布：B-057（测试基建）应从阶段 0 同步建设；B-047/B-048 的共享模块和生命周期抽象随实际修复逐步抽取，不先做全项目重写。

### 2.1 建议角色与前置依赖

| 工作项 | 建议角色 | 前置依赖/并行关系 |
|---|---|---|
| B-001、B-005、B-010 | 协议开发 + 测试 | 同一 assembler/Seq16 设计；B-057 同步建立测试，不重复各写一套 |
| B-006、B-011 | 协议/密码学开发 | 可并行；B-006 的真实握手回归在修改后执行 |
| B-002、B-012、B-042、B-045、B-061 | 安全 + 客户端/服务端开发 | 先确定信任模型和凭据作用域，再改 UI/持久化 |
| B-003、B-004、B-056、B-062 | 网络/发布/安全 | 隧道未完成鉴权前不公网启用；秘密轮换由有权限的管理员执行 |
| B-007～B-009、B-013、B-020 | 生命周期/并发开发 | 共用 session/component generation；先测试模型再调整锁 |
| B-014、B-015、B-021、B-025、B-038～B-041 | 客户端/服务端路由开发 | 共享 Routing/Membership 快照，不能为性能放宽私密检查 |
| B-016～B-019、B-050、B-058 | 服务端音频 + 并发测试 | 默认 1:1 和 mixer 分开验收；先资源所有权再优化队列 |
| B-022、B-035～B-039、B-051～B-055 | 配置/UX + 服务端开发 | 先不可变配置/状态模型，再表单和命令；群组信任决策需产品确认 |
| B-023、B-024、B-043 | 客户端上传/网络开发 | 共用上传 request/session 状态；真实 TS 验证后才确认 CONFIRMED |
| B-026～B-034、B-046、B-060、B-063 | 客户端音频 + native/性能测试 | 先能力和资源生命周期，再 AEC/性能；B-030 需外部 ABI 证据 |
| B-044、B-047～B-049 | Java 架构 + 两端开发 | 随上述修复增量抽取，不提前做大爆炸迁移 |
| B-057、B-059、B-064 | 测试/发布 | 从阶段 0 同步；无验证证据不得关闭工作项 |

---

# 3. 统一工作项

## 3.1 P0：发布阻断与协议止血

### B-001 [P0] 修复服务端分片语义，并建立有界共享重组器

- **状态/证据**：TODO；[C]。来源 C/P0-01，D/P0。
- **位置**：服务端 `ts3voice/Ts3Connection.java:542-564`；客户端同类 `:449-583`。
- **问题**：服务端把首/尾边界标志理解为“仍有后续分片”，多分片命令会等待、截断或错误拼接。
- **改动**：实现独立 `BoundedCommandAssembler`，支持首/中/尾边界、乱序、代际回绕、最大分片数/字节数/等待时间。客户端“孤儿组自愈”分支当前不可达，**禁止直接复制客户端实现作为完整修复**。
- **验收**：单包、2/3/5 片、乱序、孤立尾片、压缩大列表、丢包恢复和 `65535→0` 均通过；内存有上界；真实 `initserver/channellist/clientlist` 完整。

### B-002 [P0] 将信标信任与 TS 凭据按 endpoint 隔离

- **状态/证据**：TODO；[C]。来源 C/P0-02。
- **位置**：客户端 `TsVoiceManager.java:116-136,359-365`；`TeamspeakSvcClient.java:88-90`。
- **问题**：信标可以改变 TS endpoint，却沿用每服或全局密码，可能把为其他 TS 保存的密码用于新端点。
- **改动**：定义规范化 endpoint 与凭据作用域；首次信任/端点变更需明确授权；信标默认不继承全局或其他 endpoint 密码。同步修改当前把继承行为当成预期的旧测试。
- **验收**：跨 endpoint 不发送旧密码；明确授权后才保存；拒绝/仅一次/信任三种选择均可用；日志与 payload 调试输出不含秘密。

### B-003 [P0] 为 TCP 隧道建立认证、限额和明确部署边界

- **状态/证据**：TODO；[C]，默认关闭；启用后风险高。来源 C/P0-03、D/D1、D/4.2-19/20。
- **位置**：服务端 `net/TcpTunnelServer.java:39-50,83-105,109-178`。
- **问题**：绑定所有网卡，无鉴权、会话/速率限制；每连接创建 UDP socket 和线程。accept 瞬时异常会退出而 running 仍可能为 true；单向 TCP 空闲超时可断开纯收听客户端。
- **改动**：默认 loopback，公开监听须显式配置；首帧带时效/随机数鉴权，必要时加密；有界会话/线程/速率、心跳或双向空闲语义；accept 故障更新健康状态。未修复前不公网开放。
- **验收**：未认证不能转发；超限和异常可诊断；stop 关闭全部会话；纯收听/双向流量均不因单向空闲误断。

### B-004 [P0] 隔离敏感配置并确保部署制品与源码一致

- **状态/证据**：TODO；[C]。来源 C/P0-04、D/4.6-3/4。
- **位置**：`testserver/README.md:13`、`config/tsbridge.json:6-7`、`server.properties:37`、身份文件、测试服 mods。
- **问题**：存在真实查询/管理端秘密和身份材料；无忽略规则；同名 `tsbridge-0.3.4+26.3.jar` 与源码构建不同。
- **改动**：若曾共享/提交则轮换秘密并撤销旧身份；脱敏模板、`.gitignore`、ACL；构建和部署记录 SHA-256；排除缓存、日志、世界、旧 JAR 和 review 临时依赖。
- **验收**：发布/提交扫描无秘密和生成物；部署哈希等于干净 CI 构建；不得把“同版本号”当作一致性证明。

### B-005 [P0] 修正 TS3 16 位命令序号和代际回绕

- **状态/证据**：TODO；[C]。来源 C/P1-02、D/4.2-21、Z/3.2。
- **位置**：客户端 `ts3voice/Ts3Connection.java:453-465,493-538`。
- **问题**：`base/id` 继续增长至 65536，但线路上一包 ID 已回绕为 0；代际推断和缓存键不一致。
- **改动**：`Seq16` 值对象统一模 65536 比较；真实代际与 packetId 共同组成缓存键；与 B-001 共用设计。
- **验收**：COMMAND/COMMAND_LOW 分别覆盖 `65534,65535,0,1`、跨回绕分片、迟到旧包、重复包，解密与重组持续正确。

### B-006 [P0] 修正 RSA 固定长度无符号编码和握手输入边界

- **状态/证据**：TODO；RSA 高位 [P]，其余 [C]/[P]。来源 C/P1-03/P2-03、D/4.2-31。
- **位置**：`Ts3Crypt.java:91-109,128-147,182-204,280-313,557-601`；两端同源代码。
- **问题**：65 字节 `BigInteger.toByteArray()` 会覆盖回显区；缺参数、alpha 长度、ECDH 点和派生异常缺少统一边界。
- **改动**：固定 64 字节无符号编码；必需字段/长度校验；`validatePoint` 并拒绝无穷远点；统一转换为 `Ts3VoiceException` 立即失败。不要把 invalid-curve 风险夸大为已证明私钥泄露。
- **验收**：高位 0/1、零、短值、超长、缺参数、非曲线点均有测试；完整回显不变；畸形握手不再等待 30 秒才表现失败。

---

## 3.2 P1：生命周期、协议、服务端音频与上传

### B-007 [P1] 统一关闭顺序，防止停止后重新创建组件

- **状态/证据**：TODO；[C]/[R]。来源 D/B2，C/P1-05/P2-17 补强。
- **位置**：`TsBridgeMod.java:128-181,530-661`。
- **问题**：先停信标/隧道，再置 shutdown 并 join init 线程；初始化恢复后可在 166-170 行重新启动组件。仅调换顺序仍不足，因为 join 有 5 秒超时且 socket connect 不一定响应中断。
- **改动**：关闭代际先置终态；每个启动/提交点二次校验；DNS/连接可取消；停止提交后才逆序 close，并有界等待。
- **验收**：初始化各阶段发生 SERVER_STOPPING 都不会再启动信标、隧道、bot 或重连线程；重复 stop 幂等。

### B-008 [P1] 让连接状态机单向、可取消，并隔离旧会话回调

- **状态/证据**：TODO；[C]/[R]。来源 C/P1-06/P1-07、Z/3.3、D/B3。
- **位置**：客户端 `Ts3VoiceClient.java:52-56,252-272,313-345`；`TsVoiceManager.java:255-273,343-399,487-541`。
- **问题**：connected/failed/latch 分离，断线后可被 connect 重新置 true；重复 connect 状态不完整；旧语音/文字回调查询全局新 client；身份和头像在代际提交前发布。
- **改动**：单次使用或完整状态机；CLOSED 不可逆；连接开始即登记可取消对象；监听器捕获来源 client + generation；副作用在提交后执行。
- **验收**：连接中 stop/换服/超时交错测试；旧回调无法污染新会话；未提交会话不能发布 UUID/头像；断开发送有明确结果而非静默 return。

### B-009 [P1] 分离协议写锁、关闭路径和昂贵计算

- **状态/证据**：TODO；[C]/[R]。来源 C/P1-05/P2-04、D/4.1-10/11/12。
- **位置**：`Ts3Connection.java:108-164,216-221`；`TcpTransport.java:34-38`；`TsVoiceManager.java:214-239`；`TsQueryClient.java:56-125`。
- **问题**：TCP 阻塞写持 sendLock，stop 需同锁才能 close socket；管理器锁内关闭网络；RSA 挑战也在协议锁路径，过高 level 可放大阻塞。
- **改动**：状态、序号/加密、帧写入、socket close 分责；关闭不等待发送锁；管理器锁内只交换快照；挑战难度设合理上限并测量/移出全局锁。
- **验收**：对端不读、DNS 卡顿、Query 连接失败时 GUI/服务器停止均能在预算内结束；无线程死锁和状态回退。

### B-010 [P1] 统一 QuickLZ 读取边界和精确拷贝语义

- **状态/证据**：TODO；[C]/[P]。来源 C/P2-01、D/H2/4.2-30。
- **位置**：两端 `ts3voice/QuickerLz.java`。
- **问题**：客户端声明 compressedSize 不是后续读取硬边界，短 match 会先写 3 字节；服务端旧版还会截断合法长回引。
- **改动**：共享 checked reader；写入前验证 match；精确复制或受检失败；不得用“防御性截断”破坏 offset 语义。
- **验收**：真实向量、截断、尾部多余字节、短 match、长回引、负长度和 fuzz；合法输出与参照一致，无未受控异常。

### B-011 [P1] 修复服务端语音乱序丢弃与协议重传/超时

- **状态/证据**：TODO；[C]/[R]。来源 D/B1/4.2-28/29、C/P2-04。
- **位置**：服务端 `Ts3Connection.java:447-499`；两端重发 `Ts3Connection.java:338-424`。
- **问题**：最大序号 wins 会丢弃窗口内已乱序的新包；初始/最大 RTO 同为 1 秒，同轮多包连续翻倍；lastMessageMs 缺跨线程可见性并使用墙上时钟。
- **改动**：实现真正窗口位图或交给有界下游抖动缓冲；SRTT/RTTVAR、Karn 原则、单调时钟、出站预算。
- **验收**：窗口内乱序不丢、重复不重复播放；系统校时不影响超时；弱网下无重传风暴。

### B-012 [P1] 防止 ServerQuery 通信日志泄露密码

- **状态/证据**：TODO；[C]，默认 debug=false。来源 C/P1-01。
- **位置**：`ts/TsQueryClient.java:74-85`。
- **问题**：登录前开启当前依赖的完整命令通信日志，密码未脱敏。
- **改动**：登录前禁用通信日志；升级/包装依赖，对 login/token/密码修改做参数级过滤；最小权限专用 Query 用户，远程仅 loopback/SSH/VPN。
- **验收**：最高日志级别下无秘密；连接/重连/失败日志均覆盖；远程部署文档无裸公网高权限 Query 建议。

### B-013 [P1] 在会话/UUID 变化时真正关闭旧 SVC AudioChannel

- **状态/证据**：TODO；[C]，完整 SVC 行为待集成测试。来源 C/P1-04。
- **位置**：`TsAudioInjector.java:114-127,336-360`；`TsVoiceManager.java:227-238,323-324`。
- **问题**：只清 Java Ghost/map，没有 `closeAndKill()`；同 UUID 新流低序号可能被旧通道判为旧包并持续保持旧通道活跃。
- **改动**：`SvcAudioChannelAdapter` 由会话代际拥有通道；Ghost 删除、UUID 迁移、换会话时关闭并移除 SVC 通道，不能只重置 marker。
- **验收**：旧高序号→新低序号首帧可播放；旧线程、decoder、jitter buffer 退出；同服真实玩家资源不被误删。

### B-014 [P1] 统一语音和文字的频道就绪状态与发送结果

- **状态/证据**：TODO；[C]。来源 C/P1-14/P1-15、Z/3.1。
- **位置**：`MicRedirector.java:21-40`；`TsVoiceManager.java:418-434`；`TeamspeakSvcClient.java:162-173`。
- **问题**：文字未经路由确认可能发到旧频道；boolean 混淆接管和实际入队；编码异常/空输出时仍可能回落 SVC；停发缺少明确 TS 结束帧。
- **改动**：`VoiceSendResult` 分离 fallback 抑制与入队结果；连接接管期间所有失败路径一致，避免双重音频；文字复用同一路由快照；wasSending + 停止帧。
- **验收**：编码失败、空输出、未路由、发送异常均符合选定的“防回落”策略；未实际入队不计 ACTIVE；私密文字不到旧频道；停止状态不依赖远端超时。

### B-015 [P1] 只接受支持的 codec，并保持 TS 转义/身份映射一致

- **状态/证据**：TODO；[C]。来源 C/P1-12、Z/3.4、D/4.4（转义）。
- **位置**：客户端 `TsAudioInjector.java:99-110`；两端 `Ts3VoiceClient` 语音入口；客户端 `Ts3VoiceClient.java:382-397,787-847`。
- **问题**：非 Opus 仍进入 Opus 解码；escape 输出 `\xHH`，parseParams 无对应解码；非法 UUID 的 catch 会保留旧 MC UUID。
- **改动**：入口过滤 codec，未知格式限频计数；严格 `\xHH` 往返；描述变化时先计算 nullable UUID 再统一 put/remove。
- **验收**：Voice/Music/空结束帧保留；Speex/未知 codec 不建 Ghost、不推进序号；控制字符、空描述、非法描述均有测试。

### B-016 [P1] 修复 mixer 停止时 Opus 资源与线程的竞争

- **状态/证据**：TODO；[C]/[R]。来源 D/B4。
- **位置**：`McAudioMixer.java:78-120,167-192,299-319`。
- **问题**：stop 不等待 mixer 线程即关闭 decoder/encoder；运行中的 tick 可并发访问已关闭对象。start/stop 的 running 检查也非原子。
- **改动**：单 owner 生命周期，CAS/串行 start/stop；先停止并 join（有界），确认退出后关闭 native 资源；异常退出也更新 running。
- **验收**：反复 start/tick/stop 压力测试无 encode-after-close、NPE 或残留线程；不得宣称未实测的“必然 native 崩溃”。

### B-017 [P1] 将 TS→MC 类级锁改为每用户所有权

- **状态/证据**：TODO；[C]；性能幅度 [R]。来源 D/C1。
- **位置**：`McAudioInjector.java:83-145,272-329`。
- **问题**：所有 TS 用户共享 `static synchronized playTsAudio`，锁内解码、重分块、创建/维护 player；创建失败每帧重试并可刷日志。
- **改动**：每用户 `TsUserAudio` 串行处理，全局 registry 短临界区；创建失败负缓存/退避；关闭 refresher 与创建使用一致锁。
- **验收**：多用户并发不共享 decoder/carry；shutdown 不再创建 refresher；记录 P95/P99 处理时间和失败重试频率，不能仅凭锁改动宣称具体延迟收益。

### B-018 [P1] 让混音队列延迟有界且可收敛，修正最终饱和混音

- **状态/证据**：TODO；[C]/[R]。来源 D/C3、C/P2-18。
- **位置**：`McAudioMixer.java:198-230,323-352`。
- **问题**：每 tick 每 sender 只 poll 一帧，突发积压可增至约 2 秒才丢旧；每一路加完就 clamp，破坏后续正负抵消。
- **改动**：测量并选择低延迟高水位（例如约 100ms，而非固定承诺），超限时 drain/丢旧；int 累加所有样本后最终 clamp。
- **验收**：突发后积压能在定义时间收敛；多路极值和正负抵消与顺序无关；无连续追帧造成突发播放。

### B-019 [P1] 将 SVC 回调的同步加密/发送移出实时线程

- **状态/证据**：TODO；[C]，默认 perPlayer=true；性能幅度 [R]。来源 D/C2。
- **位置**：`McToTsDispatcher.java:19-79`；`PlayerVoiceClients.java:163-184`；`Ts3Connection.addOutgoingPacket`。
- **问题**：默认 1:1 模式在 SVC 回调线程内完成加密和 UDP 发送，其他玩家的共享 sendLock/传输故障可放大阻塞。
- **改动**：每客户端有界出站队列和独立发送执行器；SVC 回调只验证并入队；定义过载丢帧、停止帧和统计语义。
- **验收**：回调 P99 在约定预算内；弱网/对端不读不阻塞 SVC；顺序、背压、shutdown 测试通过。

### B-020 [P1] 用独立权威在线快照修复孤儿 bot 对账

- **状态/证据**：TODO；[C]/[R]。来源 D/D3、C/P2-14。
- **位置**：`PlayerVoiceClients.java:118-147`；`OnlinePlayerRegistry.java`；`TsVoicechatPlugin.java:68-87`。
- **问题**：reconcile 使用与丢失 disconnect 事件相同的 registry，无法发现该事件丢失造成的残留；knownMcUids 历史集合需要明确持久化边界。
- **改动**：MC 主线程生成权威在线 UUID 快照；registry 只是连接视图；按快照清理 bot；uid 历史来自身份仓库或明确有界策略，查询方法无写副作用。
- **验收**：模拟 disconnect 事件丢失后仍可回收连接和线程；身份重载不重复/误排除；无无限静态集合增长。

### B-021 [P1] 修正 Ghost 状态提交、皮肤注册和身份失效的时序

- **状态/证据**：TODO；[C]/[R]。来源 D/4.2-23/14、C/P1-16。
- **位置**：`GhostSkins.java:49-69`；`TsAudioInjector.java:176-209`；`GhostPlayerInjector.java:76-86,232-249`；`TsGhostPresenceListener.java:69-145`。
- **问题**：皮肤任务先于 Ghost 注入执行时永久跳过；服务端广播失败前先写 ghosts map；clientCache 在事件丢失后可能污染复用的 clid；broadcast 二次读取 onlinePlayers。
- **改动**：先提交一致状态再调度皮肤，或让皮肤不依赖尚未提交状态；广播成功才提交 map；缓存 TTL+reconcile 清理；单次在线快照。
- **验收**：主线程任务两种交错均可显示皮肤；临时反射失败可重试；clid 复用不冒名；广播目标来自同一快照。

### B-022 [P1] 校验配置及组件就绪，不呈现“已连接但不可桥接”

- **状态/证据**：TODO；[C]。来源 C/P1-13/P1-16、D/4.1-8/9。
- **位置**：`BridgeConfig.java:79-163`；`PlayerVoiceClients.java:269-307`；`fabric.mod.json:20-27`；`TsBridgeCommands.java:238-280`。
- **问题**：端口、数值、mode 无统一验证；缺 Query/频道 ID 仍可创建 bot；缺完整 SVC 依赖；save 吞 IOException，link 命令仍可能报告成功。
- **改动**：不可变配置快照 + ConfigValidator；缺能力进入 NOT_READY/DEGRADED；声明实际 voicechat 版本；保存返回失败；link 是跨组件频道切换事务而非仅改字段。
- **验收**：非法输入有明确行内/命令错误；磁盘失败不报成功；状态区分 connected/joined/ready/degraded；link 失败保留一致旧状态并处理旧永久频道。

### B-023 [P1] 重做文件上传的完成语义与回调归属

- **状态/证据**：TODO；[C]。来源 Z/3.5，C 仅覆盖连接副作用，需单列。
- **位置**：`Ts3FileTransfer.java:34-89`；`Ts3VoiceClient` 的单一 ftReplyHandler。
- **问题**：写入后一次 read 不能证明服务端接受；超时注释与返回 false 矛盾；单一 handler 不能支持并发上传，旧会话可能完成新请求。
- **改动**：按 TS3 协议校验完整结果；结果分 `SENT/CONFIRMED/UNKNOWN/FAILED`；requestId + sessionGeneration 路由回调，支持超时/取消；UNKNOWN 不写永久成功 marker。
- **验收**：短读、EOF、超时、拒绝、服务端关闭、并发上传、旧会话回复均有测试；真实 TS 文件查询确认后才 CONFIRMED。

### B-024 [P1] 按 TS endpoint 与客户端身份隔离头像缓存

- **状态/证据**：TODO；[C]。来源 Z/3.6。
- **位置**：`AvatarPipeline.java:39-67,145-148`。
- **问题**：marker 只含 MC UUID，上传 A 后换 B 会跳过；host 不是可靠的 TS 服务器身份，且发送 `client_flag_avatar` 失败不能算完整成功。
- **改动**：缓存键含 TS 虚拟服务器稳定身份/endpoint、TS client uid、MC UUID 和资源版本；区分上传/设置头像状态；支持失败重试、手动刷新、取消和会话代际。
- **验收**：A→B 必传；同服务器不重复；换 uid/皮肤/服务器失效；UNKNOWN 不永久阻断；取消后不再写 marker。

### B-025 [P1] 明确 SVC 成员未知语义，避免私密数据扩大受众

- **状态/证据**：TODO；[C]/[R]。来源 C/P2-14/P2-19、D/4.2-13/24。
- **位置**：`GhostPlayerInjector.java:176-212,282-289`；`BeaconSender.java:109-200`；`McAudioInjector.java:205-265`。
- **问题**：反射读取失败与“无人”都返回 empty；后台读 MC/SVC 状态；target 全量 clear/add 可观察空窗；广播线程读取玩家列表。
- **改动**：主线程生成不可变 `UNKNOWN/EMPTY/MEMBERS` 快照；后台只发包；UNKNOWN 保留上次已验证受众或 fail-closed；target diff，不清空后全加。
- **验收**：成员、密码和接收者来自同一快照；读取失败绝不扩为全员；刷新期无空 target 窗口。

---

## 3.3 P2：客户端音频、配置、身份与协议质量

### B-026 [P2] 用不可变 AudioGraph 替代音频全局类锁

- **状态/证据**：TODO；[C]，性能幅度 [R]。来源 C/P1-08、D/4.1-4。
- **位置**：`audio/AudioPipeline.java:28-107,165-211`；`AudioExtraScreen.java:35-42,144-148`。
- **改动**：后台构建不可变 AudioGraph，AtomicReference 发布；实时帧只读当前图；旧图在处理结束后关闭；按配置 diff 重建阶段。
- **验收**：主线程/首帧不做 DLL 解包或模型加载；重载不使用中释放；记录帧等待与 UI 卡顿。

### B-027 [P2] EFX 按能力探测和 ALC context 生命周期管理

- **状态/证据**：TODO；[C]/[R]。来源 C/P1-09、D/4.2-39。
- **位置**：`RayTracedReverb.java:48-53,123-133,208-234`；`AlSpeakerMixin.java:76-91`。
- **改动**：探测 EFX/函数地址；NOT_ATTEMPTED/READY/UNAVAILABLE；句柄绑定 context；无能力完全跳过 aux-send；失败仅降级湿声。
- **验收**：无 EFX、gen 失败、设备切换、SoundManager 重建无异常、无旧 ID 复用和泄漏。

### B-028 [P2] 以真实播放时间轴重构 AEC 参考

- **状态/证据**：TODO；[C]；实际效果 [V]。来源 C/P1-10、D/4.2-41。
- **位置**：`AlSpeakerMixin.java:63-69`；`AecReferenceMixer.java`；`AecProcessor.java:84-113`。
- **改动**：优先系统 loopback；或在最终音量/空间处理后按时间戳混音，结合 queued samples/sample offset/device latency，处理漂移和欠载。不能继续把多 speaker FIFO 串联称为混音。
- **验收**：单人、多人、静音、设备切换和不同外放延迟下测 ERLE/近端损伤；无指标前功能标为实验，不宣称已验证有效。

### B-029 [P2] native DSP 统一结果、熔断和 SVC 原生回退

- **状态/证据**：TODO；[C]/[R]。来源 C/P1-11/P2-09、D/4.2-46/48/49。
- **位置**：`AecProcessor.java:101-106`、`WebRtcNative.java:51-68`、`NvidiaDenoiser.java:93-147`、`MicProcessorMixin.java:18-30`。
- **改动**：检查所有 native 返回码；失败不复制未验证输出，限频熔断并恢复 SVC 原生 NS；AEC 失败仍可运行独立 NS；明确 48k 支持或真实重采样；AGC 单一所有者、固定 NS→AEC→AGC 顺序。
- **验收**：各阶段独立故障不影响其他功能；不重复 AGC；无每帧错误日志；采样率契约明确。

### B-030 [P2] 核实 NVAFX ABI 后再修参数映射，收敛生成绑定

- **状态/证据**：TODO；[V]，命名不一致 [C]。来源 D/C5/4.2-47、C/P1-11。
- **位置**：`NvidiaDenoiser.java:47-75`；`nativ/NvAudioEffects.java:13-16,1242-1254`。
- **问题**：isRtxVoice 与 boolean gtx 命名矛盾，但当前未证实键映射反转；类加载即 libraryLookup 有时序依赖；现代分支未明确采样率/帧长。
- **改动**：先对照 NVIDIA 头文件/真实 DLL；定义 RTX_VOICE_LEGACY/AFX_SDK 显式档案，Load 后查询采样率/通道数/帧长；保留生成物来源与生成方式，再决定收敛到实际符号封装，**不为减少行数盲改 ABI**。
- **验收**：两类支持的真实 DLL 均初始化和处理成功；未安装时可降级而非 ExceptionInInitializerError/NoClassDefFoundError；记录证据后取消 [V]。

### B-031 [P2] Ducking 只响应真实 TS 发声，并刷新现有声音

- **状态/证据**：TODO；[C]。来源 C/P2-10。
- **位置**：`DuckingManager.java:36-137`；`AlSpeakerMixin.java:63-68`。
- **改动**：TS provenance 标记，忽略普通 SVC、静音预填和零音量；按实际播放队列估算活跃；独立 multiplier + refreshCategoryVolume；单调时钟。
- **验收**：现有音乐/循环声即时压下和恢复；普通 SVC 不触发；用户滑条不被覆盖。

### B-032 [P2] 混响参数原子发布，射线按时间预算执行

- **状态/证据**：TODO；[C]/[R]。来源 C/P2-11、D/4.2-40。
- **位置**：`RayTracedReverb.java:38-107,139-193,259-359`。
- **改动**：完整不可变 ReverbParams，缺失 early/late 清零并限制范围；monoTime 时间预算、Player 限定查询、环境 dirty 驱动；reverbIntervalMs 要接入配置或删除死配置。
- **验收**：无负延迟和跨批残留；复杂场景帧耗时有界；配置修改实际生效。

### B-033 [P2] 低延迟按真实队列测量，而非只改预填值

- **状态/证据**：TODO；[C]/[R]。来源 C/P2-13。
- **位置**：`AlSpeakerMixin.java:49-60`；SVC `ALSpeakerBase.java:81-128`。
- **改动**：预填与实时队列分开；模式切换明确重置；按 queued samples/sample offset 渐进调整，不盲删有效帧。
- **验收**：冷启动、抖动、设备延迟下测实际听感延迟；不只断言 getBufferSize 返回 2。

### B-034 [P2] 关闭完整 native/FFM/缓存生命周期

- **状态/证据**：TODO；[C]/[R]。来源 C/P2-12、D/4.2-50。
- **位置**：`AudioPipeline.java:74-91`、`NvidiaDenoiser.java:37-44`、`WebRtcNative.java:91-128`。
- **改动**：NativeLibraryOwner、AudioGraph.close 接 mic/client stop；避免重复无主加载；缓存原子替换、损坏隔离重解包。
- **验收**：重复启停/设备切换无资源积累；坏缓存可恢复；临时文件无残留。

### B-035 [P2] 配置改为不可变快照，恢复时不重复迁移

- **状态/证据**：TODO；[C]。来源 C/P2-05/P2-06、D/4.2-37。
- **位置**：`ServerConfigStore.java:28-89`；`ModConfig.java:60,83-104`；`ConfigFileUtil.java:46-54`。
- **改动**：完整校验后原子替换 map；null 条目隔离；createCurrentDefaults；备份失败进入只读恢复，禁止覆盖原文件。
- **验收**：并发保存只读完整旧/新版；合法 null 不中断初始化；损坏→修改→重启不重复迁移；备份失败可见。

### B-036 [P2] 配置 UI 保留继承语义、校验输入并实时反映状态

- **状态/证据**：TODO；[C]。来源 C/P2-07/P2-08、D/4.5-1/7。
- **位置**：`ConfigScreen.java:72-299`。
- **改动**：raw/effective 分离；密码继承/覆盖/显式空三态；端口 1..65535 错误提示；状态 tick 更新、连接中取消、滚动布局；resize 不写配置，主菜单无服务器上下文不保存空 key。
- **验收**：只打开保存不改变继承；可编辑字段不丢；小窗口可完成操作；后台状态无需重开窗口。

### B-037 [P2] 让音频 Extra 面板类型安全并统一配置应用

- **状态/证据**：TODO；[C]。来源 D/4.5-2/3/4/10、Z/5.1/5.2。
- **位置**：`AudioExtraScreen.java:26-58,88-148`；`AudioPipeline.java:117-142`。
- **改动**：直接类型安全绑定或访问器，不吞异常；ConfigApplyService 统一保存/refresh/reconfigure；缺省 0.35 进入 Ducking 档位或改滑条；提示用 translatable，mc.player 未出现时可延后；补齐关键参数或明确仅 JSON 可配。
- **验收**：按钮失败有可见错误；各配置只应用一次；英文/中文完整；所有面板声明支持的项都有入口。

### B-038 [P2] 明确群组类型和镜像所有权，不误删手工群组

- **状态/证据**：TODO；[C]。来源 C/P2-15、D/4.2-17/18。
- **位置**：客户端 `GroupChannelMapper.java:77-112`；服务端 `TsChannelGroupSync.java:29-141`；`BridgeChannelManager.java:53-77`。
- **改动**：定义 OPEN/NORMAL/ISOLATED 与镜像群映射矩阵；cid 派生 UUID 并持久化所有权；名称只显示；link 旧频道清理须明确所有权。
- **验收**：三类本地/镜像群、同名冲突、重启与删除；不误删手工群；不遗留本模块拥有的旧永久频道。

### B-039 [P2] 配对状态实例化、一次性消费并校验消息来源

- **状态/证据**：TODO；[C]。来源 C/P2-16、D/4.2-15/16。
- **位置**：`PairingManager.java:54-187,269-334`。
- **改动**：pending 归 manager 实例；同一玩家只一个有效码或 cancel 删除全部；绑定成功才最终消费；targetmode/频道成员校验和限流；通知失败与绑定失败分开。
- **验收**：重启、异常、重复、私聊/跨频道、暴力尝试和 cancel 全场景符合预期。

### B-040 [P2] 统一 GroupChannelMapper 快照，减少路由静默暂停

- **状态/证据**：TODO；[C]/[R]。来源 D/4.2-22、Z/6（路由缓存需修正表述）。
- **位置**：`GroupChannelMapper.java:18-22,34-64,118-160`。
- **问题**：reset/tick 跨线程非 volatile 字段；tick 首行先清 routing，再异步确认；每帧目录查询有成本，但“遍历频道表”描述不准确（当前是 map 查询）。
- **改动**：完整 Routing 局部构建后原子发布；reset 同步代际；目录修订号/快照避免无意义重复查找；未确认仍必须阻断旧频道语音。
- **验收**：tick 异常/刷新不会额外清空已确认有效路由；群组改变仍立即阻断；不能为性能放宽隐私检查。

### B-041 [P2] 在线玩家和 Ghost 状态采用不可变快照/差异更新

- **状态/证据**：TODO；[C]/[R]。来源 D/4.2-36、Z/6。
- **位置**：`OnlinePlayers.java:25-39`；`ClientStateReflector.java:119-225`。
- **问题**：OnlinePlayers clear+refill 可被 TS 线程观察为空；Ghost 可见性刷新可重复创建状态。
- **改动**：局部构建后一次性发布不可变 map/set；按身份/名称/群组 diff 更新 owned state，保留“不覆盖原生玩家”规则。
- **验收**：任何时刻读者看到完整旧/新快照；换服/昵称/群组变更无瞬时身份丢失和原生状态覆盖。

### B-042 [P2] Ghost/皮肤身份区分“未验证映射”和“可信身份”

- **状态/证据**：TODO；[C]，信任模型需产品确认。来源 Z/7.1。
- **位置**：`TsVoiceClient` client_description 解析；`TsVoiceManager.java:535-541`；`TsAudioInjector.createGhost`。
- **问题**：TS 描述和 `[MC]` 昵称可伪造，当前可映射到真实 MC UUID、皮肤及同服玩家通道。
- **改动**：默认标为未验证的 TS 外观身份；可信映射须由 MC 服务端签名/配对证明；同服玩家音频归属不可仅凭昵称；UI 明示来源。
- **验收**：伪造描述/昵称不能冒认同服玩家或可信皮肤；合法跨服映射仍可用；信任状态可见。

### B-043 [P2] 使用 Gson 解析皮肤响应并改善上传资源管理

- **状态/证据**：TODO；[C]。来源 Z/5.3、D/4.3-68。
- **位置**：`AvatarPipeline.java:31-34,72-139`。
- **改动**：JSON 结构化解析、URL allowlist/大小限制；HttpClient executor 生命周期明确；PNG 尽量内存编码，避免临时文件；复用 NativeImage try-with-resources。
- **验收**：空格、转义、缺字段、非 200、非法 URL、大图均受控；重复上传不积累 executor/临时文件。

### B-044 [P2] 反射与协议依赖集中到 adapter，缓存句柄而非旧客户端

- **状态/证据**：TODO；[C]，性能幅度 [R]。来源 D/C4/A3、C/P2-22、Z/4.2。
- **位置**：`TsAudioInjector.java:218-265`；`ClientStateReflector`；`BeaconSender.java:165-233`；`Ts3Connection.bindClient`。
- **改动**：`SvcClientAdapter`/`SvcServerAdapter` 缓存 Class/Method/Constructor；`ClientVoicechat` 每次从当前管理器获取，不缓存旧实例；ensureReflection 快路径不加锁；协议 Listener 接口打破双向具体依赖。
- **验收**：换服后用新 ClientVoicechat，即使旧对象静默丢包也能恢复；兼容失败显式降级；热路径无重复 getMethod/Class.forName。

### B-045 [P2] 存储采用原子私有写入，保存失败必须向上可见

- **状态/证据**：TODO；[C]。来源 C/P2-21、D/D2。
- **位置**：服务端 `PairingStore.java:59-92,149-181`、`Ts3Identity.java:198-204`、`BridgeConfig.java:104-112`。
- **改动**：共享 PrivateFileStore；损坏先备份而不是空 store 后续覆盖；异步保存有错误传播/健康状态；POSIX 600 + Windows ACL；payload toString 脱敏。
- **验收**：写入中断/解析失败不丢可恢复原件；shutdown 保存失败可见；日志/异常无秘密。

### B-046 [P2] 采样、分配与队列优化以 profiling 为准

- **状态/证据**：TODO；[C]，收益 [R]。来源 C/P3-04、D/4.3、Z/6。
- **位置**：WebRtc/Nvidia/Aec/TsTransport、McAudioMixer、McToTsForwarder、RayTracedReverb、Ts3VoiceClient。
- **改动**：固定帧预分配、uid→clid 索引、CLQ.size 改有界 ring buffer、UDP maxLen+1、静态静音帧；空闲 forwarder 可 take + interrupt；ThreadLocalRandom。**不承诺实时路径绝对零分配，不跨线程共享可变帧缓冲。**
- **验收**：JFR 分配率、GC、帧 P50/P95/P99、underrun/积压对比；正确性回归先行。

---

## 3.4 P3：结构、清理、体验和工程化

### B-047 [P3/G] 按职责渐进拆分静态管理器

- **来源**：C/P3-01、D/A2/4.1-1、Z/4.1。
- **改动**：客户端 `ConfigResolver / SessionCoordinator / RoutingService / VoiceIngress / VoiceEgress / ChatBridge / StatusSnapshot`；服务端 `BridgeRuntime / LifecycleManager / HealthStatus`。
- **约束**：不是按 SOLID 名词机械拆文件；以状态唯一 owner、线程边界和可测试性为验收。构造器注入，生命周期串行协调。

### B-048 [P3/G] 抽共享 `ts3voice-core`，停止复制即架构

- **来源**：C/P0-01/P3-02、D/A1/A3/R1。
- **改动**：无 Minecraft 的 Java library：packet、Seq16、assembler、QuickLZ、crypto、identity、transport、state machine；客户端/服务端业务保留在 adapter。Bridge wire payload 单独版本化。
- **验收**：双副本清零； golden vectors 两端共用；协议不知道 GUI/SVC/世界状态。量化重复率仅作参考，不能把“92% 相同”误当可无条件合并比例。

### B-049 [P3/G] 用最小接口表达真实结果与线程契约

- **来源**：C/P3-03、D/4.1-2/3/4。
- **改动**：VoiceSendResult、AudioStage、AudioRoute、ConnectionWorker、QueryGateway；start/stop/isConnected 一致；异步 accepted 不写成 sent。
- **验收**：每种实现 success/failure/close/backpressure 语义一致；SVC 回调只入队，业务不依赖静态服务定位器。

### B-050 [P3] 清理确认的死代码和不可靠旧回退

- **来源**：C/P2-20、D/4.4。
- **范围**：AudioCodec、旧 McToTsForwarder/TS3AudioBot 路径、无效 sendSilenceFrames、重复 currentClientForSync、未使用字段/方法、magic number 和命名。
- **约束**：删除前再跑当前源码引用与入口扫描；deprecated 路径若保留，必须可选且有 pending 上限、超时、可取消重连；sendAsync 不得无界。
- **验收**：每种模式只有一个活动 AudioRoute；无“两个都启动但只一个被选中”或失败回退不可达。

### B-051 [P3] 统一日志、错误提示与状态可读性

- **来源**：D/4.5-5/6/8/9、C/P3-05。
- **改动**：内部异常详情进日志，玩家获得通用可操作文案；`/tsbridge` 帮助与分区 status；debug 可热更但通信秘密过滤仍生效；断线 Ghost 有明确保留/清理策略。
- **验收**：无 null/NPE 原文给玩家；状态能区分 configured/connecting/connected/joined/ready/degraded；健康状态与真实线程一致。

### B-052 [P3] 修正 SVC 屏幕布局与功能状态展示

- **来源**：C/P3-05、D/4.2-43。
- **改动**：新增按钮不覆盖录音计时；优先 Accessor/官方扩展点而非私有反射；状态区分 discovered/decoded/played/dropped/not-ready。
- **验收**：不同 UI 缩放和 SVC 支持版本不重叠；用户能看出“配置开启”和“真实生效”的差别。

### B-053 [P3] Mixin 采用按功能分级的失败策略

- **来源**：C/P2-22；纠正 D/4.2-42。
- **规则**：不得一律 require=0，也不得一律硬崩。核心音频改道可明确 FAIL_FAST；可选皮肤/混响/降噪应软降级；关键功能在启动/状态页报告实际能力。
- **验收**：当前 `defaultRequire=0` 已知，不再误报“MicProcessorMixin 无软降级”；每类 mixin 缺失时有预期行为。

### B-054 [P3] 服务端配置 reload、配对提示和文档与实际模式一致

- **来源**：D/4.5-8、C/P3-06、D/README 项。
- **改动**：debug 是否热更由应用机制决定；文档标出热更/重启/mode 适用范围；默认 1:1 不以配对为必经步骤；配置示例完整；版本和 Loader 要求由构建生成。
- **验收**：reload 无部分应用；README 的每条命令可复制执行；用户能理解 direct、server-bridge、group-sync 与配对关系。

### B-055 [P3] 声明真实 SVC 兼容矩阵，避免私有 API 静默失效

- **来源**：C/P2-22。
- **改动**：编译/运行基线一致；实际支持版本范围或上限；启动能力探测；不支持时明确禁用对应组件。
- **验收**：每个支持版本做启动、双向语音、Ghost、Mixin 测试；不再宣称所有 `>=2.6.20` 自动兼容。

### B-056 [P3] 依赖、wrapper、原生平台和发布许可可追溯

- **来源**：C/P2-23/P3-06。
- **改动**：升级受支持 BouncyCastle 并回归握手；固定 Loom release、依赖锁/verification、wrapper SHA；macOS Intel 补齐或声明不支持；GPL/MIT/第三方 LICENSE/NOTICE/SBOM。
- **验收**：无旧 review 缓存的干净环境可构建；两次构建制品可比较；安全公告与可达调用链分开记录，不把版本公告等同已证实利用。

---

## 3.5 横切测试与交付工作项

### B-057 [P1] 将现有回归迁入标准测试并建立 CI

- **来源**：C/P2-24、D/R5、Z/8.2。
- **改动**：51 项隔离测试迁 `src/test`；协议纯 Java 模块优先 JUnit/fuzz；集成测试单独 source set/profile；任一子步骤失败必须非零退出。
- **验收**：PR 自动执行两模块 clean build test；测试失败阻断合并；自测 main 不进生产 JAR。

### B-058 [P1] 建立生命周期与弱网确定性测试模型

- **来源**：B-007～B-025 汇总。
- **覆盖**：latch 控制 connect/disconnect/stop；阻塞 socket 对端；旧回调；connect 复用；同 UUID 序号重置；stop 与 encode；成员 UNKNOWN；上传短读/超时。
- **验收**：无 sleep 碰运气的测试；每个已修竞态至少一个可重复交错场景；线程和资源计数回到基线。

### B-059 [P2] 建立真实 TS/MC 集成验收矩阵

- **来源**：三份报告共同缺口。
- **覆盖**：双 MC + 普通 TS + tsbridge；direct/server-bridge/group-sync；私密群组、换服、重连、30 分钟以上会话、弱网、大频道列表、文件上传与头像。
- **验收**：每项有预期音频、身份、频道、资源回收和日志结果；失败保留版本、配置脱敏、日志和抓包证据。

### B-060 [P2] 建立音频与 native 设备验收

- **来源**：B-026～B-034、B-046。
- **覆盖**：无 EFX、aux 失败、设备切换、NVIDIA 缺 DLL/不同 ABI、WebRTC 缓存损坏、外放 AEC、多人说话、AGC/NS 组合。
- **验收**：干声始终可用；失败可见且回退；帧耗时/underrun/ERLE/近端损伤记录；设备证据后才关闭 [V] 项。

### B-061 [P2] 建立安全回归与秘密扫描

- **来源**：B-002～B-004、B-012、B-042、B-045。
- **覆盖**：跨 endpoint 凭据、debug 日志、payload toString、隧道鉴权/限额、配对上下文、伪造 MC UUID、工作区与 JAR 秘密。
- **验收**：自动扫描和人工威胁模型均通过；失败不泄露有效凭据信息；私密成员失败不扩大受众。

### B-062 [P3] 可重复构建、制品和文档单一来源

- **来源**：B-004、B-054～B-056。
- **改动**：CI 固定 JDK/Gradle/依赖；部署记录 SHA；版本/配置/兼容矩阵从构建元数据生成；三份旧报告标历史。
- **验收**：本文是唯一活动 backlog；旧报告不再被当作当前缺陷清单；发布包包含正确许可与依赖清单。

### B-063 [P3] 性能测量先建立基线，再做微调

- **来源**：对 D/4.3、Z/6 的统一修正。
- **改动**：记录 SVC 回调、协议发送、每用户注入、mixer、DSP 的 P50/P95/P99，队列深度、分配率、GC、underrun、线程/native 资源数量。
- **验收**：每项性能优化有前后数据；未测量不得写“延迟线性叠加”“零分配”“native 必然崩溃”等结论。

### B-064 [P1] 每项修复必须具备回归证据后才可标 DONE

- **来源**：统一工程纪律。
- **改动**：为每个工作项维护验证记录，至少包含变更提交、自动测试、必要的真机/集成证据、文档更新和剩余限制；评审通过后才能改为 DONE。
- **完成定义**：代码 + 自动测试 + 必要真机/集成验证 + 文档/status 更新；涉及 [V] 的事项附外部证据。
- **禁止**：仅编译通过、仅旧 51 项 PASS、仅静态阅读、仅替换实现、仅计划完成就标 DONE。

---

# 4. 三份报告的纠错裁决

以下条目**不得按原文直接实施**。保留它们是为了避免后续重新引入错误建议。

| 原结论 | 裁决 | 统一处理 |
|---|---|---|
| D：直接移植客户端 CommandAssembler 即可 | 不完整 | 客户端边界语义更正确，但孤儿分支不可达、缓存无预算；按 B-001 重写共享有界实现。 |
| D：NVAFX 参数键已反转 | 证据不足 | 命名矛盾确定，实际映射需 NVIDIA 资料/DLL；按 B-030 [V]。 |
| D：MicProcessorMixin 未 require=0，会硬崩 | 与当前配置矛盾 | `teamspeak-svc.mixins.json` 已有 `defaultRequire=0`；按 B-053 分级，而非统一软降级。 |
| D：缓存 ClientVoicechat，失败时刷新 | 会重新引入旧会话风险 | 旧对象可能静默丢包；只缓存反射句柄，按 B-044 每次取当前实例。 |
| D：音频实时路径应绝对零分配 | 过度目标 | 先正确性、锁和积压，再 profiling；按 B-046/B-063。 |
| D：McAudioMixer 延迟无限增长 | 表述不准确 | 队列有界 100 帧，突发可约 2 秒后丢旧；按 B-018 修延迟收敛。 |
| D：mixer stop 会导致 native 崩溃 | 竞态确定，崩溃不确定 | 按 B-016 修 encode/decode-after-close，真机记录实际结果。 |
| D：reverbIntervalMs 在开启混响时必然每 tick 满负荷 | 不成立 | 默认 500ms 且未写配置，需分批周期核算；按 B-032。 |
| D：客户端 `NvidiaDenoiser` out 数组从未读 | 与当前代码不符 | 当前读取 `outSeg.toArray`；可优化分配，不能按错误事实修。 |
| D：所有 mixin 应 require=0 | 不接受 | 核心功能与可选增强分级，见 B-053。 |
| D：group-sync 用本进程 UUID 集合即可持久防误删 | 不足 | 重启后仍可能误认领同名群；按 B-038 持久化 cid→UUID 所有权。 |
| D：Beacon 双端字段错位，需首字节版本号 | 需兼容设计 | 字段当前对称，升级时用版本握手/能力协商，不能盲目首字节插入破坏现有 codec。 |
| Z：所有麦克风失败都会回落 SVC | 范围过宽 | 当前未路由/发送异常已抑制；编码异常/空输出仍有缺口；按 B-014。 |
| Z：GroupChannelMapper 每帧遍历频道表 | 不准确 | 当前为 map 查询；仍需快照与避免无意义查询，见 B-040。 |
| Z：手写拆分 NvAudioEffects 以减少行数 | 不接受 | 生成绑定保留来源，按实际 ABI 收敛，见 B-030。 |
| C：`OnlinePlayers` 无独立问题 | 需补漏 | clear+refill 可读到空表；按 B-041。 |
| C：文件传输只归连接副作用 | 覆盖不足 | 独立上传确认/回调归属见 B-023。 |
| C：头像只归会话提交时机 | 覆盖不足 | 独立跨服务器缓存与成功语义见 B-024。 |

---

# 5. 原报告到统一条目的映射

## 5.1 `CURRENT.md` 50 项全部保留或归并

| CURRENT 原项 | 统一项 |
|---|---|
| P0-01～P0-04 | B-001～B-004 |
| P1-01 | B-012 |
| P1-02～P1-03 | B-005～B-006 |
| P1-04 | B-013 |
| P1-05 | B-009 |
| P1-06～P1-07 | B-008 |
| P1-08～P1-11 | B-026、B-027、B-028、B-029（NVAFX 另见 B-030） |
| P1-12～P1-16 | B-015、B-022、B-014、B-014、B-021/B-022/B-055 |
| P2-01 | B-010 |
| P2-02 | B-001（有界重组与孤儿分片处理） |
| P2-03 | B-006 |
| P2-04 | B-011 |
| P2-05～P2-08 | B-035、B-035、B-036、B-036 |
| P2-09～P2-13 | B-029、B-031、B-032、B-034、B-033 |
| P2-14 | B-025 |
| P2-15～P2-17 | B-038、B-039、B-007/B-008/B-009 |
| P2-18～P2-20 | B-018、B-025、B-050 |
| P2-21 | B-012/B-045 |
| P2-22～P2-24 | B-044/B-053/B-055、B-056、B-057 |
| P3-01～P3-06 | B-047、B-048/B-049、B-049、B-046/B-063、B-052、B-054/B-056/B-062 |

## 5.2 日期版独有项的主要归并

- A1/A3、静态服务定位器、状态机重复：B-047～B-049。
- B1～B4：B-011、B-007、B-008、B-016。
- C1～C4：B-017、B-019、B-018、B-044。
- C5：B-030；D1～D3：B-003、B-045、B-020。
- Pairing 多码、Ghost cache、成员/广播并发：B-021、B-025、B-039。
- 配置保存/link/reload、Query 锁、线程关闭：B-007～B-009、B-022、B-051/B-054。
- 死代码、可读性、工程化、体验：B-050～B-063；不再按“约 150 条”计数。

## 5.3 ZIP 整理版独有项的主要归并

- 麦克风失败：B-014（范围已纠正）。
- 文件上传：B-023；头像缓存：B-024。
- 身份可信度：B-042；JSON/HTTP/PNG：B-043。
- 客户端职责/音频抽象：B-047/B-049；配置应用：B-037。
- 实时分配与性能测量：B-046/B-063；macOS/许可/测试：B-056/B-057。

---

# 6. 完成定义与覆盖审计

## 6.1 单个工作项 DONE 的必要条件

1. 变更范围与本文条目一致，未顺带重写无关模块。
2. 新的自动回归覆盖原失败路径；并发项有确定性交错测试。
3. [R]/[V] 项附对应模式、设备或真实协议证据，未验证不能标 DONE。
4. 构建通过不是唯一证据；性能项附测量，安全项附秘密/受众测试。
5. 用户可见行为、配置、兼容性和文档同步更新。
6. 回写验证命令、结果、提交哈希和剩余限制。

## 6.2 合并覆盖检查

| 用户要求 | 统一清单覆盖 |
|---|---|
| 以 CURRENT.md 为骨架 | 保留 C 的优先级、验证纪律、目标架构和全部 50 项映射；以 B-xxx 统一编号。 |
| 去重 | 双副本、静态管理器、反射、音频分配、生命周期、配置等按根因合并，不按报告条数计数。 |
| 纠错 | 第 4 节列出 18 项不得照搬的原结论及原因。 |
| 补漏 | 新增关闭竞态、mixer 生命周期/积压、语音乱序、bot 对账、皮肤时序、上传、头像、身份信任等。 |
| SOLID/重复/可读性 | B-026、B-044、B-047～B-050，及各逻辑项中的最小职责边界。 |
| 性能瓶颈 | B-009、B-017～B-019、B-026、B-032、B-044、B-046、B-063。 |
| 逻辑/使用体验 | B-001～B-045 和 B-051～B-055，均有影响与验收。 |
| 可执行性 | 6 个阶段、64 个统一工作项、角色/证据/依赖/完成定义；B-057～B-064 为横切交付要求。 |

**当前状态：统一 backlog 已形成，尚未实施产品代码修复。**
