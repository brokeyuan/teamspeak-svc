$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p4-discovery'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if (!(Test-Path 'review/lib/gson.jar')) {
    $jar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$allSources = @(
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java',
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java',
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/CapabilityDiscovery.java',
    'ts3voice-core/src/main/java/com/tsvoice/protocol/capability/CapabilityWire.java',
    "$base/FallbackPolicyTest.java",
    "$base/DiscoveryFlowTest.java")
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @allSources
if ($LASTEXITCODE -ne 0) { throw 'Discovery regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.connection.FallbackPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Fallback policy regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.connection.DiscoveryFlowTest
if ($LASTEXITCODE -ne 0) { throw 'Discovery flow regression failed' }
exit $LASTEXITCODE
