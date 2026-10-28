package com.tsvoice.client.ts3voice;

import com.tsvoice.client.*;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.voice.client.*;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import java.lang.reflect.*;
import java.util.*;

public class ClientStateTests {
    static int failures;
    static Group group;
    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }
    public static void main(String[] args) throws Exception {
        UUID groupId=UUID.randomUUID(), playerId=UUID.randomUUID();
        group=(Group)Proxy.newProxyInstance(Group.class.getClassLoader(),new Class[]{Group.class},
                (p,m,a)->m.getName().equals("getId")?groupId:null);
        TeamspeakSvcClient.api=(VoicechatClientApi)Proxy.newProxyInstance(VoicechatClientApi.class.getClassLoader(),
                new Class[]{VoicechatClientApi.class},(p,m,a)->m.getName().equals("getGroup")?group:null);
        // T08c/N2: the injection entry lives on the frontend adapter; each owned
        // playback channel binds to the SVC client instance it was created against
        // and is recreated when that instance is replaced (reconnect).
        var adapter=com.tsvoice.client.frontend.Svc2625FrontendAdapter.instance();
        java.util.UUID ch=java.util.UUID.randomUUID();
        ClientVoicechat first=new ClientVoicechat(), second=new ClientVoicechat();
        ClientManager.current=first;
        var streamKey=new com.tsvoice.client.connection.VoiceState.StreamKey(
                new com.tsvoice.client.connection.VoiceState.ParticipantKey("s","u"),1);
        check(adapter.play(streamKey,ch,new byte[]{1},1),
                "play() enqueues on the owned playback channel");
        check(adapter.playbackOwnerClient(ch)==first,
                "SVC instance replacement: the channel binds to the current client");
        ClientManager.current=second;
        check(adapter.play(streamKey,ch,new byte[]{1},2),
                "play() still delivers after the SVC client instance is replaced");
        check(adapter.playbackOwnerClient(ch)==second,
                "SVC reconnect rebinds playback to the current client (stale channel recreated)");
        TsAudioInjector.removeAll();
        ClientManager.current=second;
        check(adapter.play(streamKey,ch,new byte[]{1},3)
                        && adapter.playbackOwnerClient(ch)==second,
                "SVC reconnect uses current client after cleanup (counters were reset)");

        Ts3VoiceClient c=new Ts3VoiceClient(Ts3Identity.generate(0,1),"review","");
        c.onCommand("cliententerview clid=1 ctid=7 client_unique_identifier="+c.getMyUid()+" client_nickname=Me",false);
        c.onCommand("cliententerview clid=42 ctid=7 client_unique_identifier=remote client_nickname=Player",false);
        PlayerState nativeState=new PlayerState(playerId,"Player",true,true);
        ClientManager.states.states.put(playerId,nativeState);
        ClientStateReflector.tickSnapshot();
        TsAudioInjector.onTsFrame("remote","Player",playerId,0,4,new byte[]{1});
        TsAudioInjector.syncAllGhostVisibility(c);
        check(ClientManager.states.states.get(playerId)==nativeState,"visibility sync preserves native PlayerState identity");
        TsAudioInjector.removeAll();
        check(ClientManager.states.states.get(playerId)==nativeState,"TS cleanup preserves native PlayerState");
        ClientStateReflector.injectGhost(playerId,"Wrong",TeamspeakSvcClient.api,null);
        check(ClientManager.states.states.get(playerId)==nativeState,"reflector refuses to overwrite a native PlayerState");
        ClientStateReflector.removeGhost(playerId);
        check(ClientManager.states.states.get(playerId)==nativeState,"reflector refuses to delete a state it does not own");

        UUID crossId=UUID.randomUUID();
        ClientStateReflector.injectGhost(crossId,"Cross",TeamspeakSvcClient.api,null);
        ClientStateReflector.tickSnapshot();
        check(!ClientStateReflector.hasLocalState(crossId),"injected cross-server state is not classified as a native player");
        PlayerState arrived=new PlayerState(crossId,"Arrived",true,false);
        ClientManager.states.states.put(crossId,arrived);
        ClientStateReflector.refreshGroups(TeamspeakSvcClient.api,group);
        check(arrived.group==null,"group refresh does not modify native replacement of a ghost");
        ClientStateReflector.removeGhost(crossId);
        check(ClientManager.states.states.get(crossId)==arrived,"cleanup preserves native replacement of a previous ghost");

        group=null;
        TsAudioInjector.onTsFrame("fixture-server","remote","Player",null,1,4,new byte[]{1});
        TsAudioInjector.syncAllGhostVisibility(c);
        UUID ghost=TsAudioInjector.participantUuidFor("fixture-server","remote");
        check(ClientStateReflector.activeGhosts().containsKey(ghost),"same-channel ungrouped TS user stays visible");
        Object ownedChannel=ClientStateReflector.ownedAudioChannel(ghost);
        check(ownedChannel!=null && ((Thread)ownedChannel).isAlive(),
                "owned TS audio channel is active");
        TsAudioInjector.removeAll();
        check(!ClientManager.states.states.containsKey(ghost),"cleanup removes owned TS ghost");
        check(ClientStateReflector.ownedAudioChannel(ghost)==null,"cleanup closes owned TS audio channel");
        check(adapter.playbackOwnerClient(ghost)==null,"retired channel no longer bound to any SVC client");
        if(failures!=0)throw new AssertionError(failures+" client regression checks failed");
    }
}
