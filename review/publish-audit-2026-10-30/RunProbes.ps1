$ErrorActionPreference = 'Stop'
$root = (Get-Item $PSScriptRoot).Parent.Parent.FullName
Set-Location -LiteralPath $root
$classes = Join-Path $env:TEMP ('tsvoice-publish-probes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes | Out-Null
$client = 'teamspeak-svc/src/main/java/com/tsvoice/client'
$base = 'review/publish-audit-2026-10-30'
# Real connection/crypt/transport; only the upper client callback surface is stubbed.
& javac -encoding UTF-8 -cp 'review/lib/*' -sourcepath 'ts3voice-core/src/main/java' -d $classes `
    "$client/ts3voice/Ts3Connection.java" "$client/ts3voice/Ts3Crypt.java" "$client/ts3voice/Ts3Packet.java" `
    "$client/ts3voice/TsTransport.java" "$client/ts3voice/TcpTransport.java" "$client/ts3voice/Ts3Identity.java" `
    "$client/ts3voice/Ed25519Math.java" "$client/ts3voice/PacketType.java" `
    "$client/ts3voice/Ts3VoiceException.java" "$client/ConfigFileUtil.java" `
    'review/route-lifecycle/conn-stubs/com/tsvoice/client/ts3voice/Ts3VoiceClient.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelayRuntime.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelaySession.java' `
    "$client/ts3voice/TsMcChannelTransport.java" "$base/MusicWriterProbe.java" "$base/RelayControlProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'probe compilation failed' }
& java -cp "$classes;review/lib/*" com.tsvoice.client.MusicWriterProbe
if ($LASTEXITCODE -ne 0) { throw 'music reproduction failed' }
& java -cp "$classes;review/lib/*" com.tsvoice.RelayControlProbe
if ($LASTEXITCODE -ne 0) { throw 'relay reproduction failed' }
Write-Output 'Both defects reproduced. Exit 0 means reproduction succeeded, NOT product acceptance.'
