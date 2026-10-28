# One-command CI-equivalent verification: runs every harness regression and
# the core test suite, prints a per-suite PASS/FAIL summary, and exits nonzero
# if any suite fails. Paste the raw output as delivery evidence (no hand-copied
# claims).
$ErrorActionPreference = 'Continue'
# Derive the workspace root from $PSScriptRoot (review/ -> workspace root).
# Set-Location on a path containing '&' can fail silently under some launchers,
# so never trust Get-Location here.
$root = (Get-Item $PSScriptRoot).Parent.FullName
Set-Location -LiteralPath $root
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
    'review/p3-channel/RunCommandTracker.ps1',
    'review/p4-discovery/RunFallbackPolicy.ps1',
    'review/server-fixes/RunServerChecks.ps1'
)
$failed = @()
foreach ($suite in $suites) {
    $suitePath = Join-Path $root $suite
    $proc = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $suitePath) -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $env:TEMP\tsvoice-ci-last.log -RedirectStandardError $env:TEMP\tsvoice-ci-last.err.log
    if ($proc.ExitCode -eq 0) {
        Write-Output "PASS $suite"
    } else {
        Write-Output "FAIL $suite (exit $($proc.ExitCode))"
        $failed += $suite
        Get-Content $env:TEMP\tsvoice-ci-last.log -Tail 8 | ForEach-Object { Write-Output "  $_" }
        Get-Content $env:TEMP\tsvoice-ci-last.err.log -Tail 4 | ForEach-Object { Write-Output "  $_" }
    }
}
$coreLog = Join-Path $env:TEMP 'tsvoice-ci-core.log'
$marker = Join-Path $env:TEMP 'tsvoice-ci-core.marker'
Remove-Item $marker -ErrorAction SilentlyContinue
Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile', '-Command', "Set-Location '$root\ts3voice-core'; & '$root\teamspeak-svc\gradlew.bat' clean test --no-daemon *> '$coreLog'; if (`$LASTEXITCODE -eq 0) { New-Item -ItemType File -Path '$marker' | Out-Null }") -Wait -WindowStyle Hidden
if (Test-Path $marker) {
    Write-Output 'PASS core test (ts3voice-core clean test)'
} else {
    Write-Output 'FAIL core test (ts3voice-core clean test)'
    $failed += 'ts3voice-core clean test'
    Get-Content $coreLog -Tail 8 | ForEach-Object { Write-Output "  $_" }
}
if ($failed.Count -gt 0) {
    Write-Output ("CI-EQUIVALENT RESULT: FAIL ({0} failed: {1})" -f $failed.Count, ($failed -join ', '))
    exit 1
}
Write-Output 'CI-EQUIVALENT RESULT: PASS (all suites + core test green)'
exit 0
