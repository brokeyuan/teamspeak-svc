$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$root = (Get-Item $PSScriptRoot).Parent.Parent.FullName
$base='review/m7-unified-release'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if(!(Test-Path 'review/lib/gson.jar')){
 $jar=Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
 Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/capability/CapabilityWire.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/ServerConfig.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/CapabilityAnnouncer.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/ServerRuntime.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelayRuntime.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/OpenEstablisher.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsDatagramKind.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/server/relay/TsRelaySession.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/relay/TsRelayWire.java' `
    "$base/ServerConfigTest.java" "$base/AnnouncerTest.java"
if ($LASTEXITCODE -ne 0) { throw 'M7 regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.server.ServerConfigTest
if ($LASTEXITCODE -ne 0) { throw 'Server config regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.server.AnnouncerTest
if ($LASTEXITCODE -ne 0) { throw 'Announcer regression failed' }
# ---- Artifact checks (T14.6): entries, environment, conflict, forbidden content ----
$jar = Get-ChildItem 'teamspeak-svc/build/libs' -Filter 'teamspeak-svc-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $jar) {
    # Clean checkout: build the artifact first (gradle no-ops on an up-to-date tree)
    Write-Output 'artifact: none found - running gradlew build'
    Push-Location (Join-Path $root 'teamspeak-svc')
    try {
        $env:JAVA_HOME = (Get-ChildItem 'C:\Program Files\Java' -Directory |
            Where-Object { $_.Name -like 'jdk-*' } | Sort-Object Name -Descending |
            Select-Object -First 1).FullName
        & .\gradlew.bat build --no-daemon
        $buildExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($buildExit -ne 0) { throw 'gradlew build failed during M7 artifact preparation' }
    $jar = Get-ChildItem 'teamspeak-svc/build/libs' -Filter 'teamspeak-svc-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $jar) { throw 'gradlew build produced no jar' }
}
Write-Output ("artifact: {0} ({1} bytes)" -f $jar.Name, $jar.Length)
$hash = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash
Write-Output ("artifact sha256: {0}" -f $hash)
# jar tool from the JDK (gradle's JAVA_HOME or the standard install path)
$jarTool = $null
if ($env:JAVA_HOME) { $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe' }
if (-not $jarTool -or -not (Test-Path $jarTool)) {
    $jarTool = Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk-*' } | Sort-Object Name -Descending |
        Select-Object -First 1 | ForEach-Object { Join-Path $_.FullName 'bin\jar.exe' }
}
$entries = & $jarTool tf "$($jar.FullName)"
$mustHave = @(
  'com/tsvoice/TeamspeakSvcMod.class',
  'com/tsvoice/bootstrap/ClientEntrypoint.class',
  'com/tsvoice/bootstrap/RuntimeDependencies.class',
  'com/tsvoice/compat/svc/VoicechatPluginEntrypoint.class',
  'com/tsvoice/common/net/CapabilitiesPayloads.class',
  'com/tsvoice/server/ServerRuntime.class',
  'com/tsvoice/server/ServerConfig.class',
  'com/tsvoice/server/CapabilityAnnouncer.class',
  'com/tsvoice/server/ServerCommands.class',
  'com/tsvoice/client/TeamspeakSvcClient.class',
  'fabric.mod.json',
  'teamspeak-svc.mixins.json'
)
foreach ($e in $mustHave) {
    if ($entries -notcontains $e) { throw "artifact missing required entry: $e" }
}
Write-Output 'PASS artifact: required entries present'
$forbidden = $entries | Where-Object {
    $_ -match 'review/' -or $_ -match 'identity-signing-keypair' -or $_ -match 'stubs/'
}
if ($forbidden) { throw ("artifact contains forbidden content: {0}" -f ($forbidden -join ', ')) }
Write-Output 'PASS artifact: no review/stub/identity content'
Set-Location -LiteralPath $env:TEMP
& $jarTool xf "$($jar.FullName)" fabric.mod.json
$metadata = Get-Content fabric.mod.json -Raw | ConvertFrom-Json
Remove-Item fabric.mod.json
Set-Location -LiteralPath $root
if ($metadata.environment -ne '*') { throw ("environment must be '*' (got {0})" -f $metadata.environment) }
if ($metadata.entrypoints.main[0] -ne 'com.tsvoice.TeamspeakSvcMod') { throw 'main entrypoint must be the neutral mod class' }
if ($metadata.entrypoints.client[0] -ne 'com.tsvoice.bootstrap.ClientEntrypoint') { throw 'client entrypoint must be the bootstrap client' }
if ($metadata.entrypoints.voicechat[0] -ne 'com.tsvoice.compat.svc.VoicechatPluginEntrypoint') { throw 'voicechat entrypoint must be the facade' }
if ($metadata.depends.PSObject.Properties['voicechat']) { throw 'SVC must not be a global hard dependency anymore' }
if (-not $metadata.breaks.PSObject.Properties['voice-capabilities']) { throw 'the legacy announcer must be declared as a conflict' }
Write-Output 'PASS artifact: metadata (environment/entries/depends/breaks) correct'
& $jarTool xf "$($jar.FullName)" teamspeak-svc.mixins.json
$mixins = Get-Content teamspeak-svc.mixins.json -Raw | ConvertFrom-Json
Remove-Item teamspeak-svc.mixins.json
# T31/E1: full FQN -> JAR entry assertion (the old check verified only counts,
# which allowed the entire mixin package to be wrong in a shippable JAR).
$mixinPkg = $mixins.package
if (-not $mixinPkg) { throw 'mixin config has no package' }
$mixinPkgPath = $mixinPkg.Replace('.', '/')
# The SVC-conditional plugin must ship in the artifact (config references it).
$mixinPlugin = $mixins.plugin
if ($mixinPlugin) {
    $pluginEntry = $mixinPlugin.Replace('.', '/') + '.class'
    if ($entries -notcontains $pluginEntry) { throw "mixin plugin class missing from artifact: $pluginEntry" }
}
function Assert-MixinEntry([string]$rel, [string]$listName) {
    if (-not $rel) { throw "empty mixin name in $listName" }
    $fqn = "$mixinPkgPath/$rel.Replace('.', '/')"
    $fqn = "$mixinPkgPath/" + $rel.Replace('.', '/') + '.class'
    if ($entries -notcontains $fqn) { throw "mixin class missing from artifact: $fqn (declared in $listName)" }
}
foreach ($m in $mixins.client) { Assert-MixinEntry $m 'client' }
foreach ($m in $mixins.mixins) { Assert-MixinEntry $m 'mixins(common)' }
if ($mixins.server) { foreach ($m in $mixins.server) { Assert-MixinEntry $m 'server' } }
Write-Output ("PASS artifact: every configured mixin class exists in JAR (package={0}, client={1}, common={2}, plugin={3})" -f `
    $mixinPkg, @($mixins.client).Count, @($mixins.mixins).Count, $mixinPlugin)
# T31/E1: the common list must contain only the intentionally SVC-conditional
# server mixin (targets SVC-internal Server.class; gated by TsVoiceMixinPlugin).
$allowedCommon = @('server.SvcServerSendPacketMixin')
$unexpected = @($mixins.mixins | Where-Object { $_ -notin $allowedCommon })
if ($unexpected.Count -ne 0) { throw ('unexpected common mixins: {0}' -f ($unexpected -join ',')) }
if ($mixins.client.Count -lt 7) { throw ("client mixins must be environment-scoped (got {0})" -f $mixins.client.Count) }
Write-Output 'PASS artifact: mixin config is client-scoped with SVC-gated common mixin'
Write-Output ("M7-ARTIFACT-CHECK RESULT: PASS ({0})" -f $hash)
exit $LASTEXITCODE
