$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/ts-mc-relay'
New-Item -ItemType Directory -Path "$base/classes" -Force | Out-Null
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelayRuntime.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelaySession.java' `
    "$base/RelayRuntimeTest.java"
if ($LASTEXITCODE -ne 0) { throw 'relay regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.RelayRuntimeTest
if ($LASTEXITCODE -ne 0) { throw 'relay runtime regression failed' }
exit $LASTEXITCODE
