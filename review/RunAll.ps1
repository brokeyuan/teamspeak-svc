# One-command CI-equivalent verification (N0 rework, 2026-10-29).
#
# Ordering rules (do not shuffle back):
#   1. ts3voice-core clean test runs FIRST. Five harness suites below link
#      against ts3voice-core/build/classes/java/main, so a clean checkout must
#      compile core before those suites can run; "clean" also guarantees the
#      test run is not judged by stale build output.
#   2. Every harness suite writes its own log pair under
#      $env:TEMP\tsvoice-ci-logs\ - a later suite can never overwrite an
#      earlier suite's evidence.
#   3. Any failure -> nonzero process exit. The raw output of this script is
#      delivery evidence; per-suite full logs live in the log directory.
# The script writes nothing into the workspace tree (git status stays clean).
$ErrorActionPreference = 'Continue'
# Derive the workspace root from $PSScriptRoot (review/ -> workspace root).
# Set-Location on a path containing '&' can fail silently under some launchers,
# so never trust Get-Location here.
$root = (Get-Item $PSScriptRoot).Parent.FullName
Set-Location -LiteralPath $root

# Gradle needs JAVA_HOME; CI hosts may only have java on PATH. Probe the
# standard JDK install locations once instead of failing mid-run.
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk-25*' } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
Write-Output ("RUNALL env: JAVA_HOME={0}" -f $env:JAVA_HOME)

$logDir = Join-Path $env:TEMP 'tsvoice-ci-logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$failed = @()

# ---- Phase 1: ts3voice-core clean test (also builds the classes 5 harnesses link against)
$coreLog = Join-Path $logDir 'ts3voice-core-clean-test.log'
$coreErr = Join-Path $logDir 'ts3voice-core-clean-test.err.log'
$coreProc = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
    -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
        "Set-Location -LiteralPath '$root\ts3voice-core'; & '$root\teamspeak-svc\gradlew.bat' clean test --no-daemon *>> '$coreLog' 2>> '$coreErr'; exit `$LASTEXITCODE") `
    -Wait -PassThru -WindowStyle Hidden
if ($coreProc.ExitCode -eq 0) {
    Write-Output 'PASS ts3voice-core clean test (gradle)'
} else {
    Write-Output "FAIL ts3voice-core clean test (exit $($coreProc.ExitCode))"
    $failed += 'ts3voice-core clean test'
    Write-Output "--- last 12 stdout lines ($coreLog) ---"
    Get-Content $coreLog -Tail 12 -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "  $_" }
    Write-Output "--- last 6 stderr lines ($coreErr) ---"
    Get-Content $coreErr -Tail 6 -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "  $_" }
}

# ---- Phase 2: harness suites (23 = 20 previous + upload-confirmation + gui-integration + m7)
$suites = @(
    'review/channel-fixes/RunProtocol.ps1',
    'review/channel-fixes/RunMapper.ps1',
    'review/client-fixes/RunTests.ps1',
    'review/client-fixes/RunManagerTests.ps1',
    'review/p0-defects/RunMicRedirector.ps1',
    'review/p0-defects/RunClientIdentity.ps1',
    'review/p0-defects/RunBridgeMembership.ps1',
    'review/p0-defects/RunIdentityIssuing.ps1',
    'review/p1-isolation/RunSessionIsolation.ps1',
    'review/p1-transport/RunTransportShutdown.ps1',
    'review/p1-playback/RunStableVolume.ps1',
    'review/p2-frontend/RunFrontend.ps1',
    'review/p3-config/RunBookmarkConfig.ps1',
    'review/route-lifecycle/RunRouteLifecycle.ps1',
    'review/session-consumption/RunSessionConsumption.ps1',
    'review/playback-ownership/RunPlaybackOwnership.ps1',
    'review/standalone-frontend/RunStandaloneFrontend.ps1',
    'review/p3-channel/RunCommandTracker.ps1',
    'review/p4-discovery/RunFallbackPolicy.ps1',
    'review/server-fixes/RunServerChecks.ps1',
    'review/upload-confirmation/RunUploadConfirmation.ps1',
    'review/gui-integration/RunGuiIntegration.ps1',
    'review/ts-mc-relay/RunTsMcRelay.ps1',
    'review/svc-mc-channel/RunSvcMcChannel.ps1',
    'review/music-0/RunMusicCore.ps1',
    'review/m7-unified-release/RunM7.ps1'
)
foreach ($suite in $suites) {
    $name = ($suite -replace '^review/', '' -replace '/Run', '__' -replace '\.ps1$', '')
    $out = Join-Path $logDir "$name.log"
    $err = Join-Path $logDir "$name.err.log"
    $proc = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root $suite)) `
        -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
    if ($proc.ExitCode -eq 0) {
        Write-Output "PASS $suite"
    } else {
        Write-Output "FAIL $suite (exit $($proc.ExitCode))"
        $failed += $suite
        Write-Output "--- last 12 stdout lines ($out) ---"
        Get-Content $out -Tail 12 -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "  $_" }
        Write-Output "--- last 6 stderr lines ($err) ---"
        Get-Content $err -Tail 6 -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "  $_" }
    }
}

if ($failed.Count -gt 0) {
    Write-Output ("CI-EQUIVALENT RESULT: FAIL ({0} failed: {1})" -f $failed.Count, ($failed -join ', '))
    Write-Output ("Full per-suite logs: {0}" -f $logDir)
    exit 1
}
Write-Output ('CI-EQUIVALENT RESULT: PASS (core test + all 23 harness suites green)')
exit 0
