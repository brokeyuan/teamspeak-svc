$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p3-channel'
$core = Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol' -Filter '*.java' | Select-Object -ExpandProperty FullName
$ts3voice = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @core @ts3voice `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' `
    "$base/CommandTrackerTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Command tracker regression compilation failed' }
$env:TBRIDGE_TUNNEL_SECRET = '0123456789abcdef0123456789abcdef'
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.ts3voice.CommandTrackerTest
if ($LASTEXITCODE -ne 0) { throw 'Command tracker regression failed' }
exit $LASTEXITCODE
