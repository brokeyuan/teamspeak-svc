$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
$cacheRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$dependencies = @(
    @('org.bouncycastle/bcprov-jdk18on/1.78.1', 'bcprov-jdk18on-1.78.1.jar'),
    @('org.slf4j/slf4j-api/2.0.17', 'slf4j-api-2.0.17.jar'),
    @('de.maxhenkel.voicechat/voicechat-api/2.6.20', 'voicechat-api-2.6.20.jar')
)
foreach ($dependency in $dependencies) {
    $destination = Join-Path 'review/lib' $dependency[1]
    if (!(Test-Path -LiteralPath $destination)) {
        $source = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $dependency[0]) -Recurse -File -Filter $dependency[1] | Select-Object -First 1
        if (!$source) { throw "Missing cached dependency: $($dependency[1])" }
        Copy-Item -LiteralPath $source.FullName -Destination $destination
    }
}
$sources = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } |
    Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -cp 'review/lib/*' -d 'review/classes' @sources 'review/ProtocolAudit.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java'
if ($LASTEXITCODE -ne 0) { throw 'Protocol compilation failed' }
& java -cp 'review/classes;review/lib/*' com.tsvoice.client.ts3voice.ProtocolAudit
if ($LASTEXITCODE -ne 0) { throw 'Protocol probe failed' }
& "$PSScriptRoot/RunLifecycleChecks.ps1"
& javac -encoding UTF-8 -cp 'review/lib/*' -d 'review/classes' 'review/runtime-stubs/com/svcb/tsbridge/TsBridgeMod.java' 'teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/net/TcpTunnelServer.java' 'review/TunnelAudit.java'
if ($LASTEXITCODE -ne 0) { throw 'Tunnel compilation failed' }
& java -cp 'review/classes;review/lib/*' TunnelAudit
if ($LASTEXITCODE -ne 0) { throw 'Tunnel probe failed' }
