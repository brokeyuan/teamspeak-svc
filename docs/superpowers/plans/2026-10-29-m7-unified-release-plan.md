# teamspeak-svc 下一轮实施任务书：M7 统一发布与可选服务端

> **执行范围更新：**本文件现作为[持续推进总任务书](2026-10-29-continuous-development-master-plan.md)的阶段 B。开工以当前工程收尾检查通过为前提，客户端验收按用户要求暂缓，不再要求先取得全部客户端验收结果。M7 完成工程检查后可继续后续已确定阶段；下文“M7 不实施 TCP/音乐”仅表示这些工作属于其他子阶段，不是整个任务的停点。

> **给 GLM 5.3 Flash / 下一轮代码 AI：**本任务以 M0–M6 / T00–T13 已完成并通过约定验收为前提，直接实施 M7/T14。使用上一轮最终交付提交作为基线，按 T14.1–T14.8 执行。本轮不重开 R01–R10 修复清单，不实施 TS TCP、SVC TCP、音乐 Bot 或媒体桥接。执行环境提供技能时，可使用 `superpowers:executing-plans` 按任务推进。

**日期：**2026-10-29。**任务书版本：**1.0。**对应规划：**V3.0 第二阶段 / 里程碑 B。

**Goal：**发布同一个 teamspeak-svc JAR：客户端保留完整 TS/SVC 体验；Minecraft 服务端可选安装，且不装 SVC 时也能提供本扩展自身的能力公告，不再需要独立公告模组。

**Architecture：**现有客户端功能保留；新增环境安全的入口、公共 payload 注册和按 MinecraftServer 实例管理的服务端运行时；整合现有公告实现。共享纯协议仍放 ts3voice-core，服务端专用代码不依赖客户端 GUI、音频或 native 初始化。

**Tech Stack：**沿用上一轮完成版的 MC/Fabric/Java/SVC 版本。编写时工程定位为 MC 26.3、Java 25、Loader 0.20.1、Fabric API 0.165.0+26.3、SVC 2.6.25；本轮不顺手升级这些版本。

**Spec：**用户《长期开发与产品演进计划 V3.0》及已确定的五项补充；继承 [上一轮任务书 1.1](2026-10-28-glm53flash-improvement-report.md) 的产品约束。后续路线已调整，见 [V3 传输与音乐设计备忘](2026-10-29-v3-transport-music-design.md)。

## 1. 开工前提与本轮边界

这是**在上一轮完成后直接交付给代码 AI 的任务书**。“M0–M6 已完成”是本任务的前提，不是编写本文时对正在进行的工作的验收声明。实际开工只需记录上一轮交付提交、构建产物和验收报告位置，并适配最终类名；不再把“重新核对上一轮是否完成”列为新一轮主任务。

如果执行中发现本轮改动引起回归，必须修复；如果发现与前提不符的具体缺口，单独记录并隔离影响，不擅自回到上一轮大规模重做。当前运行中的开发分支和原任务文档不因本文而改变。

### 必须交付

- 一个可由玩家和管理员安装的 teamspeak-svc 发布 JAR，以同一文件哈希验证客户端和 dedicated server。
- 客户端仍安装 SVC；服务端仅使用公告时不需要 SVC。
- 公告由 teamspeak-svc 本身提供，旧 voice-capabilities-fabric 不再出现在必装清单。
- 有边界的服务端配置、生命周期、故障状态和管理诊断。
- 不重复注册 payload，不在服务端加载客户端声音/界面类或初始化客户端 native 音频。
- 回归、真实安装矩阵、部署文档、迁移与回滚说明。

### 保留要求

保留上一轮完成的独立 TS 音频、TS/SVC 选择、书签/频道/用户 UI、原生频道跟随与锁定、身份与资源隔离、nonce/TTL/授权、三种操作“断开 TS / 切 SVC / 全关语音”。无公告仍允许手动或已启用的本地/全局 TS 目标；PAUSED/OFF 不被公告或换服撤销。

