$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p1-isolation'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if (!(Test-Path 'review/lib/gson.jar')) {
    $jar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$stubs = @{
    'com/tsvoice/client/TeamspeakSvcClient.java' = @'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("p1-isolation");
 public static ModConfig config=new ModConfig();
 public static ModConfig config(){return config;}
}
'@
    'com/tsvoice/client/GroupChannelMapper.java' = @'
package com.tsvoice.client;
public class GroupChannelMapper {
 public static boolean ready;
 public static void reset(){}
 public static void tick(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static boolean isReadyForVoice(com.tsvoice.client.ts3voice.Ts3VoiceClient c){return ready;}
 public record SessionInfo(String channelName){}
}
'@
    'com/tsvoice/client/ClientIdentityService.java' = @'
package com.tsvoice.client;
public class ClientIdentityService {
 public static void beginProof(com.tsvoice.client.ts3voice.Ts3VoiceClient c,com.tsvoice.client.ts3voice.Ts3Identity id){}
 public static java.util.UUID getVerifiedMcUuid(String serverUid,String tsClientUid){return null;}
 public static void tsDisconnected(com.tsvoice.client.ts3voice.Ts3VoiceClient c){}
 public static void clearVerified(){}
}
'@
    'com/tsvoice/client/AvatarPipeline.java' = @'
package com.tsvoice.client;
public class AvatarPipeline {
 public static void uploadAvatarAsync(com.tsvoice.client.ts3voice.Ts3VoiceClient c,java.util.UUID id,String namespace,String host){}
}
'@
    'com/tsvoice/client/OnlinePlayers.java' = @'
package com.tsvoice.client;
public class OnlinePlayers {public static boolean isOnline(java.util.UUID id){return false;}}
'@
    'com/tsvoice/client/audio/AecReferenceMixer.java' = @'
package com.tsvoice.client.audio;
public class AecReferenceMixer {public static void clear(){}}
'@
    'com/tsvoice/client/net/BeaconPayload.java' = @'
package com.tsvoice.client.net;
public record BeaconPayload(String host,int port,String channel,String backendMode,int tunnelPort){}
'@
    'com/tsvoice/client/ts3voice/Ts3Identity.java' = @'
package com.tsvoice.client.ts3voice;
public class Ts3Identity {
 public static Ts3Identity load(java.nio.file.Path path){return new Ts3Identity();}
 public static Ts3Identity generate(int a,long b){return new Ts3Identity();}
 public void save(java.nio.file.Path path){}
 public String getClientUid(){return "uid";}
}
'@
    'com/tsvoice/client/ts3voice/Ts3VoiceException.java' = @'
package com.tsvoice.client.ts3voice;
public class Ts3VoiceException extends Exception {}
'@
    'net/minecraft/network/chat/Component.java' = @'
package net.minecraft.network.chat;
public class Component {public static Component literal(String text){return new Component();}}
'@
    'net/fabricmc/loader/api/FabricLoader.java' = @'
package net.fabricmc.loader.api;
public class FabricLoader {
 public static FabricLoader getInstance(){return new FabricLoader();}
 public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of("review/p1-isolation/config");}
}
'@
}
$sources = foreach ($item in $stubs.GetEnumerator()) {
    $path = Join-Path "$base/stubs" $item.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path (Get-Location) $path), $item.Value)
    $path
}
# Recording stubs written outside the generated set.
$sources += @("$base/stubs/com/tsvoice/client/ts3voice/Ts3VoiceClient.java",
    "$base/stubs/com/tsvoice/client/TsAudioInjector.java",
    "$base/stubs/net/minecraft/client/Minecraft.java")
$client = 'teamspeak-svc/src/main/java/com/tsvoice/client'
# The connection control-plane classes are part of the fix; tolerate their absence
# when proving the old code fails.
$prod = @("$client/TsVoiceManager.java", "$client/audio/MicRouteGate.java", "$client/connection/VoiceState.java",
    "$client/connection/BackendCoordinator.java", "$client/connection/SelectionPolicy.java", "$client/connection/TsChannelRoute.java",
    "$client/ts3voice/TsChannelSnapshot.java",
    "$client/config/TsBookmarkStore.java", "$client/config/EndpointCredentialStore.java", "$client/backend/SvcBackendAdapter.java",
    "$client/ModConfig.java", "$client/ServerConfigStore.java", "$client/ConfigFileUtil.java") |
    Where-Object { Test-Path $_ }
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @prod @sources "$base/SessionIsolationTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Session isolation regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.SessionIsolationTest
if ($LASTEXITCODE -ne 0) { throw 'Session isolation regression failed' }
exit $LASTEXITCODE
