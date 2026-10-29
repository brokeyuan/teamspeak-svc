$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p1-transport'
$core = Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol' -Filter '*.java' | Select-Object -ExpandProperty FullName
# E4/E6: protocol subpackages (relay wire, music engine) are compiled deps now
$core += Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol/relay' -Filter '*.java' | Select-Object -ExpandProperty FullName
$core += Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol/music' -Filter '*.java' | Select-Object -ExpandProperty FullName
$ts3voice = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @core @ts3voice `
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' `
    "$base/TransportShutdownTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Transport shutdown regression compilation failed' }
$env:TBRIDGE_TUNNEL_SECRET = '0123456789abcdef0123456789abcdef'
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.ts3voice.TransportShutdownTest
if ($LASTEXITCODE -ne 0) { throw 'Transport shutdown regression failed' }
exit $LASTEXITCODE
