# 工程收尾轮交付报告（N0–N7）

日期：2026-10-29。范围：`b732169` → 终点 HEAD（逐任务提交，见下表）。
本报告区分**已实现 / 已测试 / 已构建 / 待真机验收 / 仍存在的问题**五类。

## 提交范围（每任务独立提交，可单独回滚）

| 提交 | 任务 | 内容 |
|---|---|---|
| `58fbbc5` | N0 | RunAll v2（core 先行、upload-confirmation/gui-integration 纳入、每组独立日志、失败非零退出）；STATUS/DELIVERY 记录纠错；HttpClientSmoke 环境分类 |
| `1af74dc` | N1 | D04+D05：确认路由五态生命周期、clientmove 进命令 tracker、全局单调写出代际、策略拒绝原子撤销、acceptCurrentChannel 入口；RouteMoveLifecycleTest + RouteEpochRevocationTest |
| `1b175a7` | N2 | D01+D02：自有 AudioChannel 独立播放、失效租约接管顺序、SVC 建立/断开交接、所有权链统一；FrontendLeaseTest 39 项；ABI.md 修订 |
| `1702411` | N3 | D03：ClientVoicechatMixin（processSoundPacket HEAD 门控）、OFF 清空原生播放通道、PAUSED 不压制；OffGateTest O1–O6 |
| `13b4f4b` | N4 | T09：书签 schema v3 + v2 迁移备份、书签列表/编辑屏、VoiceStatusModel、ConfigScreen 三操作与受众行、删除活动书签保护；GuiFlowTest G 段 |
| `554a145` | N5 | T10：TsChannelsScreen（cid 加入、锁定/跟随、stay-here、参与者 UUID 用户列表、创建≠移动）、路由策略入口；GuiFlowTest C 段 |
| `f715369` | N6 | T12：CapabilitiesPayloads（同 ID/有界）、JOIN hello（canSend 门控）、公告消费+信任授权、候选进选择链、FailureKind 分类与终止性重试、退避 jitter；GuiFlowTest D 段 |
| `3976267` | N7 | harness 再生桩与脚本一致化 |

回滚影响：任一提交可单独 revert 而不影响其他任务的功能面；书签 v2→v3 迁移
只新增默认字段并保留 `bookmarks.v2-backup.json`，revert 后旧文件仍可读；
无任何提交删除用户书签、凭据或 TS 身份。

## D01–D05 关闭证据

| 缺陷 | 关闭方式 | 自动化证据 |
|---|---|---|
| D01 无 SVC 会话时 TS 下行被静默丢弃 | 确证 2.6.25 字节码早退门为事实（非未知风险）；TS 下行改为适配器直接拥有每条流的 AudioChannel（InitializationData 传 null，字节码存储后从不读取）；诊断分级 attempts/enqueued/resourceFailures | FrontendLeaseTest F5/F5b（原生门从未被咨询、无伪造 secret、资源失败不伪报）；ABI.md 修订 |
| D02 死亡租约占位、恢复循环抖动 | acquire 在创建新资源前释放失效租约；SVC 断开钩子接入 manager；建立/周期双路借用交接 | FrontendLeaseTest R1（4 轮恢复唯一存活采集源）+ R2；RouteLifecycleTest C/D 租约断言 |
| D03 OFF 不覆盖原生 SVC 下行 | processSoundPacket HEAD mixin 意图直读门控；disableAllVoice 同步清空原生通道队列；用户设置零触碰 | OffGateTest O1–O6；mixin 目标/签名 javap 核对（运行期应用属真机验收） |
| D04 锁定移动后旧队列可写新频道 | confirmChannel 策略拒绝即原子撤销+递增代际；routeEpoch 全局单调跨会话不复位；observeSelf 拒绝路径清队 | RouteEpochRevocationTest R（真实连接 writer 全链）；RouteMoveLifecycleTest M1 |
| D05 拒绝/歧义未形成持续阻断 | 五态 MovePhase；clientmove 经 return_code 关联、结果绑定请求实例；拒绝/超时/歧义进入 FAILED 持续阻断直至用户明确操作或新请求确认 | RouteMoveLifecycleTest M2–M8；GuiFlowTest C2/C3 |

## T09/T10/T12 接线位置

- T09：`TsBookmarkStore`（schema v3+迁移）、`TsBookmarksScreen`/`TsBookmarkEditScreen`、
  `VoiceStatusModel`、`ConfigScreen`（受众行+三操作+书签入口）、`TsVoiceManager.onBookmarkDeleted`。
- T10：`TsChannelsScreen`、`TsChannelRoute.setChannelPolicy/lockCurrentChannel`、
  `Ts3VoiceClient.clientsSnapshot`。
- T12：`net/CapabilitiesPayloads`、`TeamspeakSvcClient`（注册+JOIN hello+接收器）、
  `TsVoiceManager`（discovery 链/授权/候选选择/失败分类/jitter）、`SelectionPolicy.backoffMillis`。

## 测试与构建结果（终点 HEAD）

- 干净副本（git archive）`review/RunAll.ps1`：**PASS —— core test + 22 组 harness 全绿**
  （[n7-clean-copy-runall.log](evidence/n7-clean-copy-runall.log)，进程退出码 0）。
- `gradlew build`（teamspeak-svc，含 ts3voice-core）：**BUILD SUCCESSFUL**
  （[n7-build.log](evidence/n7-build.log)）；候选产物
  `teamspeak-svc/build/libs/teamspeak-svc-0.9.8+26.3.jar`
  SHA-256 `5FA9DF880E53C35CFFE04D12996A58CC9DC00A01DE677AA1E6A5B6FA4C1C6447`。
  后续 M7 两端验证使用同一哈希候选 JAR。
- 每组 harness 的完整日志在运行期 `$env:TEMP\tsvoice-ci-logs\`；本轮关键证据归档于本目录 evidence/。

## 已实现但未验证（明确列出）

1. 真机双向音频（TS 直连与原生 SVC）——E01–E15 全部维持"用户主动暂缓"。
2. Mixin 在真实 MC 类加载器中的应用效果（N3 门控运行期行为）。
3. 能力公告与真实 announcer 服务端的网络往返（hello/公告在线交互）。
4. GUI 真实渲染/点击/快捷键（E07）。
5. 独立 AudioChannel 的真实听感、个人音量/设备切换一致性（E11）。
6. 真实 TS 服务器的错误语义映射核对（当前按消息文本分类，协议错误 id 未逐项实测）。

## 仍存在的问题 / 后续工作

1. 公告候选的用户显式授权界面未交付（当前按信标已信任 endpoint 自动授权；
   未信任候选保持未授权并记录日志）——随 M7 界面工作补齐。
2. 频道页列表超 8 条无分页滚动（当前仅前 8 条 + 滚动仅书签页实现）。
3. M7 统一发布（同 JAR 可选服务端、公告并入、装载隔离）为下一阶段，
   按总任务书继续；同一候选 JAR 哈希如上。
