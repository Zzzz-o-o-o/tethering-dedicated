<#
.SYNOPSIS
  テザリング専用化アプリの ADB 事前設定（確認 / 適用 / 元に戻す / インストール）

.DESCRIPTION
  引数なしで実行すると、現在の値を表示するだけで何も変更しません。
  -Apply は変更前の値を backup\ に保存してから推奨値を適用します（2回目以降も最初の値を保持）。
  -Restore で保存した値に戻します。
  変更するのは、通信を止めるおそれのない「省電力・常時運用」向けの設定だけです。
  キャリアのテザリング制限やプロビジョニングに関わる設定は扱いません。

  第 2 段階（仕様 11 章）で追加した項目:
  - 画面の不意な点灯を防ぐ（通知・持ち上げ・タップでの点灯を OFF）… -Apply に含む
  - 位置情報と「モバイルデータを常に ON」を OFF … -Apply に含む（-KeepLocation なら位置情報は変えない）
  - このアプリ以外のユーザーアプリのバックグラウンド制限 … -RestrictApps を付けたときだけ（-KeepApps で除外）

.EXAMPLE
  .\adb-setup.ps1                                   # 確認だけ（変更しない）
  .\adb-setup.ps1 -Install ..\TetheringDedicated-v0.3.0.apk
  .\adb-setup.ps1 -Apply                            # 推奨値を適用（変更前の値を保存）
  .\adb-setup.ps1 -Apply -RestrictApps              # ほかのユーザーアプリもまとめてバックグラウンド制限
  .\adb-setup.ps1 -Apply -RestrictApps -KeepApps com.example.vpn,com.example.remote
  .\adb-setup.ps1 -Apply -KeepLocation              # 位置情報は変えない
  .\adb-setup.ps1 -Apply -IncludeOta                # システムの自動更新も止める（手動更新が前提）
  .\adb-setup.ps1 -Apply -ScreenOffSec 30           # PIN 等の画面ロックがあっても画面消灯を 30 秒にする
  .\adb-setup.ps1 -Restore                          # 元に戻す
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$Restore,
    [string]$Install,
    [switch]$IncludeOta,
    [switch]$RestrictApps,
    [string[]]$KeepApps = @(),
    [switch]$KeepLocation,
    [ValidateRange(15, 1800)][int]$ScreenOffSec = 30,
    [string]$Serial,
    [string]$Adb
)

$ErrorActionPreference = 'Stop'
$Package = 'local.tethering.dedicated'
$BackupDir = Join-Path $PSScriptRoot 'backup'
# .bat から「-KeepApps a,b」と渡すと 1 つの文字列になるので、カンマで分ける
$Keep = @($KeepApps | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
# アプリの使用頻度（App Standby のバケット）。adb から設定できるのは active / working_set / frequent / rare
$BucketNames = @{ 5 = 'exempted'; 10 = 'active'; 20 = 'working_set'; 30 = 'frequent'; 40 = 'rare'; 45 = 'restricted'; 50 = 'never' }

function Find-Adb {
    if ($Adb) {
        if (Test-Path $Adb) { return (Resolve-Path $Adb).Path }
        throw "指定された adb が見つかりません: $Adb"
    }
    $candidates = @(Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    $candidates += 'C:\Program Files (x86)\LetsView\LetsView\Usb\adb.exe'
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    throw 'adb.exe が見つかりません。-Adb でパスを指定してください。'
}

# Windows PowerShell 5.1 では、ネイティブコマンドの stderr を 2>&1 で受けると
# $ErrorActionPreference = 'Stop' のときに例外になる（adb はデーモン起動の案内を stderr に出す）ため、ここだけ Continue にする
function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowFail, [switch]$NoSerial)
    $argList = @()
    if ($script:DeviceSerial -and -not $NoSerial) { $argList += @('-s', $script:DeviceSerial) }
    $argList += $Arguments
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:AdbPath @argList 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    $text = (($out | Where-Object { $_ -notmatch '^\* daemon' }) -join "`n").Trim()
    if ($code -ne 0 -and -not $AllowFail) { throw "adb $($Arguments -join ' ') が失敗しました (exit $code): $text" }
    return $text
}

