package com.svcb.tsbridge.ts;

import com.github.theholywaffle.teamspeak3.TS3Api;
import com.github.theholywaffle.teamspeak3.api.wrapper.Channel;
import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.TsVoicechatPlugin;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatServerApi;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * group-sync 模式：把 TS 父频道（默认 MINECRAFT）下的直接子频道镜像为
 * SVC 常驻群组（persistent，空群也全员可见 —— SVC 原生行为）。
 *
 * <ul>
 *     <li>新增 TS 子频道 → 创建同名 persistent 群组（UUID 由频道名派生，重启后稳定）；</li>
 *     <li>TS 子频道被删除 → 移除本类创建的对应群组（只动自己建的，绝不碰管理员手工群组）；</li>
 *     <li>SVC 群组名规则（^[\p{C}\s] 起头、无控制字符、≤24 字符）由 {@link #sanitize} 保证。</li>
 * </ul>
 *
 * <p>由维护任务周期调用（幂等）；ServerQuery / SVC API 不可用时静默跳过。</p>
 */
public final class TsChannelGroupSync {

    /** 本类创建的群组名（防止误删管理员手工建的 persistent 群组）。 */
    private static final Set<String> ownGroupNames = new HashSet<>();

    private TsChannelGroupSync() {
    }

    public static synchronized void syncNow() {
        try {
            VoicechatServerApi api = TsVoicechatPlugin.api;
            if (api == null) {
                return;
            }
            TS3Api query = TsBridgeMod.tsQueryClient != null && TsBridgeMod.tsQueryClient.isConnected()
                    ? TsBridgeMod.tsQueryClient.getApi() : null;
            if (query == null) {
                return;
            }
            String parent = TsBridgeMod.config != null && TsBridgeMod.config.groupSyncParentChannel != null
                    && !TsBridgeMod.config.groupSyncParentChannel.isBlank()
                    ? TsBridgeMod.config.groupSyncParentChannel : "MINECRAFT";

            List<Channel> all = query.getChannels();
            Integer parentCid = null;
            for (Channel c : all) {
                if (parent.equals(c.getName())) {
                    parentCid = c.getId();
                    break;
                }
            }
            if (parentCid == null) {
                TsBridgeMod.LOGGER.debug("[tsbridge] group-sync: parent channel '{}' not found", parent);
                return;
            }

            // TS 侧期望的群组名集合（父频道的直接子频道）
            Set<String> expected = new HashSet<>();
            for (Channel c : all) {
                if (c.getParentChannelId() == parentCid) {
                    String name = sanitize(c.getName());
                    if (name != null) {
                        expected.add(name);
                    }
                }
            }

            // SVC 侧现存群组（按名归类，仅处理本类管理的名字）
            Set<String> existing = new HashSet<>();
            for (Group g : api.getGroups()) {
                if (g != null && g.getName() != null) {
                    existing.add(g.getName());
                }
            }

            // 补建缺失
            for (String name : expected) {
                if (existing.contains(name)) {
                    ownGroupNames.add(name);
                    continue;
                }
                try {
                    api.groupBuilder()
                            .setId(groupUuidFor(name))
                            .setName(name)
                            .setPersistent(true)
                            .setHidden(false)
                            .setType(Group.Type.OPEN)
                            .build();
                    ownGroupNames.add(name);
                    TsBridgeMod.LOGGER.info("[tsbridge] group-sync: created persistent group '{}' (mirrors TS channel)", name);
                } catch (Exception e) {
                    TsBridgeMod.LOGGER.warn("[tsbridge] group-sync: failed to create group '{}': {}",
                            name, e.getMessage());
                }
            }

            // 移除多余（仅限本类创建、且 TS 侧已不存在对应频道的）
            for (String name : ownGroupNames.toArray(new String[0])) {
                if (!expected.contains(name)) {
                    UUID id = groupUuidFor(name);
                    try {
                        if (api.getGroup(id) != null) {
                            api.removeGroup(id);
                            TsBridgeMod.LOGGER.info("[tsbridge] group-sync: removed group '{}' (TS channel gone)", name);
                        }
                    } catch (Exception e) {
                        TsBridgeMod.LOGGER.debug("[tsbridge] group-sync: remove group '{}' failed: {}",
                                name, e.getMessage());
                    }
                    ownGroupNames.remove(name);
                }
            }
        } catch (Exception e) {
            TsBridgeMod.LOGGER.debug("[tsbridge] group-sync failed: {}", e.getMessage());
        }
    }

    /** 群组 UUID 由频道名确定性派生（跨重启稳定，玩家 autojoin/记忆不失效）。 */
    static UUID groupUuidFor(String channelName) {
        return UUID.nameUUIDFromBytes(("ts-channel-group-" + channelName)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 按 SVC GROUP_REGEX（^[^\p{C}\s][^\p{C}]{0,23}$）清洗频道名；清洗后为空返回 null。 */
    static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String s = name.replaceAll("\\p{C}", "").replaceFirst("^\\s+", "");
        if (s.length() > 24) {
            s = s.substring(0, 24);
        }
        s = s.replaceAll("\\p{C}", "");
        return s.isBlank() ? null : s;
    }
}
