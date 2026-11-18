param(
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk' }),
    [string]$Jdk = $env:JAVA_HOME,
    [switch]$IncludeDeviceTests
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$sdkJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^35\.0\.\d+$' } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildTools -or -not (Test-Path -LiteralPath $sdkJar)) { throw 'Android SDK Platform 35 / Build Tools 35.0.x are required.' }
if (-not (Test-Path -LiteralPath (Join-Path $Jdk 'bin\javac.exe'))) { throw 'Pass -Jdk with a JDK 17+ installation.' }
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + [IO.Path]::PathSeparator + $env:PATH
$out = Join-Path $root 'app\build\offline'
# Windows SDK native tools cannot reliably open non-ASCII paths. Stage in TEMP.
$work = Join-Path ([IO.Path]::GetTempPath()) ('WakeTerminal-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $work 'classes'
$generated = Join-Path $work 'generated'
$dex = Join-Path $work 'dex'
New-Item -ItemType Directory -Force -Path $classes,$generated,$dex | Out-Null
New-Item -ItemType Directory -Force -Path $out | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'app\src\main\res') -Destination (Join-Path $work 'res') -Recurse
Copy-Item -LiteralPath (Join-Path $root 'app\src\main\java') -Destination (Join-Path $work 'java') -Recurse
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with $LASTEXITCODE" }
}
$aapt = Join-Path $buildTools.FullName 'aapt2.exe'
$resources = Join-Path $work 'resources.zip'
Invoke-Checked $aapt @('compile','--dir',(Join-Path $work 'res'),'-o',$resources)
[xml]$manifest = Get-Content -LiteralPath (Join-Path $root 'app\src\main\AndroidManifest.xml') -Raw -Encoding UTF8
$manifest.manifest.SetAttribute('package', 'jp.waketerminal')
$manifest.manifest.application.SetAttribute('debuggable', 'http://schemas.android.com/apk/res/android', 'true') | Out-Null
$localManifest = Join-Path $work 'AndroidManifest.xml'
$manifest.Save($localManifest)
$unsigned = Join-Path $work 'unsigned.apk'
Invoke-Checked $aapt @('link','-o',$unsigned,'-I',$sdkJar,'--manifest',$localManifest,'--java',$generated,
    '--min-sdk-version','26','--target-sdk-version','35','--version-code','3','--version-name','1.1.1',$resources)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $work 'java'),$generated -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $Jdk 'bin\javac.exe') (@('-J-Duser.language=en','-Xlint:-options','-encoding','UTF-8','--release','8','-classpath',$sdkJar,'-d',$classes) + $sources)
$classesJar = Join-Path $work 'classes.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($classes, $classesJar)
Invoke-Checked (Join-Path $buildTools.FullName 'd8.bat') @('--lib',$sdkJar,'--min-api','26','--output',$dex,$classesJar)
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $file.Name) | Out-Null
    }
} finally { $archive.Dispose() }
$aligned = Join-Path $work 'aligned.apk'
Invoke-Checked (Join-Path $buildTools.FullName 'zipalign.exe') @('-f','4',$unsigned,$aligned)
$key = Join-Path $out 'debug.keystore'
if (-not (Test-Path -LiteralPath $key)) {
    Invoke-Checked (Join-Path $Jdk 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android',
        '-keypass','android','-alias','androiddebugkey','-dname','CN=Android Debug,O=WakeTerminal,C=JP',
        '-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt')
}
$apk = Join-Path $out 'ultimate-wake-alarm-debug.apk'
Invoke-Checked (Join-Path $buildTools.FullName 'apksigner.bat') @('sign','--ks',$key,'--ks-pass','pass:android',
    '--key-pass','pass:android','--out',$apk,$aligned)
Invoke-Checked (Join-Path $buildTools.FullName 'apksigner.bat') @('verify','--verbose',$apk)
Write-Output "APK: $apk"
if ($IncludeDeviceTests) {
    $testClasses = Join-Path $work 'test-classes'
    $testDex = Join-Path $work 'test-dex'
    New-Item -ItemType Directory -Force -Path $testClasses,$testDex | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'app\src\androidTest\java') -Destination (Join-Path $work 'test-java') -Recurse
    Copy-Item -LiteralPath (Join-Path $root 'app\src\androidTest\AndroidManifest.xml') -Destination (Join-Path $work 'test-manifest.xml')
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $work 'test-java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
    Invoke-Checked (Join-Path $Jdk 'bin\javac.exe') (@('-J-Duser.language=en','-Xlint:-options','-encoding','UTF-8','--release','8',
        '-classpath',($sdkJar + [IO.Path]::PathSeparator + $classesJar),'-d',$testClasses) + $testSources)
    $testJar = Join-Path $work 'test.jar'
    [IO.Compression.ZipFile]::CreateFromDirectory($testClasses, $testJar)
    Invoke-Checked (Join-Path $buildTools.FullName 'd8.bat') @('--lib',$sdkJar,'--classpath',$classesJar,'--min-api','26','--output',$testDex,$testJar)
    $testUnsigned = Join-Path $work 'test-unsigned.apk'
    Invoke-Checked $aapt @('link','-o',$testUnsigned,'-I',$sdkJar,'--manifest',(Join-Path $work 'test-manifest.xml'))
    $archive = [IO.Compression.ZipFile]::Open($testUnsigned, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($file in Get-ChildItem -LiteralPath $testDex -Filter '*.dex') {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $file.Name) | Out-Null
        }
    } finally { $archive.Dispose() }
    $testAligned = Join-Path $work 'test-aligned.apk'
    Invoke-Checked (Join-Path $buildTools.FullName 'zipalign.exe') @('-f','4',$testUnsigned,$testAligned)
    $testApk = Join-Path $out 'ultimate-wake-alarm-device-tests.apk'
    Invoke-Checked (Join-Path $buildTools.FullName 'apksigner.bat') @('sign','--ks',$key,'--ks-pass','pass:android',
        '--key-pass','pass:android','--out',$testApk,$testAligned)
    Write-Output "TEST APK: $testApk"
}
