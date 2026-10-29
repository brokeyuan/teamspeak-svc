# DELIVERY — 验收候选轮（F0–F7）实际交付说明

> 2026-10-30 复核未通过：本报告为原交付记录，不能据此判 F0–F7 全部完成。当前缺陷与修复顺序见 [独立复核](../publish-audit-2026-10-30/REVIEW.md)。

日期：2026-10-29。基线 `0072d00` → 终点 `77fc8ea`（候选打包提交；功能终点 `80dd40f`）。
本轮按 `2026-10-29-client-acceptance-candidate-plan.md` F0–F7 执行，**到此交接并停止新增功能**。

## 1. 已修复并验证（反例先行，逐项独立提交）

| 项 | 提交 | 修复 | 验证 |
|---|---|---|---|
| F1 | 861f60d | SVC read() 阻塞在原生 UDP 时，内部 MC 换态不再把 SocketException 抛给 SVC 读线程（受控唤醒识别，真关闭/其他故障仍按契约退出） | ClientAcceptanceBoundaryTest F1（同线程拿到 MC 帧）+ svc-mc-channel 40 PASS |
| F2 | 229806c | 生产 DATA 携带入队真实 routeEpoch（TsTransport.send(data,epoch) 重载 → writer 原样入帧）；频道确认点真实发送 ROUTE_BARRIER；bytes/s token bucket 真实计费拒绝 | RelayProductionEpochTest 11 断言（barrier 下旧帧拒/新帧过/倒退拒、字节预算+回填、双桶独立） |
| F3 | 861f60d | PAUSED/无选中后端不再解释为 SVC：原生 SVC 收听压制，仅显式选择 SVC 才恢复 | ClientAcceptanceBoundaryTest F3 + OffGate/SingleBackend 断言按产品规则更新 |
| F4 | 861f60d | 音乐 playback generation：编码前捕获、编码后/入队前复核；cancel 后编码中的帧不再调 sender | ClientAcceptanceBoundaryTest F4（阻塞编码器 + cancel → 0 sends） |
| F5-生产 | c30bf8d | MusicSession 生产连接面（ProductionConnection/房间绑定/独立 closer）；MusicBotManager：独立 identity-music.txt、独立 UDP 连接、SVC API 编码器工厂、发送桥；/tsvoice music 命令组；TS 断开/切SVC/OFF/退出自动停本机 Bot | gradle 编译 + RealCodecRoundTripTest（真实 Opus encode→decode：960 样本→196B→960 样本 rms4660）+ music 套件 47 PASS |
| F5-受控 | 5aaa1ea | `relay.musicBotEnabled`（默认 false）授权 MUSIC_BOT 第二中继会话；启用后过 purpose 门（与 PLAYER_VOICE 分桶、分 purpose 不覆盖） | RelayMusicBotPurposeTest 6 断言 |
| F6 | 80dd40f | F2/F5 变更传导的 9 个 harness 修复（MusicBotLifecycle 惰性解耦、stub barrier payload/canSend/epoch 重载）；RunAll 门恢复全绿 | RunAll：core + 26 套件全 PASS，exit 0 |

## 2. 本轮证据（review/client-acceptance-next/evidence/）
- `f1-f3-f4-boundary.log`：三反例修复回归（8 PASS）。
- `f2-relay-epoch.log`：生产代际/barrier/字节（relay 套件 75→76 PASS）。
- `f5-music-realcodec.log`：音乐套件 + **真实 Opus 往返**。
- `f6-relay-musicbot-config.log`：MUSIC_BOT 授权开关。
- RunAll 全绿日志：`e9-runall.log`（E9 轮）+ 本轮重跑结果见 STATUS 摘要（27 PASS）。

## 3. 候选
- `dist/client-acceptance/80dd40f/teamspeak-svc-0.9.8+26.3.jar`
- SHA-256 `8585708afc5e3740309411b28b46138fb0886b5e08552fe99e20f5ad6c4d6a1c`，39,396,995 字节。
- 依赖/安装/四模式配置：`MANIFEST.md`。

## 4. 残留限制（如实，不是环境借口）
1. **真实 MC 网络/实体设备未验证**：TS 握手、语音、听感由用户按手册验收（客户端暂缓矩阵延续）。
2. **F2 背压边界**：DNS/建 socket 仍在 OPEN 同步链（未移出 MC 主线程）；MC 提交侧为 Fabric send 直发，未做有界 drain 队列。字节/pps 限额与队列有界已生效；这两条列入下一轮工程项，不影响本轮验收路径。
3. **音乐频道许可简化**：Bot 发送许可为会话级常量代际（独立代际空间），未实现逐频道确认；房间绑定（serverUID/cid）已落字段，"被移出房间即停"的检测需 Bot 频道事件（下一轮）。
4. **远程点歌/跨服控制未实现**（可信 sender 元数据链未完成）——验收标 N/A，不是暂缓。
5. **编码后端仅客户端 API**：无 SVC 的服务端音乐托管编码（未来 Music-3）未实现，本轮声明不变。

## 5. 交接说明
- 验收手册：`CLIENT-ACCEPTANCE.md`；结果表：`RESULTS.md`（初始全部"待用户执行"）。
- 用户验收反馈后：先修验收缺陷，再谈 Music-2/3/在线曲库/P7（本轮不自动继续）。
- 历史 deferred 清单（`review/engineering-closeout/DEFERRED-CLIENT-ACCEPTANCE.md`）由本轮新清单承接。
- 用户现有运行服务器（testserver 内进程）未被本轮启动/停止/改动。
