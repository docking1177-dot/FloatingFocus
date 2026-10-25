param(
    [ValidateSet('prepare', 'draft', 'publish', 'verify')][string]$Action = 'prepare',
    [string]$DebugCheck, [string]$ReleaseCheck, [string]$DeviceReport
)
$ErrorActionPreference = 'Stop'
[string[]]$arguments = @((Join-Path $PSScriptRoot 'release.py'), $Action)
if ($DebugCheck) { $arguments += @('--debug-check', $DebugCheck) }
if ($ReleaseCheck) { $arguments += @('--release-check', $ReleaseCheck) }
if ($DeviceReport) { $arguments += @('--device-report', $DeviceReport) }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw "Release $Action failed; fix the reported gate and rerun the same stage." }
