$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base='review/standalone-frontend'
New-Item -ItemType Directory -Path 'review/lib' -Force | Out-Null
if(!(Test-Path 'review/lib/gson.jar')){
 $jar=Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -Recurse -Filter '*.jar' | Select-Object -First 1
 Copy-Item -LiteralPath $jar.FullName -Destination 'review/lib/gson.jar'
}
$client = 'teamspeak-svc/src/main/java/com/tsvoice/client'
$prod = @("$client/frontend/SvcFrontendAdapter.java", "$client/frontend/FrontendCapabilities.java",
    "$client/frontend/FrontendLease.java", "$client/frontend/Svc2625FrontendAdapter.java",
    "$client/connection/VoiceState.java", "$client/connection/BackendCoordinator.java",
    "$client/connection/SelectionPolicy.java") |
    Where-Object { Test-Path $_ }
$extra = @(
    'review/standalone-frontend/stubs/com/tsvoice/client/TeamspeakSvcClient.java',
    'review/standalone-frontend/stubs/com/tsvoice/client/ts3voice/Ts3VoiceClient.java'
)
$svcStubs = @(Get-ChildItem -LiteralPath "$base/stubs" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName) + $extra
& javac -encoding UTF-8 -cp 'review/lib/*' -d "$base/classes" @prod $svcStubs `
    "$base/FrontendLeaseTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Standalone frontend regression compilation failed' }
& java -cp "$base/classes;review/lib/*" com.tsvoice.client.frontend.FrontendLeaseTest
if ($LASTEXITCODE -ne 0) { throw 'Standalone frontend regression failed' }
exit $LASTEXITCODE
