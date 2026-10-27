package com.tsvoice.client.ts3voice;

public final class ProtocolRegression {
    private static int failures;
    private static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }
    public static void main(String[] args) throws Exception {
        Ts3VoiceClient c = new Ts3VoiceClient(Ts3Identity.generate(0, 1), "test", "");
        c.onCommand("clid=42 cid=7 client_unique_identifier=remote client_nickname=Player", false);
        check(c.getChannelOf(42) == 7, "clientlist snapshot retains cid");
        c.onCommand("cid=10 pid=1 channel_name=Lobby|cid=20 pid=2 channel_name=Lobby", false);
        check(Integer.valueOf(10).equals(c.getSubChannelIdByName("Lobby", 1)), "duplicate names retain parent 1");
        check(Integer.valueOf(20).equals(c.getSubChannelIdByName("Lobby", 2)), "duplicate names retain parent 2");
        check(c.getChannelIdByName("Lobby") == null, "global duplicate name lookup rejects ambiguity");
        c.onCommand("notifychanneldeleted cid=20 reasonid=0", false);
        check(c.getSubChannelIdByName("Lobby", 2) == null, "deleted channel removed");
        check(Integer.valueOf(10).equals(c.getChannelIdByName("Lobby")), "remaining duplicate becomes unambiguous");
        c.onCommand("notifychanneledited cid=10 channel_name=Renamed", false);
        check(c.getChannelIdByName("Lobby") == null, "rename removes former name");
        check(Integer.valueOf(10).equals(c.getSubChannelIdByName("Renamed", 1)), "rename preserves parent");
        c.onCommand("notifychannelmoved cid=10 cpid=3 order=0", false);
        check(c.getSubChannelIdByName("Renamed", 1) == null, "move removes old parent association");
        check(Integer.valueOf(10).equals(c.getSubChannelIdByName("Renamed", 3)), "move updates parent association");
        check("Renamed".equals(c.getChannelNameByCid(10)), "reverse lookup retains original case");
        if (failures > 0) throw new AssertionError(failures + " channel regressions failed");
    }
}
