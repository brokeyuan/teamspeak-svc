package com.tsvoice.client.ts3voice;

import java.net.InetAddress;

/**
 * 频道操作诊断（CLI）：验证 channellist（含压缩包解压）/ channelfind /
 * 子频道创建 / clientmove 的完整链路。
 *
 * <p>用法：{@code java com.tsvoice.client.ts3voice.Ts3ChannelTest [host] [port] [主频道名] [子频道名]}</p>
 */
public final class Ts3ChannelTest {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9987;
        String mainChannel = args.length > 2 ? args[2] : "MINECRAFT";
        String subChannel = args.length > 3 ? args[3] : "TsTestSub";

        Ts3Connection.DEBUG = true;
        Ts3Connection.DUMP_FRAGMENTS = true;
        System.out.println("=== TS3 channel ops test ===");
        Ts3Identity identity = Ts3Identity.generate(24, 8_000);
        Ts3VoiceClient client = new Ts3VoiceClient(identity, "TsChannelTest", "");
        client.connect(InetAddress.getByName(host), port, 15_000);
        System.out.println("[ok] connected clid=" + client.getMyClid() + " myChannel=" + client.getMyChannelId());

        // 1. channellist（大应答，触发压缩解压路径）
        client.requestChannelList();
        Thread.sleep(800);
        Integer mainCid = client.getChannelIdByName(mainChannel);
        System.out.println((mainCid != null ? "[ok]" : "[FAIL]") + " channellist parse + lookup '" + mainChannel
                + "' -> " + mainCid);
        System.out.println("[..] known channels: " + client.debugChannels().size());

        // 2. channelfind（小应答）
        client.findChannel(mainChannel);
        Thread.sleep(500);
        Integer findCid = client.getChannelIdByName(mainChannel);
        System.out.println((findCid != null && findCid.equals(mainCid) ? "[ok]" : "[FAIL]")
                + " channelfind lookup -> " + findCid);

        // 3. clientmove 到主频道
        if (mainCid != null && mainCid != client.getMyChannelId()) {
            client.joinChannel(mainCid);
            Thread.sleep(1000);
            System.out.println((client.getMyChannelId() == mainCid ? "[ok]" : "[FAIL]")
                    + " moved to main channel, myChannel=" + client.getMyChannelId());
        }

        // 4. 创建半永久子频道 + 定位 + 移入
        if (mainCid != null) {
            client.createChannel(subChannel, mainCid);
            Thread.sleep(600);
            client.requestChannelList();
            Thread.sleep(600);
            Integer subCid = client.getSubChannelIdByName(subChannel, mainCid);
            System.out.println((subCid != null ? "[ok]" : "[FAIL]") + " sub-channel '" + subChannel
                    + "' created under main -> cid=" + subCid);
            System.out.println("[..] channel map at lookup: " + client.debugChannels());
            if (subCid != null) {
                client.joinChannel(subCid);
                Thread.sleep(1000);
                System.out.println((client.getMyChannelId() == subCid ? "[ok]" : "[FAIL]")
                        + " moved into sub-channel, myChannel=" + client.getMyChannelId());
            }
        }

        boolean ok = client.getChannelIdByName(mainChannel) != null && client.wasEverConnected();
        System.out.println(ok ? "=== CHANNEL TEST PASSED ===" : "=== CHANNEL TEST FAILED ===");
        client.disconnect();
        System.exit(ok ? 0 : 1);
    }
}