function Select-Device {
    $lines = (Invoke-Adb -Arguments @('devices') -NoSerial) -split "`n" | Select-Object -Skip 1 | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $ready = @($lines | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
    $unauth = @($lines | Where-Object { $_ -match '\tunauthorized$' })
    if ($Serial) {
        if ($ready -notcontains $Serial) { throw "端末 $Serial が見つかりません（接続中: $($ready -join ', ')）" }
        return $Serial
    }
    if ($ready.Count -eq 1) { return $ready[0] }
    if ($ready.Count -gt 1) { throw "複数の端末が接続されています。-Serial で指定してください: $($ready -join ', ')" }
    if ($unauth.Count -gt 0) { throw '端末の画面で「USBデバッグを許可しますか？」を許可してください。' }
    throw '端末が見つかりません。USBデバッグを有効にして接続してください。'
}

function Get-Setting([string]$Ns, [string]$Key) {
    $v = Invoke-Adb -Arguments @('shell', 'settings', 'get', $Ns, $Key)
    if ($v -eq 'null' -or $v -eq '') { return $null }
    return $v
}

function Set-Setting([string]$Ns, [string]$Key, $Value) {
    if ($null -eq $Value) {
        Invoke-Adb -Arguments @('shell', 'settings', 'delete', $Ns, $Key) | Out-Null
    } else {
        Invoke-Adb -Arguments @('shell', 'settings', 'put', $Ns, $Key, "$Value") | Out-Null
    }
}

function Test-Installed {
    $p = Invoke-Adb -Arguments @('shell', 'pm', 'path', $Package) -AllowFail
    return ($p -match '^package:')
}

function Test-Whitelisted {
    $w = Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist') -AllowFail
    return ($w -match [regex]::Escape(",$Package,"))
}

function Test-SecureLock {
    # Android 10 の locksettings は、PIN・パターン等が設定されていると現在の資格情報なしではエラーになる
    $r = Invoke-Adb -Arguments @('shell', 'locksettings', 'get-disabled') -AllowFail
    return ($r -match 'Credential')
}

function Test-PhoneGranted {
    $d = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $Package) -AllowFail
    return ($d -match 'android\.permission\.READ_PHONE_STATE: granted=true')
}

# ---- このアプリ以外のユーザーアプリ（仕様 F-40）。システムアプリは対象にしない ----

function Get-UserApps {
    $out = Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', '-3')
    return @($out -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -like 'package:*' } |
        ForEach-Object { $_.Substring(8) } | Where-Object { $_ -ne $Package } | Sort-Object)
}

function Get-BgMode([string]$Pkg) {
    $r = Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'get', $Pkg, 'RUN_ANY_IN_BACKGROUND') -AllowFail
    if ($r -match 'RUN_ANY_IN_BACKGROUND:\s*(\w+)') { return $Matches[1] }
    return $null  # 未設定（＝許可）
}

function Get-Bucket([string]$Pkg) {
    $r = Invoke-Adb -Arguments @('shell', 'am', 'get-standby-bucket', $Pkg) -AllowFail
    if ($r -match '^\s*(\d+)\s*$') { return [int]$Matches[1] }
    return $null
}

function Format-Bucket($b) {
    if ($null -eq $b) { return '取得不可' }
    if ($BucketNames.ContainsKey([int]$b)) { return "$($BucketNames[[int]$b])（$b）" }
    return "$b"
}

function Show-Table($rows) {
    $rows | Format-Table -AutoSize -Property 項目, 現在, 推奨, 状態 | Out-String -Width 200 | Write-Host
}

# ---- 開始 ----

