<#
.SYNOPSIS
  テザリング専用化アプリの ADB 事前設定（確認 / 適用 / 元に戻す / インストール）
  ADB setup for the Tethering Dedicated app (check / apply / restore / install).

.DESCRIPTION
  引数なしで実行すると、現在の値を表示するだけで何も変更しません。
  -Apply は変更前の値を backup\ に保存してから推奨値を適用します（2回目以降も最初の値を保持）。
  -Restore で保存した値に戻します。
  変更するのは、通信を止めるおそれのない「省電力・常時運用」向けの設定だけです。
  キャリアのテザリング制限やプロビジョニングに関わる設定は扱いません。
  表示の言語は PC の表示言語に合わせます（日本語・フランス語、ほかは英語）。-Lang ja|en|fr で指定もできます。

  Without arguments, the script only shows the current values and changes nothing.
  -Apply saves the current values to backup\ and then applies the recommended settings; -Restore puts them back.
  Messages follow the PC's display language (Japanese, French, otherwise English), or -Lang ja|en|fr.

  第 2 段階（仕様 11 章）で追加した項目:
  - 画面の不意な点灯を防ぐ（通知・持ち上げ・タップでの点灯を OFF）… -Apply に含む
  - 位置情報と「モバイルデータを常に ON」を OFF … -Apply に含む（-KeepLocation なら位置情報は変えない）
  - このアプリ以外のユーザーアプリのバックグラウンド制限 … -RestrictApps を付けたときだけ（-KeepApps で除外）

  第 3 段階で追加した項目:
  - このアプリに「使用状況へのアクセス」を許可（アプリ別の通信量を読むだけ）… -Apply に含む
  - このアプリに「設定変更の権限」（WRITE_SECURE_SETTINGS）を付ける（バッテリー駆動中の自動切り替え用）… -AllowAutoSwitch を付けたときだけ

  第 4 段階で追加した項目:
  - このアプリに「システム設定の変更」（WRITE_SETTINGS）を許可する（時間帯スケジュールでテザリングを止める・再開する。v0.7.0 からは起動時のテザリング ON にも使う）… -AllowTetherControl を付けたときだけ
  - テザリングのハードウェアアクセラレーションを有効に戻す（tether_offload_disabled = 0）… -EnableOffload を付けたときだけ。
    端末にオフロードの HAL（tetheroffload）がないときは効果がないので変更しない
  - アクセスポイントの帯域（2.4GHz／5GHz）とオフロードの対応を表示する（アプリからは読めないため。表示だけ）

  Android 11（API 30）・Android 12（API 31・32）の端末（v0.10.0 から。アプリは TetheringDedicated-<版>_android11.apk・_android12.apk を使う）:
  - 「アクセスポイントの自動OFF」は OS の設定値ではなくなり、ADB では変えられないので扱わない（端末の画面で OFF にする）
  - このアプリの「使わないときの権限の削除」を OFF にする（しばらく開かないと「電話」の権限が外れないように。Android 12 では休止状態も防ぐ）… -Apply に含む

.EXAMPLE
  .\adb-setup.ps1                                   # 確認だけ（変更しない） / check only
  .\adb-setup.ps1 -Install ..\TetheringDedicated-v0.10.0_android10.apk   # Android 11 の端末には _android11.apk、Android 12 には _android12.apk
  .\adb-setup.ps1 -Apply                            # 推奨値を適用（変更前の値を保存）
  .\adb-setup.ps1 -Apply -RestrictApps              # ほかのユーザーアプリもまとめてバックグラウンド制限
  .\adb-setup.ps1 -Apply -RestrictApps -KeepApps com.example.vpn,com.example.remote
  .\adb-setup.ps1 -Apply -KeepLocation              # 位置情報は変えない
  .\adb-setup.ps1 -Apply -AllowAutoSwitch           # 自動切り替え（バッテリーセーバー・Doze）の権限も付ける
  .\adb-setup.ps1 -Apply -AllowTetherControl        # 起動時のテザリング ON・時間帯スケジュールの許可も付ける
  .\adb-setup.ps1 -Apply -EnableOffload             # ハードウェアアクセラレーションを有効に戻す（対応端末のみ）
  .\adb-setup.ps1 -Apply -IncludeOta                # システムの自動更新も止める（手動更新が前提）
  .\adb-setup.ps1 -Apply -ScreenOffSec 30           # PIN 等の画面ロックがあっても画面消灯を 30 秒にする
  .\adb-setup.ps1 -Restore                          # 元に戻す / undo
  .\adb-setup.ps1 -Lang en                          # 英語で表示 / messages in English
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
    [switch]$AllowAutoSwitch,
    [switch]$AllowTetherControl,
    [switch]$EnableOffload,
    [ValidateRange(15, 1800)][int]$ScreenOffSec = 30,
    [string]$Serial,
    [string]$Adb,
    [ValidateSet('ja', 'en', 'fr')][string]$Lang
)

