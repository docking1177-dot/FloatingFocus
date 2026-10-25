function Get-CheckTasks {
    param([string]$Mode, [string]$Variant = 'Debug', [switch]$Offline, [string]$ProjectRoot)
    [string[]]$result = if ($Mode -eq 'Unit') { @("test${Variant}UnitTest") } else {
        @("assemble$Variant", "test${Variant}UnitTest", "lint$Variant")
    }
    if ($Offline) {
        $result += '--offline'
        $bridge = Join-Path $ProjectRoot '.tools/maven-bridge.init.gradle'
        if (Test-Path -LiteralPath $bridge) { $result += @('-I', $bridge) }
    }
    return $result
}

function Get-CheckSource {
    $json = & python (Join-Path $PSScriptRoot 'source_state.py')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot capture source identity.' }
    return ($json | ConvertFrom-Json)
}
