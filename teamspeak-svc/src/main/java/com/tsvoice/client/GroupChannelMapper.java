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

/** SVC 群组到 TS 频道的映射；目标未确认时禁止把语音送入旧频道。 */
public final class GroupChannelMapper {
    private static final long MIRROR_UUID_PREFIX = 0x7473766380018000L;
    private static final Map<UUID, GroupInfoPayload> groupInfo = new ConcurrentHashMap<>();
    private static volatile Routing routing;
    private static volatile Group lastGroup;
    private static volatile String lastLoggedTarget;
    private static long lastChannelRefreshMs;
    private static String lastCreateTarget;
    private static long lastCreateMs;

    private record Routing(Ts3VoiceClient client, UUID groupId, int targetCid, String targetName, int parentCid) {}
    public record SessionInfo(String channelName) {}
    private GroupChannelMapper() {}

    /** 信标保留原始 TS 名称（镜像群组）和成员专属密码；从不记录密码。 */
    public static void onGroupInfo(GroupInfoPayload payload) {
        if (payload != null) groupInfo.put(payload.id(), payload);
    }

    /** 麦克风热路径：只读当前群组及已确认的目标，不发网络请求。 */
    public static boolean isReadyForVoice(Ts3VoiceClient client) {
        Routing selected = routing;
        if (selected == null || selected.client() != client) return false;
        try {
            VoicechatClientApi api = TeamspeakSvcClient.clientApi();
            if (api == null) return false;
            Group live = api.getGroup();
            UUID liveId = live == null ? null : live.getId();
            if (!Objects.equals(liveId, selected.groupId()) || client.getMyChannelId() != selected.targetCid()) return false;
            Integer cached = live == null || selected.targetCid() == selected.parentCid()
                    ? client.getChannelIdByName(selected.targetName())
                    : client.getSubChannelIdByName(selected.targetName(), selected.parentCid());
            return cached != null && cached == selected.targetCid();
        } catch (Exception e) {
            return false;
        }
    }

    public static void tick(Ts3VoiceClient client) {
        routing = null;
        // P3 行为分模式：显式 TS 频道选择（书签意图）时不做周期映射 —— legacy
        // 群组映射只在 SVC 群组路径（LEGACY_GROUP_MAPPING）下驱动频道移动，
        // 手动 TS 频道选择（TS_CHANNEL_SELECTION）完全由用户/UI 控制。
        if (TsVoiceManager.hasExplicitBookmarkIntent()) {
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
                    targetCid = mainCid;
                    targetName = mainName;
                    routing = new Routing(client, groupId, targetCid, targetName, mainCid);
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
            if (targetCid != client.getMyChannelId()) {
                client.joinChannel(targetCid, channelPassword);
                return; // clientmove 仅提交请求；必须等 TS 通知确认后才开放语音。
            }
            routing = new Routing(client, groupId, targetCid, targetName, mainCid);
        } catch (Exception e) {
            TeamspeakSvcClient.LOGGER.warn("[teamspeak-svc] group mapping failed: {}", e.toString());
        }
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
        routing = null;
        groupInfo.clear();
        lastGroup = null;
        lastLoggedTarget = null;
        lastChannelRefreshMs = 0;
        lastCreateTarget = null;
        lastCreateMs = 0;
    }
}