$ErrorActionPreference = 'Stop'
$Package = 'local.tethering.dedicated'
$BackupDir = Join-Path $PSScriptRoot 'backup'
# .bat から「-KeepApps a,b」と渡すと 1 つの文字列になるので、カンマで分ける
$Keep = @($KeepApps | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
# アプリの使用頻度（App Standby のバケット）。adb から設定できるのは active / working_set / frequent / rare
$BucketNames = @{ 5 = 'exempted'; 10 = 'active'; 20 = 'working_set'; 30 = 'frequent'; 40 = 'rare'; 45 = 'restricted'; 50 = 'never' }

# 表示の言語。指定がなければ PC の表示言語（日本語・フランス語、ほかは英語）
$script:L = $Lang
if (-not $script:L) {
    $ui = [System.Globalization.CultureInfo]::CurrentUICulture.TwoLetterISOLanguageName
    $script:L = if ($ui -eq 'ja' -or $ui -eq 'fr') { $ui } else { 'en' }
}

# 今の言語の文字列。文言はダブルクォートで書く（PowerShell は “ ” ‘ ’ も引用符として扱うので、文言の中では使わない）
function T([string]$Ja, [string]$En, [string]$Fr) {
    switch ($script:L) {
        'ja' { return $Ja }
        'fr' { return $Fr }
        default { return $En }
    }
}

function Find-Adb {
    if ($Adb) {
        if (Test-Path $Adb) { return (Resolve-Path $Adb).Path }
        throw (T "指定された adb が見つかりません: $Adb" "adb not found at the given path: $Adb" "adb introuvable à l'emplacement indiqué : $Adb")
    }
    $candidates = @(Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    $candidates += 'C:\Program Files (x86)\LetsView\LetsView\Usb\adb.exe'
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    throw (T "adb.exe が見つかりません。-Adb でパスを指定してください。" "adb.exe not found. Specify its path with -Adb." "adb.exe introuvable. Indiquez son chemin avec -Adb.")
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
    if ($code -ne 0 -and -not $AllowFail) {
        $a = $Arguments -join ' '
        throw (T "adb $a が失敗しました (exit $code): $text" "adb $a failed (exit $code): $text" "adb $a a échoué (code $code) : $text")
    }
    return $text
}

function Select-Device {
    $lines = (Invoke-Adb -Arguments @('devices') -NoSerial) -split "`n" | Select-Object -Skip 1 | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $ready = @($lines | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
    $unauth = @($lines | Where-Object { $_ -match '\tunauthorized$' })
    $list = $ready -join ', '
    if ($Serial) {
        if ($ready -notcontains $Serial) {
            throw (T "端末 $Serial が見つかりません（接続中: $list）" "Device $Serial not found (connected: $list)" "Appareil $Serial introuvable (connectés : $list)")
        }
        return $Serial
    }
    if ($ready.Count -eq 1) { return $ready[0] }
    if ($ready.Count -gt 1) {
        throw (T "複数の端末が接続されています。-Serial で指定してください: $list" "Several devices are connected. Choose one with -Serial: $list" "Plusieurs appareils sont connectés. Choisissez-en un avec -Serial : $list")
    }
    if ($unauth.Count -gt 0) {
        throw (T "端末の画面で「USBデバッグを許可しますか？」を許可してください。" "On the phone, accept the 'Allow USB debugging?' prompt." "Sur le téléphone, acceptez la demande « Autoriser le débogage USB ? ».")
    }
    throw (T "端末が見つかりません。USBデバッグを有効にして接続してください。" "No device found. Enable USB debugging and connect the phone." "Aucun appareil trouvé. Activez le débogage USB et connectez le téléphone.")
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

# 設定変更の権限（自動切り替え用。第 3 段階）
function Test-SecureSettingsGranted {
    $d = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $Package) -AllowFail
    return ($d -match 'android\.permission\.WRITE_SECURE_SETTINGS: granted=true')
}

# 使用状況へのアクセス（アプリ別の通信量を読む。第 3 段階）。未設定なら $null
function Get-UsageMode {
    $r = Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'get', $Package, 'GET_USAGE_STATS') -AllowFail
    if ($r -match 'GET_USAGE_STATS:\s*(\w+)') { return $Matches[1] }
    return $null
}

# 「システム設定の変更」（時間帯スケジュールでテザリングを切り替える。第 4 段階）。未設定なら $null
function Get-WriteSettingsMode {
    $r = Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'get', $Package, 'WRITE_SETTINGS') -AllowFail
    if ($r -match 'WRITE_SETTINGS:\s*(\w+)') { return $Matches[1] }
    return $null
}

# 「使わないときの権限の削除」（Android 11 から。ignore = OFF＝権限が外れない）。未設定なら $null。
# 端末の画面のスイッチは UID 単位の値を変え、UID 単位の値がアプリ単位より優先されるので、変えるときは --uid を使う
# （appops get は UID 単位の値があればそれを先に出す）
function Get-AutoRevokeMode {
    $r = Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'get', $Package, 'AUTO_REVOKE_PERMISSIONS_IF_UNUSED') -AllowFail
    if ($r -match 'AUTO_REVOKE_PERMISSIONS_IF_UNUSED:\s*(\w+)') { return $Matches[1] }
    return $null
}

# インストールされているアプリの targetSdk（29 = Android 10 用の APK、30 = Android 11 用、31 = Android 12 用）
function Get-AppTargetSdk {
    $d = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $Package) -AllowFail
    if ($d -match 'targetSdk=(\d+)') { return [int]$Matches[1] }
    return $null
}

# API レベルから Android のバージョン（29 = 10、30 = 11、31・32 = 12（12L）、33 = 13 …）
function Get-AndroidMajor([int]$Sdk) {
    if ($Sdk -le 30) { return $Sdk - 19 }
    if ($Sdk -le 32) { return 12 }
    return $Sdk - 20
}

# この端末に合う APK の Android のバージョン（APK は Android 10・11・12 用がある。13 以降には 12 用）
function Get-BestApkMajor([int]$Sdk) {
    return [Math]::Max(10, [Math]::Min(12, (Get-AndroidMajor $Sdk)))
}

