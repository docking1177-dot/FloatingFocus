param(
    [ValidateSet('Unit', 'Full', 'Emulator')][string]$Mode = 'Full',
    [ValidateSet('Debug', 'Release')][string]$Variant = 'Debug',
    [switch]$Offline,
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [ValidateRange(30, 600)][int]$BootTimeoutSeconds = 180
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'check-common.ps1')
if ($Mode -eq 'Emulator' -and $Variant -ne 'Debug') { throw 'Emulator mode requires a signed Debug APK.' }
$outputRoot = Join-Path $projectRoot '.tools/checks'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$logPath = Join-Path $outputRoot "check-$($Mode.ToLowerInvariant())-$($Variant.ToLowerInvariant())-$stamp.log"
$runSummaryPath = [IO.Path]::ChangeExtension($logPath, '.json')
$summaryPath = Join-Path $outputRoot 'latest.json'
$watch = [Diagnostics.Stopwatch]::StartNew()
$summary = [ordered]@{ schemaVersion = 1; mode = $Mode; variant = $Variant; offline = [bool]$Offline; startedUtc = [DateTime]::UtcNow.ToString('o'); status = 'running'; tests = 0; failures = 0; errors = 0; skipped = 0; log = $logPath }

function Invoke-Adb([string[]]$AdbArguments) {
    $result = & $script:adbExe -s $Serial @AdbArguments 2>&1
    $code = $LASTEXITCODE
    $result | Out-File -LiteralPath $logPath -Append -Encoding utf8
    if ($code -ne 0) { throw "ADB failed ($code): $($AdbArguments -join ' ')" }
    return ($result -join "`n")
}

try {
    Push-Location -LiteralPath $projectRoot
    try {
        $identity = Get-CheckSource
        $summary['source'] = $identity.source
        $summary['localBuildInputs'] = $identity.localBuildInputs
        [string[]]$tasks = @(Get-CheckTasks -Mode $Mode -Variant $Variant -Offline:$Offline -ProjectRoot $projectRoot)
        $summary['tasks'] = $tasks
        Write-Output "Checking $Mode; full output: $logPath"
        & (Join-Path $PSScriptRoot 'build-local.ps1') -Tasks $tasks *> $logPath
        $reports = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "app/build/test-results/test${Variant}UnitTest") -Filter 'TEST-*.xml')
        if ($reports.Count -eq 0) { throw 'No JUnit reports found.' }
        foreach ($report in $reports) {
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
            foreach ($field in @('tests', 'failures', 'errors', 'skipped')) {
                $summary[$field] += [int]$xml.testsuite.GetAttribute($field)
            }
        }
        if ($summary.failures -gt 0 -or $summary.errors -gt 0) { throw 'JUnit failures found.' }
        if ($Mode -ne 'Unit') {
            [xml]$lint = Get-Content -LiteralPath (Join-Path $projectRoot "app/build/reports/lint-results-$($Variant.ToLowerInvariant()).xml") -Raw
            $issues = @($lint.issues.issue | Where-Object { $null -ne $_ })
            $summary['lintErrors'] = @($issues | Where-Object { $_.severity -in @('Error', 'Fatal') }).Count
            $summary['lintWarnings'] = @($issues | Where-Object { $_.severity -eq 'Warning' }).Count
            $summary['lintHints'] = @($issues | Where-Object { $_.severity -in @('Hint', 'Information', 'Informational') }).Count
            if ($summary.lintErrors -gt 0) { throw 'Lint errors found.' }
            $apkFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "app/build/outputs/apk/$($Variant.ToLowerInvariant())") -Filter '*.apk')
            if ($apkFiles.Count -ne 1) { throw 'Expected exactly one APK output.' }
            $summary['apk'] = @{ path = $apkFiles[0].FullName; sha256 = (Get-FileHash -LiteralPath $apkFiles[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        }
        if ($Mode -eq 'Emulator') {
            $script:adbExe = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'
            if (-not (Test-Path -LiteralPath $script:adbExe)) { throw 'Local ADB missing; see docs/EMULATOR.md.' }
            if ($Serial -eq 'emulator-5554') {
                & (Join-Path $PSScriptRoot 'start-emulator.ps1') *>> $logPath
            }
            $deadline = [DateTime]::UtcNow.AddSeconds($BootTimeoutSeconds)
            $booted = $false
            Write-Output "Waiting for $Serial boot (up to $BootTimeoutSeconds seconds)..."
            do {
                $state = & $script:adbExe -s $Serial shell getprop sys.boot_completed 2>&1
                if ($LASTEXITCODE -eq 0 -and ($state -join '').Trim() -eq '1') { $booted = $true; break }
                Start-Sleep -Seconds 2
            } while ([DateTime]::UtcNow -lt $deadline)
            if (-not $booted) { throw "Emulator boot timeout: $Serial" }
            $apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
            $install = Invoke-Adb @('install', '-r', $apk)
            if ($install -notmatch '(?m)^Success\s*$') { throw 'ADB did not report a successful installation.' }
            $launch = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', 'app.floatingfocus/.MainActivity')
            if ($launch -notmatch '(?m)^Status:\s*ok\s*$' -or $launch -match '(?m)^Error:') { throw 'Activity launch failed.' }
            $summary['device'] = $Serial
            $summary['deviceCheck'] = 'installed and launched; UI regression not automated'
        }
        $after = Get-CheckSource
        $summary['sourceAfter'] = $after.source
        if ($identity.source.commit -ne $after.source.commit -or $identity.source.fingerprint -ne $after.source.fingerprint -or
            ($identity.localBuildInputs | ConvertTo-Json -Compress) -ne ($after.localBuildInputs | ConvertTo-Json -Compress)) {
            throw 'Source or local build inputs changed during checks; rerun after edits finish.'
        }
        $summary.status = 'passed'
        Write-Output "PASS | $($summary.tests) tests | $($summary.failures) failures | $($summary.errors) errors"
        if ($Mode -ne 'Unit') { Write-Output "Lint | $($summary.lintErrors) errors | $($summary.lintWarnings) warnings | $($summary.lintHints) hints" }
        if ($Mode -eq 'Emulator') { Write-Output 'Emulator | installed and launched; perform relevant UI regression separately.' }
    } finally { Pop-Location }
} catch {
    $summary.status = 'failed'
    $summary['error'] = $_.Exception.Message
    Write-Output "FAIL | $($_.Exception.Message)"
    if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Tail 25 | Write-Output }
} finally {
    $watch.Stop()
    $summary['elapsedSeconds'] = [Math]::Round($watch.Elapsed.TotalSeconds, 1)
    $summary['finishedUtc'] = [DateTime]::UtcNow.ToString('o')
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $runSummaryPath -Encoding utf8
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    Write-Output "Summary: $runSummaryPath"
    Write-Output "Latest copy: $summaryPath"
}
if ($summary.status -ne 'passed') { exit 1 }
