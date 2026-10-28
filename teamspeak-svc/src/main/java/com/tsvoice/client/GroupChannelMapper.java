package com.tsvoice.client;

import com.tsvoice.client.net.GroupInfoPayload;
import com.tsvoice.client.ts3voice.Ts3VoiceClient;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SVC 群组到 TS 频道的映射（LEGACY_GROUP_MAPPING 模式的目标解析器）。
 * T02 起 mapper 不再独占发送许可：它只解析群组模式的目标并提交给共用确认路由
 * （{@link com.tsvoice.client.connection.TsChannelRoute}）；书签默认频道与
 * TS_NATIVE_FOLLOW/LOCKED_TARGET 模式完全不经此处。目标未确认时禁止把语音
 * 送入旧频道 —— 许可只能由协调器的 confirmChannel 发布。
 */
public final class GroupChannelMapper {
    private static final long MIRROR_UUID_PREFIX = 0x7473766380018000L;
    private static final Map<UUID, GroupInfoPayload> groupInfo = new ConcurrentHashMap<>();
    private static volatile UUID lastGroupId;
    private static volatile Group lastGroup;
    private static volatile String lastLoggedTarget;
    private static long lastChannelRefreshMs;
    private static String lastCreateTarget;
    private static long lastCreateMs;

    public record SessionInfo(String channelName) {}
    private GroupChannelMapper() {}

    /** 信标保留原始 TS 名称（镜像群组）和成员专属密码；从不记录密码。 */
    public static void onGroupInfo(GroupInfoPayload payload) {
        if (payload != null) groupInfo.put(payload.id(), payload);
    }

    /**
     * 麦克风热路径：同一有效路由 + legacy 群组约束仍然成立（活动群组与目标
     * 解析时一致）。非 legacy 模式直接读共用路由，mapper 不参与判定。
     */
    public static boolean isReadyForVoice(Ts3VoiceClient client) {
        if (!com.tsvoice.client.connection.TsChannelRoute.isReadyForVoice(client)) {
            return false;
        }
        if (com.tsvoice.client.connection.TsChannelRoute.currentPolicy()
                != com.tsvoice.client.connection.VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING) {
            return true; // 非 legacy 模式：共用路由已足够
        }
        try {
            VoicechatClientApi api = TeamspeakSvcClient.clientApi();
            if (api == null) return false;
            Group live = api.getGroup();
            UUID liveId = live == null ? null : live.getId();
            return Objects.equals(liveId, lastGroupId);
        } catch (Exception e) {
            return false;
        }
    }

