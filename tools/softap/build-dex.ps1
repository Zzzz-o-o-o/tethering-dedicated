<#
.SYNOPSIS
  SoftApTool.java を、端末の app_process で動く td-softap.dex にする（開発用。配布する td-softap.dex はリポジトリに入れてある）
  Builds td-softap.dex (run on the phone with app_process) from SoftApTool.java. For development only.

.DESCRIPTION
  JDK（javac）と Android SDK の build-tools（d8）が要ります。場所は -Jdk と -BuildTools で指定できます。
  作った td-softap.dex は adb-setup.ps1 の -MaxClients が使います（Android 11 以降）。
#>
param(
    [string]$Jdk = (Join-Path $env:USERPROFILE '.jdks\jdk-21.0.12.1+1'),
    [string]$BuildTools = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.0.0')
)

$ErrorActionPreference = 'Stop'
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('td-softap-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    # Java 8 のクラスファイルにする（d8 がそのまま読める。Android 11 以降の app_process で動く）
    & (Join-Path $Jdk 'bin\javac.exe') --release 8 -Xlint:-options -d $work (Join-Path $PSScriptRoot 'SoftApTool.java')
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    $env:JAVA_HOME = $Jdk
    $class = Join-Path $work 'local\tethering\dedicated\tools\SoftApTool.class'
    & (Join-Path $BuildTools 'd8.bat') --release --min-api 30 --output $work $class
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
    $dex = Join-Path $PSScriptRoot 'td-softap.dex'
    Copy-Item (Join-Path $work 'classes.dex') $dex -Force
    Write-Host ('td-softap.dex: ' + (Get-Item $dex).Length + ' bytes')
} finally {
    Remove-Item -Recurse -Force $work
}