$script:AdbPath = Find-Adb
$script:DeviceSerial = Select-Device
$model = Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model')
$sdk = [int](Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk'))
Write-Host "端末: $model（$($script:DeviceSerial)） / Android API $sdk"
Write-Host "adb : $($script:AdbPath)"
if ($sdk -ne 29) { Write-Warning 'このアプリと設定は Android 10（API 29）向けです。別のバージョンでは効かない項目があります。' }

if ($Install) {
    if (-not (Test-Path $Install)) { throw "APK が見つかりません: $Install" }
    $apk = (Resolve-Path $Install).Path
    Write-Host "インストール: $apk"
    Write-Host (Invoke-Adb -Arguments @('install', '-r', $apk))
}

$items = @(
    [pscustomobject]@{ Name = 'アクセスポイントの自動OFF'; Ns = 'global'; Key = 'soft_ap_timeout_enabled'; Want = '0' }
    [pscustomobject]@{ Name = 'Wi-Fiスキャン（常時）'; Ns = 'global'; Key = 'wifi_scan_always_enabled'; Want = '0' }
    [pscustomobject]@{ Name = 'Bluetoothスキャン（常時）'; Ns = 'global'; Key = 'ble_scan_always_enabled'; Want = '0' }
    [pscustomobject]@{ Name = '充電中は画面をスリープしない'; Ns = 'global'; Key = 'stay_on_while_plugged_in'; Want = '0' }
    [pscustomobject]@{ Name = '画面消灯までの時間（ミリ秒）'; Ns = 'system'; Key = 'screen_off_timeout'; Want = "$($ScreenOffSec * 1000)" }
    # 画面の不意な点灯を防ぐ（仕様 F-39）。未設定（機種の既定で ON のことがある）でも 0 にする。-Restore で未設定に戻る
    [pscustomobject]@{ Name = '通知で画面を点灯（アンビエント表示）'; Ns = 'secure'; Key = 'doze_enabled'; Want = '0' }
    [pscustomobject]@{ Name = '常に表示（アンビエント表示）'; Ns = 'secure'; Key = 'doze_always_on'; Want = '0' }
    [pscustomobject]@{ Name = '持ち上げて通知を確認'; Ns = 'secure'; Key = 'doze_pulse_on_pick_up'; Want = '0' }
    [pscustomobject]@{ Name = 'ダブルタップで通知を確認'; Ns = 'secure'; Key = 'doze_pulse_on_double_tap'; Want = '0' }
    [pscustomobject]@{ Name = 'タップで通知を確認'; Ns = 'secure'; Key = 'doze_tap_gesture'; Want = '0' }
    [pscustomobject]@{ Name = '持ち上げて画面を点灯'; Ns = 'secure'; Key = 'wake_gesture_enabled'; Want = '0' }
    [pscustomobject]@{ Name = 'ダブルタップで画面を点灯'; Ns = 'secure'; Key = 'double_tap_to_wake'; Want = '0' }
    # 「モバイルデータを常に ON」（仕様 F-41。Wi-Fi 接続中もモバイル回線を待機させる機能で、テザリングには使わない）
    [pscustomobject]@{ Name = 'モバイルデータを常にON'; Ns = 'global'; Key = 'mobile_data_always_on'; Want = '0' }
)
if (-not $KeepLocation) {
    # 位置情報（仕様 F-41）。このアプリは使わない。Android 10 では location_mode が位置情報の ON/OFF そのもの
    $items += [pscustomobject]@{ Name = '位置情報（0 = OFF）'; Ns = 'secure'; Key = 'location_mode'; Want = '0' }
}
if ((Test-SecureLock) -and -not $PSBoundParameters.ContainsKey('ScreenOffSec')) {
    # 画面ロックがあると、消灯を短くするたびに PIN 入力が必要になり操作しづらいため、明示されたときだけ変える
    Write-Warning '画面ロック（PIN 等）があるため、画面消灯までの時間は変更しません（短くすると消灯のたびにロックがかかります）。変える場合は -ScreenOffSec 30 のように指定してください。'
    $items = @($items | Where-Object { $_.Key -ne 'screen_off_timeout' })
}
if ($IncludeOta) {
    $items += [pscustomobject]@{ Name = 'システムの自動更新を止める'; Ns = 'global'; Key = 'ota_disable_automatic_update'; Want = '1' }
}
if ($sdk -gt 29) {
    # Android 11 以降は自動OFFの設定がこのキーではない
    $items = @($items | Where-Object { $_.Key -ne 'soft_ap_timeout_enabled' })
}

$installed = Test-Installed
$safeSerial = ($script:DeviceSerial -replace '[^A-Za-z0-9._-]', '_')
$backupPath = Join-Path $BackupDir "adb-setup-backup-$safeSerial.json"
$userApps = Get-UserApps

if ($Restore) {
    if (-not (Test-Path $backupPath)) { throw "この端末の保存データがありません: $backupPath" }
    $b = Get-Content -Path $backupPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($s in $b.settings) {
        Set-Setting $s.ns $s.key $s.value
        Write-Host "戻しました: $($s.ns) $($s.key) = $(if ($null -eq $s.value) { '(未設定)' } else { $s.value })"
    }
    foreach ($a in @($b.apps)) {
        if (-not $a) { continue }
        if ((Invoke-Adb -Arguments @('shell', 'pm', 'path', $a.pkg) -AllowFail) -notmatch '^package:') {
            Write-Host "スキップ（アンインストール済み）: $($a.pkg)"
            continue
        }
        # 未設定だったものは「許可」に戻す（未設定と同じ動作。ignore 以外は制限とみなされるため default にはしない）
        $mode = if ($a.bg) { $a.bg } else { 'allow' }
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $a.pkg, 'RUN_ANY_IN_BACKGROUND', $mode) -AllowFail | Out-Null
        $bucket = if ($null -ne $a.bucket -and @(10, 20, 30, 40) -contains [int]$a.bucket) { $BucketNames[[int]$a.bucket] } else { 'active' }
        Invoke-Adb -Arguments @('shell', 'am', 'set-standby-bucket', $a.pkg, $bucket) -AllowFail | Out-Null
        Write-Host "戻しました: $($a.pkg)（バックグラウンド $mode / 使用頻度 $bucket）"
    }
    if ($installed -and $null -ne $b.deviceIdleWhitelisted -and -not $b.deviceIdleWhitelisted -and (Test-Whitelisted)) {
        Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist', "-$Package") | Out-Null
        Write-Host '戻しました: 電池の最適化の除外を解除'
    }
    if ($installed -and $null -ne $b.phoneStateGranted -and -not $b.phoneStateGranted -and (Test-PhoneGranted)) {
        Invoke-Adb -Arguments @('shell', 'pm', 'revoke', $Package, 'android.permission.READ_PHONE_STATE') -AllowFail | Out-Null
        Write-Host '戻しました: 電話の権限を取り消し'
    }
    $done = Join-Path $BackupDir ("adb-setup-backup-$safeSerial.restored-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    Move-Item -Path $backupPath -Destination $done
    Write-Host "保存データは $done に移しました（次の -Apply で改めて元の値を保存します）。"
    return
}

if ($Apply) {
    # 変更前の値を保存する。すでに保存データがある項目は上書きしない（2回目の -Apply で元の値を失わないように）
    $old = $null
    if (Test-Path $backupPath) { $old = Get-Content -Path $backupPath -Raw -Encoding UTF8 | ConvertFrom-Json }
    $saved = [System.Collections.ArrayList]@()
    if ($old) { foreach ($s in $old.settings) { [void]$saved.Add([ordered]@{ ns = $s.ns; key = $s.key; value = $s.value }) } }
    foreach ($i in $items) {
        if (-not ($saved | Where-Object { $_.ns -eq $i.Ns -and $_.key -eq $i.Key })) {
            [void]$saved.Add([ordered]@{ ns = $i.Ns; key = $i.Key; value = (Get-Setting $i.Ns $i.Key) })
        }
    }
    $savedApps = [System.Collections.ArrayList]@()
    if ($old -and $old.apps) { foreach ($a in @($old.apps)) { if ($a) { [void]$savedApps.Add([ordered]@{ pkg = $a.pkg; bg = $a.bg; bucket = $a.bucket }) } } }
    $targets = @()
    if ($RestrictApps) {
        $targets = @($userApps | Where-Object { $Keep -notcontains $_ })
        foreach ($p in $targets) {
            if (-not ($savedApps | Where-Object { $_.pkg -eq $p })) {
                [void]$savedApps.Add([ordered]@{ pkg = $p; bg = (Get-BgMode $p); bucket = (Get-Bucket $p) })
            }
        }
    }
    $wl = if ($old -and $null -ne $old.deviceIdleWhitelisted) { $old.deviceIdleWhitelisted } elseif ($installed) { Test-Whitelisted } else { $null }
    $ph = if ($old -and $null -ne $old.phoneStateGranted) { $old.phoneStateGranted } elseif ($installed) { Test-PhoneGranted } else { $null }
    $backup = [ordered]@{
        createdAt             = if ($old) { $old.createdAt } else { (Get-Date).ToString('s') }
        serial                = $script:DeviceSerial
        model                 = $model
        sdk                   = $sdk
        settings              = $saved
        apps                  = $savedApps
        deviceIdleWhitelisted = $wl
        phoneStateGranted     = $ph
    }
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $backup | ConvertTo-Json -Depth 5 | Set-Content -Path $backupPath -Encoding UTF8
    Write-Host "変更前の値を保存しました: $backupPath"

    foreach ($i in $items) { Set-Setting $i.Ns $i.Key $i.Want }
    foreach ($p in $targets) {
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $p, 'RUN_ANY_IN_BACKGROUND', 'ignore') | Out-Null
        Invoke-Adb -Arguments @('shell', 'am', 'set-standby-bucket', $p, 'rare') -AllowFail | Out-Null
    }
    if ($targets.Count -gt 0) { Write-Host "バックグラウンドを制限しました: $($targets -join ', ')" }
    if ($installed) {
        Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist', "+$Package") | Out-Null
        Invoke-Adb -Arguments @('shell', 'pm', 'grant', $Package, 'android.permission.READ_PHONE_STATE') -AllowFail | Out-Null
    } else {
        Write-Warning 'アプリが未インストールのため、電池の最適化の除外と権限の付与は行っていません（-Install を先に）。'
    }
}

