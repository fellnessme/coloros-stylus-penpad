# 下载 PenPad 的全部构建依赖到 .\libs
# 依赖来源：Google Maven / Maven Central
#
# 用法： powershell -ExecutionPolicy Bypass -File fetch-deps.ps1

$ErrorActionPreference = 'Stop'
$PROJ = $PSScriptRoot
$LIBS = Join-Path $PROJ 'libs'
New-Item -ItemType Directory -Path $LIBS -Force | Out-Null
$7z = 'C:\Program Files\7-Zip\7z.exe'
if (-not (Test-Path $7z)) { $7z = '7z' }   # 若 7z 在 PATH 里

function Get-File($url, $out) {
  if (Test-Path $out) { Write-Host "exists: $(Split-Path $out -Leaf)"; return }
  Write-Host "download: $(Split-Path $out -Leaf)"
  Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing -TimeoutSec 300
}

function Extract-AarClasses($aar, $targetJar) {
  $tmp = Join-Path $env:TEMP ('aar_' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $tmp -Force | Out-Null
  & $7z e $aar "-o$tmp" classes.jar -y | Out-Null
  Move-Item (Join-Path $tmp 'classes.jar') $targetJar -Force
  Remove-Item $tmp -Recurse -Force
}

$google = 'https://dl.google.com/android/maven2/androidx'
$central = 'https://repo1.maven.org/maven2'

# ---- Jetpack Ink 1.0.0 ---------------------------------------------------
foreach ($a in 'ink-strokes-android','ink-brush-android','ink-rendering-android',
               'ink-nativeloader-android','ink-geometry-android') {
  $aar = Join-Path $LIBS "$a.aar"
  Get-File "$google/ink/$a/1.0.0/$a-1.0.0.aar" $aar
  Extract-AarClasses $aar (Join-Path $LIBS "$a.jar")
}
# 原生渲染器 libink.so（arm64）
$nlAar = Join-Path $LIBS 'ink-nativeloader-android.aar'
$soDir = Join-Path $LIBS 'arm64-v8a'
New-Item -ItemType Directory -Path $soDir -Force | Out-Null
& $7z e $nlAar "-o$soDir" 'jni/arm64-v8a/libink.so' -y | Out-Null
Copy-Item (Join-Path $soDir 'libink.so') (Join-Path $LIBS 'libink.so') -Force

# ---- Kotlin 运行时 / androidx -------------------------------------------
Get-File "$central/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar" `
         (Join-Path $LIBS 'kotlin-stdlib-2.0.21.jar')
Get-File "$google/collection/collection-jvm/1.4.4/collection-jvm-1.4.4.jar" `
         (Join-Path $LIBS 'collection-jvm-1.4.4.jar')
Get-File "$google/annotation/annotation/1.7.0/annotation-1.7.0.jar" `
         (Join-Path $LIBS 'annotation-1.7.0.jar')

# ---- Shizuku API 13.1.5 --------------------------------------------------
Get-File "$central/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar"      (Join-Path $LIBS 'shizuku-api.aar')
Get-File "$central/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar"    (Join-Path $LIBS 'shizuku-aidl.aar')
Get-File "$central/dev/rikka/shizuku/shared/13.1.5/shared-13.1.5.aar" (Join-Path $LIBS 'shizuku-shared.aar')
Extract-AarClasses (Join-Path $LIBS 'shizuku-api.aar')    (Join-Path $LIBS 'shizuku-api.jar')
Extract-AarClasses (Join-Path $LIBS 'shizuku-aidl.aar')   (Join-Path $LIBS 'shizuku-aidl.jar')
Extract-AarClasses (Join-Path $LIBS 'shizuku-shared.aar') (Join-Path $LIBS 'shizuku-shared.jar')

Write-Host ''
Write-Host 'deps ready:' -ForegroundColor Green
Get-ChildItem $LIBS -Filter *.jar | Select-Object Name, @{n='KB';e={[math]::Round($_.Length/1KB)}} | Format-Table -AutoSize