# テザリングのハードウェアアクセラレーション（オフロード）の HAL があるか（第 4 段階）。
# HAL は VINTF マニフェストで宣言されるので、それを読む（lshal は機種によって数分かかることがあるため使わない）
function Test-OffloadHal {
    $files = '/vendor/etc/vintf/manifest.xml /vendor/etc/vintf/manifest/*.xml /vendor/manifest.xml /odm/etc/vintf/manifest.xml /odm/etc/vintf/manifest/*.xml'
    $r = Invoke-Adb -Arguments @('shell', "cat $files 2>/dev/null | grep -c -i tetheroffload") -AllowFail
    return ($r -match '(?m)^\s*[1-9]\d*\s*$')
}

# アクセスポイントの帯域。アクセスポイントが ON のときだけ dumpsys wifi に出る（0 = 2.4GHz、1 = 5GHz、-1 = 自動）。
# Android 11 からは SoftApConfiguration の mBand（ビットの組み合わせ: 1 = 2.4GHz、2 = 5GHz、4 = 6GHz）。
# 行の名前は Android 11 が mApConfig.SoftApConfiguration.mBand、Android 12 が mCurrentSoftApConfiguration.mBand
function Get-ApBand {
    $r = Invoke-Adb -Arguments @('shell', 'dumpsys', 'wifi') -AllowFail
    if ($r -match '(?:mApConfig\.SoftApConfiguration|mCurrentSoftApConfiguration)\.mBand:\s*(\d+)') {
        $v = [int]$Matches[1]
        $names = @()
        if ($v -band 1) { $names += (T "2.4GHz" "2.4 GHz" "2,4 GHz") }
        if ($v -band 2) { $names += (T "5GHz" "5 GHz" "5 GHz") }
        if ($v -band 4) { $names += (T "6GHz" "6 GHz" "6 GHz") }
        if ($names.Count -eq 0) { return (T "不明（$v）" "unknown ($v)" "inconnu ($v)") }
        if ($names.Count -eq 1) { return $names[0] }
        $j = $names -join '／'
        return (T "自動（$j）" "auto ($($names -join '/'))" "auto ($($names -join '/'))")
    }
    if ($r -match 'mApConfig\.apBand:\s*(-?\d+)') {
        $v = $Matches[1]
        switch ($v) {
            '0' { return (T "2.4GHz" "2.4 GHz" "2,4 GHz") }
            '1' { return (T "5GHz" "5 GHz" "5 GHz") }
            '-1' { return (T "自動（2.4／5GHz）" "auto (2.4/5 GHz)" "auto (2,4/5 GHz)") }
            default { return (T "不明（$v）" "unknown ($v)" "inconnu ($v)") }
        }
    }
    return $null
}

# アプリの「Doze を早める」設定（Auto.kt の AutoPolicy.DOZE_EARLY と同じ文字列）。アプリが元に戻す前に削除された場合の後始末用
$DozeEarly = 'light_after_inactive_to=60000,inactive_to=300000,sensing_to=0,locating_to=0,idle_after_inactive_to=300000'

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
    if ($null -eq $b) { return (T "取得不可" "N/A" "indisponible") }
    if ($BucketNames.ContainsKey([int]$b)) {
        $n = $BucketNames[[int]$b]
        return (T "$n（$b）" "$n ($b)" "$n ($b)")
    }
    return "$b"
}

function NotSet { return (T "(未設定)" "(not set)" "(non défini)") }

function Show-Table($rows) {
    $rows | Format-Table -AutoSize -Property @(
        @{ Label = (T "項目" "Item" "Élément"); Expression = { $_.Name } }
        @{ Label = (T "現在" "Current" "Actuel"); Expression = { $_.Current } }
        @{ Label = (T "推奨" "Recommended" "Recommandé"); Expression = { $_.Want } }
        @{ Label = (T "状態" "Status" "État"); Expression = { $_.Status } }
    ) | Out-String -Width 200 | Write-Host
}

# ---- 開始 ----