    public static void tick(Ts3VoiceClient client) {
        // 行为分模式：只在 legacy 群组映射策略下驱动频道移动 —— 显式书签
        // （TS_NATIVE_FOLLOW/LOCKED_TARGET）的频道选择由共用路由服务处理。
        if (com.tsvoice.client.connection.TsChannelRoute.currentPolicy()
                != com.tsvoice.client.connection.VoiceState.ChannelPolicy.LEGACY_GROUP_MAPPING) {
            return;
        }
        try {
            VoicechatClientApi api = TeamspeakSvcClient.clientApi();
            SessionInfo session = TsVoiceManager.currentSession();
            if (api == null || session == null || session.channelName() == null || session.channelName().isBlank()) return;
            Group group = api.getGroup();
            UUID groupId = group == null ? null : group.getId();
            UUID previousId = lastGroup == null ? null : lastGroup.getId();
            if (!Objects.equals(groupId, previousId)) {
                lastGroup = group;
                lastGroupId = groupId;
                lastLoggedTarget = null;
                lastCreateTarget = null;
                ClientStateReflector.refreshGroups(api, group);
            }
            String mainName = session.channelName();
            Integer mainCid = client.getChannelIdByName(mainName);
            if (mainCid == null) {
                refreshChannels(client, mainName);
                logOnce("main channel is unavailable or ambiguous; voice paused");
                return;
            }
            Integer targetCid = mainCid;
            String targetName = mainName;
            var config = TsVoiceManager.currentSessionConfig();
            String channelPassword = config == null ? null : config.channelPassword();
            if (group != null) {
                GroupInfoPayload info = groupInfo.get(groupId);
                boolean mirror = isMirroredGroup(group);
                // Local OPEN groups intentionally remain in the main channel. TS-mirror
                // groups are OPEN in SVC but must continue mapping to their TS channel.
                if (!mirror && group.getType() == de.maxhenkel.voicechat.api.Group.Type.OPEN) {
                    publishTarget(client, mainCid, mainCid);
                    return;
                }
                if (info == null && (mirror || TsVoiceManager.currentBeacon() != null)) {
                    logOnce("waiting for group target information; voice paused");
                    return;
                }
                if (group.hasPassword() && (info == null || info.password() == null || info.password().isEmpty())) {
                    logOnce("waiting for group password information; voice paused");
                    return;
                }
                channelPassword = info == null ? null : info.password();
                // 只对本地创建的群组清洗；服务端镜像携带原始频道名，不能截断或改写。
                targetName = info != null ? info.name() : sanitize(group.getName());
                if (targetName == null || targetName.isBlank()) return;
                targetCid = client.getSubChannelIdByName(targetName, mainCid);
                if (targetCid == null) {
                    refreshChannels(client, targetName);
                    if (!mirror) {
                        String createKey = mainCid + "/" + targetName;
                        long now = System.currentTimeMillis();
                        if (!createKey.equals(lastCreateTarget) || now - lastCreateMs >= 10_000L) {
                            lastCreateTarget = createKey;
                            lastCreateMs = now;
                            client.createChannel(targetName, mainCid, channelPassword);
                        }
                    }
                    logOnce("group channel unavailable; voice paused until target is confirmed");
                    return;
                }
            }
            publishTarget(client, targetCid, mainCid);
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] group mapping failed: {}", e.toString());
        }
    }

    /**
     * 提交 legacy 目标到共用确认路由：目标即当前频道时确认观察可直接批准；
     * 否则发起移动请求（先撤销许可）。无论哪条路径都以观察到的实际频道为准。
     */
    private static void publishTarget(Ts3VoiceClient client, int targetCid, int parentCid) {
        com.tsvoice.client.connection.TsChannelRoute.setLegacyTarget(client, targetCid);
        if (targetCid != client.getMyChannelId()) {
            String password = null;
            var config = TsVoiceManager.currentSessionConfig();
            password = config == null ? null : config.channelPassword();
            // clientmove 仅提交请求；必须等 TS 通知确认后才开放语音。
            com.tsvoice.client.connection.TsChannelRoute.requestMove(client, targetCid, password);
        }
        // 观察提交放在目标提交之后：同一 tick 内"目标==当前"即可获得批准。
        com.tsvoice.client.connection.TsChannelRoute.observeSelf(client);
    }

    private static void refreshChannels(Ts3VoiceClient client, String name) {
        long now = System.currentTimeMillis();
        if (now - lastChannelRefreshMs < 10_000L) return;
        lastChannelRefreshMs = now;
        try {
            client.requestChannelList();
            client.findChannel(name);
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.debug("[teamspeak-svc] channel refresh failed: {}", e.getMessage());
        }
    }

    /** 新镜像 UUID 有固定标识；兼容旧版按显示名派生的常驻群组。 */
    private static boolean isMirroredGroup(Group group) {
        UUID id = group.getId();
        return id.getMostSignificantBits() == MIRROR_UUID_PREFIX || id.equals(UUID.nameUUIDFromBytes(
                ("ts-channel-group-" + group.getName()).getBytes(StandardCharsets.UTF_8)));
    }

    private static String sanitize(String name) {
        if (name == null) return null;
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) out.append(c < 0x20 || c == '/' || c == '\\' || c == '*' || c == '?' ? ' ' : c);
        String clean = out.toString().trim();
        return clean.length() > 30 ? clean.substring(0, 30) : clean;
    }

    private static void logOnce(String message) {
        if (!message.equals(lastLoggedTarget)) {
            lastLoggedTarget = message;
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] {}", message);
        }
    }

    public static void reset() {
        groupInfo.clear();
        lastGroup = null;
        lastGroupId = null;
        lastLoggedTarget = null;
        lastChannelRefreshMs = 0;
        lastCreateTarget = null;
        lastCreateMs = 0;
        com.tsvoice.client.connection.TsChannelRoute.onMapperReset();
    }
}
