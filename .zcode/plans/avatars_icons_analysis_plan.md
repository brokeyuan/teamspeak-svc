# 双向头像/图标显示可行性分析（teamspeak-svc）

> 分析文档（未实施）。三个诉求：A. SVC 群组界面显示跨服玩家真实皮肤；B. TS 里 MC 玩家显示头像；C. MC 里 TS 用户显示指定皮肤。

## Repository Research（决定方案的关键事实）

### 事实 1：SVC 皮肤渲染机制 —— 决定 A/C 的方案形态
`simple-voice-chat-26.2/common-client/src/main/java/de/maxhenkel/voicechat/gui/GameProfileUtils.java`：
```java
public static PlayerSkin getSkin(UUID uuid) {
    PlayerInfo playerInfo = connection.getPlayerInfo(uuid);   // 只查本服在线玩家表
    if (playerInfo == null) {
        return DefaultPlayerSkin.get(uuid);                    // 查不到 → 原版默认头
    }
    return playerInfo.getSkin();
}
```
- **SVC 从不向 Mojang 按 UUID 拉皮肤**——只查本地连接的 PlayerInfo（=本服 tab 列表）。
- 因此「把 ghost UUID 设成真实 MC UUID」**不够**：跨服玩家不在本服 PlayerInfo 里，照样默认头。
- `GroupEntry`/`PlayerVolumeEntry`/说话高亮等 UI 全部经此工具类取皮肤 → **mixin 这一个静态方法即可全场景生效**（A/C 共用基建）。
- MC 26.3 未混淆，mixin 目标稳定；SVC 是我们可控的邻居 mod（2.6.25 已实测结构）。

### 事实 2：TS3 头像机制 —— 决定 B 的方案形态
本地官方文档 `D:/Service/teamspeak3-server_win64/serverquerydocs/ftinitupload.txt`：
- TS3 客户端头像 = 传到服务器文件仓库（cid=0，路径 `/avatar_<名字>.png`）+ `clientupdate client_flag_avatar=<文件名>`。
- 上传走 **file transfer 子协议**：`ftinitupload`（voice 命令通道）→ 返回 `ftkey` + `port=30033` → TCP 直连发裸数据。
- 需要 `i_ft_file_upload_power` 权限（**当前 Guest 组未授予，需服主补**，与之前补建频道权限同类操作）。
- 我们已有完整 voice 命令通道（ts3voice 包），FT 是其上的新小协议（TSLib 有权威参照可移植）。

### 事实 3：跨服身份分发通道已存在
- TS 客户端可用 `clientupdate client_description=...` 附带自定义元数据（频道内他人经 notifyclientupdated 收到）。
- 目前 ghost UUID = `nameUUIDFromBytes("ts-ghost-"+tsUid)` 派生（`TsAudioInjector.ghostUuidFor`）——纯派生、无皮肤。

## 三个方向的可行性与方案

### A. 跨服玩家显示真实皮肤 ✅ 可行（中高难度，依赖正版账号）
- **身份分发**：客户端 mod 连上 TS 后 `clientupdate client_description=mc:<mcUuid>`；对端 mod 解析频道内客户端的 description → 得到「TS 客户端 ↔ 真实 MC UUID」映射（无外网依赖、离线服也传 UUID，但**皮肤仍需正版账号**）。
- **皮肤来源**：正版 UUID → Mojang session API 拉皮肤贴图（`sessionserver` textures，一次拉取本地缓存）；**离线/盗版玩家无皮肤可拉 → 显示默认头（诚实限制）**。
- **注入渲染**：mixin `GameProfileUtils.getSkin`：命中我们的映射表 → 返回按 UUID 预取构造的 `PlayerSkin`；未命中走原逻辑。
- 需要实现的构件：mixin 1 个、Mojang 皮肤拉取+缓存、description 收发解析、PlayerSkin 构造（26.3 `SkinManager` 注册外链贴图——实现时需验证具体 API 形状）。

### B. TS 里 MC 玩家显示头像 ✅ 可行（中高难度，需要服务器权限）
- **素材**：渲染自己的皮肤脸（皮肤贴图 8×8 脸 + 帽子层裁剪 → 放大到 64×64+ PNG；皮肤从本客户端 `PlayerInfo.getSkin()` 拿，无外网依赖）。
- **上传**：实现 TS3 FT 上传小协议（ftinitupload → TCP 30033；TSLib 参照移植，约百行级）→ `/avatar_<uid前8>.png` → `clientupdate client_flag_avatar=...`。
- **前置**：服主给 Guest 组加 `i_ft_file_upload_power`（一条 Query 命令，我们可顺手做）。
- 效果：TS 用户在 TS 客户端里看到每个 MC 玩家的 MC 脸头像（可单独右键查看大图）。

### C. MC 里 TS 用户显示指定皮肤 ✅ 可行（与 A 共享 90% 基建，最简单）
- 同一个 `getSkin` mixin：对「我们派生的 TS ghost UUID 集合」（`ClientStateReflector.activeGhosts` 已在维护）返回**指定皮肤**。
- 指定皮肤两种来源：① mod 内置一张 TS 品牌皮肤贴图（开箱即用，推荐默认）；② 配置里填一个正版账号 UUID，拉其皮肤（社区自选"TS 专属皮"）。
- 限制：所有 TS 用户共用同一张指定皮肤（TS 侧无法提供 MC 皮肤；除非未来 TS 客户端插件生态）。区分度靠 `[TS] 昵称` 文本。

## 组合实施建议（如果做）
一次实施 C+A（同一个 mixin + 皮肤管线）→ B 独立第二阶段（FT 协议）。顺序理由：C/A 不需要任何服务器侧权限；B 需要服主开 FT 权限且协议开发量独立。

## 工作量与风险评估
| 方向 | 新构件 | 难度 | 主要风险 |
|---|---|---|---|
| C | mixin×1 + 内置贴图 + 配置 | 低 | SVC 版本升级改 utils 类名（可控，反射探测兜底） |
| A | C 全部 + description 收发 + Mojang 拉取缓存 | 中高 | Mojang API 限流（缓存+懒加载缓解）；离线玩家无皮肤（诚实降级默认头）；SkinManager 构造 PlayerSkin 的 26.3 具体 API 待验证 |
| B | FT 上传协议 + 皮肤脸 PNG 渲染 + clientupdate | 中高 | 服务器 FT 权限未开会失败（检测后聊天栏提示一次）；FT 协议细节与 TSLib 差异 |

## 验证方式（届时）
- C/A：双客户端同频道，SVC 群组界面截图对比（默认头 vs 真皮/指定皮）
- B：TS 客户端查看该 MC 玩家头像；ServerQuery `clientdbinfo`/`clientinfo` 的 client_flag_avatar 字段

## 结论
三个方向**技术上全部可行**，无硬阻塞。优先级建议：**C（快速见效）→ A（核心体验）→ B（锦上添花但需服主配合）**。
