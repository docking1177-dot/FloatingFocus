param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
    $bundledJbr = 'C:\Program Files\JetBrains\PyCharm 2025.3.3\jbr'
    if (Test-Path "$bundledJbr/bin/javac.exe") { $env:JAVA_HOME = $bundledJbr }
    else { throw 'Set JAVA_HOME to a JDK 17 or 21 installation.' }
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
$debugKey = Join-Path $projectRoot '.tools/debug.keystore'
if (-not (Test-Path -LiteralPath $debugKey)) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $debugKey -alias androiddebugkey -storepass android -keypass android -dname 'CN=Android Debug,O=Floating Focus,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the local debug keystore.' }
}
# Respect the existing network configuration without changing user/system proxies.
if ($env:HTTPS_PROXY) {
    $buildProxy = [Uri]$env:HTTPS_PROXY
    $env:GRADLE_OPTS = "-Dhttps.proxyHost=$($buildProxy.Host) -Dhttps.proxyPort=$($buildProxy.Port) -Dhttp.proxyHost=$($buildProxy.Host) -Dhttp.proxyPort=$($buildProxy.Port)"
}
& ./gradlew.bat @Tasks '-PworkspaceDebugKeystore=.tools/debug.keystore' --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
