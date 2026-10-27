import com.svcb.tsbridge.TsBridgeMod;
import com.svcb.tsbridge.config.BridgeConfig;
import com.svcb.tsbridge.net.TcpTunnelServer;
import com.svcb.tsbridge.ts.TsQueryClient;
import com.svcb.tsbridge.transport.PlayerVoiceClients;
import com.svcb.tsbridge.ts3voice.Ts3VoiceClient;
import com.svcb.tsbridge.svc.McToTsDispatcher;
import com.svcb.tsbridge.ghost.GhostPlayerInjector;
import com.github.theholywaffle.teamspeak3.TS3Query;
import net.fabricmc.loader.api.FabricLoader;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import java.net.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerRegression {
    static int failures;
    interface Check {void run() throws Exception;}
    static void test(String name, Check check) {try {check.run();System.out.println("PASS "+name);}catch(Throwable e){failures++;System.out.println("FAIL "+name+": "+e);}}
    static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    static Object proxy(Class<?> type, java.util.function.Function<Method,Object> values) {
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->values.apply(m));
    }
    public static void main(String[] args) throws Exception {
        FabricLoader.path=Path.of("review/server-fixes/config").toAbsolutePath();
        Files.createDirectories(FabricLoader.path);
        for(String stage:List.of("connect","login","select","nickname"))test("failed query cleans "+stage,()->{
            BridgeConfig cfg=new BridgeConfig();cfg.queryPassword="fixture";TS3Query.fail=stage;
            TsQueryClient client=new TsQueryClient(cfg);
            try{client.connect();throw new AssertionError("expected failure");}catch(IOException|IllegalStateException expected){}
            require(TS3Query.last.closed,"uncommitted query must close");require(!client.isConnected()&&client.getApi()==null,"failed connection must not publish");
        });
        test("successful query stays open until disconnect",()->{
            TS3Query.fail="";BridgeConfig cfg=new BridgeConfig();cfg.queryPassword="fixture";
            TsQueryClient c=new TsQueryClient(cfg);c.connect();require(!TS3Query.last.closed&&c.isConnected(),"success should retain connection");c.disconnect();require(TS3Query.last.closed,"disconnect should close");
        });
        test("offline player cannot commit newly connected client",()->{
            TsBridgeMod.config=new BridgeConfig();
            Class<?> type=Class.forName("com.svcb.tsbridge.transport.PlayerVoiceClients$PlayerClient");
            Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,String.class);constructor.setAccessible(true);
            UUID id=UUID.randomUUID();Object pc=constructor.newInstance(id,"test");
            Field players=PlayerVoiceClients.class.getDeclaredField("players");players.setAccessible(true);((Map)players.get(null)).put(id,pc);
            Ts3VoiceClient.entered=new CountDownLatch(1);Ts3VoiceClient.release=new CountDownLatch(1);
            Method connect=PlayerVoiceClients.class.getDeclaredMethod("connect",type);connect.setAccessible(true);
            Thread worker=new Thread(()->{try{connect.invoke(null,pc);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
            worker.start();require(Ts3VoiceClient.entered.await(2,TimeUnit.SECONDS),"connect must start");
            PlayerVoiceClients.onPlayerDisconnected(id);Ts3VoiceClient.release.countDown();worker.join(2000);
            require(!worker.isAlive(),"connection must finish");
            Field field=type.getDeclaredField("client");field.setAccessible(true);
            require(field.get(pc)==null&&Ts3VoiceClient.last.disconnected,"offline connection must close instead of publish");
        });
        test("stopped tunnel closes established socket",()->{
            try(DatagramSocket target=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
                target.setSoTimeout(1500);String secret="fixture-only-tunnel-secret-at-least-32-characters";
                TcpTunnelServer.start(0,"127.0.0.1",target.getLocalPort(),secret);
                Field f=TcpTunnelServer.class.getDeclaredField("serverSocket");f.setAccessible(true);
                int port=((ServerSocket)f.get(null)).getLocalPort();
                try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),port)) {
                    socket.setSoTimeout(500);DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                    out.write(com.tsvoice.protocol.TunnelTicket.issue(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),System.currentTimeMillis()/1000L,60).encode());
                    out.writeInt(1);out.writeByte(7);out.flush();target.receive(new DatagramPacket(new byte[20],20));
                    TcpTunnelServer.stop();boolean closed=false;
                    try{closed=socket.getInputStream().read()==-1;}catch(SocketException e){closed=true;}
                    require(closed,"stop must close active TCP and UDP session");
                }finally{TcpTunnelServer.stop();}
            }
        });
        test("unauthenticated and replayed tunnel tickets cannot forward",()->{
            try(DatagramSocket target=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
                target.setSoTimeout(250);String secret="fixture-only-tunnel-secret-at-least-32-characters";
                TcpTunnelServer.start(0,"127.0.0.1",target.getLocalPort(),secret);
                Field f=TcpTunnelServer.class.getDeclaredField("serverSocket");f.setAccessible(true);
                int port=((ServerSocket)f.get(null)).getLocalPort();
                byte[] ticket=com.tsvoice.protocol.TunnelTicket.issue(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),System.currentTimeMillis()/1000L,60).encode();
                try {
                    try(Socket valid=new Socket(InetAddress.getLoopbackAddress(),port)) {
                        DataOutputStream out=new DataOutputStream(valid.getOutputStream());
                        out.write(ticket);out.writeInt(1);out.writeByte(7);out.flush();
                        target.receive(new DatagramPacket(new byte[20],20));
                    }
                    for(boolean authenticated:new boolean[]{false,true}) {
                        try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),port)) {
                            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                            if(authenticated)out.write(ticket); else out.write(new byte[65]);
                            out.writeInt(1);out.writeByte(7);out.flush();
                            target.receive(new DatagramPacket(new byte[20],20));
                            throw new AssertionError("unauthenticated/replayed tunnel forwarded a packet");
                        } catch(SocketTimeoutException|SocketException expected) {}
                    }
                } finally {TcpTunnelServer.stop();}
            }
        });
        test("unknown reflected membership is empty",()->require(GhostPlayerInjector.getBridgeGroupMemberUuids()!=null&&GhostPlayerInjector.getBridgeGroupMemberUuids().isEmpty(),"reflection failure must never mean everyone"));
        test("unknown membership preserves last verified audience",()->{
            com.svcb.tsbridge.svc.BridgeMembershipService.reset();
            UUID member=UUID.randomUUID(),group=UUID.randomUUID();
            UUID oldBridgeId=TsBridgeMod.bridgeGroupId;TsBridgeMod.bridgeGroupId=group;
            try {
                com.svcb.tsbridge.svc.BridgeMembershipService.publish(Map.of(member,group));
                require(com.svcb.tsbridge.svc.BridgeMembershipService.availability()==com.svcb.tsbridge.svc.BridgeMembershipService.Availability.MEMBERS,"valid snapshot must be MEMBERS");
                com.svcb.tsbridge.svc.BridgeMembershipService.markUnknown();
                require(com.svcb.tsbridge.svc.BridgeMembershipService.availability()==com.svcb.tsbridge.svc.BridgeMembershipService.Availability.UNKNOWN,"read failure must be UNKNOWN");
                require(GhostPlayerInjector.getBridgeGroupMemberUuids().contains(member),"unknown must preserve last verified audience");
            } finally {TsBridgeMod.bridgeGroupId=oldBridgeId;}
            com.svcb.tsbridge.svc.BridgeMembershipService.reset();
            require(GhostPlayerInjector.getBridgeGroupMemberUuids().isEmpty(),"reset must clear audience");
        });
        test("microphone uses current packet group, not stale membership cache",()->{
            UUID id=UUID.randomUUID(),bridge=UUID.randomUUID();TsBridgeMod.bridgeGroupId=bridge;TsBridgeMod.perPlayerMode=false;TsBridgeMod.mixer.sent=0;
            ServerPlayer player=(ServerPlayer)proxy(ServerPlayer.class,m->m.getName().equals("getUuid")?id:null);
            MicrophonePacket packet=(MicrophonePacket)proxy(MicrophonePacket.class,m->m.getName().equals("getOpusEncodedData")?new byte[]{1}:null);
            for(UUID groupId:new UUID[]{null,UUID.randomUUID(),bridge,null}){
                Group group=groupId==null?null:(Group)proxy(Group.class,m->m.getName().equals("getId")?groupId:null);
                VoicechatConnection conn=(VoicechatConnection)proxy(VoicechatConnection.class,m->switch(m.getName()){case "getPlayer"->player;case "getGroup"->group;case "isInGroup","isConnected"->true;default->null;});
                MicrophonePacketEvent event=(MicrophonePacketEvent)proxy(MicrophonePacketEvent.class,m->switch(m.getName()){case "getSenderConnection"->conn;case "getPacket"->packet;default->null;});
                McToTsDispatcher.onMicrophonePacket(event);
            }
            require(TsBridgeMod.mixer.sent==1,"only current bridge-group packet may forward, including immediately after leaving");
        });
        test("unpaired fallback microphone is not gated by pairing",()->{
            UUID id=UUID.randomUUID(),bridge=UUID.randomUUID();
            TsBridgeMod.bridgeGroupId=bridge;TsBridgeMod.perPlayerMode=false;
            TsBridgeMod.mixer.sent=0;TsBridgeMod.mixer=null;
            var oldForwarder=TsBridgeMod.forwarder;TsBridgeMod.forwarder=new TsBridgeMod.Forwarder();
            var oldPairings=TsBridgeMod.pairings;TsBridgeMod.pairings=new TsBridgeMod.Pairings();
            try {
                ServerPlayer player=(ServerPlayer)proxy(ServerPlayer.class,m->m.getName().equals("getUuid")?id:null);
                MicrophonePacket packet=(MicrophonePacket)proxy(MicrophonePacket.class,m->m.getName().equals("getOpusEncodedData")?new byte[]{1}:null);
                Group group=(Group)proxy(Group.class,m->m.getName().equals("getId")?bridge:null);
                VoicechatConnection conn=(VoicechatConnection)proxy(VoicechatConnection.class,m->switch(m.getName()){case "getPlayer"->player;case "getGroup"->group;default->null;});
                MicrophonePacketEvent event=(MicrophonePacketEvent)proxy(MicrophonePacketEvent.class,m->switch(m.getName()){case "getSenderConnection"->conn;case "getPacket"->packet;default->null;});
                McToTsDispatcher.onMicrophonePacket(event);
                require(TsBridgeMod.forwarder.sent==1,"unpaired bridge-group microphone must use fallback");
            } finally {
                TsBridgeMod.forwarder=oldForwarder;TsBridgeMod.pairings=oldPairings;
                TsBridgeMod.mixer=new TsBridgeMod.Mixer();
            }
        });
        test("restart-only reload is rejected without partial update",()->{
            BridgeConfig cfg=new BridgeConfig();cfg.pairCodeLength=6;
            Files.writeString(FabricLoader.path.resolve("tsbridge.json"),"{\"mode\":\"group-sync\",\"pairCodeLength\":9,\"tcpTunnelPort\":12345}");
            boolean rejected=false;try{cfg.reload();}catch(IllegalStateException e){rejected=true;require(e.getMessage().toLowerCase().contains("restart"),"admin must be told restart required");}
            require(rejected,"restart fields must reject reload");require(cfg.mode.equals("bridge")&&cfg.tcpTunnelPort==0&&cfg.pairCodeLength==6,"reject all changes atomically");
        });
        test("supported reload updates runtime policy",()->{
            BridgeConfig cfg=new BridgeConfig();Files.writeString(FabricLoader.path.resolve("tsbridge.json"),"{\"pairCodeLength\":9,\"autoPairing\":true,\"sendSilenceFrames\":true}");cfg.reload();
            require(cfg.pairCodeLength==9&&cfg.autoPairing&&cfg.sendSilenceFrames,"hot policy changes should apply");
        });
        if(failures>0)throw new AssertionError(failures+" server regressions failed");
    }
}