本轮不引入新必装 Bridge、不修改音频效果默认值、不重排全部目录、不迁移整套 TS 协议栈、不实现占位 TCP/音乐服务，不把未来配置开关公告成已可用功能。

## 2. 源码依据与方案选择

以下是编写任务书时确认的结构位置，供完成版定位，不是对上一轮进度的评分：

| 文件 | 与 M7 直接有关的结构 |
|---|---|
| `teamspeak-svc/src/main/resources/fabric.mod.json` | 当前发布环境为 client；voicechat 自定义入口直指客户端插件；全局依赖 voicechat/voicechat_api |
| `teamspeak-svc/src/main/resources/teamspeak-svc.mixins.json` | SoundEngineAccessor、SoundManagerAccessor 指向 MC 客户端声音类，却列在通用 mixins 数组；统一发布时必须限制整个客户端配置的环境 |
| `teamspeak-svc/src/main/java/com/tsvoice/client/VoicechatPluginImpl.java` | 客户端事件、TeamspeakSvcClient 和 MicRedirector 的入口；不能原样让 dedicated server 的 SVC 实例初始化它 |
| `teamspeak-svc/build.gradle` | core、TS 加密、音频库与 native 已打包；打包不等于服务端必须初始化，需保留明确边界 |
| `voice-capabilities-fabric/.../CapabilityMod.java` | 提供公告 codec、hello receiver；模组 ID 为 `voice-capabilities` |
| `voice-capabilities-fabric/.../CapabilityConfig.java` | 读取 `config/voice-capabilities.json`；具备候选、TTL 等校验，可迁用逻辑 |
| `teamspeak-svc/.../net/`、`connection/CapabilityDiscovery.java` | 以上一轮完成版为准；客户端已接通，M7 只统一两端公共定义和注册所有权 |
| `ts3voice-core/.../capability/CapabilityWire.java` | 已有纯 Java wire；应继续复用，M7 不为未来 TS_MC_CHANNEL 私自改 wire 语义 |

