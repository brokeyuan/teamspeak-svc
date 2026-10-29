$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$base = 'review/p0-defects'
& javac -encoding UTF-8 -d "$base/classes" `
    'teamspeak&svcbridge/src/main/java/com/svcb/tsbridge/svc/BridgeMembershipService.java' `
    "$base/BridgeMembershipRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Bridge membership regression compilation failed' }
& java -cp "$base/classes" com.svcb.tsbridge.svc.BridgeMembershipRegression
if ($LASTEXITCODE -ne 0) { throw 'Bridge membership regression failed' }
