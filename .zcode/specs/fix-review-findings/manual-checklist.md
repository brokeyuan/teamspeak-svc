# teamspeak-svc 0.9.0 人工联调清单

本清单覆盖自动化环境无法验证的实机场景。建议环境：testserver（MC 26.3 + SVC 2.6.25）+ 本地/远程 TS3 服务器 + 至少 2 个安装本 mod 的 MC 客户端 + 1 个原生 TS 客户端。

## 1. 连接生命周期
- [ ] 删除 `config/teamspeak-svc/identity.txt` 后首次启动生成的新身份，能连入设置了“最低客户端安全等级 8”的 TS 服务器（若被拒，说明安全等级位序需修正，见 review R1-F8）。
- [ ] 进入已启用 SVC 的服务器后 10 秒内，TS `clientlist` 出现 `[MC] 玩家名`。
- [ ] 退出世界后该 TS 客户端消失。
- [ ] TS 服务器重启/断网后 60 秒内自动重连。
- [ ] `/tsvoice disconnect` 后修改任意音频设置/触发信标，不会被自动重连；`/tsvoice connect` 可恢复。

## 2. 麦克风改道与注入
- [ ] 按键说话时 TS 频道其他成员可闻；MC 服务端日志无该玩家 MicPacket。
- [ ] TS 用户说话时 MC 客户端可闻，SVC 界面 `[TS] 昵称` 说话指示点亮。
- [ ] 两个 mod 客户端互闻，且不经 MC 服务器转发。
- [ ] whisper 键行为与 SVC 原生一致（v1 仍发往频道）。

## 3. 群组与密码
- [ ] 加入无密码群组 → TS 客户端进入 `<主频道>/<群组名>`。
- [ ] 加入带密码群组 → 子频道带密码创建/进入成功（需 tsbridge 下发群组信息）。
- [ ] 在群组间切换 → 密码信息不丢失，客户端正确移动。
- [ ] 离开群组 → 回主频道。
- [ ] 群组名含空格/斜杠/超长 → 子频道名被安全处理，不影响语音。

## 4. GUI 与配置
- [ ] 打开配置 GUI 后点击“完成”/“连接”，已存密码不被清空；密码框显示为掩码。
- [ ] 信标模式下密码仍可编辑并保存。
- [ ] “音频增强”入口可进入，关闭后返回来源界面。
- [ ] `/tsvoice status` 正常输出（无异常），`/tsvoice say 你好` 与 `/tsvoice 你好` 均发送到 TS 频道。
- [ ] 修改 `duckingLevel`/`aecDelayMs`/`networkToleranceMs`/NVIDIA 路径后 `/tsvoice reload` 生效。

## 5. 音频增强
- [ ] 对比 TS 原生客户端与 MC 内的语音延迟（同频道、同说话人，主观 A/B）；`lowLatencyMode=true` 时应明显低于 SVC 默认 5 包缓冲。
- [ ] 在 SVC 设置中确认 `denoiser` 开启（RNNoise）；说话时底噪/键盘声应被抑制，且不出现明显吞字。
- [ ] 外放场景开启 AEC（`echoCancelEnabled=true`）后回声明显减弱（同步 AEC 生效）。
- [ ] 关闭 AEC、切 WebRTC/NVIDIA 降噪，底噪变化符合预期；NVIDIA 缺失时回退 SVC 原生且有提示。
- [ ] TS 有人说话时游戏音量被压低，停止说话后平滑恢复；玩家手动调音量滑条不被反复拉回。
- [ ] 混响随环境变化（室内/洞穴/水下差异可闻）；`/tsvoice rays` 可视化正常。
- [ ] 同服 mod 玩家跑动时有多普勒音调变化；纯 TS 用户等响度。

## 6. 稳定性
- [ ] 连续 `/tsvoice reload` 20 次无崩溃、无 native 泄漏症状（内存稳定）。
- [ ] 长时间语音（≥30 分钟）无断流、无 Ghost 残留。
- [ ] 加入/离开服务器 10 次，主菜单停留后无异常日志。
- [ ] 日志中无明文密码；POSIX 系统上 `identity.txt` / `servers.json` / `teamspeak-svc.json` 权限为 600。