Fabric 官方文档支持按物理环境区分入口和 Mixin 配置；依赖字典按模组 ID/版本表达，不能自行发明“仅客户端 depends”字段。本计划采用公共软依赖元数据加客户端明确校验，而非让服务器被全局 SVC 硬依赖阻止启动。[Fabric 元数据文档](https://docs.fabricmc.net/develop/loader/fabric-mod-json)

主入口与客户端入口要分成不同类；只在一个含客户端引用的大类内部加环境 if，不能据此证明装载安全。[Fabric 入口文档](https://wiki.fabricmc.net/documentation:entrypoint)

SVC 的 Fabric 集成使用 `voicechat` 自定义入口，不能认为它天然是 client-only；该入口需要独立的环境安全适配。SVC 文档通常建议 API 版本硬依赖，本项目因服务端公告可独立运行，须在实际插件创建前保留等价的版本/可用性检查，并进行两端装载验证。[SVC 插件接入文档](https://dev.modrepo.de/minecraft/voicechat/api/getting_started)

这些文档用于确认机制；本轮仍以目标 Loader 和实际 SVC JAR 的运行结果确认具体兼容性，不将在线文档示例版本当作本项目版本。

### 推荐方案与替代方案

| 方案 | 取舍 | 决定 |
|---|---|---|
| 单 JAR、共同初始化入口、客户端薄入口、可选 SVC 插件门面 | 改动集中，管理员安装简单；必须做好延迟装载和依赖校验 | **本轮首选实施** |
| 同一下载产物内嵌额外 Fabric 子模组 | 可获得更细元数据隔离，但增加 ID、入口顺序和嵌套依赖复杂度 | 仅首选方案有明确阻碍时比较，不先引入 |
| 多个需要用户手动安装的本项目模组 | 维护上可拆分，但违背统一安装目标 | 不得自行采用；需负责人确认的专门例外决策 |

内部继续使用 core 与其他源码模块不受限制。无需把声称“分层”的包结构一次性全部建立出来。

## 3. 具体入口、配置与生命周期设计

### 3.1 入口职责

下面是拟实施位置；若上一轮已有等价类，应修改/复用并在交付中记录映射，不能保留两套注册和运行时。

| 文件（相对 `teamspeak-svc/src/main/java/`） | 职责 |
|---|---|
| `com/tsvoice/TeamspeakSvcMod.java`（新增） | main 入口；只做公共 payload 注册及服务器生命周期监听，不直接引用 SVC、client、OpenAL、WebRTC |
| `com/tsvoice/bootstrap/ClientEntrypoint.java`（新增） | client 薄入口；先检查客户端必需的 SVC/API 及版本，再进入现有 TeamspeakSvcClient；本类不在字段/签名中引用 SVC 类型 |
| `com/tsvoice/bootstrap/RuntimeDependencies.java`（新增） | 基于 Loader 元数据检查环境/依赖，输出明确原因；不通过加载 SVC 类来探测它是否存在 |
| `com/tsvoice/compat/svc/VoicechatPluginEntrypoint.java`（新增） | 实现公共 VoicechatPlugin API 的门面；只有 SVC 主动消费自定义入口时才实例化；客户端延迟委托现有客户端插件，服务端 M7 不注册客户端事件 |
| `com/tsvoice/common/net/CapabilitiesPayloads.java`（迁用/新增） | 两端共享 payload ID 和 codec，无 ClientPlayNetworking/ServerPlayNetworking 注册副作用 |
| `com/tsvoice/common/net/ProtocolRegistration.java`（新增或复用） | 同一 JVM 中一次注册 codec，调用点仅 main；移除原客户端/新服务端重复注册 |
| `com/tsvoice/server/ServerRuntime.java`（新增） | 每个 MinecraftServer 一份，管理公告状态、连接级限流、配置和关闭；不持客户端全局状态 |
| `com/tsvoice/server/ServerConfig.java`（迁用/新增） | 配置读取、校验、原子保存与旧公告配置迁移；不依赖客户端 ConfigFileUtil |
| `com/tsvoice/server/CapabilityAnnouncer.java`（迁用/新增） | 从当前运行时和当前连接处理 hello、构造公告；不主动猜测/扫描 TS 地址 |
| `com/tsvoice/server/ServerCommands.java`（新增） | 本轮最小管理诊断和配置重载；控制台/有权限管理员可用 |
| `com/tsvoice/bootstrap/ClientMixinGuard.java`（必要时新增） | 中性 Mixin 配置插件；缺 SVC 时不解析依赖 SVC 的客户端 Mixin，配合 client 入口输出可读错误；插件自身不得引用目标客户端/SVC 类 |

`fabric.mod.json` 拟改为 `environment: "*"`，main 指向 TeamspeakSvcMod，client 指向 ClientEntrypoint，voicechat 指向门面；保留可选 ModMenu 入口。将整个现有客户端 Mixin 配置标成 client 环境，并把客户端 Accessor 一并限定，不仅移动两个名字而漏掉后续新增 Mixin。

公共元数据保留 Fabric/MC/Java 基础依赖，将 SVC 相关必需性移至客户端明确校验；不能用仅 `suggests` 而不校验的方式悄悄放行缺 SVC 的客户端。Mixins 可能在客户端入口之前处理，因此“晚到 client 初始化才报错”不够：缺依赖时必须避免先出现目标类装载异常。不要打包 SVC 内部类/完整 SVC mod 来填补这个问题。

SVC 门面本身可以依赖 SVC 公共 API，但 common/main/server 不能主动引用它。实例化客户端委托必须延迟到物理客户端且通过依赖检查之后；不要将现有 VoicechatPluginImpl 作为公共静态字段初始化。在 dedicated server 有 SVC 时，门面 M7 只返回稳定插件 ID 并保持无客户端事件，不伪造后续媒体功能。客户端与单人集成服务器共处一个 JVM 时，门面仍保留客户端事件，未来服务器事件按实际事件上下文扩展。

### 3.2 配置：只暴露已经实现的能力

使用项目已有 JSON/Gson，不因规划示例 YAML 新增配置依赖。新文件为 `config/teamspeak-svc/server.json`，客户端现有书签/凭据/全局配置保持独立。

```json
{
  "schemaVersion": 1,
  "integratedServerEnabled": false,
  "capabilities": {
    "enabled": false,
    "serviceInstanceId": "<首次创建时生成并持久保存的 UUID>",
    "ttlSeconds": 30,
    "targets": []
  }
}
```

上例 UUID 是说明占位，实际文件不能写此字面量。全新安装默认关闭公告、目标为空，管理员配置后开启；V3 的 `enabled: true` 是建议示例，不代表要求自动推荐示例地址。`targets` 保留上一轮 endpoint/协议描述；M7 不发明旧客户端无法理解的新传输类型。

`targets` 单项至少沿用现有 `kind/host/port` 三字段，例如管理员实际填写的 `{ "kind": "ts_endpoint", "host": "实际TS主机", "port": 9987 }`；旧 `candidates` 映射到新 `targets`，完成版增加的已支持字段必须原义保留。TS endpoint 端口校验为 1–65535，host/候选/TTL 继续满足 core wire 上限。只有完成版已支持、已配置的候选可发出；不通过手写 kind 增加未实现的本地中继/SVC TCP/音乐能力。

迁移规则：新文件已存在则优先使用且不覆盖；仅新文件不存在、旧 `voice-capabilities.json` 存在时，备份并校验后迁移。保留管理员真实的 endpoint、TTL 和明确 serviceInstanceId；旧默认 `voice-capabilities` 标识改为新生成持久 ID 并提示可能需要重新授权。精确匹配旧模板的 `voice.example.org:9987` 视为未配置示例，保持关闭。不得删除旧文件；损坏输入不覆盖用户原内容，公告停止并显示配置错误，客户端直连和其他独立功能不受影响。

旧配置若确为有效非模板配置且没有禁用标记，迁移后 `capabilities.enabled=true`，保持其原有公告意图；旧明确关闭则保持关闭。迁移和保存都必须记录来源但不记录秘密，不能因为改文件结构就静默启用一个以前未配置的目标。

未来 `ts_relay/svc_tcp/music/media_bridge` 在 M7 **不写成可启用的默认配置项**。若用户手动加入这些字段，本轮给出“该版本未实现”的诊断，不创建虚假 ACTIVE 状态或公告；未来对应实现完成时升级 schema 并迁移。解析未知字段时不静默抹掉用户输入。

状态必须区分 `DISABLED / INVALID_CONFIG / ACTIVE / FAILED`；仅配置有效、服务实际启动且 enabled 的公告器回复。TS endpoint 作为管理员推荐目标，不宣称已通过 TS 握手；后续本机中继的可用性则必须由真实已启动的服务提供，不能只看配置布尔值。

### 3.3 运行时和注册边界

建议的最小接口（新设计，非现有 API）：

```java
final class ServerRuntime implements AutoCloseable {
    static ServerRuntime start(MinecraftServer server, ServerConfig config);
    CapabilityStatus status();
    ReloadResult reload(ServerConfig next);
    void close();  // 幂等，只释放自己拥有的连接记录/任务
}
record CapabilityStatus(State state, String reason, int configuredTargets) {}
enum State { DISABLED, INVALID_CONFIG, ACTIVE, FAILED }
record ReloadResult(boolean applied, String reason) {}
```

common 入口注册 ServerLifecycleEvents 的开始/停止事件，按实际 MinecraftServer 实例创建/关闭 runtime。既覆盖 dedicated，也覆盖集成服务器；集成服务器默认不启用服务端增强。不要只在 dedicated `server` 入口建立所有功能，导致单人世界生命周期成为隐式例外。

codec 和全局 receiver 只注册一次；receiver 根据当前 player 所属 server 获取 runtime，不捕获首个世界的旧 server。退出世界后再进入世界复用注册定义，但新建运行时。MC 连接退出时清理对应限流记录，使用连接身份而非仅 player UUID 区分重连。

M7 hello 不执行 DNS、TS 连接或文件读取。配置在服务器启动/管理员重载时读取、验证；重载先准备不可变快照，再提交。密码、私钥和完整凭据不进公告或日志。

保持上一轮 16KiB 外层帧上限、8 候选、60 秒 TTL 上限及客户端 nonce/context/授权语义。同一逻辑候选不重复广告；S2C/C2S ID 不变。单连接 hello 限流建议 2 次/秒、突发 4 次，时钟可注入；限流只影响公告响应，不踢出普通玩家或断开已建立的 TS。与上轮客户端刷新频率联测后才能固定该初值。

旧 `voice-capabilities` 与新包同时安装会竞争相同 payload，优先用精确的 Loader 冲突声明阻止这种无效安装并在迁移文档说明。不要依赖“谁先初始化谁成功”，不要捕获重复注册异常后当成功，也不要冒充提供旧模组 ID 来隐藏仍在运行的旧入口。旧 `tsbridge` 不能仅因名字相近一律加入冲突名单；若发现确实相交的功能，按功能显式处理，保持原计划兼容边界。

## 4. 执行步骤

每项完成后运行对应自动化和必要装载测试、独立提交。测试应覆盖实际入口/codec/runtime，不复制实现逻辑，也不以“类存在”作为完成条件。

### T14.1 固定本轮边界及公共注册所有者

**文件：**公共 CapabilitiesPayloads、ProtocolRegistration、原客户端和旧公告 codec/注册调用点；`review/m7-unified-release/STATUS.md`（新增）。

- [ ] 记录上一轮最终提交及交付报告位置；仅核对本轮会调整的实际文件/类名，不重新开 T00。
- [ ] 将完成版 payload 类型/codec 迁入共同包，保持 wire/ID、限制和错误行为。旧包若需要过渡门面，必须委托同一类型/注册所有者，不能再声明第二套同 ID 的 TYPE。
- [ ] 新增 `PayloadOwnershipTest`：客户端初始化+集成服务器启动+世界重开不重复注册；客户端/服务端 golden frame 互通；超大载荷在分配前拒绝。
- [ ] common 注册与 client receiver 职责分离；确认注册顺序不会依赖某个 client 初始化先发生。

**验收：**上一轮能力协商测试继续通过；同 JVM 中重开世界不抛重复注册，旧 ID 不变。**回滚：**单提交恢复注册位置，不能同时保留两个活动所有者。

### T14.2 实施通用元数据与依赖检查

**文件：**fabric.mod.json、teamspeak-svc.mixins.json、ClientEntrypoint、RuntimeDependencies、必要的 ClientMixinGuard。

- [ ] 建立中性 main/client 薄入口，调整 environment 和全局 SVC 依赖；保留原客户端内部初始化顺序和功能。
- [ ] 整体限定客户端 Mixin 环境，包含声音 Accessor 和上轮新增的前端 Mixin；不通过降低 required/取消所有 Mixin 来伪造服务端可启动。
- [ ] 缺 SVC/不兼容 API 的客户端给出明确依赖错误；dedicated 无 SVC 正常进入公告运行时。缺依赖检查不得触发 SVC/客户端目标类解析。
- [ ] `EnvironmentLoadingTest` 静态扫描入口依赖，辅以真实 dedicated 启动。测试客户端无 SVC 的预期失败原因，禁止把 NoClassDefFoundError 当合格错误提示。

**验收：**同一个候选 JAR 在 server 无 SVC 时启动，client 有 SVC 时仍能进入游戏；client 缺 SVC 清晰失败。**回滚：**回退元数据和 bootstrap 提交，保留实验日志；不能把 SVC 改回全局必装后宣称达成。

### T14.3 环境安全的 SVC 插件门面

**文件：**VoicechatPluginEntrypoint、现有 VoicechatPluginImpl、元数据 voicechat 入口。

- [ ] 在 SVC 实际目标版本确认门面装载时机；client 延迟创建现有客户端插件，server 无客户端委托，不主动启动音频。
- [ ] 无 SVC 时公共入口、服务端配置、诊断都不触碰插件 API 类型；有 SVC 时核对 API 版本，避免延迟失败进入公共服务器启动过程。
- [ ] 测试 dedicated+SVC 不注册 ClientSoundEvent/MicRedirector 等客户端事件；client+SVC 事件只注册一次，独立 TS 前端仍完整工作。
- [ ] 真实 client 与集成服务器运行中验证门面没有因为物理/逻辑环境判断错误而关闭客户端语音。

**验收：**专用服务器有无 SVC 两条路径均可装载，客户端插件效果与上一轮一致。**回滚：**回退门面/元数据成对提交，不能留悬空 entrypoint。

### T14.4 服务端配置及公告迁移

**文件：**ServerConfig、CapabilityAnnouncer、ServerRuntime；旧 CapabilityConfig/CapabilityMod 作为参考，不删除。

- [ ] 实施 3.2 的 JSON schema、开关、默认空目标和原子迁移；服务端文件工具放在 server/common，不引用客户端工具类。
- [ ] 移植上一轮最终公告实现的有效校验、回复逻辑及限流，接入当前 server runtime；不将早期有缺口的版本覆盖到完成版上。
- [ ] 新增 `ServerConfigMigrationTest`：全新安装、旧有效文件、旧示例、损坏输入、新旧并存、二次启动、迁移中断；新配置优先，旧文件保留。
- [ ] 新增 `AnnouncerIntegrationTest`：enabled/disabled/invalid、正确 nonce、未知版本、无候选、超大 payload、限流及客户端不支持接收通道；无效请求不触发出站 TS 探测。

**验收：**只安装统一扩展即可得到合法公告；关闭时无公告服务副作用；旧授权因服务 ID 变化可清晰重新确认，本地 TS 目标不受影响。**回滚：**使用备份恢复配置，停公告而不影响直接 TS；不自动重新装旧公告 mod。

### T14.5 生命周期、状态和管理员诊断

**文件：**ServerRuntime、ServerCommands、主入口生命周期注册；测试运行时。

- [ ] 每个 MinecraftServer 独立 runtime；开始/关闭幂等；连接级记录在退出/服务器停止时清理。
- [ ] 新增管理命令 `/tsvoice-server status`、`/tsvoice-server reload`：控制台或目标 MC 版本对应的管理员权限才可执行；精确 API 以当前版本为准，不能为了复用旧桥额外强依赖其权限模块。
- [ ] status 只报告本轮真实存在的功能、状态、原因、配置路径及目标数，不显示“relay ready/music ready”等占位状态；无秘密输出。
- [ ] reload 失败时停止不再可信的公告并给出错误，保留文件；已连接客户端仍按既有 TTL/选择策略工作，不在服务器端强迫它们切网络。
- [ ] 新增 `ServerRuntimeLifecycleTest`：单人世界 A→菜单→世界 B、dedicated 重启、连续 reload、非法配置恢复、普通玩家滥用 hello；旧 runtime 不向新连接回复。

**验收：**旧世界没有遗留任务/记录，无重复接收器，配置错误不破坏其他独立语音功能。**回滚：**停用服务端公告运行时；公共客户端功能维持上一轮行为。

### T14.6 打包、旧组件冲突及安装说明

**文件：**build.gradle/settings.gradle（仅确有需要时）、fabric.mod.json、README、THIRD-PARTY-NOTICES；`review/m7-unified-release/RunArtifactChecks.ps1`（新增）。

- [ ] 产物只有一个需要用户安装的本项目 JAR；core/既有库可按当前机制嵌套，不包含另一个独立公告 mod 的可执行入口。
- [ ] 不把完整 SVC JAR、svc-internal-classes、review 测试替身/配置、TS 私钥或样例秘密打进发布包。保留来源与许可证，迁用代码的声明不删除。
- [ ] 检查 nested JAR 去重和 metadata，不因统一发布改变音频库版本。dedicated 启动无 OpenAL/WebRTC/NVIDIA 初始化；允许文件被打包，不允许客户端资源被服务器误实例化。
- [ ] 精确处理 `voice-capabilities` 重复安装的 Loader 诊断；保留旧源码供参考/回归，不把它继续写进新部署步骤。
- [ ] README 按客户端、服务端仅公告、服务端 SVC+本扩展、无本扩展的 MC 服务端列安装表，清除过时的“客户端必须等 SVC 服务端/配套 tsbridge”描述。对旧隧道保留历史兼容说明，但不写成未来默认方案。

**验收：**ArtifactChecks 验证入口、依赖、嵌套包和禁止内容；单产物可复现，迁移说明可执行。**回滚：**发布候选回退至上一轮，不修改用户旧工程；统一包仍未通过时不得宣称最终发布完成。

### T14.7 同一产物安装与功能验收

- [ ] 执行第 5 节矩阵，记录同一候选 JAR 的 SHA-256、MC/Fabric/SVC 版本、实际 mods 清单、启动和停止日志。
- [ ] 运行上一轮完整回归总入口及本轮新增测试；本轮引起的客户端行为变化必须修复。
- [ ] 使用该产物验证无 SVC 服务端的真实 TS 双向音频，以及原生 SVC 和三个操作，证明打包与入口变化未破坏上一轮成果。这是修改后的回归，不是重新规划上一轮开发。
- [ ] 确认 dedicated 无 SVC/有 SVC，客户端有 SVC，集成服务器多次开关均通过；缺真实环境则记录未验证，不将 M7 宣布完成。

**验收：**同一产物通过必测矩阵，功能状态/原始日志可核对。失败仅按本轮具体回归定位，禁止扩大成全面重构。

### T14.8 交付与下一轮接点

- [ ] 新增 `review/m7-unified-release/DELIVERY.md`，包括提交、配置迁移、安装步骤、测试日志、矩阵、已知限制和回滚方式。
- [ ] 新增 `docs/deployment/server-features.md`，说明唯一发布包和真实已实现功能；未来条目显式标“未实现”，不提供看似能启用的样例。
- [ ] 写明后续 TS_MC_CHANNEL 接入点：共同 payload 注册、per-server runtime、per-MC-connection 所有权及配置来源。只记录边界，不创建未实现 RelayService/MusicService 线程或对外 capability。
- [ ] 更新任务状态，仅 T14 子任务；不在交付时自动开始 M9/P6、M8/P5 或音乐开发。

**完成条件：**管理员只安装本扩展及正常 Fabric 依赖即可使用公告；不安装本扩展的 MC 服务器不影响客户端直连 TS。若单 JAR 方案受阻，必须提交最小复现、已尝试隔离手段和替代方案，由负责人决定，不擅自要求多个本项目模组。

## 5. 测试与产物验收

### 自动化入口

在实现阶段新增 `review/m7-unified-release/RunM7.ps1`，包含上述实际生产类测试与 ArtifactChecks，并接入现有总入口。不得只用 development classpath 启动证明发布 JAR 正常，必须另做真实安装验证。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\review\RunAll.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\review\m7-unified-release\RunM7.ps1
& .\teamspeak-svc\gradlew.bat -p .\ts3voice-core test --no-daemon
& .\teamspeak-svc\gradlew.bat -p .\teamspeak-svc build --no-daemon
```

这些是执行阶段命令，不是编写本文时的通过记录。运行时产物路径、JDK 与实际完成版 build 任务以仓库为准。

| 编号 | 环境/操作 | 验收结果 |
|---|---|---|
| M7-E01 | dedicated：Fabric+本扩展，无 SVC/旧公告/Bridge | 启动、关闭正常，配置服务可用，无客户端/native 初始化 |
| M7-E02 | 同上，开启合法 TS endpoint 公告；扩展客户端加入 | 完成 hello/nonce/TTL/授权，客户端按策略连接；只装一个本项目 JAR |
| M7-E03 | dedicated：SVC+本扩展，公告关闭/开启各一次 | 原生 SVC 正常，客户端插件未被服务端执行；公告状态独立 |
| M7-E04 | 客户端：SVC+同一哈希 JAR；加入无 SVC/本扩展的 MC 服 | 手动 TS 真实双向通话与界面正常 |
| M7-E05 | 客户端加入仅 SVC 的 MC 服，分别无 TS 目标/有本地自动 TS | 无公告的旧选择规则保持；手动 TS 不受服务端是否安装本扩展限制 |
| M7-E06 | 客户端缺 SVC，或 API 不兼容 | 可读依赖诊断；不是客户端类/Mixin随机崩溃；不伪装可用 |
| M7-E07 | 单人世界 A→菜单→世界 B；集成服务默认禁用/明确启用 | 客户端音频不被逻辑服务端关闭；无重复注册、旧世界回复或泄漏 |
| M7-E08 | 旧有效/旧模板/损坏/新旧并存配置，重载与重启 | 按迁移规则，无配置覆盖、无示例域名自动连接，状态可解释 |
| M7-E09 | 同时安装旧 voice-capabilities | 在正常 receiver 注册前有明确冲突诊断，不靠异常吞噬；迁移说明正确 |
| M7-E10 | 连续 hello、超大帧、未知版本、普通无扩展客户端加入 | 有界处理，不探测任意地址，不影响正常玩家网络或已有 TS 通话 |
| M7-E11 | TS 中断、三个操作、频道管理员移动、个人音量、设备切换 | 上一轮行为保持；M7 未引入麦克风归属/前端回归 |
| M7-E12 | 检查最终 JAR 内容、哈希与安装清单 | 单一外部分发产物；无 SVC 内部类/测试替身/凭据，无额外必装公告/Bridge |

### Review Focus

1. 全局 SVC 硬依赖改掉后，缺 SVC 客户端在入口前被 Mixin 弄崩：T14.2/M7-E06。
2. 自定义 voicechat 入口在服务器创建客户端类：T14.3/M7-E03。
3. 单人世界复用 JVM 导致 codec/receiver 重复注册或旧 server 捕获：T14.1/T14.5/M7-E07。
4. 自动迁移旧示例目标、服务 ID 变化或坏配置覆盖有效新配置：T14.4/M7-E08。
5. 打包含 client native 库导致服务端静态初始化、或漏打共享 core：T14.6/M7-E01/E12。

## 6. 失败处理与报告格式

普通配置/可选公告失败应隔离，不影响客户端基础直连。安装冲突、客户端缺必需依赖需给明确诊断；这不等于吞掉所有启动错误继续伪运行。不能用全局 catch(Throwable)、禁掉 Mixin 或恢复服务器强制 SVC 依赖来获得表面 PASS。

每个 T14.x 报告：目标、实际变更文件、生产入口、提交、自动化命令/原始输出、真实矩阵行、未验证项、配置影响和回滚。四列状态分别记录代码、自动化、集成、真实环境。

本轮成功交付的是**统一发布与公告能力**。TS_MC_CHANNEL、SVC_MC_CHANNEL、Music 和 P7 都不因预留入口而算完成；后续轮次按单独任务书接入。
