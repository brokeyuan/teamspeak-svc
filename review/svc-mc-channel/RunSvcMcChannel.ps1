$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/svc-mc-channel'
New-Item -ItemType Directory -Path "$base/classes" -Force | Out-Null
# Local MC/Fabric stubs (checked in; SvcServerBridge's MinecraftServer param).
$mcStubs = Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" `
    'teamspeak-svc/src/main/java/com/tsvoice/common/net/SvcMcChannelPayloads.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/svc/SvcMcChannelAdapter.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/svc/SvcServerBridge.java' `
    "$base/SvcMcChannelTest.java" $mcStubs
if ($LASTEXITCODE -ne 0) { throw 'SVC MC channel regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.SvcMcChannelTest
if ($LASTEXITCODE -ne 0) { throw 'SVC MC channel regression failed' }
exit $LASTEXITCODE
