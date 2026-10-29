$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/music-0'
New-Item -ItemType Directory -Path "$base/classes" -Force | Out-Null
# Real opus round-trip needs SVC's transitive runtime (log4j, fabric-loader, asm)
# - resolved from the gradle cache; missing entries degrade to an explicit SKIP.
$gradleCache = "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1"
$realCp = @()
foreach ($spec in @(
    @{ dir = 'org.apache.logging.log4j/log4j-api'; filter = 'log4j-api-*.jar' },
    @{ dir = 'org.apache.logging.log4j/log4j-core'; filter = 'log4j-core-*.jar' },
    @{ dir = 'net.fabricmc/fabric-loader'; filter = 'fabric-loader-0.20.1.jar' },
    @{ dir = 'org.ow2.asm/asm'; filter = 'asm-9*.jar' }
)) {
    $hit = Get-ChildItem -Path (Join-Path $gradleCache $spec.dir) -Recurse -Filter $spec.filter -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($hit) { $realCp += $hit.FullName }
}
$realCpStr = "$base/classes;teamspeak-svc/libs/voicechat-fabric-2.6.25+26.3.jar;review/lib/*;$($realCp -join ';')"
& javac -encoding UTF-8 -d "$base/classes" `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/PcmSource.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/TestToneSource.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/WavPcmSource.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/MusicEngine.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicSession.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicSendPermit.java' `
    "$base/MusicEngineTest.java" "$base/MusicEngineBoundaryTest.java" "$base/MusicSessionTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Music-0 regression compilation failed' }
& java -cp "$base/classes" com.tsvoice.MusicEngineTest
if ($LASTEXITCODE -ne 0) { throw 'Music-0 regression failed' }
# E6/T37: engine correctness boundaries (lossless backpressure, terminal close,
# paused no-spin, generation isolation, source lifecycle, control-plane liveness)
& java -cp "$base/classes" com.tsvoice.MusicEngineBoundaryTest
if ($LASTEXITCODE -ne 0) { throw 'Music-0 boundary regression failed' }
# E7/T38: independent music session (own identity/encoder/permit, no player coupling)
& java -cp "$base/classes" com.tsvoice.client.music.MusicSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Music-0 session regression failed' }
# G3/P1-01,P2-04: real bot production assembly -> real writer -> controlled transport
# TeamspeakSvcClient stub for LOGGER only (real file drags in the GUI world)
New-Item -ItemType Directory -Path "$base/botstubs/com/tsvoice/client" -Force | Out-Null
@'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("teamspeak-svc");
}
'@ | Set-Content -Path "$base/botstubs/com/tsvoice/client/TeamspeakSvcClient.java"
& javac -encoding UTF-8 -cp "review/lib/*" -d "$base/classes" "$base/botstubs/com/tsvoice/client/TeamspeakSvcClient.java"
if ($LASTEXITCODE -ne 0) { throw 'bot stub compile failed' }
# G3: real-source compile emits javac stderr notes; let error text flow.
$eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& javac -encoding UTF-8 -cp "$realCpStr" -sourcepath 'ts3voice-core/src/main/java' -d "$base/classes" `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsProtocolException.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Connection.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Crypt.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Identity.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3Packet.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/PacketType.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3VoiceException.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ed25519Math.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsCommandTracker.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsChannelSnapshot.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TcpTransport.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsTransport.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/Ts3VoiceClient.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicBotAssembly.java' `
    "$base/MusicBotProductionTest.java" 2>&1 | ForEach-Object { Write-Output "$_" }
$ErrorActionPreference = $eap
if ($LASTEXITCODE -ne 0) { throw 'Music-0 bot production compilation failed' }
& java -cp "$realCpStr" com.tsvoice.MusicBotProductionTest
if ($LASTEXITCODE -ne 0) { throw 'Music-0 bot production regression failed' }
# G6/P2-05: personal listening volume (stable participant key, no send coupling)
# F5: real opus encode->decode round-trip (concentus, no native lib needed)
& javac -encoding UTF-8 -cp 'review/lib/voicechat-api-2.6.20.jar' -d "$base/classes" "$base/RealCodecRoundTripTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Music-0 real codec compilation failed' }
& java -cp "$realCpStr" com.tsvoice.RealCodecRoundTripTest
if ($LASTEXITCODE -ne 0) { throw 'Music-0 real codec regression failed' }
exit $LASTEXITCODE
