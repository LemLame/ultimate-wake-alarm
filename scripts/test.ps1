param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'app\build\state-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$files = @(
    (Join-Path $root 'app\src\main\java\jp\waketerminal\WakeEvent.java'),
    (Join-Path $root 'app\src\main\java\jp\waketerminal\AlarmSession.java'),
    (Join-Path $root 'app\src\test\java\jp\waketerminal\AlarmSessionTest.java')
)
& (Join-Path $Jdk 'bin\javac.exe') '-J-Duser.language=en' '-Xlint:-options' -encoding UTF-8 --release 8 -d $out @files
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -ea -cp $out jp.waketerminal.AlarmSessionTest
if ($LASTEXITCODE -ne 0) { throw 'State machine tests failed' }