$script:AdbPath = Find-Adb
$script:DeviceSerial = Select-Device
$model = Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model')
$sdk = [int](Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk'))
$sn = $script:DeviceSerial
Write-Host (T "端末: $model（$sn） / Android API $sdk" "Phone: $model ($sn) / Android API $sdk" "Téléphone : $model ($sn) / Android API $sdk")
Write-Host "adb : $($script:AdbPath)"
if ($sdk -lt 29 -or $sdk -gt 32) {
    Write-Warning (T "このアプリと設定は Android 10・11・12（API 29〜32）向けです。別のバージョンでは効かない項目があります。" "This app and these settings are for Android 10, 11 and 12 (API 29 to 32). Some items may not work on other versions." "Cette application et ces réglages sont prévus pour Android 10, 11 et 12 (API 29 à 32). Certains éléments peuvent ne pas fonctionner sur d'autres versions.")
}
$phoneMajor = Get-AndroidMajor $sdk
$bestMajor = Get-BestApkMajor $sdk

if ($Install) {
    if (-not (Test-Path $Install)) { throw (T "APK が見つかりません: $Install" "APK not found: $Install" "APK introuvable : $Install") }
    # APK は Android のバージョンごとに分かれている（_android10 / _android11 / _android12。それより新しい端末には入らない）
    $leaf = Split-Path $Install -Leaf
    if ($leaf -match '_android(\d+)') {
        $apkMajor = [int]$Matches[1]
        if ($apkMajor -gt $phoneMajor) {
            throw (T "$leaf は Android $apkMajor 以降用です。この端末（Android $phoneMajor）には _android$bestMajor.apk を使ってください。" "$leaf is for Android $apkMajor and later. Use the _android$bestMajor.apk on this phone (Android $phoneMajor)." "$leaf est prévu pour Android $apkMajor et ultérieur. Utilisez le fichier _android$bestMajor.apk sur ce téléphone (Android $phoneMajor).")
        }
        if ($apkMajor -lt $bestMajor) {
            Write-Warning (T "この端末は Android $phoneMajor です。_android$bestMajor.apk を使ってください（$leaf でも動きますが、Android $apkMajor 用です）。" "This phone runs Android $phoneMajor. Use the _android$bestMajor.apk ($leaf works, but it's made for Android $apkMajor)." "Ce téléphone utilise Android $phoneMajor. Utilisez le fichier _android$bestMajor.apk ($leaf fonctionne, mais il est prévu pour Android $apkMajor).")
        }
    }
    $apk = (Resolve-Path $Install).Path
    Write-Host (T "インストール: $apk" "Installing: $apk" "Installation : $apk")
    Write-Host (Invoke-Adb -Arguments @('install', '-r', $apk))
}

$items = @(
    [pscustomobject]@{ Name = (T "アクセスポイントの自動OFF" "Hotspot auto turn-off" "Arrêt automatique du point d'accès"); Ns = 'global'; Key = 'soft_ap_timeout_enabled'; Want = '0' }
    [pscustomobject]@{ Name = (T "Wi-Fiスキャン（常時）" "Wi-Fi scanning (always)" "Recherche Wi-Fi (permanente)"); Ns = 'global'; Key = 'wifi_scan_always_enabled'; Want = '0' }
    [pscustomobject]@{ Name = (T "Bluetoothスキャン（常時）" "Bluetooth scanning (always)" "Recherche Bluetooth (permanente)"); Ns = 'global'; Key = 'ble_scan_always_enabled'; Want = '0' }
    [pscustomobject]@{ Name = (T "充電中は画面をスリープしない" "Stay awake while charging" "Rester activé pendant la charge"); Ns = 'global'; Key = 'stay_on_while_plugged_in'; Want = '0' }
    [pscustomobject]@{ Name = (T "画面消灯までの時間（ミリ秒）" "Screen timeout (ms)" "Délai de mise en veille de l'écran (ms)"); Ns = 'system'; Key = 'screen_off_timeout'; Want = "$($ScreenOffSec * 1000)" }
    # 画面の不意な点灯を防ぐ（仕様 F-39）。未設定（機種の既定で ON のことがある）でも 0 にする。-Restore で未設定に戻る
    [pscustomobject]@{ Name = (T "通知で画面を点灯（アンビエント表示）" "Wake for notifications (ambient display)" "Allumer pour les notifications (affichage en veille)"); Ns = 'secure'; Key = 'doze_enabled'; Want = '0' }
    [pscustomobject]@{ Name = (T "常に表示（アンビエント表示）" "Always on (ambient display)" "Toujours activé (affichage en veille)"); Ns = 'secure'; Key = 'doze_always_on'; Want = '0' }
    [pscustomobject]@{ Name = (T "持ち上げて通知を確認" "Lift to check notifications" "Soulever pour voir les notifications"); Ns = 'secure'; Key = 'doze_pulse_on_pick_up'; Want = '0' }
    [pscustomobject]@{ Name = (T "ダブルタップで通知を確認" "Double-tap to check notifications" "Appuyer deux fois pour voir les notifications"); Ns = 'secure'; Key = 'doze_pulse_on_double_tap'; Want = '0' }
    [pscustomobject]@{ Name = (T "タップで通知を確認" "Tap to check notifications" "Appuyer pour voir les notifications"); Ns = 'secure'; Key = 'doze_tap_gesture'; Want = '0' }
    [pscustomobject]@{ Name = (T "持ち上げて画面を点灯" "Lift to wake" "Soulever pour activer"); Ns = 'secure'; Key = 'wake_gesture_enabled'; Want = '0' }
    [pscustomobject]@{ Name = (T "ダブルタップで画面を点灯" "Double-tap to wake" "Appuyer deux fois pour activer"); Ns = 'secure'; Key = 'double_tap_to_wake'; Want = '0' }
    # 「モバイルデータを常に ON」（仕様 F-41。Wi-Fi 接続中もモバイル回線を待機させる機能で、テザリングには使わない）
    [pscustomobject]@{ Name = (T "モバイルデータを常にON" "Mobile data always active" "Données mobiles toujours actives"); Ns = 'global'; Key = 'mobile_data_always_on'; Want = '0' }
)
if (-not $KeepLocation) {
    # 位置情報（仕様 F-41）。このアプリは使わない。Android 10 では location_mode が位置情報の ON/OFF そのもの
    $items += [pscustomobject]@{ Name = (T "位置情報（0 = OFF）" "Location (0 = OFF)" "Localisation (0 = OFF)"); Ns = 'secure'; Key = 'location_mode'; Want = '0' }
}
if ((Test-SecureLock) -and -not $PSBoundParameters.ContainsKey('ScreenOffSec')) {
    # 画面ロックがあると、消灯を短くするたびに PIN 入力が必要になり操作しづらいため、明示されたときだけ変える
    Write-Warning (T "画面ロック（PIN 等）があるため、画面消灯までの時間は変更しません（短くすると消灯のたびにロックがかかります）。変える場合は -ScreenOffSec 30 のように指定してください。" "The phone has a screen lock (PIN, etc.), so the screen timeout is not changed (a short timeout locks the phone every time the screen turns off). To change it anyway, add e.g. -ScreenOffSec 30." "Le téléphone a un verrouillage de l'écran (PIN, etc.) : le délai de mise en veille n'est pas modifié (un délai court verrouille le téléphone à chaque extinction). Pour le changer quand même, ajoutez par ex. -ScreenOffSec 30.")
    $items = @($items | Where-Object { $_.Key -ne 'screen_off_timeout' })
}
if ($IncludeOta) {
    $items += [pscustomobject]@{ Name = (T "システムの自動更新を止める" "Stop automatic system updates" "Arrêter les mises à jour automatiques du système"); Ns = 'global'; Key = 'ota_disable_automatic_update'; Want = '1' }
}
if ($sdk -gt 29) {
    # Android 11 以降は自動OFFの設定がこのキーではない
    $items = @($items | Where-Object { $_.Key -ne 'soft_ap_timeout_enabled' })
}
$offloadHal = Test-OffloadHal
if ($EnableOffload) {
    if ($offloadHal) {
        # 有効にするのは、テザリングを次に開始したときから
        $items += [pscustomobject]@{ Name = (T "テザリングのハードウェアアクセラレーションを無効にする（0 = 有効）" "Disable tethering hardware acceleration (0 = enabled)" "Désactiver l'accélération matérielle du partage (0 = activée)"); Ns = 'global'; Key = 'tether_offload_disabled'; Want = '0' }
    } else {
        Write-Warning (T "この端末にはテザリングのオフロード（tetheroffload）の HAL がないため、ハードウェアアクセラレーションの設定は変えません（変えても効果がありません）。" "This phone has no tethering offload HAL (tetheroffload), so the hardware acceleration setting is not changed (it would have no effect)." "Ce téléphone n'a pas de HAL de délestage du partage (tetheroffload) : le réglage d'accélération matérielle n'est pas modifié (il n'aurait aucun effet).")
    }
}

$installed = Test-Installed
$safeSerial = ($script:DeviceSerial -replace '[^A-Za-z0-9._-]', '_')
$backupPath = Join-Path $BackupDir "adb-setup-backup-$safeSerial.json"
$userApps = Get-UserApps

if ($Restore) {
    if (-not (Test-Path $backupPath)) { throw (T "この端末の保存データがありません: $backupPath" "No saved data for this phone: $backupPath" "Aucune donnée enregistrée pour ce téléphone : $backupPath") }
    $b = Get-Content -Path $backupPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($s in $b.settings) {
        Set-Setting $s.ns $s.key $s.value
        $v = if ($null -eq $s.value) { NotSet } else { $s.value }
        $what = "$($s.ns) $($s.key) = $v"
        Write-Host (T "戻しました: $what" "Restored: $what" "Rétabli : $what")
    }
    foreach ($a in @($b.apps)) {
        if (-not $a) { continue }
        $pkg = $a.pkg
        if ((Invoke-Adb -Arguments @('shell', 'pm', 'path', $pkg) -AllowFail) -notmatch '^package:') {
            Write-Host (T "スキップ（アンインストール済み）: $pkg" "Skipped (uninstalled): $pkg" "Ignoré (désinstallée) : $pkg")
            continue
        }
        # 未設定だったものは「許可」に戻す（未設定と同じ動作。ignore 以外は制限とみなされるため default にはしない）
        $mode = if ($a.bg) { $a.bg } else { 'allow' }
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $pkg, 'RUN_ANY_IN_BACKGROUND', $mode) -AllowFail | Out-Null
        $bucket = if ($null -ne $a.bucket -and @(10, 20, 30, 40) -contains [int]$a.bucket) { $BucketNames[[int]$a.bucket] } else { 'active' }
        Invoke-Adb -Arguments @('shell', 'am', 'set-standby-bucket', $pkg, $bucket) -AllowFail | Out-Null
        Write-Host (T "戻しました: $pkg（バックグラウンド $mode / 使用頻度 $bucket）" "Restored: $pkg (background $mode / standby bucket $bucket)" "Rétabli : $pkg (arrière-plan $mode / groupe de veille $bucket)")
    }
    if ($installed -and $null -ne $b.deviceIdleWhitelisted -and -not $b.deviceIdleWhitelisted -and (Test-Whitelisted)) {
        Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist', "-$Package") | Out-Null
        Write-Host (T "戻しました: 電池の最適化の除外を解除" "Restored: removed the battery optimization exemption" "Rétabli : exemption d'optimisation de la batterie retirée")
    }
    if ($installed -and $null -ne $b.phoneStateGranted -and -not $b.phoneStateGranted -and (Test-PhoneGranted)) {
        Invoke-Adb -Arguments @('shell', 'pm', 'revoke', $Package, 'android.permission.READ_PHONE_STATE') -AllowFail | Out-Null
        Write-Host (T "戻しました: 電話の権限を取り消し" "Restored: revoked the Phone permission" "Rétabli : autorisation Téléphone révoquée")
    }
    if ($installed -and $b.PSObject.Properties.Name -contains 'usageStatsMode' -and $b.usageStatsMode -ne 'allow' -and (Get-UsageMode) -eq 'allow') {
        $mode = if ($b.usageStatsMode) { $b.usageStatsMode } else { 'default' }
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $Package, 'GET_USAGE_STATS', $mode) -AllowFail | Out-Null
        Write-Host (T "戻しました: 使用状況へのアクセス（$mode）" "Restored: usage access ($mode)" "Rétabli : accès aux données d'utilisation ($mode)")
    }
    if ($installed -and $b.PSObject.Properties.Name -contains 'secureSettingsGranted' -and $null -ne $b.secureSettingsGranted -and -not $b.secureSettingsGranted -and (Test-SecureSettingsGranted)) {
        Invoke-Adb -Arguments @('shell', 'pm', 'revoke', $Package, 'android.permission.WRITE_SECURE_SETTINGS') -AllowFail | Out-Null
        Write-Host (T "戻しました: 設定変更の権限を取り消し" "Restored: revoked the settings permission" "Rétabli : autorisation de modifier les paramètres révoquée")
    }
    if ($installed -and $b.PSObject.Properties.Name -contains 'writeSettingsMode' -and $b.writeSettingsMode -ne 'allow' -and (Get-WriteSettingsMode) -eq 'allow') {
        # 許可を外すとアプリはテザリングを切り替えられなくなる（止めていたテザリングは手動で ON にする）
        $mode = if ($b.writeSettingsMode) { $b.writeSettingsMode } else { 'default' }
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $Package, 'WRITE_SETTINGS', $mode) -AllowFail | Out-Null
        Write-Host (T "戻しました: システム設定の変更（$mode）。起動時のテザリング ON と時間帯スケジュールは使えなくなります（アプリの設定で OFF にしてください）" "Restored: Modify system settings ($mode). Turning the hotspot on at startup and the quiet-hours schedule no longer work (turn them off in the app's settings)" "Rétabli : Modifier les paramètres système ($mode). L'activation au démarrage et la planification ne fonctionnent plus (désactivez-les dans les paramètres de l'application)")
    }
    if ($installed -and $sdk -ge 30 -and $b.PSObject.Properties.Name -contains 'autoRevokeMode' -and $b.autoRevokeMode -ne 'ignore' -and (Get-AutoRevokeMode) -eq 'ignore') {
        $mode = if ($b.autoRevokeMode) { $b.autoRevokeMode } else { 'default' }
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', '--uid', $Package, 'AUTO_REVOKE_PERMISSIONS_IF_UNUSED', $mode) -AllowFail | Out-Null
        Write-Host (T "戻しました: 使わないときの権限の削除（$mode）" "Restored: remove permissions if unused ($mode)" "Rétabli : suppression des autorisations si inutilisée ($mode)")
    }
    # アプリが「Doze を早める」設定を戻さないまま止まった（削除された）場合の後始末
    if ((Get-Setting 'global' 'device_idle_constants') -eq $DozeEarly) {
        Set-Setting 'global' 'device_idle_constants' $null
        Write-Host (T "戻しました: Doze の設定（アプリが早めたまま残っていたもの）" "Restored: Doze settings (left 'earlier' by the app)" "Rétabli : réglages Doze (laissés avancés par l'application)")
    }
    $done = Join-Path $BackupDir ("adb-setup-backup-$safeSerial.restored-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    Move-Item -Path $backupPath -Destination $done
    Write-Host (T "保存データは $done に移しました（次の -Apply で改めて元の値を保存します）。" "Saved data moved to $done (the next -Apply saves the original values again)." "Données enregistrées déplacées vers $done (le prochain -Apply enregistrera à nouveau les valeurs d'origine).")
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
    # 第 3 段階の権限。前の保存データにない（古い版で保存した）ときは今の状態を保存する
    $us = if ($old -and $old.PSObject.Properties.Name -contains 'usageStatsMode') { $old.usageStatsMode } elseif ($installed) { Get-UsageMode } else { $null }
    $ss = if ($old -and $old.PSObject.Properties.Name -contains 'secureSettingsGranted' -and $null -ne $old.secureSettingsGranted) { $old.secureSettingsGranted } elseif ($installed) { Test-SecureSettingsGranted } else { $null }
    # 第 4 段階。保存データにないときだけ今の状態を保存する
    $ws = if ($old -and $old.PSObject.Properties.Name -contains 'writeSettingsMode') { $old.writeSettingsMode } elseif ($installed) { Get-WriteSettingsMode } else { $null }
    # Android 11 から。保存データにないときだけ今の状態を保存する
    $ar = if ($old -and $old.PSObject.Properties.Name -contains 'autoRevokeMode') { $old.autoRevokeMode } elseif ($installed -and $sdk -ge 30) { Get-AutoRevokeMode } else { $null }
    $backup = [ordered]@{
        createdAt             = if ($old) { $old.createdAt } else { (Get-Date).ToString('s') }
        serial                = $script:DeviceSerial
        model                 = $model
        sdk                   = $sdk
        settings              = $saved
        apps                  = $savedApps
        deviceIdleWhitelisted = $wl
        phoneStateGranted     = $ph
        usageStatsMode        = $us
        secureSettingsGranted = $ss
        writeSettingsMode     = $ws
        autoRevokeMode        = $ar
    }
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $backup | ConvertTo-Json -Depth 5 | Set-Content -Path $backupPath -Encoding UTF8
    Write-Host (T "変更前の値を保存しました: $backupPath" "Saved the previous values: $backupPath" "Valeurs précédentes enregistrées : $backupPath")

    foreach ($i in $items) { Set-Setting $i.Ns $i.Key $i.Want }
    foreach ($p in $targets) {
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $p, 'RUN_ANY_IN_BACKGROUND', 'ignore') | Out-Null
        Invoke-Adb -Arguments @('shell', 'am', 'set-standby-bucket', $p, 'rare') -AllowFail | Out-Null
    }
    if ($targets.Count -gt 0) {
        $t = $targets -join ', '
        Write-Host (T "バックグラウンドを制限しました: $t" "Restricted background activity: $t" "Activité en arrière-plan restreinte : $t")
    }
    if ($installed) {
        Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist', "+$Package") | Out-Null
        Invoke-Adb -Arguments @('shell', 'pm', 'grant', $Package, 'android.permission.READ_PHONE_STATE') -AllowFail | Out-Null
        # アプリ別の通信量を読むため（読むだけ。ほかのアプリには何もしない）
        Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $Package, 'GET_USAGE_STATS', 'allow') -AllowFail | Out-Null
        if ($sdk -ge 30) {
            # Android 11 から。しばらくアプリを開かなくても「電話」の権限が外れないようにする
            Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', '--uid', $Package, 'AUTO_REVOKE_PERMISSIONS_IF_UNUSED', 'ignore') -AllowFail | Out-Null
        }
        if ($AllowAutoSwitch) {
            # 自動切り替え（バッテリーセーバー・Doze）。アプリは global low_power と device_idle_constants だけを変える
            $g = Invoke-Adb -Arguments @('shell', 'pm', 'grant', $Package, 'android.permission.WRITE_SECURE_SETTINGS') -AllowFail
            if ($g) { Write-Warning (T "設定変更の権限を付けられませんでした: $g（アプリが v0.4.0 以降か確認してください）" "Couldn't grant the settings permission: $g (check that the app is v0.4.0 or later)" "Impossible d'accorder l'autorisation de modifier les paramètres : $g (vérifiez que l'application est en v0.4.0 ou ultérieure)") }
        }
        if ($AllowTetherControl) {
            # 起動時のテザリング ON と時間帯スケジュール（テザリングの停止・再開）。アプリの設定で ON にしたときだけ使う
            $g = Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $Package, 'WRITE_SETTINGS', 'allow') -AllowFail
            if ($g) { Write-Warning (T "システム設定の変更を許可できませんでした: $g（アプリが v0.5.0 以降か確認してください）" "Couldn't allow Modify system settings: $g (check that the app is v0.5.0 or later)" "Impossible d'autoriser Modifier les paramètres système : $g (vérifiez que l'application est en v0.5.0 ou ultérieure)") }
        }
    } else {
        Write-Warning (T "アプリが未インストールのため、電池の最適化の除外と権限の付与は行っていません（-Install を先に）。" "The app isn't installed, so the battery optimization exemption and permissions were not applied (use -Install first)." "L'application n'est pas installée : l'exemption d'optimisation de la batterie et les autorisations n'ont pas été appliquées (utilisez d'abord -Install).")
    }
}

