$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/ts-mc-relay'
New-Item -ItemType Directory -Path "$base/classes" -Force | Out-Null
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsDatagramKind.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/OpenEstablisher.java' `
        'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/PacketType.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelayRuntime.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelaySession.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsTransport.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice/TsMcChannelTransport.java' `
    "$base/RelayRuntimeTest.java" "$base/RelayProductionBridgeTest.java" "$base/RelayBoundaryRegressionTest.java" "$base/RelayMusicBotPurposeTest.java" "$base/RelayProductionEpochTest.java" "$base/RelayControlMediaBarrierTest.java" "$base/RelayAsyncOpenTest.java"
if ($LASTEXITCODE -ne 0) { throw 'relay regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayRuntimeTest
if ($LASTEXITCODE -ne 0) { throw 'relay runtime regression failed' }
# E3/T32+T34: production bridge contract + connection-keyed OPEN correlation
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayProductionBridgeTest
if ($LASTEXITCODE -ne 0) { throw 'relay production bridge regression failed' }
# E4/T33+T35: barrier enforcement, real pps token bucket, unified 4096 bounds
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayBoundaryRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'relay boundary regression failed' }
# E8/Music-1: MUSIC_BOT purpose wire + purpose-scoped registry (default-off)
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayMusicBotPurposeTest
if ($LASTEXITCODE -ne 0) { throw 'relay MUSIC_BOT purpose regression failed' }
# F2: production epoch/barrier/bytes enforcement
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayProductionEpochTest
if ($LASTEXITCODE -ne 0) { throw 'relay production epoch regression failed' }
# G2/P1-02: media barrier vs control traffic isolation (real TS header fixtures)
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayControlMediaBarrierTest
if ($LASTEXITCODE -ne 0) { throw 'relay control/media barrier regression failed' }
# G4: async OPEN establisher (bounded workers/pending, duplicate rejection)
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayAsyncOpenTest
if ($LASTEXITCODE -ne 0) { throw 'relay async open regression failed' }
exit $LASTEXITCODE
