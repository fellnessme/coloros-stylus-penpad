# PenPad build script (no Gradle)
#
# 需要：
#   · JDK 17（设置 JAVA_HOME，或在 PATH 里）
#   · Android SDK（自动探测 ANDROID_HOME / ANDROID_SDK_ROOT / %LOCALAPPDATA%\Android\Sdk）
#   · 先跑一次 fetch-deps.ps1 把依赖下载到 .\libs
#
# 用法： powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = 'Continue'
$PROJ = $PSScriptRoot
$OUT  = Join-Path $PROJ 'build'
$LIBS = Join-Path $PROJ 'libs'

function Step($msg) { Write-Host ">>> $msg" -ForegroundColor Cyan }

# ---- 定位 SDK ------------------------------------------------------------
$SDK = $env:ANDROID_HOME
if (-not $SDK) { $SDK = $env:ANDROID_SDK_ROOT }
if (-not $SDK) { $SDK = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $SDK)) { throw "Android SDK not found. Set ANDROID_HOME." }

$BT = Get-ChildItem (Join-Path $SDK 'build-tools') -Directory -ErrorAction SilentlyContinue |
      Sort-Object Name -Descending | Select-Object -First 1
if (-not $BT) { throw "build-tools not found under $SDK" }

$PLATFORM = Get-ChildItem (Join-Path $SDK 'platforms') -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'android.jar' } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $PLATFORM) { throw "android.jar not found under $SDK\platforms" }

$JAVA = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { '' }
$javac = if ($JAVA) { Join-Path $JAVA 'javac.exe' } else { 'javac' }
$jarc  = if ($JAVA) { Join-Path $JAVA 'jar.exe' }   else { 'jar' }
$keyt  = if ($JAVA) { Join-Path $JAVA 'keytool.exe' } else { 'keytool' }

# ---- 依赖 ---------------------------------------------------------------
$depNames = @(
  'ink-strokes-android','ink-brush-android','ink-rendering-android',
  'ink-nativeloader-android','ink-geometry-android',
  'kotlin-stdlib-2.0.21','collection-jvm-1.4.4','annotation-1.7.0',
  'shizuku-api','shizuku-aidl','shizuku-shared'
)
$deps = @()
foreach ($n in $depNames) {
  $p = Join-Path $LIBS "$n.jar"
  if (Test-Path $p) { $deps += $p } else { Write-Host "missing dep: $p (run fetch-deps.ps1)" -ForegroundColor Yellow }
}
$cpAll = ($PLATFORM + ';' + ($deps -join ';'))

if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Path $OUT,"$OUT\gen","$OUT\classes","$OUT\dex" -Force | Out-Null

Step 'aapt2 compile'
& (Join-Path $BT.FullName 'aapt2.exe') compile --dir (Join-Path $PROJ 'res') -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Step 'aapt2 link'
& (Join-Path $BT.FullName 'aapt2.exe') link -o "$OUT\app-unaligned.apk" -I $PLATFORM `
    --manifest (Join-Path $PROJ 'AndroidManifest.xml') --java "$OUT\gen" `
    --min-sdk-version 29 --target-sdk-version 33 "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Step 'javac'
$srcs = @()
$srcs += Get-ChildItem (Join-Path $PROJ 'src') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$srcs += Get-ChildItem "$OUT\gen" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& $javac -source 8 -target 8 -encoding UTF-8 -nowarn -classpath $cpAll -d "$OUT\classes" $srcs 2>&1 |
    ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Step 'd8'
& $jarc cf "$OUT\classes.jar" -C "$OUT\classes" .
$d8args = @('--lib', $PLATFORM, '--min-api', '29', '--output', "$OUT\dex", "$OUT\classes.jar") + $deps
& (Join-Path $BT.FullName 'd8.bat') @d8args 2>&1 | ForEach-Object { Write-Host $_ }
if (-not (Test-Path "$OUT\dex\classes.dex")) { throw 'd8 failed' }

Step 'pack dex + native lib'
Copy-Item "$OUT\app-unaligned.apk" "$OUT\app-withdex.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\app-withdex.apk", 'Update')
try {
  foreach ($f in Get-ChildItem "$OUT\dex" -Filter 'classes*.dex') {
    $e = $zip.GetEntry($f.Name); if ($e) { $e.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $f.Name, 'Optimal')
  }
  $so = Join-Path $LIBS 'libink.so'
  if (Test-Path $so) {
    $entry = 'lib/arm64-v8a/libink.so'
    $e = $zip.GetEntry($entry); if ($e) { $e.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $so, $entry, 'Optimal')
  } else {
    Write-Host 'missing libs\libink.so (run fetch-deps.ps1)' -ForegroundColor Yellow
  }
} finally { $zip.Dispose() }

Step 'zipalign + sign'
& (Join-Path $BT.FullName 'zipalign.exe') -f -p 4 "$OUT\app-withdex.apk" "$OUT\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = Join-Path $PROJ 'debug.keystore'
if (-not (Test-Path $ks)) {
  & $keyt -genkeypair -keystore $ks -storepass android -keypass android `
      -alias penpad -keyalg RSA -keysize 2048 -validity 10000 `
      -dname 'CN=PenPad, OU=Dev, O=OSS, L=CN, S=CN, C=CN' 2>&1 | Out-Null
}
& (Join-Path $BT.FullName 'apksigner.bat') sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out (Join-Path $PROJ 'PenPad.apk') "$OUT\app-aligned.apk" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Step 'done'
Get-Item (Join-Path $PROJ 'PenPad.apk') |
  Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,2)}} | Format-Table -AutoSize
