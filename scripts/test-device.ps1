param(
    [ValidateSet('smoke', 'edges', 'inline', 'settings', 'home', 'all')][string]$Suite = 'all',
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [switch]$AllowTestDataChanges
)
$ErrorActionPreference = 'Stop'
[string[]]$arguments = @((Join-Path $PSScriptRoot 'device_regression.py'), '--suite', $Suite, '--serial', $Serial)
if ($AllowTestDataChanges) { $arguments += '--allow-test-data-changes' }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Device regression failed; inspect the reported results and screenshots.' }
