$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/music-0'
New-Item -ItemType Directory -Path "$base/classes" -Force | Out-Null
& javac -encoding UTF-8 -d "$base/classes" `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/PcmSource.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/TestToneSource.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/WavPcmSource.java' `
    'ts3voice-core/src/main/java/com/tsvoice/protocol/music/MusicEngine.java' `
    "$base/MusicEngineTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Music-0 regression compilation failed' }
& java -cp "$base/classes" com.tsvoice.MusicEngineTest
if ($LASTEXITCODE -ne 0) { throw 'Music-0 regression failed' }
exit $LASTEXITCODE