# ---- 結果（確認） ----

$ok = 'OK'
$change = T "要変更" "Change" "À changer"
$allowed = T "許可" "Allowed" "Autorisé"
$notAllowed = T "未許可" "Not allowed" "Non autorisé"
$optional = T "任意" "Optional" "Facultatif"
$rows = foreach ($i in $items) {
    $cur = Get-Setting $i.Ns $i.Key
    [pscustomobject]@{
        Name    = $i.Name
        Current = if ($null -eq $cur) { NotSet } else { $cur }
        Want    = $i.Want
        Status  = if ("$cur" -eq $i.Want) { $ok } else { $change }
    }
}
if ($installed) {
    $wlNow = Test-Whitelisted
    $excluded = T "除外" "Excluded" "Exclue"
    $rows = @($rows) + [pscustomobject]@{
        Name = (T "電池の最適化から除外（本アプリ）" "Battery optimization exemption (this app)" "Exemption d'optimisation de la batterie (cette appli)")
        Current = $(if ($wlNow) { $excluded } else { T "対象" "Optimized" "Optimisée" }); Want = $excluded; Status = $(if ($wlNow) { $ok } else { $change })
    }
    $phNow = Test-PhoneGranted
    $rows = @($rows) + [pscustomobject]@{
        Name = (T "電話の権限（本アプリ）" "Phone permission (this app)" "Autorisation Téléphone (cette appli)")
        Current = $(if ($phNow) { $allowed } else { $notAllowed }); Want = $allowed; Status = $(if ($phNow) { $ok } else { $change })
    }
    $um = Get-UsageMode
    $rows = @($rows) + [pscustomobject]@{
        Name = (T "使用状況へのアクセス（本アプリ）" "Usage access (this app)" "Accès aux données d'utilisation (cette appli)")
        Current = $(if ($um -eq 'allow') { $allowed } else { $notAllowed }); Want = $allowed; Status = $(if ($um -eq 'allow') { $ok } else { $change })
    }
    if ($sdk -ge 30) {
        $arNow = Get-AutoRevokeMode
        $rows = @($rows) + [pscustomobject]@{
            Name = (T "使わないときの権限の削除（本アプリ・Android 11 以降）" "Remove permissions if unused (this app, Android 11+)" "Suppression des autorisations si inutilisée (cette appli, Android 11+)")
            Current = $(if ($arNow -eq 'ignore') { 'OFF' } else { 'ON' }); Want = 'OFF'; Status = $(if ($arNow -eq 'ignore') { $ok } else { $change })
        }
        # 端末に合う APK（targetSdk 29 = Android 10 用、30 = 11 用、31 = 12 用）が入っているか
        $tsdk = Get-AppTargetSdk
        if ($null -ne $tsdk -and $tsdk -ge 29) {
            $appMajor = Get-AndroidMajor $tsdk
            if ($appMajor -lt $bestMajor) {
                Write-Warning (T "インストールされているのは Android $appMajor 用の APK（targetSdk $tsdk）です。_android$bestMajor.apk に入れ替えるのがおすすめです（設定とログは引き継がれます）。" "The installed APK is the Android $appMajor one (targetSdk $tsdk). Replacing it with the _android$bestMajor.apk is recommended (settings and logs are kept)." "L'APK installé est celui pour Android $appMajor (targetSdk $tsdk). Il est conseillé de le remplacer par le fichier _android$bestMajor.apk (paramètres et journaux conservés).")
            }
        }
    }
    $sg = Test-SecureSettingsGranted
    $rows = @($rows) + [pscustomobject]@{
        Name = (T "設定変更の権限（自動切り替え用・任意）" "Settings permission (auto switching, optional)" "Autorisation de modifier les paramètres (bascule automatique, facultatif)")
        Current = $(if ($sg) { T "付与" "Granted" "Accordée" } else { T "なし" "None" "Non" }); Want = $optional
        Status = $(if ($sg) { $ok } else { T "-AllowAutoSwitch で付与" "Grant with -AllowAutoSwitch" "Accorder avec -AllowAutoSwitch" })
    }
    $wm = Get-WriteSettingsMode
    $rows = @($rows) + [pscustomobject]@{
        Name = (T "システム設定の変更（起動時のテザリング ON・時間帯スケジュール用・任意）" "Modify system settings (hotspot on at startup, quiet-hours schedule, optional)" "Modifier les paramètres système (activation au démarrage, planification, facultatif)")
        Current = $(if ($wm -eq 'allow') { $allowed } else { $notAllowed }); Want = $optional
        Status = $(if ($wm -eq 'allow') { $ok } else { T "-AllowTetherControl で許可" "Allow with -AllowTetherControl" "Autoriser avec -AllowTetherControl" })
    }
} else {
    Write-Host (T "アプリは未インストールです（-Install でインストールできます）。" "The app isn't installed (install it with -Install)." "L'application n'est pas installée (installez-la avec -Install).")
}
Show-Table $rows

