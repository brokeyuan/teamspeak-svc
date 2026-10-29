$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$sources = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*;ts3voice-core/build/classes/java/main' -d 'review/channel-fixes/protocol-classes' @sources 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' 'review/channel-fixes/ProtocolRegression.java' 'review/channel-fixes/RsaRegression.java'
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& java -cp 'review/channel-fixes/protocol-classes;review/lib/*;ts3voice-core/build/classes/java/main' com.tsvoice.client.ts3voice.ProtocolRegression
if($LASTEXITCODE -ne 0){throw 'Protocol regression failed'}
& java -cp 'review/channel-fixes/protocol-classes;review/lib/*;ts3voice-core/build/classes/java/main' com.tsvoice.client.ts3voice.RsaRegression
if ($LASTEXITCODE -ne 0) { throw 'Protocol regression failed' }
