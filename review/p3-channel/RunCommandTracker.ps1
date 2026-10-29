$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p3-channel'
$core = Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol' -Filter '*.java' | Select-Object -ExpandProperty FullName
$relay = Get-ChildItem -LiteralPath 'ts3voice-core/src/main/java/com/tsvoice/protocol/relay' -Filter '*.java' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$ts3voice = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } | Select-Object -ExpandProperty FullName
New-Item -ItemType Directory -Path "$base/stubs/com/tsvoice/client" -Force | Out-Null
[IO.File]::WriteAllText((Join-Path (Get-Location) "$base/stubs/com/tsvoice/client/TeamspeakSvcClient.java"), @'
package com.tsvoice.client;
public class TeamspeakSvcClient {
 public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("p3-channel");
}
'@)
$stubSources = Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$allSources = @($core) + @($relay) + @($ts3voice) + @($stubSources) + @(
    'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java',
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java',
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java',
    "$base/CommandTrackerTest.java",
    "$base/FileTransferRegression.java")
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @allSources
if ($LASTEXITCODE -ne 0) { throw 'Command tracker regression compilation failed' }
$env:TBRIDGE_TUNNEL_SECRET = '0123456789abcdef0123456789abcdef'
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.ts3voice.CommandTrackerTest
if ($LASTEXITCODE -ne 0) { throw 'Command tracker regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.FileTransferRegression
if ($LASTEXITCODE -ne 0) { throw 'File transfer regression failed' }
exit $LASTEXITCODE
