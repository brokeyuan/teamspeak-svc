package com.tsvoice.client.ts3voice;

import java.net.*;

/** Local review probes. No connections to an external TeamSpeak server. */
public class ProtocolAudit {
    public static void main(String[] args) throws Exception {
        Ts3VoiceClient c = new Ts3VoiceClient(Ts3Identity.generate(0, 1), "review", "");
        c.onCommand("clid=42 cid=7 client_unique_identifier=review-user client_nickname=Reviewer", false);
        System.out.println("clientlist cid=7 -> channel=" + c.getChannelOf(42));
        c.onCommand("cid=10 pid=1 channel_name=Lobby|cid=20 pid=2 channel_name=Lobby", false);
        System.out.println("duplicate channel Lobby under parent 1 -> " + c.getSubChannelIdByName("Lobby", 1));
        System.out.println("global Lobby lookup -> " + c.getChannelIdByName("Lobby"));
        c.onCommand("notifychanneldeleted cid=20 reasonid=0", false);
        System.out.println("deleted cid=20 remains cached -> " + c.getChannelIdByName("Lobby"));

        try (DatagramSocket recv = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket send = new DatagramSocket()) {
            recv.setSoTimeout(1000);
            DatagramPacket p = new DatagramPacket(new byte[4096], 4096);
            send.send(new DatagramPacket(new byte[8], 8, InetAddress.getLoopbackAddress(), recv.getLocalPort()));
            recv.receive(p);
            int first = p.getLength();
            send.send(new DatagramPacket(new byte[300], 300, InetAddress.getLoopbackAddress(), recv.getLocalPort()));
            recv.receive(p);
            System.out.println("reused UDP packet sizes: " + first + ", " + p.getLength());
        }
    }
}
