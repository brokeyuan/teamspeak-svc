$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p1-playback'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if (!(Test-Path 'review/lib/gson.jar')) {
    $jar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
# Reuse the client state stub set (checked in; regenerated identically by
# review/client-fixes/RunTests.ps1).
Copy-Item -Path 'review/client-fixes/stubs/*' -Destination "$base/stubs/" -Recurse -Force
$protocol = Get-ChildItem -LiteralPath 'teamspeak-svc/src/main/java/com/tsvoice/client/ts3voice' -Filter '*.java' |
    Where-Object { $_.Name -notin @('Ts3VoiceSelfTest.java', 'Ts3ChannelTest.java') } | Select-Object -ExpandProperty FullName

if (Test-Path "$base/oldsrc/OldBehaviorProbe.java") {
    # Failing-first evidence: compile the pre-change sources and show the old
    # authority-less UUID scheme and unconditional channel close.
    $oldSources = Get-ChildItem -LiteralPath "$base/oldsrc" -Filter '*.java' | Select-Object -ExpandProperty FullName
    & javac -encoding UTF-8 -cp 'review/lib/*;ts3voice-core/build/classes/java/main' -d "$base/oldclasses" @protocol `
        'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' `
        @oldSources `
        (Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Old-behavior probe compilation failed' }
    & java -cp "$base/oldclasses;review/lib/*;ts3voice-core/build/classes/java/main" com.tsvoice.client.OldBehaviorProbe
    # Probe exits 0 when it observed the old defects; 2 means inconclusive.
    if ($LASTEXITCODE -ne 0) { throw 'Old behavior probe inconclusive' }
    Write-Output 'old-behavior probe reproduced both defects (expected failures)'
}

& javac -encoding UTF-8 -cp 'review/lib/*;ts3voice-core/build/classes/java/main' -d "$base/classes" @protocol `
    'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransfer.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/Ts3FileTransferBridge.java' 'teamspeak-svc/src/main/java/com/tsvoice/client/ConfigFileUtil.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/BackendCoordinator.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/TsPlaybackRegistry.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/ClientStateReflector.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/TsAudioInjector.java' `
    (Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName) `
    "$base/StableParticipantVolumeTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Stable participant volume regression compilation failed' }
& java -cp "$base/classes;review/lib/*;ts3voice-core/build/classes/java/main" com.tsvoice.client.StableParticipantVolumeTest
if ($LASTEXITCODE -ne 0) { throw 'Stable participant volume regression failed' }
exit $LASTEXITCODE