# 第 4 段階（表示だけ）。アプリからは読めない情報
$offload = Get-Setting 'global' 'tether_offload_disabled'
$band = Get-ApBand
Write-Host (T "テザリングの方式（表示だけ）:" "Tethering (display only):" "Partage de connexion (affichage seulement) :")
$accel = if ($offload -eq '1') { T "無効" "disabled" "désactivée" } else { T "有効（既定）" "enabled (default)" "activée (par défaut)" }
$raw = if ($null -eq $offload) { T "未設定" "not set" "non défini" } else { $offload }
$hal = if ($offloadHal) {
    T "あり（-EnableOffload で有効に戻せます）" "yes (-EnableOffload can re-enable it)" "oui (-EnableOffload peut la réactiver)"
} else {
    T "なし（この端末では設定を変えても効果がありません）" "no (changing the setting has no effect on this phone)" "non (modifier le réglage n'a aucun effet sur ce téléphone)"
}
Write-Host (T "  ハードウェアアクセラレーション: $accel（tether_offload_disabled = $raw） / オフロードの HAL: $hal" "  Hardware acceleration: $accel (tether_offload_disabled = $raw) / offload HAL: $hal" "  Accélération matérielle : $accel (tether_offload_disabled = $raw) / HAL de délestage : $hal")
$bandText = if ($band) { $band } else { T "取得不可（アクセスポイントが OFF のときは読めません）" "N/A (can't be read while the hotspot is off)" "indisponible (illisible quand le point d'accès est arrêté)" }
Write-Host (T "  アクセスポイントの帯域: $bandText（消費の差はアプリの比較テストで確かめられます）" "  Hotspot band: $bandText (compare power use with the app's comparison test)" "  Bande du point d'accès : $bandText (comparez la consommation avec le test comparatif de l'application)")
Write-Host ''

