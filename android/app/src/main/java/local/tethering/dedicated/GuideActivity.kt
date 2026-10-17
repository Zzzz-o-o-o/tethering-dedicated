package local.tethering.dedicated

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MenuItem
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 設定ガイド（仕様 F-03〜F-09 / F-17 / F-18 / F-05、第 2 段階の F-34 / F-39 / F-40 / F-41、第 3 段階の F-31 / F-35 / F-42、
 * 第 4 段階の F-37 / F-38 / F-44 / F-45）。
 * 現在の値を読んで推奨値と並べ、OS の設定画面へ案内する。OS の設定はこの画面からは一切変更しない。
 * 区分: APK（このアプリで行う）／ADB事前設定（PC から tools/adb-setup.ps1 で行う）／手動設定／要検証。
 */
class GuideActivity : Activity() {
    private enum class Level(val mark: String, val color: Int) {
        OK("✓", R.color.status_ok),
        WARN("⚠", R.color.status_warn),
        INFO("・", R.color.text_secondary),
    }

    private class Item(
        val title: String,
        val tags: String,
        val read: ((Context) -> Pair<String, Level>)?,
        val recommend: String,
        val body: String,
        val actions: List<Pair<String, (Activity) -> Unit>> = emptyList(),
        val adb: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(c, "状態の確認と、OSの設定画面への案内だけを行います。この画面からOSの設定は変更しません。", 13.5f)
            Ui.small(c, "「ADB事前設定」の項目は、PCから tools\\adb-setup.ps1 でまとめて設定・元に戻すことができます（変更前の値を保存します）。")
        }
        val statusViews = ArrayList<Pair<Item, TextView>>()
        for ((section, items) in sections()) {
            Ui.text(page, section, 15f, R.color.text_secondary, bold = true).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 8), 0, Ui.dp(context, 6)) }
            for (item in items) {
                val c = Ui.card(page)
                Ui.text(c, item.title, 15.5f, bold = true)
                Ui.small(c, "［${item.tags}］")
                val status = Ui.text(c, if (item.read != null) "確認中…" else "（読み取りなし）", 14f)
                Ui.small(c, "推奨: ${item.recommend}")
                Ui.spacer(c, 4)
                Ui.text(c, item.body, 13.5f)
                item.adb?.let {
                    Ui.spacer(c, 4)
                    Ui.small(c, "ADB（PCから）:")
                    Ui.mono(c, it)
                }
                for ((label, action) in item.actions) Ui.button(c, label) { action(this) }
                statusViews.add(item to status)
            }
        }
        bg.execute {
            val results = statusViews.map { (item, _) -> item.read?.let { r -> runCatching { r(this) }.getOrElse { Fmt.NA to Level.INFO } } }
            main.post {
                statusViews.forEachIndexed { i, (_, view) ->
                    val r = results[i] ?: return@forEachIndexed
                    view.text = "${r.second.mark} 現在: ${r.first}"
                    view.setTextColor(getColor(r.second.color))
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun onOff(on: Boolean?, okWhenOff: Boolean = true, onText: String = "ON", offText: String = "OFF"): Pair<String, Level> = when (on) {
        null -> Fmt.NA to Level.INFO
        true -> onText to if (okWhenOff) Level.WARN else Level.OK
        false -> offText to if (okWhenOff) Level.OK else Level.WARN
    }

    private fun globalFlag(c: Context, key: String, onWarn: Boolean = true): Pair<String, Level> = when (Os.global(c, key)) {
        null -> "未設定（既定値）" to Level.INFO
        "0" -> "OFF" to if (onWarn) Level.OK else Level.INFO
        else -> "ON" to if (onWarn) Level.WARN else Level.INFO
    }

    /** Secure の ON/OFF の設定をまとめて表示する（未設定は機種の既定。機能自体がない機種も多い） */
    private fun secureFlags(c: Context, items: List<Pair<String, String>>): Pair<String, Level> {
        val values = items.map { (label, key) -> label to Os.secure(c, key) }
        val text = values.joinToString("\n") { (label, v) ->
            "$label: " + when (v) {
                null -> "未設定"
                "0" -> "OFF"
                else -> "ON"
            }
        }
        val level = when {
            values.any { it.second != null && it.second != "0" } -> Level.WARN
            values.all { it.second == null } -> Level.INFO
            else -> Level.OK
        }
        return "\n$text" to level
    }

    private fun sections(): List<Pair<String, List<Item>>> = listOf(
        "テザリングを止めないために（重要）" to listOf(
            Item(
                "アクセスポイントの自動OFF", "手動設定・ADB事前設定",
                { c ->
                    when (Os.global(c, TetherProbe.KEY_SOFT_AP_TIMEOUT)) {
                        null -> "未設定（AOSPの既定はON）" to Level.WARN
                        "0" -> "OFF" to Level.OK
                        else -> "ON（接続がないと自動で停止）" to Level.WARN
                    }
                },
                "OFF",
                "接続端末がしばらくないとアクセスポイントが自動で止まる設定です。ルーターとして常時使うならOFFにします。テザリング設定 → Wi-Fiアクセスポイント →「アクセスポイントを自動的にOFFにする」。",
                listOf("アクセスポイントの設定を開く" to { a -> SystemScreens.hotspot(a) }),
                "adb shell settings put global ${TetherProbe.KEY_SOFT_AP_TIMEOUT} 0\n（戻す: … ${TetherProbe.KEY_SOFT_AP_TIMEOUT} 1）",
            ),
            Item(
                "電池の最適化（このアプリ）", "APK・ADB事前設定",
                { c -> onOff(c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName), okWhenOff = false, onText = "除外済み", offText = "対象") },
                "除外",
                "対象のままだと、画面消灯が続いたとき（Doze）に再確認が最大 9 分ほど遅れることがあります。除外しても、このアプリは低頻度のアラームとイベントだけで動くので常時の電力消費は増えません。",
                listOf("除外を依頼する" to { a -> SystemScreens.requestIgnoreBatteryOptimizations(a) }, "一覧を開く" to { a -> SystemScreens.batteryOptimizationList(a) }),
                "adb shell dumpsys deviceidle whitelist +${BuildConfig.APPLICATION_ID}",
            ),
            Item(
                "データセーバー", "手動設定",
                { c ->
                    // 「OFF 以外」は ON（このアプリが例外に入っていても ON のあいだはテザリング不可）
                    when (c.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus?.let { it == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED }) {
                        null -> Fmt.NA to Level.INFO
                        true -> "OFF" to Level.OK
                        false -> "ON（テザリングが使えません）" to Level.WARN
                    }
                },
                "OFF",
                "データセーバーがONのあいだ、Android はテザリングを使えなくします。通信量を抑えたい場合は、アプリごとの「バックグラウンドデータ」を制限してください（下の「バックグラウンド通信の抑制」）。",
                listOf("データ使用量の設定を開く" to { a -> SystemScreens.dataUsage(a) }),
            ),
            Item(
                "画面ロック", "手動設定",
                { c -> onOff(c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure, onText = "PIN・パターン等あり", offText = "なし・スワイプ") },
                "なし（端末を安全な場所に置ける場合）",
                "PIN などのロックがあると、再起動のあとロックを解除するまで Android が起動完了を通知しないため、監視と起動後チェックが始まりません。",
                listOf("セキュリティ設定を開く" to { a -> SystemScreens.security(a) }),
            ),
        ),
        "画面・電源の省電力" to listOf(
            Item(
                "画面消灯までの時間", "手動設定・ADB事前設定",
                { c ->
                    val ms = Os.system(c, Settings.System.SCREEN_OFF_TIMEOUT)?.toLongOrNull()
                    when {
                        ms == null -> Fmt.NA to Level.INFO
                        ms < 60_000 -> "${ms / 1000}秒" to if (ms <= 30_000) Level.OK else Level.WARN
                        else -> "${ms / 60_000}分" to Level.WARN
                    }
                },
                "30秒以下（PIN などの画面ロックがある場合は運用開始後に）",
                "画面は消灯してもテザリングは止まりません。画面の点灯は大きな電力消費と発熱の原因になります。" +
                    "PIN などの画面ロックがあると消灯のたびにロックがかかり操作しづらいので、設定作業中は長めにしておき、運用を始めてから短くしてください。",
                listOf("画面の設定を開く" to { a -> SystemScreens.display(a) }),
                "adb shell settings put system screen_off_timeout 30000",
            ),
            Item(
                "画面の明るさ", "手動設定",
                { c ->
                    val auto = Os.system(c, Settings.System.SCREEN_BRIGHTNESS_MODE) == "1"
                    val v = Os.system(c, Settings.System.SCREEN_BRIGHTNESS)?.toIntOrNull()
                    if (auto) "自動" to Level.OK else (v?.let { "手動 $it/255" } ?: Fmt.NA) to if ((v ?: 255) <= 64) Level.OK else Level.INFO
                },
                "自動、または低め",
                "点灯するのは確認のときだけなので、低めで十分です。",
                listOf("画面の設定を開く" to { a -> SystemScreens.display(a) }),
            ),
            Item(
                "充電中は画面をスリープにしない", "ADB事前設定（開発者向けオプション）",
                { c ->
                    when (Os.global(c, Settings.Global.STAY_ON_WHILE_PLUGGED_IN)) {
                        null, "0" -> "OFF" to Level.OK
                        else -> "ON（給電中は画面が消えない）" to Level.WARN
                    }
                },
                "OFF",
                "給電しながら運用する場合、ONだと画面が点きっぱなしになり、発熱と焼き付きの原因になります。",
                listOf("開発者向けオプションを開く" to { a -> SystemScreens.developer(a) }),
                "adb shell settings put global stay_on_while_plugged_in 0",
            ),
            Item(
                "通知で画面が点く（アンビエント表示）", "ADB事前設定・手動設定",
                { c -> secureFlags(c, listOf("通知で点灯" to "doze_enabled", "常に表示" to "doze_always_on")) },
                "OFF",
                "新しい通知のたびに画面が点く機能です（機種によって「アンビエント表示」「新しい通知で画面をオン」など）。運用中の不意な点灯は電力の無駄になります。未設定は機種の既定で、この機能がない機種も多いです。",
                listOf("画面の設定を開く" to { a -> SystemScreens.display(a) }),
                "adb shell settings put secure doze_enabled 0\nadb shell settings put secure doze_always_on 0\n（まとめて: tools\\adb-setup.bat -Apply　戻す: -Restore）",
            ),
            Item(
                "持ち上げ・タップで画面が点く", "ADB事前設定・手動設定",
                { c ->
                    secureFlags(
                        c,
                        listOf(
                            "持ち上げて点灯" to "wake_gesture_enabled",
                            "ダブルタップで点灯" to "double_tap_to_wake",
                            "持ち上げて通知を確認" to "doze_pulse_on_pick_up",
                            "ダブルタップで通知を確認" to "doze_pulse_on_double_tap",
                            "タップで通知を確認" to "doze_tap_gesture",
                        ),
                    )
                },
                "OFF",
                "端末を動かしたり触れたりしたときに画面が点く機能です。置いたまま使うなら不要です。メーカー独自のジェスチャー（画面オフ時のジェスチャーなど）は、設定アプリの「ジェスチャー」などから手動で OFF にしてください。",
                listOf("画面の設定を開く" to { a -> SystemScreens.display(a) }),
                "adb shell settings put secure wake_gesture_enabled 0\nadb shell settings put secure double_tap_to_wake 0\n（まとめて: tools\\adb-setup.bat -Apply　戻す: -Restore）",
            ),
            Item(
                "バッテリーセーバー", "手動設定・要検証",
                { c -> onOff(c.getSystemService(PowerManager::class.java)?.isPowerSaveMode, okWhenOff = true) },
                "給電運用ではOFF",
                "給電中はOFFを推奨します（性能の制限を避けるため）。バッテリー駆動で長く持たせたい場合はONも選択肢です。このアプリはフォアグラウンドサービスなので監視は続きますが、機種によっては通信や性能に制限がかかるため、実機で確認してください。",
                listOf("バッテリーセーバーの設定を開く" to { a -> SystemScreens.batterySaver(a) }),
            ),
            Item(
                "バッテリー駆動中の自動切り替え", "APK・ADB事前設定",
                { c ->
                    val prefs = Prefs(c)
                    val on = prefs.bool(Defs.AUTO_SAVER) || prefs.bool(Defs.AUTO_DOZE)
                    val permitted = AutoSwitch.permitted(c)
                    when {
                        !on -> "使わない" + (if (permitted) "（権限は付与済み）" else "") to Level.INFO
                        !permitted -> "ON だが設定変更の権限がない（ADB で付与が必要）" to Level.WARN
                        else -> listOfNotNull(
                            "バッテリーセーバー".takeIf { prefs.bool(Defs.AUTO_SAVER) },
                            "Doze を早める".takeIf { prefs.bool(Defs.AUTO_DOZE) },
                        ).joinToString("・") + " を自動で切り替え" to Level.OK
                    }
                },
                "バッテリー駆動で長く使うなら ON",
                "バッテリー駆動中（または残量が設定以下のとき）にバッテリーセーバーを ON にし、Doze（省電力待機）を早めます。給電すると元に戻します。" +
                    "データセーバー・機内モード・モバイルデータは変えません。使うには PC から設定変更の権限を付けてから、設定の「自動切り替え」を ON にします。",
                listOf("設定を開く" to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
                "tools\\adb-setup.bat -Apply -AllowAutoSwitch\n（個別に: adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS）",
            ),
            Item(
                "充電上限・バイパス充電", "手動確認・要検証",
                { _ -> "非対応として扱う（Android 10 標準に設定なし）" to Level.INFO },
                "メーカーの機能があれば使う",
                "Android 10 の標準には充電の上限やバイパス給電の設定がありません。メーカー独自の「電池保護」などがあれば利用し、なければ熱のこもらない置き場所・ケースを外す・温度警告の活用で対策します。給電中でも発熱と劣化はなくなりません。" +
                    "スマートプラグがあれば、下の「スマートプラグで充電の範囲を保つ」で代わりにできます。",
                listOf("電池の設定を開く" to { a -> SystemScreens.battery(a) }),
            ),
        ),
        "無線機能" to listOf(
            Item(
                "Bluetooth", "手動設定",
                { _ ->
                    @Suppress("DEPRECATION")
                    val bt = BluetoothAdapter.getDefaultAdapter()
                    when {
                        bt == null -> "非搭載" to Level.OK
                        bt.isEnabled -> "ON" to Level.INFO
                        else -> "OFF" to Level.OK
                    }
                },
                "OFF（Bluetoothテザリングを使わない場合）",
                "使わない無線はOFFにすると、わずかに電力と電波の干渉が減ります。",
                listOf("Bluetoothの設定を開く" to { a -> SystemScreens.bluetooth(a) }),
            ),
            Item(
                "NFC", "手動設定",
                { c ->
                    val nfc = NfcAdapter.getDefaultAdapter(c)
                    when {
                        nfc == null -> "非搭載" to Level.OK
                        nfc.isEnabled -> "ON" to Level.INFO
                        else -> "OFF" to Level.OK
                    }
                },
                "OFF",
                "ルーター用途では使いません。",
                listOf("NFCの設定を開く" to { a -> SystemScreens.nfc(a) }),
            ),
            Item(
                "位置情報", "手動設定・ADB事前設定",
                { c ->
                    when (c.getSystemService(LocationManager::class.java)?.isLocationEnabled) {
                        null -> Fmt.NA to Level.INFO
                        true -> "ON" to Level.WARN
                        false -> "OFF" to Level.OK
                    }
                },
                "OFF（このアプリは使いません）",
                "このアプリは位置情報を使いません。他のアプリの位置取得（GPS・Wi-Fi・モバイル網）による電力消費を抑えられます。",
                listOf("位置情報の設定を開く" to { a -> SystemScreens.location(a) }),
                "adb shell settings put secure location_mode 0\n（まとめて: tools\\adb-setup.bat -Apply　戻す: -Restore）",
            ),
            Item(
                "モバイルデータを常に ON", "ADB事前設定（開発者向けオプション）",
                { c ->
                    when (Os.global(c, "mobile_data_always_on")) {
                        "0" -> "OFF" to Level.OK
                        null -> "未設定（既定は ON の機種が多い）" to Level.WARN
                        else -> "ON" to Level.WARN
                    }
                },
                "OFF",
                "Wi-Fi に接続しているあいだもモバイル回線を待機させておく機能です。テザリング専用機ではモバイル回線が常に主回線なので不要で、OFF にしてもテザリングには影響しません。",
                listOf("開発者向けオプションを開く" to { a -> SystemScreens.developer(a) }),
                "adb shell settings put global mobile_data_always_on 0",
            ),
            Item(
                "Wi-Fiスキャン（常時）", "手動設定・ADB事前設定",
                { c -> globalFlag(c, "wifi_scan_always_enabled") },
                "OFF",
                "Wi-FiがOFFでも周囲のWi-Fiを探し続ける機能です。位置情報 → Wi-FiとBluetoothのスキャン で変更できます。",
                listOf("位置情報の設定を開く" to { a -> SystemScreens.location(a) }),
                "adb shell settings put global wifi_scan_always_enabled 0",
            ),
            Item(
                "Bluetoothスキャン（常時）", "手動設定・ADB事前設定",
                { c -> globalFlag(c, "ble_scan_always_enabled") },
                "OFF",
                "BluetoothがOFFでも周囲の機器を探し続ける機能です。",
                listOf("位置情報の設定を開く" to { a -> SystemScreens.location(a) }),
                "adb shell settings put global ble_scan_always_enabled 0",
            ),
        ),
        "回線方式の見直し" to listOf(
            Item(
                "優先ネットワークの種類（5G／4G）", "手動設定・要検証",
                { c ->
                    val mode = Os.preferredNetworkMode(c)
                    when {
                        mode == null -> Fmt.NA to Level.INFO
                        NetworkModes.hasNr(mode) -> NetworkModes.label(mode) to Level.WARN
                        else -> "${NetworkModes.label(mode)}（5G を使わない設定なので変更不要）" to Level.OK
                    }
                },
                "5G 対応の端末は 4G（LTE）に固定",
                "5G の電波を探したり 4G と行き来したりする電力を減らせる可能性があります（エリアによっては速度が落ちます）。" +
                    "設定 → ネットワークとインターネット → モバイル ネットワーク → 詳細設定 → 優先ネットワークの種類 で「4G」や「LTE」を選びます。効果は比較テストで確かめてください。",
                listOf("モバイル ネットワークの設定を開く" to { a -> SystemScreens.mobileNetwork(a) }),
            ),
            Item(
                "VoLTE（データ専用 SIM）", "手動設定・要検証",
                { _ -> "取得不可（SIM ごとの設定のため、設定画面で確認してください）" to Level.INFO },
                "データ専用 SIM では OFF",
                "通話をしないデータ専用 SIM では、VoLTE（4G での音声）を OFF にすると、使わない IMS の登録を保つための通信と電力が減る可能性があります。" +
                    "設定 → ネットワークとインターネット → モバイル ネットワーク の「VoLTE」「4G 通話」「拡張 4G LTE モード」などを OFF にします。効果は比較テスト「VoLTE を OFF」で確かめてください。",
                listOf(
                    "モバイル ネットワークの設定を開く" to { a -> SystemScreens.mobileNetwork(a) },
                    "比較テストを開く" to { a -> a.startActivity(Intent(a, ExperimentActivity::class.java)) },
                ),
            ),
        ),
        "テザリングの方式・時間帯・給電" to listOf(
            Item(
                "時間帯スケジュール（使わない時間帯は止める）", "APK・要検証",
                { c ->
                    val prefs = Prefs(c)
                    val q = QuietHours.parse(prefs.text(Defs.QUIET_HOURS))
                    val permitted = TetherControl.permitted(c)
                    when {
                        !prefs.bool(Defs.TETHER_SCHEDULE) -> "使わない" + (if (permitted) "（許可は済み）" else "") to Level.INFO
                        !permitted -> "ON だが「システム設定の変更」が未許可" to Level.WARN
                        q == null -> "ON だが時間帯が未設定" to Level.WARN
                        else -> "${q.label} は止める（接続 0 台が 10 分続いたら）" to Level.OK
                    }
                },
                "毎日使わない時間帯がはっきりしているなら ON",
                "使わない時間帯に入ったら、接続端末が 0 台の状態が 10 分続くのを待ってテザリングを止め、時間帯の終わりに再開します。止めている間は接続 0 台の待機電力がかかりません。" +
                    "このアプリが止めたときだけ再開し、利用者が手で ON にしたらその時間帯はもう止めません。再開に 3 回失敗したら、テザリング停止の異常として通知します。" +
                    "Android 10 の非公開の仕組みを使うため、端末によっては使えません（設定の「切り替えられるか確認する」で、止めずに確かめられます）。",
                listOf(
                    "設定を開く" to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) },
                    "「システム設定の変更」を許可する" to { a -> SystemScreens.writeSettings(a) },
                ),
                "adb shell appops set ${BuildConfig.APPLICATION_ID} WRITE_SETTINGS allow\n（まとめて: tools\\adb-setup.bat -Apply -AllowTetherControl　戻す: -Restore）",
            ),
            Item(
                "アクセスポイントの帯域（2.4GHz／5GHz）", "手動設定・要検証",
                { _ -> "アプリからは読めません（PC の tools\\adb-setup.bat で表示します）" to Level.INFO },
                "比較テストで消費の少ない方を選ぶ",
                "5GHz は速く混雑しにくい一方、壁などで弱まりやすく、2.4GHz は遠くまで届きます。消費の差は端末によって違うので、比較テスト「アクセスポイントを 5GHz にする／2.4GHz にする」で実測して決めてください。" +
                    "帯域を変えると接続端末はつなぎ直しになり、5GHz に対応していない接続端末はつながらなくなります。",
                listOf(
                    "アクセスポイントの設定を開く" to { a -> SystemScreens.hotspot(a) },
                    "比較テストを開く" to { a -> a.startActivity(Intent(a, ExperimentActivity::class.java)) },
                ),
                "adb shell dumpsys wifi | findstr apBand\n（0 = 2.4GHz、1 = 5GHz、-1 = 自動）",
            ),
            Item(
                "テザリングのハードウェアアクセラレーション", "手動設定・ADB事前設定・要検証",
                { c ->
                    when (Os.global(c, "tether_offload_disabled")) {
                        null, "0" -> "ON（既定）" to Level.OK
                        else -> "OFF（開発者向けオプションで無効）" to Level.INFO
                    }
                },
                "ON（対応している端末だけ効果があります）",
                "テザリングの中継を CPU ではなく通信用のチップに任せる機能で、対応していれば CPU の負担と消費が減ります。対応していない端末（オフロード用の HAL がない端末）では、ON にしても何も変わりません。" +
                    "アプリからは対応しているか読めないので、PC の tools\\adb-setup.bat で確認してください。効果は比較テスト「ハードウェアアクセラレーションを ON」で確かめられます。",
                listOf("開発者向けオプションを開く" to { a -> SystemScreens.developer(a) }),
                "adb shell settings put global tether_offload_disabled 0\n（対応の確認: adb shell lshal | findstr tetheroffload　まとめて: tools\\adb-setup.bat -Apply -EnableOffload）",
            ),
            Item(
                "スマートプラグで充電の範囲を保つ", "APK・要検証（プラグ次第）",
                { c ->
                    val prefs = Prefs(c)
                    if (!prefs.bool(Defs.PLUG)) "使わない" to Level.INFO
                    else "${PlugUrls.label(prefs.text(Defs.PLUG_KIND))}・${prefs.int(Defs.PLUG_LOW)}〜${prefs.int(Defs.PLUG_HIGH)}%" to Level.OK
                },
                "給電しながら長く運用するなら、40〜80% の範囲で使う",
                "充電上限のない端末で、つなぎっぱなしの満充電と高温を避けるために、スマートプラグの ON／OFF で電池残量を範囲の中に保ちます。高温のときは給電を止めます。" +
                    "Shelly・Tasmota はアドレスを入れるだけで使え、そのほかのプラグや Home Assistant は ON／OFF の URL を指定します。" +
                    "プラグをこの端末のテザリングにつなぐ場合、テザリングを止めている間はプラグに届きません（プラグ側の状態のまま）。Shelly は OFF のあと自動で ON に戻すタイマーを付けるので、このアプリが止まっても電池切れになりません。",
                listOf("設定を開く" to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
            ),
            Item(
                "外部への通知（ntfy・Discord・Slack）", "APK",
                { c ->
                    val prefs = Prefs(c)
                    val d = ExtNotify.parse(prefs.text(Defs.EXT_URL))
                    when {
                        !prefs.bool(Defs.EXT_NOTIFY) -> "使わない" to Level.INFO
                        d == null -> "ON だが通知先の URL が正しくない" to Level.WARN
                        else -> "${d.label} に送る" to Level.OK
                    }
                },
                "離れた場所に置くなら ON",
                "端末の画面を見なくても、回線・テザリングが止まっていた時間（復旧してから送ります）、温度・電池の警告、再起動を、スマートフォンや PC で受け取れます。" +
                    "ntfy はアプリを入れてトピックを購読するだけで使えます（トピック名は他人に推測されない長いものにしてください）。送るのは状態だけで、IP アドレス・SSID・接続端末の情報は送りません。",
                listOf("設定を開く" to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
            ),
        ),
        "同期・通知・通話" to listOf(
            Item(
                "自動同期", "手動設定",
                { _ -> if (ContentResolver.getMasterSyncAutomatically()) "ON" to Level.INFO else "OFF" to Level.OK },
                "OFF（必要なアカウントだけ手動で同期）",
                "アカウントの自動同期は、端末自身のバックグラウンド通信と電力消費の主な原因です。",
                listOf("アカウントと同期を開く" to { a -> SystemScreens.sync(a) }),
            ),
            Item(
                "バックグラウンド通信の抑制", "手動設定・要検証",
                null,
                "通信量の多いアプリの「バックグラウンドデータ」をOFF",
                "アプリ情報 → モバイルデータ → バックグラウンドデータ をOFFにします。データセーバーはテザリングを止めるので使いません。どのアプリが通信しているかはデータ使用量の画面で確認できます。",
                listOf("データ使用量の設定を開く" to { a -> SystemScreens.dataUsage(a) }, "アプリの整理を開く" to { a -> a.startActivity(Intent(a, AppsActivity::class.java)) }),
            ),
            Item(
                "通知の抑制", "手動設定",
                { c ->
                    val nm = c.getSystemService(NotificationManager::class.java)
                    val enabled = nm?.areNotificationsEnabled()
                    val alert = nm?.getNotificationChannel(Notifier.CH_ALERT)?.importance
                    when {
                        enabled == false -> "このアプリの通知がOFF" to Level.WARN
                        alert == NotificationManager.IMPORTANCE_NONE -> "「異常・警告」がOFF" to Level.WARN
                        else -> "このアプリの警告は有効" to Level.OK
                    }
                },
                "不要なアプリの通知はOFF、このアプリの「異常・警告」はON",
                "運用の妨げになる通知を減らします。重要な警告（このアプリの「異常・警告」）は残してください。",
                listOf("このアプリの通知設定を開く" to { a -> SystemScreens.appNotifications(a) }),
            ),
            Item(
                "通話・SMS", "手動設定・要検証",
                null,
                "電話・SMSのアプリは無効化しない。着信音などは音の設定で小さく",
                "緊急通報や回線維持への影響を避けるため、電話・SMS 関連のシステムアプリは止めません。データ専用SIMでは通話は使われません。通常のアプリだけで通話・SMSを完全に止めることはできません。",
                listOf("音の設定を開く" to { a -> SystemScreens.sound(a) }),
            ),
            Item(
                "不要なアプリの整理", "APK・ADB事前設定",
                { c ->
                    val n = c.packageManager.getInstalledApplications(0).count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != c.packageName }
                    "ユーザーアプリ ${n}個" to Level.INFO
                },
                "使わないアプリはアンインストール",
                "ユーザーが入れたアプリだけを一覧にします（システムアプリの一括無効化はしません）。アンインストール・無効化はアプリ情報の画面から行います。",
                listOf("アプリの一覧を開く" to { a -> a.startActivity(Intent(a, AppsActivity::class.java)) }),
                "adb shell pm disable-user --user 0 <パッケージ名>\n（戻す: adb shell pm enable <パッケージ名>）",
            ),
            Item(
                "アプリのバックグラウンド制限", "ADB事前設定",
                { c ->
                    val n = c.packageManager.getInstalledApplications(0).count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != c.packageName }
                    "ユーザーアプリ ${n}個（制限の状態は PC の adb-setup で確認できます）" to Level.INFO
                },
                "このアプリ以外をまとめて制限（必要なアプリは除外）",
                "このアプリ以外のユーザーアプリを「バックグラウンドでの動作を制限」し、使用頻度を「低い」（rare）にして、裏での動作と通信を減らします。" +
                    "アプリを開けば普通に使えます。裏で動いてほしいアプリ（VPN・遠隔操作・通知が必要なものなど）は除外してください。",
                listOf("アプリの一覧を開く" to { a -> a.startActivity(Intent(a, AppsActivity::class.java)) }),
                "tools\\adb-setup.bat -Apply -RestrictApps\n（除外: -KeepApps パッケージ名,…　戻す: -Restore）\n" +
                    "個別に:\nadb shell cmd appops set <パッケージ名> RUN_ANY_IN_BACKGROUND ignore\nadb shell am set-standby-bucket <パッケージ名> rare",
            ),
            Item(
                "使用状況へのアクセス（アプリ別の通信量）", "APK・ADB事前設定",
                { c -> onOff(AppUsageProbe.hasAccess(c), okWhenOff = false, onText = "許可", offText = "未許可") },
                "許可（読むだけ）",
                "「省電力の分析」で、アプリ別のモバイル通信量と、テザリング（接続端末）の通信量を分けて見るために使います。ほかのアプリには何もしません。",
                listOf(
                    "使用状況へのアクセスを開く" to { a -> SystemScreens.usageAccess(a) },
                    "省電力の分析を開く" to { a -> a.startActivity(Intent(a, AnalysisActivity::class.java)) },
                ),
                "adb shell appops set ${BuildConfig.APPLICATION_ID} GET_USAGE_STATS allow\n（tools\\adb-setup.bat -Apply でも許可します）",
            ),
            Item(
                "接続端末の「従量制」設定", "手動設定（接続端末）",
                null,
                "接続端末を「従量制」「省データモード」に",
                "接続端末の OS 更新・クラウド同期は、テザリングの通信量と消費の大きな原因です。Windows・Mac・iPhone・Android の手順をまとめています。",
                listOf(
                    "接続端末の設定ガイドを開く" to { a -> a.startActivity(Intent(a, ClientGuideActivity::class.java)) },
                    "通信量（時間帯別）を開く" to { a -> a.startActivity(Intent(a, TrafficActivity::class.java)) },
                ),
            ),
        ),
        "更新管理" to listOf(
            Item(
                "システムの自動更新（OS OTA）", "ADB事前設定・手動設定・要検証",
                { c ->
                    when (Os.global(c, "ota_disable_automatic_update")) {
                        "1" -> "自動更新しない" to Level.INFO
                        else -> "自動更新する（既定）" to Level.INFO
                    }
                },
                "運用中は手動。月1回などメンテナンスモードで更新",
                "自動更新は運用中の突然の再起動につながります。止める場合も、セキュリティ更新を止めっぱなしにせず、定期的に「メンテナンスモード → 更新 → 再起動 → 起動後チェックの確認」を行ってください。この設定に従わない機種もあります。",
                listOf("システムアップデートを開く" to { a -> SystemScreens.systemUpdate(a) }, "開発者向けオプションを開く" to { a -> SystemScreens.developer(a) }),
                "adb shell settings put global ota_disable_automatic_update 1\n（戻す: … ota_disable_automatic_update 0）",
            ),
            Item(
                "Play ストアのアプリ自動更新", "手動設定",
                null,
                "「Wi-Fi経由のみ」または「自動更新しない」",
                "Play ストア → プロフィール → 設定 → ネットワーク設定 → アプリの自動更新。この端末はモバイル回線で通信するので「Wi-Fi経由のみ」なら実質止まります。定期的に手動で更新してください。",
                listOf("Play ストアを開く" to { a -> SystemScreens.playStore(a) }),
            ),
            Item(
                "Google Play システムアップデート", "手動確認",
                null,
                "定期的に状態を確認",
                "OS が管理するため止められません。設定 → セキュリティ → Google Play システムアップデート で確認します。適用に再起動が必要な場合があります。",
                listOf("セキュリティ設定を開く" to { a -> SystemScreens.security(a) }),
            ),
            Item(
                "このアプリの更新", "ADB",
                { _ -> "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）" to Level.INFO },
                "PCから上書きインストール",
                "新しい APK を adb install -r で上書きします（同じ署名鍵が必要）。更新後は監視が自動で再開します。作業前にメンテナンスモードにしておくと誤検知の通知が出ません。",
                adb = "adb install -r TetheringDedicated-v<版>.apk",
            ),
        ),
    )
}
