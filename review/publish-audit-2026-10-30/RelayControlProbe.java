package com.tsvoice;

import com.tsvoice.client.ts3voice.TsMcChannelTransport;
import com.tsvoice.protocol.relay.TsRelayWire;
import com.tsvoice.server.relay.TsRelayRuntime;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立复核探针：真实 transport → 生产同形包装 → 真实 runtime。 */
public final class RelayControlProbe {
    public static void main(String[] args) throws Exception {
        try (DatagramSocket target = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             TsMcChannelTransport transport = new TsMcChannelTransport()) {
            TsRelayRuntime runtime = new TsRelayRuntime((c, p, b) -> true,
                     new TsRelayRuntime.RelayConfig(true,
                             Map.of("ts", new TsRelayRuntime.RelayConfig.RelayTargetConfig("127.0.0.1", target.getLocalPort())),
                             TsRelayRuntime.MAX_SESSIONS_GLOBAL,
                             new TsRelayRuntime.Limits(4096, 100, 100_000), false));
            try {
            var opened = runtime.onOpen("probe", "player", TsRelayWire.encodeOpen(
                    new TsRelayWire.OpenFrame(1, "ts", TsRelayWire.Purpose.PLAYER_VOICE)));
            if (!opened.ok()) throw new AssertionError("OPEN failed");
            byte[] id = opened.relaySessionId();
            transport.attachRelaySession(id, 4096);
            AtomicBoolean accepted = new AtomicBoolean();
            transport.setRelaySender((data, epoch) -> accepted.set(runtime.onData("probe",
                    TsRelayWire.encodeData(TsRelayWire.MessageType.DATA_C2S,
                            new TsRelayWire.DataFrame(id, epoch, 0, data)))));
            transport.send(new byte[]{1});
            if (!accepted.get()) throw new AssertionError("pre-barrier control rejected");
            if (!runtime.onRouteBarrier("probe", TsRelayWire.encodeRouteBarrier(
                    new TsRelayWire.RouteBarrierFrame(id, 200)))) throw new AssertionError("barrier failed");
            transport.send(new byte[]{2});
            if (accepted.get()) throw new AssertionError("expected reproduction absent");
            System.out.println("REPRODUCED: send(control) accepted before barrier and rejected after epoch=200");
            transport.send(new byte[]{3}, 200);
            if (!accepted.get()) throw new AssertionError("current epoch data rejected");
            System.out.println("CONTROL: send(data,200) forwarded through the same runtime");
            } finally { runtime.close(); }
        }
    }
}