if ($userApps.Count -gt 0) {
    Write-Host (T "このアプリ以外のユーザーアプリ（バックグラウンド制限。システムアプリは対象外）:" "User apps other than this one (background restriction; system apps are not included):" "Applications utilisateur autres que celle-ci (restriction en arrière-plan ; applications système exclues) :")
    $appRows = foreach ($p in $userApps) {
        $bg = Get-BgMode $p
        $bk = Get-Bucket $p
        [pscustomobject]@{
            Package    = $p
            Background = if ($bg -eq 'ignore') { T "制限" "Restricted" "Restreinte" } elseif ($null -eq $bg) { T "許可（未設定）" "Allowed (not set)" "Autorisée (non défini)" } else { $bg }
            Bucket     = Format-Bucket $bk
            Status     = if ($Keep -contains $p) { T "除外" "Excluded" "Exclue" } elseif ($bg -eq 'ignore' -and $null -ne $bk -and $bk -ge 40) { $ok } else { T "未制限" "Not restricted" "Non restreinte" }
        }
    }
    $appRows | Format-Table -AutoSize -Property @(
        @{ Label = (T "パッケージ" "Package" "Paquet"); Expression = { $_.Package } }
        @{ Label = (T "バックグラウンド" "Background" "Arrière-plan"); Expression = { $_.Background } }
        @{ Label = (T "使用頻度" "Standby bucket" "Groupe de veille"); Expression = { $_.Bucket } }
        @{ Label = (T "状態" "Status" "État"); Expression = { $_.Status } }
    ) | Out-String -Width 200 | Write-Host
    if (-not ($Apply -and $RestrictApps)) {
        Write-Host (T "まとめて制限するには -Apply -RestrictApps（裏で動いてほしいアプリは -KeepApps で除外）。戻すのは -Restore。" "To restrict them all, use -Apply -RestrictApps (exclude apps that must run in the background with -KeepApps). Undo with -Restore." "Pour toutes les restreindre, utilisez -Apply -RestrictApps (excluez avec -KeepApps celles qui doivent tourner en arrière-plan). Annulez avec -Restore.")
    }
}

if ($Install) {
    # 権限の付与（-Apply）が済んでから起動する。一度起動しておかないと、再起動後の自動開始（BOOT_COMPLETED）が届かない
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', "$Package/.MainActivity") | Out-Null
    if ($Apply) { Write-Host (T "アプリを起動しました。" "App started." "Application démarrée.") }
    else { Write-Host (T "アプリを起動しました。端末の画面で「電話」の権限と、電池の最適化からの除外を許可してください。" "App started. On the phone, allow the Phone permission and the battery optimization exemption." "Application démarrée. Sur le téléphone, autorisez « Téléphone » et l'exemption d'optimisation de la batterie.") }
}
if (-not $Apply -and -not $Install) { Write-Host (T "確認のみです（変更していません）。適用は -Apply、元に戻すのは -Restore。" "Check only (nothing was changed). Apply with -Apply, undo with -Restore." "Vérification seulement (rien n'a été modifié). Appliquez avec -Apply, annulez avec -Restore.") }
