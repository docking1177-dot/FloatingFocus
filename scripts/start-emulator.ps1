param([switch]$ShowWindow)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkRoot = Join-Path $projectRoot '.tools/android-sdk'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools/avd'
$emulatorExe = Join-Path $sdkRoot 'emulator/emulator.exe'
$adbExe = Join-Path $sdkRoot 'platform-tools/adb.exe'
$avdName = 'FloatingFocus_API_36'
if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"))) {
    throw 'AVD missing. Follow docs/EMULATOR.md to install the image and create it first.'
}
$devices = & $adbExe devices
if ($devices -match '^emulator-5554\s+device') {
    Write-Output 'Emulator already running on emulator-5554.'
    exit 0
}
if ($devices -match '^emulator-5554\s+') { throw 'Port 5554 is occupied by an offline emulator.' }
$arguments = @('-avd', $avdName, '-port', '5554', '-no-audio', '-no-boot-anim',
    '-no-snapshot', '-gpu', 'software', '-memory', '2560', '-cores', '2', '-no-metrics')
if (-not $ShowWindow) { $arguments += '-no-window' }
Start-Process -FilePath $emulatorExe -ArgumentList $arguments -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $projectRoot '.tools/emulator-stdout.log') `
    -RedirectStandardError (Join-Path $projectRoot '.tools/emulator-stderr.log') | Out-Null
Write-Output 'Starting FloatingFocus_API_36. Check adb devices and sys.boot_completed before installing.'
