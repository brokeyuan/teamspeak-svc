# teamspeak-svc

保留 Simple Voice Chat 的游戏内操作，通过 TeamSpeak 3 与原生 TS 用户通话。客户端和可选服务端使用同一个 JAR。

## 当前状态

开发候选，尚未通过客户端验收。2026-10-30 独立复核发现音乐 Bot 发送许可、TS MC 中继控制包和一项回归编译缺陷；**不应将当前候选作为正式发布版**。

- [最新复核与修复顺序](review/publish-audit-2026-10-30/REVIEW.md)
- [候选依赖和配置](review/client-acceptance-next/MANIFEST.md)
- [客户端验收步骤](review/client-acceptance-next/CLIENT-ACCEPTANCE.md)与[结果表](review/client-acceptance-next/RESULTS.md)

源码功能终点 `80dd40f`，原交接提交 `5ec6f2b`。候选位于 `dist/client-acceptance/80dd40f/teamspeak-svc-0.9.8+26.3.jar`，SHA-256：

```text
8585708afc5e3740309411b28b46138fb0886b5e08552fe99e20f5ad6c4d6a1c
```

## 使用方式

客户端安装 Fabric、Simple Voice Chat 和本扩展，继续使用 SVC 的麦克风、PTT、语音激活和音量操作，并通过扩展入口管理 TS 书签和频道。当前目标版本为 Minecraft 26.3、Java 25，具体依赖见候选清单。

TS UDP 直连不要求 MC 服务端安装本扩展或 SVC。管理员可选安装同一 JAR，启用能力公告或 MC 网络通道适配；这些能力不要求安装旧 Bridge 或独立公告模组。

| 模式 | 路径 | 当前边界 |
|---|---|---|
| A | 客户端 → TS3 UDP | 已接入，真实通话待验收 |
| B | 客户端 → MC 通道 → 服务端 UDP → TS3 | 已接入，频道屏障误挡控制包，修复前不判通过 |
| C | 客户端 → 原生 SVC UDP | 保留原路径，待回归验收 |
| D | 客户端 → MC 通道 → SVC | 自有适配已接入，真实网络及 SVC-Extra 双端互操作待验证 |

B、D 借助 MC 连接承载数据，不是把原生 TS/SVC UDP Socket 直接改成 TCP。MC 通道需要服务端支持相应能力。模式 D 还需要服务端 SVC。

## 连接和收听规则

- 手动选择的 TS 目标优先；没有公告时，明确启用的本地或全局 TS 自动目标仍可使用。
- 没有已配置 TS 目标时，SVC 可用则使用 SVC；服务器公告须经过信任流程。
- 麦克风只发送到选中的后端；默认只收听选中的后端。
- 断开 TS 后进入 PAUSED，不自动改为 SVC；切换 SVC、关闭全部语音是独立操作。
- 多个 MC 服务器的玩家连接同一个 TS 频道可跨服通话。普通 SVC 用户不会因此自动与 TS 用户互通。

## 音乐和后续方向

已有独立音乐 Bot、WAV 播放引擎、真实 Opus 编码和命令入口，但当前候选存在发送许可缺陷。音乐界面、本地音量实际接线、Bot MC 中继客户端和频道许可仍未完成，不承诺本版可用。

后续优先修复已确认缺陷并完成客户端验收，再推进独立音乐体验、可选媒体互通和跨平台接入。远程点歌、在线曲库、SVC↔TS 媒体桥接、网页及手机接入均未交付。

## 开发

保留现有多模块目录：`teamspeak-svc/` 为统一扩展，`ts3voice-core/` 为公共协议；`teamspeak&svcbridge/`、`voice-capabilities-fabric/` 和 SVC 参考源码属于历史/参考材料，不是新部署依赖。模块内旧 README 可能描述历史架构，当前状态以本文和最新复核为准。

Java 25 环境下，在 `teamspeak-svc/` 运行 `gradlew.bat build`；仓库根目录运行 `powershell -File review/RunAll.ps1` 执行完整检查。当前已知干净副本的麦克风 harness 编译缺陷见复核报告，不能仅凭旧日志宣称全绿。

本项目使用 GPL-3.0；第三方组件保留各自许可。
