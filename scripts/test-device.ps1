param(
    [ValidateSet('smoke', 'edges', 'inline', 'settings', 'home', 'home_unified', 'home_reorder', 'home_swipe', 'overlay_stability', 'overlay_rotation', 'timer_lifecycle', 'stopwatch_reminders', 'stopwatch_hours', 'interactions', 'interaction_c4', 'interaction_theme', 'screen_on', 'overtime', 'overtime_reminders', 'overtime_reminders_lifecycle', 'overtime_preview', 'tile', 'preferences', 'startup', 'theme', 'c4', 'c4_keypad', 'c4_compact', 'onboarding', 'developer', 'history', 'preset_styles', 'preset_shortcuts', 'preset_c4', 'preset_c4_extensions', 'widgets', 'quota', 'all')][string]$Suite = 'all',
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [switch]$AllowTestDataChanges
)
$ErrorActionPreference = 'Stop'
[string[]]$arguments = @((Join-Path $PSScriptRoot 'device_regression.py'), '--suite', $Suite, '--serial', $Serial)
if ($AllowTestDataChanges) { $arguments += '--allow-test-data-changes' }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Device regression failed; inspect the reported results and screenshots.' }
