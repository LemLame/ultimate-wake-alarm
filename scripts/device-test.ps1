param(
    [string]$Serial = 'emulator-5580',
    [switch]$Realtime,
    [switch]$KeepState
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This destructive test suite is restricted to an isolated emulator.' }
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'app\build\offline'
function Invoke-Adb([string[]]$Arguments) {
    & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $Arguments" }
}
Invoke-Adb @('install','--no-incremental','-r',(Join-Path $out 'ultimate-wake-alarm-debug.apk'))
Invoke-Adb @('install','--no-incremental','-r',(Join-Path $out 'ultimate-wake-alarm-device-tests.apk'))
foreach ($permission in @('READ_CALENDAR','WRITE_CALENDAR','POST_NOTIFICATIONS')) {
    Invoke-Adb @('shell','pm','grant','jp.waketerminal',"android.permission.$permission")
}
Invoke-Adb @('shell','appops','set','jp.waketerminal','USE_FULL_SCREEN_INTENT','allow')
Invoke-Adb @('shell','cmd','notification','allow_dnd','jp.waketerminal')
$instrument = @('shell','am','instrument','-w')
if ($Realtime) { $instrument += @('-e','realtime','true') }
if ($KeepState) { $instrument += @('-e','keepState','true') }
$instrument += 'jp.waketerminal.test/jp.waketerminal.DeviceTests'
# am instrument can exit 0 on a failed test; validate the actual instrumentation output.
$result = & adb -s $Serial @instrument 2>&1
$result | Write-Output
if ($LASTEXITCODE -ne 0 -or -not ($result -match 'PASS: \d+ device checks') -or ($result -match 'FAIL|INSTRUMENTATION_FAILED')) {
    throw 'Device tests failed; inspect the instrumentation output.'
}
