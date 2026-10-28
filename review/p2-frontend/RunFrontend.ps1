$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p2-frontend'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if (!(Test-Path 'review/lib/gson.jar')) {
    $jar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" `
    'review/p2-frontend/stubs/com/tsvoice/client/ts3voice/Ts3VoiceClient.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/VoiceState.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/connection/SelectionPolicy.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendCapabilities.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/SvcFrontendAdapter.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/FrontendLease.java' `
    'teamspeak-svc/src/main/java/com/tsvoice/client/frontend/Svc2625FrontendAdapter.java' `
    (Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName) `
    "$base/FrontendOwnershipTest.java" `
    "$base/FrontendCompatibilityTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Frontend regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.frontend.FrontendOwnershipTest
if ($LASTEXITCODE -ne 0) { throw 'Frontend ownership regression failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.frontend.FrontendCompatibilityTest
if ($LASTEXITCODE -ne 0) { throw 'Frontend compatibility regression failed' }
exit $LASTEXITCODE
