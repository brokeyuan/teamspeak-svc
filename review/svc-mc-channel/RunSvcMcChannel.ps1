$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/svc-mc-channel'
New-Item -ItemType Directory -Path "$base/classes" -Force | Out-Null
# Local MC/Fabric stubs (checked in; SvcServerBridge's MinecraftServer param).
$mcStubs = Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp "$base/stubs;review/lib/*" -d "$base/classes" `
    'teamspeak-svc/src/main/java/com/tsvoice/common/net/SvcMcChannelPayloads.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/svc/SvcMcChannelAdapter.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/svc/SvcServerBridge.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/backend/SvcMcChannelSocket.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/backend/SvcMcChannelClient.java' `
    "$base/SvcMcChannelTest.java" "$base/SvcMcChannelLoopbackTest.java" $mcStubs
if ($LASTEXITCODE -ne 0) { throw 'SVC MC channel regression compilation failed' }
& java -cp "$base/classes;$base/stubs;review/lib/*" com.tsvoice.SvcMcChannelTest
if ($LASTEXITCODE -ne 0) { throw 'SVC MC channel regression failed' }
# E2/T36: native UDP protection + client wiring + connection-scoped registry
& java -cp "$base/classes;$base/stubs;review/lib/*" com.tsvoice.SvcMcChannelLoopbackTest
if ($LASTEXITCODE -ne 0) { throw 'SVC MC channel loopback/UDP-protection regression failed' }
exit $LASTEXITCODE