# ---- 結果（確認） ----

$rows = foreach ($i in $items) {
    $cur = Get-Setting $i.Ns $i.Key
    [pscustomobject]@{
        項目 = $i.Name
        現在 = if ($null -eq $cur) { '(未設定)' } else { $cur }
        推奨 = $i.Want
        状態 = if ("$cur" -eq $i.Want) { 'OK' } else { '要変更' }
    }
}
if ($installed) {
    $rows = @($rows) + [pscustomobject]@{ 項目 = '電池の最適化から除外（本アプリ）'; 現在 = $(if (Test-Whitelisted) { '除外' } else { '対象' }); 推奨 = '除外'; 状態 = $(if (Test-Whitelisted) { 'OK' } else { '要変更' }) }
    $rows = @($rows) + [pscustomobject]@{ 項目 = '電話の権限（本アプリ）'; 現在 = $(if (Test-PhoneGranted) { '許可' } else { '未許可' }); 推奨 = '許可'; 状態 = $(if (Test-PhoneGranted) { 'OK' } else { '要変更' }) }
} else {
    Write-Host 'アプリは未インストールです（-Install でインストールできます）。'
}
Show-Table $rows

if ($userApps.Count -gt 0) {
    Write-Host 'このアプリ以外のユーザーアプリ（バックグラウンド制限。システムアプリは対象外）:'
    $appRows = foreach ($p in $userApps) {
        $bg = Get-BgMode $p
        $bk = Get-Bucket $p
        [pscustomobject]@{
            パッケージ   = $p
            バックグラウンド = if ($bg -eq 'ignore') { '制限' } elseif ($null -eq $bg) { '許可（未設定）' } else { $bg }
            使用頻度     = Format-Bucket $bk
            状態       = if ($Keep -contains $p) { '除外' } elseif ($bg -eq 'ignore' -and $null -ne $bk -and $bk -ge 40) { 'OK' } else { '未制限' }
        }
    }
    $appRows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    if (-not ($Apply -and $RestrictApps)) { Write-Host 'まとめて制限するには -Apply -RestrictApps（裏で動いてほしいアプリは -KeepApps で除外）。戻すのは -Restore。' }
}

if ($Install) {
    # 権限の付与（-Apply）が済んでから起動する。一度起動しておかないと、再起動後の自動開始（BOOT_COMPLETED）が届かない
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', "$Package/.MainActivity") | Out-Null
    if ($Apply) { Write-Host 'アプリを起動しました。' }
    else { Write-Host 'アプリを起動しました。端末の画面で「電話」の権限と、電池の最適化からの除外を許可してください。' }
}
if (-not $Apply -and -not $Install) { Write-Host '確認のみです（変更していません）。適用は -Apply、元に戻すのは -Restore。' }
