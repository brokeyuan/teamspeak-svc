$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/client-acceptance-next'
New-Item -ItemType Directory -Path "$base/classes" -Force | Out-Null
& javac -encoding UTF-8 -cp "$base/stubs;review/music-0/classes;review/lib/*" -d "$base/classes" `
    'teamspeak-svc/src/main/java/com/tsvoice/common/net/SvcMcChannelPayloads.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/PcmSource.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/TestToneSource.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/backend/SvcMcChannelSocket.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/backend/SvcMcChannelClient.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/backend/SvcBackendAdapter.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/music/MusicSession.java' `

                                                                        "$base/ClientAcceptanceBoundaryTest.java"
if ($LASTEXITCODE -ne 0) { throw 'client-acceptance boundary compilation failed' }
& java -cp "$base/classes;$base/stubs;review/music-0/classes;review/lib/*" com.tsvoice.ClientAcceptanceBoundaryTest
if ($LASTEXITCODE -ne 0) { throw 'client-acceptance boundary regression failed' }
exit $LASTEXITCODE
