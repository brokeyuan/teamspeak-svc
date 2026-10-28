$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/playback-ownership'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if (!(Test-Path 'review/lib/gson.jar')) {
    $jar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
# Reuse the client state stub set (checked in; regenerated identically by
# review/client-fixes/RunTests.ps1).
New-Item -ItemType Directory -Path "$base/stubs" -Force | Out-Null
Copy-Item -Path 'review/client-fixes/stubs/*' -Destination "$base/stubs/" -Recurse -Force
$protocol = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } | Select-Object -ExpandProperty FullName

# The stream registry is part of the fix; tolerate its absence when proving the
# old code fails (the O5 checks skip via reflection in that case).
$prodExtra = @()
if (Test-Path 'teamspeak-svc/src/main/java/com/tsvoice/client/TsPlaybackRegistry.java') {
    $prodExtra += 'teamspeak-svc/src/main/java/com/tsvoice/client/TsPlaybackRegistry.java'
}

& javac -encoding UTF-8 -cp 'review/lib/*;ts3voice-core/build/classes/java/main' -d "$base/classes" @protocol `
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/SvcFrontendAdapter.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendCapabilities.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendLease.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/Svc2625FrontendAdapter.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ClientStateReflector.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/TsAudioInjector.java' `
    $prodExtra `
    (Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName) `
    "$base/PlaybackOwnershipTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Playback ownership regression compilation failed' }
& java -cp "$base/classes;review/lib/*;ts3voice-core/build/classes/java/main" com.tsvoice.client.PlaybackOwnershipTest
if ($LASTEXITCODE -ne 0) { throw 'Playback ownership regression failed' }
exit $LASTEXITCODE
