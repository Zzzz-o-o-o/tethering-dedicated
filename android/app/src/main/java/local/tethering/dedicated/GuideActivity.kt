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
            Ui.text(
                c,
                tr(
                    "状態の確認と、OSの設定画面への案内だけを行います。この画面からOSの設定は変更しません。",
                    "This screen only checks the state and opens the right system settings. It never changes system settings itself.",
                    "Cet écran vérifie seulement l'état et ouvre les bons paramètres du système. Il ne modifie jamais lui-même les paramètres.",
                ),
                13.5f,
            )
            Ui.small(
                c,
                tr(
                    "「ADB事前設定」の項目は、PCから tools\\adb-setup.ps1 でまとめて設定・元に戻すことができます（変更前の値を保存します）。",
                    "Items tagged “${adbSetup()}” can be applied and undone together from a PC with tools\\adb-setup.ps1 (the previous values are saved first).",
                    "Les éléments marqués « ${adbSetup()} » peuvent être appliqués et annulés ensemble depuis un PC avec tools\\adb-setup.ps1 (les valeurs précédentes sont enregistrées d'abord).",
                ),
            )
        }
        val statusViews = ArrayList<Pair<Item, TextView>>()
        for ((section, items) in sections()) {
            Ui.text(page, section, 15f, R.color.text_secondary, bold = true).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 8), 0, Ui.dp(context, 6)) }
            for (item in items) {
                val c = Ui.card(page)
                Ui.text(c, item.title, 15.5f, bold = true)
                Ui.small(c, item.tags.let { tr("［$it］", "[$it]", "[$it]") })
                val status = Ui.text(c, if (item.read != null) tr("確認中…", "Checking…", "Vérification…") else tr("（読み取りなし）", "(not read)", "(non lu)"), 14f)
                Ui.small(c, item.recommend.let { tr("推奨: $it", "Recommended: $it", "Recommandé : $it") })
                Ui.spacer(c, 4)
                Ui.text(c, item.body, 13.5f)
                item.adb?.let {
                    Ui.spacer(c, 4)
                    Ui.small(c, tr("ADB（PCから）:", "ADB (from a PC):", "ADB (depuis un PC) :"))
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
                    val mark = r.second.mark
                    val v = r.first
                    view.text = tr("$mark 現在: $v", "$mark Now: $v", "$mark Actuel : $v")
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

    // ---- 区分（タグ） ----

    private fun manual() = tr("手動設定", "Manual", "Manuel")

    private fun adbSetup() = tr("ADB事前設定", "ADB setup", "Config. ADB")

    private fun apk() = tr("APK", "This app", "Cette appli")

    private fun verify() = tr("要検証", "To verify", "À vérifier")

    private fun devOptions() = tr("開発者向けオプション", "Developer options", "Options pour les développeurs")

    private fun tags(vararg t: String) = Fmt.join(t.toList())

    // ---- よく使う言い回し ----

    private fun notUsed() = tr("使わない", "Not used", "Désactivé")

    private fun openSettingsApp() = tr("設定を開く", "Open Settings", "Ouvrir les paramètres")

    private fun openDeveloper() = tr("開発者向けオプションを開く", "Open Developer options", "Ouvrir les options pour les développeurs")

    private fun openDisplay() = tr("画面の設定を開く", "Open display settings", "Ouvrir les paramètres d'affichage")

    private fun openLocation() = tr("位置情報の設定を開く", "Open Location settings", "Ouvrir les paramètres de localisation")

    private fun openMobile() = tr("モバイル ネットワークの設定を開く", "Open Mobile network settings", "Ouvrir les paramètres du réseau mobile")

    private fun openExperiment() = tr("比較テストを開く", "Open Comparison test", "Ouvrir le test comparatif")

    private fun openApps() = tr("アプリの一覧を開く", "Open the app list", "Ouvrir la liste des applications")

    private fun openDataUsage() = tr("データ使用量の設定を開く", "Open Data usage settings", "Ouvrir les paramètres de consommation des données")

    private fun openSecurity() = tr("セキュリティ設定を開く", "Open Security settings", "Ouvrir les paramètres de sécurité")

    private fun bulk() = tr("（まとめて: tools\\adb-setup.bat -Apply　戻す: -Restore）", "(all at once: tools\\adb-setup.bat -Apply; undo: -Restore)", "(en une fois : tools\\adb-setup.bat -Apply ; annuler : -Restore)")

    private fun allowWriteSettings() = tr("「システム設定の変更」を許可する", "Allow “Modify system settings”", "Autoriser « Modifier les paramètres système »")

    /** テザリングの切り替え（起動時の ON・時間帯スケジュール）に要る許可を、PC から付けるコマンド */
    private fun tetherControlAdb() = "adb shell appops set ${BuildConfig.APPLICATION_ID} WRITE_SETTINGS allow\n" +
        tr(
            "（まとめて: tools\\adb-setup.bat -Apply -AllowTetherControl　戻す: -Restore）",
            "(all at once: tools\\adb-setup.bat -Apply -AllowTetherControl; undo: -Restore)",
            "(en une fois : tools\\adb-setup.bat -Apply -AllowTetherControl ; annuler : -Restore)",
        )

    private fun notSetDefault() = tr("未設定（既定値）", "Not set (default)", "Non défini (par défaut)")

    private fun userApps(n: Int) = tr("ユーザーアプリ ${n}個", "$n user app(s)", "$n application(s) utilisateur")

    private fun onOff(on: Boolean?, okWhenOff: Boolean = true, onText: String = "ON", offText: String = "OFF"): Pair<String, Level> = when (on) {
        null -> Fmt.NA to Level.INFO
        true -> onText to if (okWhenOff) Level.WARN else Level.OK
        false -> offText to if (okWhenOff) Level.OK else Level.WARN
    }

    private fun globalFlag(c: Context, key: String, onWarn: Boolean = true): Pair<String, Level> = when (Os.global(c, key)) {
        null -> notSetDefault() to Level.INFO
        "0" -> "OFF" to if (onWarn) Level.OK else Level.INFO
        else -> "ON" to if (onWarn) Level.WARN else Level.INFO
    }

    /** Secure の ON/OFF の設定をまとめて表示する（未設定は機種の既定。機能自体がない機種も多い） */
    private fun secureFlags(c: Context, items: List<Pair<String, String>>): Pair<String, Level> {
        val values = items.map { (label, key) -> label to Os.secure(c, key) }
        val text = values.joinToString("\n") { (label, v) ->
            SettingsCodec.issue(
                label,
                when (v) {
                    null -> tr("未設定", "not set", "non défini")
                    "0" -> "OFF"
                    else -> "ON"
                },
            )
        }
        val level = when {
            values.any { it.second != null && it.second != "0" } -> Level.WARN
            values.all { it.second == null } -> Level.INFO
            else -> Level.OK
        }
        return "\n$text" to level
    }

    private fun sections(): List<Pair<String, List<Item>>> = listOf(
        tr("テザリングを止めないために（重要）", "Keeping the hotspot running (important)", "Garder le point d'accès actif (important)") to listOf(
            Item(
                tr("アクセスポイントの自動OFF", "Hotspot auto turn-off", "Arrêt automatique du point d'accès"),
                if (TetherProbe.autoOffReadable) tags(manual(), adbSetup()) else tags(manual()),
                { c ->
                    when {
                        // Android 11 からはアプリから読めない（ADB の settings でも変えられない）
                        !TetherProbe.autoOffReadable -> TetherProbe.autoOffUnreadable() to Level.INFO
                        else -> when (Os.global(c, TetherProbe.KEY_SOFT_AP_TIMEOUT)) {
                            null -> tr("未設定（AOSPの既定はON）", "Not set (AOSP default: ON)", "Non défini (AOSP par défaut : ON)") to Level.WARN
                            "0" -> "OFF" to Level.OK
                            else -> tr("ON（接続がないと自動で停止）", "ON (stops automatically when nothing is connected)", "ON (s'arrête automatiquement sans appareil connecté)") to Level.WARN
                        }
                    }
                },
                "OFF",
                tr(
                    "接続端末がしばらくないとアクセスポイントが自動で止まる設定です。ルーターとして常時使うならOFFにします。テザリング設定 → Wi-Fiアクセスポイント →「アクセスポイントを自動的にOFFにする」。",
                    "This turns the hotspot off automatically when nothing has been connected for a while. Turn it off to use the phone as a router. Hotspot & tethering → Wi-Fi hotspot → “Turn off hotspot automatically”.",
                    "Ce réglage arrête automatiquement le point d'accès quand aucun appareil n'est connecté pendant un moment. Désactivez-le pour utiliser le téléphone comme routeur. Point d'accès et partage de connexion → Point d'accès Wi-Fi → « Désactiver automatiquement le point d'accès ».",
                ),
                listOf(tr("アクセスポイントの設定を開く", "Open hotspot settings", "Ouvrir les paramètres du point d'accès") to { a -> SystemScreens.hotspot(a) }),
                if (TetherProbe.autoOffReadable) {
                    "adb shell settings put global ${TetherProbe.KEY_SOFT_AP_TIMEOUT} 0\n" +
                        tr("（戻す: … ${TetherProbe.KEY_SOFT_AP_TIMEOUT} 1）", "(undo: … ${TetherProbe.KEY_SOFT_AP_TIMEOUT} 1)", "(annuler : … ${TetherProbe.KEY_SOFT_AP_TIMEOUT} 1)")
                } else {
                    null
                },
            ),
            Item(
                tr("起動時のテザリング ON", "Hotspot on at startup", "Point d'accès activé au démarrage"), tags(apk(), verify()),
                { c ->
                    val on = Prefs(c).bool(Defs.TETHER_ON_BOOT)
                    val last = BootTether.lastResult(c)?.let { "\n" + tr("前回の起動時: $it", "Last startup: $it", "Dernier démarrage : $it") } ?: ""
                    when {
                        !on -> tr(
                            "OFF（再起動すると、手で ON にするまでテザリングは止まったまま）", "OFF (after a reboot, the hotspot stays off until you turn it on)",
                            "OFF (après un redémarrage, le point d'accès reste arrêté jusqu'à ce que vous l'activiez)",
                        ) to Level.WARN
                        !TetherControl.permitted(c) -> tr(
                            "ON だが「システム設定の変更」が未許可", "ON, but “Modify system settings” isn't allowed",
                            "ON, mais « Modifier les paramètres système » n'est pas autorisé",
                        ) + last to Level.WARN
                        else -> "ON$last" to Level.OK
                    }
                },
                tr("ON（テザリング専用機として使うなら）", "ON (for a phone used only as a hotspot)", "ON (pour un téléphone utilisé uniquement comme point d'accès)"),
                tr(
                    "Android は再起動のあとテザリングを OFF で始めます。この設定を ON にすると、端末の起動直後にこのアプリが Wi-Fiテザリングを ON にします。" +
                        "PIN などの画面ロックがあっても、ロック解除の前に ON にします（Android 10 の Direct Boot）。ON にならなければ、約 10 分のあいだ数回やり直します。" +
                        "ロック解除までは監視（チェック・通知・ログ）は始まりません。結果は、ロック解除のあとログ（「端末起動」）と起動後チェックの通知で確かめられます。" +
                        "時間帯スケジュールと同じく Android 10 の非公開の仕組みを使うため、端末によっては使えません（設定の「切り替えられるか確認する」で確かめられます）。",
                    "Android starts with the hotspot off after a reboot. With this setting on, the app turns the Wi-Fi hotspot on right after the phone starts, " +
                        "even before a PIN or other screen lock is unlocked (Android 10 Direct Boot). If it doesn't come on, it retries a few times over about 10 minutes. " +
                        "Monitoring (checks, notifications, logs) doesn't start until you unlock. After unlocking, check the result in the log (“Phone started”) and the after-reboot check notification. " +
                        "Like the quiet-hours schedule, it uses a non-public Android 10 mechanism, so it doesn't work on every phone (check with “Check that switching works” in Settings).",
                    "Android démarre avec le point d'accès arrêté après un redémarrage. Avec ce réglage, l'application active le point d'accès Wi-Fi juste après le démarrage du téléphone, " +
                        "même avant le déverrouillage d'un code PIN ou d'un autre verrouillage de l'écran (Direct Boot d'Android 10). S'il ne s'active pas, quelques nouveaux essais ont lieu pendant environ 10 minutes. " +
                        "La surveillance (vérifications, notifications, journal) ne démarre qu'après le déverrouillage. Ensuite, consultez le résultat dans le journal (« Démarrage du téléphone ») et la notification de vérification après redémarrage. " +
                        "Comme la planification, cela utilise un mécanisme non public d'Android 10 et ne fonctionne donc pas sur tous les téléphones (vérifiez avec « Vérifier que la bascule fonctionne » dans les paramètres).",
                ),
                listOf(
                    openSettingsApp() to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) },
                    allowWriteSettings() to { a -> SystemScreens.writeSettings(a) },
                ),
                tetherControlAdb(),
            ),
            Item(
                tr("電池の最適化（このアプリ）", "Battery optimization (this app)", "Optimisation de la batterie (cette appli)"), tags(apk(), adbSetup()),
                { c ->
                    onOff(
                        c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName), okWhenOff = false,
                        onText = tr("除外済み", "Excluded", "Exclue"), offText = tr("対象", "Optimized", "Optimisée"),
                    )
                },
                tr("除外", "Excluded", "Exclue"),
                tr(
                    "対象のままだと、画面消灯が続いたとき（Doze）に再確認が最大 9 分ほど遅れることがあります。除外しても、このアプリは低頻度のアラームとイベントだけで動くので常時の電力消費は増えません。",
                    "If optimized, rechecks may be delayed by up to about 9 minutes while the screen stays off (Doze). Excluding it doesn't increase power use: the app runs only on infrequent alarms and events.",
                    "Si l'application est optimisée, les revérifications peuvent être retardées jusqu'à environ 9 minutes écran éteint (Doze). L'exclure n'augmente pas la consommation : l'application ne fonctionne qu'avec des alarmes et des événements peu fréquents.",
                ),
                listOf(
                    tr("除外を依頼する", "Request exclusion", "Demander l'exclusion") to { a -> SystemScreens.requestIgnoreBatteryOptimizations(a) },
                    tr("一覧を開く", "Open the list", "Ouvrir la liste") to { a -> SystemScreens.batteryOptimizationList(a) },
                ),
                "adb shell dumpsys deviceidle whitelist +${BuildConfig.APPLICATION_ID}",
            ),
            Item(
                tr("データセーバー", "Data Saver", "Économiseur de données"), manual(),
                { c ->
                    // 「OFF 以外」は ON（このアプリが例外に入っていても ON のあいだはテザリング不可）
                    when (c.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus?.let { it == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED }) {
                        null -> Fmt.NA to Level.INFO
                        true -> "OFF" to Level.OK
                        false -> tr("ON（テザリングが使えません）", "ON (the hotspot can't be used)", "ON (le point d'accès est inutilisable)") to Level.WARN
                    }
                },
                "OFF",
                tr(
                    "データセーバーがONのあいだ、Android はテザリングを使えなくします。通信量を抑えたい場合は、アプリごとの「バックグラウンドデータ」を制限してください（下の「バックグラウンド通信の抑制」）。",
                    "While Data Saver is on, Android blocks the hotspot. To reduce data use, restrict “Background data” per app instead (see “Limit background traffic” below).",
                    "Tant que l'économiseur de données est activé, Android bloque le point d'accès. Pour réduire la consommation, limitez plutôt les « Données en arrière-plan » par application (voir « Limiter le trafic en arrière-plan » ci-dessous).",
                ),
                listOf(openDataUsage() to { a -> SystemScreens.dataUsage(a) }),
            ),
            Item(
                tr("画面ロック", "Screen lock", "Verrouillage de l'écran"), manual(),
                { c ->
                    onOff(
                        c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure,
                        onText = tr("PIN・パターン等あり", "PIN, pattern, etc.", "PIN, schéma, etc."), offText = tr("なし・スワイプ", "None or swipe", "Aucun ou balayage"),
                    )
                },
                tr("なし（端末を安全な場所に置ける場合）", "None (if the phone is in a safe place)", "Aucun (si le téléphone est dans un endroit sûr)"),
                tr(
                    "PIN などのロックがあると、再起動のあとロックを解除するまで Android が起動完了を通知しないため、監視と起動後チェックが始まりません。" +
                        "テザリングを ON にすること（上の「起動時のテザリング ON」）だけは、ロック解除の前に行えます。",
                    "With a PIN or similar lock, Android doesn't report that startup is complete until you unlock after a reboot, so monitoring and the after-reboot check don't start. " +
                        "Only turning the hotspot on (“Hotspot on at startup” above) can happen before unlocking.",
                    "Avec un code PIN ou un verrouillage similaire, Android n'indique pas la fin du démarrage tant que vous n'avez pas déverrouillé après un redémarrage : la surveillance et la vérification après redémarrage ne démarrent pas. " +
                        "Seule l'activation du point d'accès (« Point d'accès activé au démarrage » ci-dessus) peut avoir lieu avant le déverrouillage.",
                ),
                listOf(openSecurity() to { a -> SystemScreens.security(a) }),
            ),
        ),
        tr("画面・電源の省電力", "Screen and power saving", "Économies d'écran et d'énergie") to listOf(
            Item(
                tr("画面消灯までの時間", "Screen timeout", "Délai de mise en veille de l'écran"), tags(manual(), adbSetup()),
                { c ->
                    val ms = Os.system(c, Settings.System.SCREEN_OFF_TIMEOUT)?.toLongOrNull()
                    when {
                        ms == null -> Fmt.NA to Level.INFO
                        ms < 60_000 -> (ms / 1000).let { tr("${it}秒", "$it s", "$it s") } to if (ms <= 30_000) Level.OK else Level.WARN
                        else -> (ms / 60_000).let { tr("${it}分", "$it min", "$it min") } to Level.WARN
                    }
                },
                tr(
                    "30秒以下（PIN などの画面ロックがある場合は運用開始後に）", "30 s or less (with a PIN or other screen lock, after setup is finished)",
                    "30 s ou moins (avec un code PIN ou autre verrouillage, une fois la configuration terminée)",
                ),
                tr(
                    "画面は消灯してもテザリングは止まりません。画面の点灯は大きな電力消費と発熱の原因になります。" +
                        "PIN などの画面ロックがあると消灯のたびにロックがかかり操作しづらいので、設定作業中は長めにしておき、運用を始めてから短くしてください。",
                    "The hotspot keeps running with the screen off. A lit screen is a major cause of power use and heat. " +
                        "With a PIN or other screen lock, the phone locks every time the screen turns off, so keep the timeout longer while setting up and shorten it once you start running.",
                    "Le point d'accès continue de fonctionner écran éteint. Un écran allumé est une cause majeure de consommation et de chaleur. " +
                        "Avec un code PIN ou un autre verrouillage, le téléphone se verrouille à chaque extinction : gardez un délai plus long pendant la configuration, puis raccourcissez-le une fois en service.",
                ),
                listOf(openDisplay() to { a -> SystemScreens.display(a) }),
                "adb shell settings put system screen_off_timeout 30000",
            ),
            Item(
                tr("画面の明るさ", "Brightness", "Luminosité"), manual(),
                { c ->
                    val auto = Os.system(c, Settings.System.SCREEN_BRIGHTNESS_MODE) == "1"
                    val v = Os.system(c, Settings.System.SCREEN_BRIGHTNESS)?.toIntOrNull()
                    if (auto) {
                        tr("自動", "Auto", "Auto") to Level.OK
                    } else {
                        (v?.let { tr("手動 $it/255", "Manual $it/255", "Manuel $it/255") } ?: Fmt.NA) to if ((v ?: 255) <= 64) Level.OK else Level.INFO
                    }
                },
                tr("自動、または低め", "Auto, or low", "Auto, ou faible"),
                tr(
                    "点灯するのは確認のときだけなので、低めで十分です。", "The screen is only on when you check something, so low is enough.",
                    "L'écran ne s'allume que pour une vérification : une luminosité faible suffit.",
                ),
                listOf(openDisplay() to { a -> SystemScreens.display(a) }),
            ),
            Item(
                tr("充電中は画面をスリープにしない", "Stay awake while charging", "Rester activé pendant la charge"), adbSetup() + Fmt.paren(devOptions()),
                { c ->
                    when (Os.global(c, Settings.Global.STAY_ON_WHILE_PLUGGED_IN)) {
                        null, "0" -> "OFF" to Level.OK
                        else -> tr("ON（給電中は画面が消えない）", "ON (the screen stays on while charging)", "ON (l'écran reste allumé en charge)") to Level.WARN
                    }
                },
                "OFF",
                tr(
                    "給電しながら運用する場合、ONだと画面が点きっぱなしになり、発熱と焼き付きの原因になります。",
                    "When running on a charger, ON keeps the screen lit all the time, causing heat and screen burn-in.",
                    "En fonctionnement sur chargeur, ON garde l'écran allumé en permanence, ce qui cause chaleur et marquage de l'écran.",
                ),
                listOf(openDeveloper() to { a -> SystemScreens.developer(a) }),
                "adb shell settings put global stay_on_while_plugged_in 0",
            ),
            Item(
                tr("通知で画面が点く（アンビエント表示）", "Screen wakes for notifications (ambient display)", "L'écran s'allume pour les notifications (affichage en veille)"),
                tags(adbSetup(), manual()),
                { c ->
                    secureFlags(
                        c,
                        listOf(
                            tr("通知で点灯", "Wake for notifications", "Allumer pour les notifications") to "doze_enabled",
                            tr("常に表示", "Always on", "Toujours activé") to "doze_always_on",
                        ),
                    )
                },
                "OFF",
                tr(
                    "新しい通知のたびに画面が点く機能です（機種によって「アンビエント表示」「新しい通知で画面をオン」など）。運用中の不意な点灯は電力の無駄になります。未設定は機種の既定で、この機能がない機種も多いです。",
                    "This lights the screen for each new notification (called “Ambient display”, “New notifications”, etc. depending on the phone). Unexpected wake-ups waste power. “Not set” means the phone's default; many phones don't have this feature.",
                    "Cette fonction allume l'écran à chaque nouvelle notification (« Affichage en mode veille », « Nouvelles notifications », etc. selon le téléphone). Les allumages imprévus gaspillent de l'énergie. « Non défini » correspond au réglage par défaut ; beaucoup de téléphones n'ont pas cette fonction.",
                ),
                listOf(openDisplay() to { a -> SystemScreens.display(a) }),
                "adb shell settings put secure doze_enabled 0\nadb shell settings put secure doze_always_on 0\n" + bulk(),
            ),
            Item(
                tr("持ち上げ・タップで画面が点く", "Screen wakes when lifted or tapped", "L'écran s'allume au soulèvement ou au toucher"), tags(adbSetup(), manual()),
                { c ->
                    secureFlags(
                        c,
                        listOf(
                            tr("持ち上げて点灯", "Lift to wake", "Soulever pour activer") to "wake_gesture_enabled",
                            tr("ダブルタップで点灯", "Double-tap to wake", "Appuyer deux fois pour activer") to "double_tap_to_wake",
                            tr("持ち上げて通知を確認", "Lift to check notifications", "Soulever pour voir les notifications") to "doze_pulse_on_pick_up",
                            tr("ダブルタップで通知を確認", "Double-tap to check notifications", "Appuyer deux fois pour voir les notifications") to "doze_pulse_on_double_tap",
                            tr("タップで通知を確認", "Tap to check notifications", "Appuyer pour voir les notifications") to "doze_tap_gesture",
                        ),
                    )
                },
                "OFF",
                tr(
                    "端末を動かしたり触れたりしたときに画面が点く機能です。置いたまま使うなら不要です。メーカー独自のジェスチャー（画面オフ時のジェスチャーなど）は、設定アプリの「ジェスチャー」などから手動で OFF にしてください。",
                    "These light the screen when the phone is moved or touched. Not needed for a phone that stays in place. Turn off manufacturer gestures (such as screen-off gestures) manually under “Gestures” or similar in the Settings app.",
                    "Ces fonctions allument l'écran quand le téléphone est déplacé ou touché. Inutiles pour un téléphone qui reste en place. Désactivez manuellement les gestes du fabricant (gestes écran éteint, etc.) dans « Gestes » ou équivalent dans l'application Paramètres.",
                ),
                listOf(openDisplay() to { a -> SystemScreens.display(a) }),
                "adb shell settings put secure wake_gesture_enabled 0\nadb shell settings put secure double_tap_to_wake 0\n" + bulk(),
            ),
            Item(
                tr("バッテリーセーバー", "Battery Saver", "Économiseur de batterie"), tags(manual(), verify()),
                { c -> onOff(c.getSystemService(PowerManager::class.java)?.isPowerSaveMode, okWhenOff = true) },
                tr("給電運用ではOFF", "OFF when running on a charger", "OFF en fonctionnement sur chargeur"),
                tr(
                    "給電中はOFFを推奨します（性能の制限を避けるため）。バッテリー駆動で長く持たせたい場合はONも選択肢です。このアプリはフォアグラウンドサービスなので監視は続きますが、機種によっては通信や性能に制限がかかるため、実機で確認してください。",
                    "OFF is recommended while charging (to avoid performance limits). On battery, ON is an option to last longer. This app is a foreground service so monitoring continues, but some phones limit networking or performance, so check on your phone.",
                    "OFF est recommandé en charge (pour éviter les limitations de performances). Sur batterie, ON permet de tenir plus longtemps. Cette application est un service au premier plan, la surveillance continue, mais certains téléphones limitent le réseau ou les performances : vérifiez sur votre téléphone.",
                ),
                listOf(tr("バッテリーセーバーの設定を開く", "Open Battery Saver settings", "Ouvrir les paramètres de l'économiseur de batterie") to { a -> SystemScreens.batterySaver(a) }),
            ),
            Item(
                tr("バッテリー駆動中の自動切り替え", "Auto switching on battery", "Bascule automatique sur batterie"), tags(apk(), adbSetup()),
                { c ->
                    val prefs = Prefs(c)
                    val on = prefs.bool(Defs.AUTO_SAVER) || AutoSwitch.dozeEnabled(prefs)
                    val permitted = AutoSwitch.permitted(c)
                    when {
                        !on -> notUsed() + (if (permitted) Fmt.paren(tr("権限は付与済み", "permission granted", "autorisation accordée")) else "") to Level.INFO
                        !permitted -> tr(
                            "ON だが設定変更の権限がない（ADB で付与が必要）", "ON, but the settings permission is missing (grant it via ADB)",
                            "ON, mais l'autorisation de modifier les paramètres manque (à accorder via ADB)",
                        ) to Level.WARN
                        else -> Fmt.join(
                            listOfNotNull(
                                tr("バッテリーセーバー", "Battery Saver", "Économiseur de batterie").takeIf { prefs.bool(Defs.AUTO_SAVER) },
                                tr("Doze を早める", "earlier Doze", "Doze avancé").takeIf { AutoSwitch.dozeEnabled(prefs) },
                            ),
                        ).let { tr("$it を自動で切り替え", "$it switched automatically", "$it en bascule automatique") } to Level.OK
                    }
                },
                tr("バッテリー駆動で長く使うなら ON", "ON if you run on battery for long", "ON si vous fonctionnez longtemps sur batterie"),
                tr(
                    "バッテリー駆動中（または残量が設定以下のとき）にバッテリーセーバーを ON にし、Doze（省電力待機）を早めます。給電すると元に戻します。" +
                        "データセーバー・機内モード・モバイルデータは変えません。使うには PC から設定変更の権限を付けてから、設定の「自動切り替え」を ON にします。",
                    "On battery (or below a set level), turns on Battery Saver and makes Doze (deep sleep) start sooner. Everything is restored when charging. " +
                        "Data Saver, airplane mode and mobile data are never changed. To use it, grant the settings permission from a PC, then turn on “Auto switching” in Settings.",
                    "Sur batterie (ou sous un niveau défini), active l'économiseur de batterie et avance Doze (veille profonde). Tout est rétabli en charge. " +
                        "L'économiseur de données, le mode avion et les données mobiles ne sont jamais modifiés. Pour l'utiliser, accordez l'autorisation depuis un PC, puis activez « Bascule automatique » dans les paramètres.",
                ),
                listOf(openSettingsApp() to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
                "tools\\adb-setup.bat -Apply -AllowAutoSwitch\n" + tr("（個別に: ", "(individually: ", "(individuellement : ") +
                    "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS" + tr("）", ")", ")"),
            ),
            Item(
                tr("充電上限・バイパス充電", "Charge limit, bypass charging", "Limite de charge, charge directe"), tags(tr("手動確認", "Manual check", "Vérification manuelle"), verify()),
                { _ -> tr("非対応として扱う（Android 10 標準に設定なし）", "Treated as unsupported (no such setting in standard Android 10)", "Considéré comme non pris en charge (aucun réglage dans Android 10 standard)") to Level.INFO },
                tr("メーカーの機能があれば使う", "Use the manufacturer's feature if there is one", "Utilisez la fonction du fabricant s'il y en a une"),
                tr(
                    "Android 10 の標準には充電の上限やバイパス給電の設定がありません。メーカー独自の「電池保護」などがあれば利用し、なければ熱のこもらない置き場所・ケースを外す・温度警告の活用で対策します。給電中でも発熱と劣化はなくなりません。" +
                        "スマートプラグがあれば、下の「スマートプラグで充電の範囲を保つ」で代わりにできます。",
                    "Standard Android 10 has no charge limit or bypass charging setting. Use the manufacturer's “battery protection” if available; otherwise, keep the phone somewhere cool, remove the case and use the temperature alerts. Charging doesn't stop heat and wear. " +
                        "With a smart plug, “Keep the charge in range with a smart plug” below can do this instead.",
                    "Android 10 standard n'a ni limite de charge ni charge directe. Utilisez la « protection de la batterie » du fabricant si elle existe ; sinon, placez le téléphone au frais, retirez la coque et utilisez les alertes de température. La charge n'empêche ni la chaleur ni l'usure. " +
                        "Avec une prise connectée, « Garder la charge dans une plage avec une prise connectée » ci-dessous peut s'en charger.",
                ),
                listOf(tr("電池の設定を開く", "Open battery settings", "Ouvrir les paramètres de la batterie") to { a -> SystemScreens.battery(a) }),
            ),
        ),
        tr("無線機能", "Wireless", "Sans fil") to listOf(
            Item(
                "Bluetooth", manual(),
                { _ ->
                    @Suppress("DEPRECATION")
                    val bt = BluetoothAdapter.getDefaultAdapter()
                    when {
                        bt == null -> tr("非搭載", "Not available", "Absent") to Level.OK
                        bt.isEnabled -> "ON" to Level.INFO
                        else -> "OFF" to Level.OK
                    }
                },
                tr("OFF（Bluetoothテザリングを使わない場合）", "OFF (if you don't use Bluetooth tethering)", "OFF (si vous n'utilisez pas le partage Bluetooth)"),
                tr(
                    "使わない無線はOFFにすると、わずかに電力と電波の干渉が減ります。", "Turning off unused radios slightly reduces power use and interference.",
                    "Désactiver les radios inutilisées réduit légèrement la consommation et les interférences.",
                ),
                listOf(tr("Bluetoothの設定を開く", "Open Bluetooth settings", "Ouvrir les paramètres Bluetooth") to { a -> SystemScreens.bluetooth(a) }),
            ),
            Item(
                "NFC", manual(),
                { c ->
                    val nfc = NfcAdapter.getDefaultAdapter(c)
                    when {
                        nfc == null -> tr("非搭載", "Not available", "Absent") to Level.OK
                        nfc.isEnabled -> "ON" to Level.INFO
                        else -> "OFF" to Level.OK
                    }
                },
                "OFF",
                tr("ルーター用途では使いません。", "Not used for a router.", "Inutile pour un routeur."),
                listOf(tr("NFCの設定を開く", "Open NFC settings", "Ouvrir les paramètres NFC") to { a -> SystemScreens.nfc(a) }),
            ),
            Item(
                tr("位置情報", "Location", "Localisation"), tags(manual(), adbSetup()),
                { c ->
                    when (c.getSystemService(LocationManager::class.java)?.isLocationEnabled) {
                        null -> Fmt.NA to Level.INFO
                        true -> "ON" to Level.WARN
                        false -> "OFF" to Level.OK
                    }
                },
                tr("OFF（このアプリは使いません）", "OFF (this app doesn't use it)", "OFF (cette application ne l'utilise pas)"),
                tr(
                    "このアプリは位置情報を使いません。他のアプリの位置取得（GPS・Wi-Fi・モバイル網）による電力消費を抑えられます。",
                    "This app doesn't use Location. Turning it off avoids power used by other apps getting a location (GPS, Wi-Fi, mobile network).",
                    "Cette application n'utilise pas la localisation. La désactiver évite la consommation des autres applications qui cherchent une position (GPS, Wi-Fi, réseau mobile).",
                ),
                listOf(openLocation() to { a -> SystemScreens.location(a) }),
                "adb shell settings put secure location_mode 0\n" + bulk(),
            ),
            Item(
                tr("モバイルデータを常に ON", "Mobile data always active", "Données mobiles toujours actives"), adbSetup() + Fmt.paren(devOptions()),
                { c ->
                    when (Os.global(c, "mobile_data_always_on")) {
                        "0" -> "OFF" to Level.OK
                        null -> tr("未設定（既定は ON の機種が多い）", "Not set (ON by default on many phones)", "Non défini (ON par défaut sur beaucoup de téléphones)") to Level.WARN
                        else -> "ON" to Level.WARN
                    }
                },
                "OFF",
                tr(
                    "Wi-Fi に接続しているあいだもモバイル回線を待機させておく機能です。テザリング専用機ではモバイル回線が常に主回線なので不要で、OFF にしてもテザリングには影響しません。",
                    "Keeps the mobile line on standby even while connected to Wi-Fi. On a dedicated hotspot phone, the mobile line is always the main connection, so it isn't needed; turning it off doesn't affect tethering.",
                    "Garde la ligne mobile en attente même connecté au Wi-Fi. Sur un téléphone dédié au partage, la ligne mobile est toujours la connexion principale : inutile, et le désactiver n'affecte pas le partage.",
                ),
                listOf(openDeveloper() to { a -> SystemScreens.developer(a) }),
                "adb shell settings put global mobile_data_always_on 0",
            ),
            Item(
                tr("Wi-Fiスキャン（常時）", "Wi-Fi scanning (always)", "Recherche Wi-Fi (permanente)"), tags(manual(), adbSetup()),
                { c -> globalFlag(c, "wifi_scan_always_enabled") },
                "OFF",
                tr(
                    "Wi-FiがOFFでも周囲のWi-Fiを探し続ける機能です。位置情報 → Wi-FiとBluetoothのスキャン で変更できます。",
                    "Keeps looking for nearby Wi-Fi networks even when Wi-Fi is off. Change it under Location → Wi-Fi and Bluetooth scanning.",
                    "Continue à chercher les réseaux Wi-Fi alentour même Wi-Fi désactivé. Modifiable dans Localisation → Recherche Wi-Fi et Bluetooth.",
                ),
                listOf(openLocation() to { a -> SystemScreens.location(a) }),
                "adb shell settings put global wifi_scan_always_enabled 0",
            ),
            Item(
                tr("Bluetoothスキャン（常時）", "Bluetooth scanning (always)", "Recherche Bluetooth (permanente)"), tags(manual(), adbSetup()),
                { c -> globalFlag(c, "ble_scan_always_enabled") },
                "OFF",
                tr(
                    "BluetoothがOFFでも周囲の機器を探し続ける機能です。", "Keeps looking for nearby devices even when Bluetooth is off.",
                    "Continue à chercher les appareils alentour même Bluetooth désactivé.",
                ),
                listOf(openLocation() to { a -> SystemScreens.location(a) }),
                "adb shell settings put global ble_scan_always_enabled 0",
            ),
        ),
        tr("回線方式の見直し", "Network type", "Type de réseau") to listOf(
            Item(
                tr("優先ネットワークの種類（5G／4G）", "Preferred network type (5G/4G)", "Type de réseau préféré (5G/4G)"), tags(manual(), verify()),
                { c ->
                    val mode = Os.preferredNetworkMode(c)
                    when {
                        mode == null -> Fmt.NA to Level.INFO
                        NetworkModes.hasNr(mode) -> NetworkModes.label(mode) to Level.WARN
                        else -> NetworkModes.label(mode) + Fmt.paren(
                            tr("5G を使わない設定なので変更不要", "5G isn't used, so no change needed", "la 5G n'est pas utilisée, rien à changer"),
                        ) to Level.OK
                    }
                },
                tr("5G 対応の端末は 4G（LTE）に固定", "On 5G phones, fix it to 4G (LTE)", "Sur les téléphones 5G, fixez-le sur 4G (LTE)"),
                tr(
                    "5G の電波を探したり 4G と行き来したりする電力を減らせる可能性があります（エリアによっては速度が落ちます）。" +
                        "設定 → ネットワークとインターネット → モバイル ネットワーク → 詳細設定 → 優先ネットワークの種類 で「4G」や「LTE」を選びます。効果は比較テストで確かめてください。",
                    "This may save the power spent searching for 5G and switching between 5G and 4G (speed may drop in some areas). " +
                        "Choose “4G” or “LTE” under Settings → Network & internet → Mobile network → Advanced → Preferred network type. Check the effect with a comparison test.",
                    "Cela peut économiser l'énergie dépensée à chercher la 5G et à basculer entre 5G et 4G (le débit peut baisser selon la zone). " +
                        "Choisissez « 4G » ou « LTE » dans Paramètres → Réseau et Internet → Réseau mobile → Paramètres avancés → Type de réseau préféré. Vérifiez l'effet avec un test comparatif.",
                ),
                listOf(openMobile() to { a -> SystemScreens.mobileNetwork(a) }),
            ),
            Item(
                tr("VoLTE（データ専用 SIM）", "VoLTE (data-only SIM)", "VoLTE (SIM données uniquement)"), tags(manual(), verify()),
                { _ ->
                    Fmt.NA + Fmt.paren(
                        tr(
                            "SIM ごとの設定のため、設定画面で確認してください", "it's a per-SIM setting; check it in Settings",
                            "réglage propre à chaque SIM ; vérifiez dans les paramètres",
                        ),
                    ) to Level.INFO
                },
                tr("データ専用 SIM では OFF", "OFF on a data-only SIM", "OFF avec une SIM données uniquement"),
                tr(
                    "通話をしないデータ専用 SIM では、VoLTE（4G での音声）を OFF にすると、使わない IMS の登録を保つための通信と電力が減る可能性があります。" +
                        "設定 → ネットワークとインターネット → モバイル ネットワーク の「VoLTE」「4G 通話」「拡張 4G LTE モード」などを OFF にします。効果は比較テスト「VoLTE を OFF」で確かめてください。",
                    "On a data-only SIM with no calls, turning off VoLTE (voice over 4G) may reduce the traffic and power used to keep an unused IMS registration. " +
                        "Turn off “VoLTE”, “4G Calling”, “Enhanced 4G LTE Mode” or similar under Settings → Network & internet → Mobile network. Check the effect with the comparison test “Turn off VoLTE”.",
                    "Avec une SIM données uniquement, sans appels, désactiver la VoLTE (voix sur 4G) peut réduire le trafic et l'énergie utilisés pour maintenir un enregistrement IMS inutile. " +
                        "Désactivez « VoLTE », « Appels 4G », « Mode 4G LTE avancé » ou équivalent dans Paramètres → Réseau et Internet → Réseau mobile. Vérifiez l'effet avec le test comparatif « Désactiver la VoLTE ».",
                ),
                listOf(
                    openMobile() to { a -> SystemScreens.mobileNetwork(a) },
                    openExperiment() to { a -> a.startActivity(Intent(a, ExperimentActivity::class.java)) },
                ),
            ),
        ),
        tr("テザリングの方式・時間帯・給電", "Hotspot band, schedule and charging", "Bande, planification et charge du point d'accès") to listOf(
            Item(
                tr("時間帯スケジュール（使わない時間帯は止める）", "Quiet-hours schedule (off when unused)", "Planification (arrêt aux heures d'inactivité)"), tags(apk(), verify()),
                { c ->
                    val prefs = Prefs(c)
                    val q = QuietHours.parse(prefs.text(Defs.QUIET_HOURS))
                    val permitted = TetherControl.permitted(c)
                    when {
                        !prefs.bool(Defs.TETHER_SCHEDULE) -> notUsed() + (if (permitted) Fmt.paren(tr("許可は済み", "permission granted", "autorisation accordée")) else "") to Level.INFO
                        !permitted -> tr(
                            "ON だが「システム設定の変更」が未許可", "ON, but “Modify system settings” isn't allowed",
                            "ON, mais « Modifier les paramètres système » n'est pas autorisé",
                        ) to Level.WARN
                        q == null -> tr("ON だが時間帯が未設定", "ON, but quiet hours aren't set", "ON, mais les heures d'inactivité ne sont pas définies") to Level.WARN
                        else -> q.label.let {
                            tr("$it は止める（接続 0 台が 10 分続いたら）", "Off during $it (after 10 min with no devices)", "Arrêt pendant $it (après 10 min sans appareil)")
                        } to Level.OK
                    }
                },
                tr("毎日使わない時間帯がはっきりしているなら ON", "ON if there are clear unused hours every day", "ON s'il y a chaque jour des heures clairement inutilisées"),
                tr(
                    "使わない時間帯に入ったら、接続端末が 0 台の状態が 10 分続くのを待ってテザリングを止め、時間帯の終わりに再開します。止めている間は接続 0 台の待機電力がかかりません。" +
                        "このアプリが止めたときだけ再開し、利用者が手で ON にしたらその時間帯はもう止めません。再開に 3 回失敗したら、テザリング停止の異常として通知します。" +
                        "Android 10 の非公開の仕組みを使うため、端末によっては使えません（設定の「切り替えられるか確認する」で、止めずに確かめられます）。",
                    "During quiet hours, the hotspot is turned off once no device has been connected for 10 minutes, and turned back on when they end. While it's off, no standby power is spent. " +
                        "It's turned back on only if the app turned it off; if you turn it on by hand, it stays on for that period. If turning it back on fails 3 times, you're notified that the hotspot is off. " +
                        "It uses a non-public Android 10 mechanism, so it doesn't work on every phone (check without turning it off with “Check that switching works” in Settings).",
                    "Pendant les heures d'inactivité, le point d'accès est arrêté après 10 minutes sans appareil connecté, et réactivé à la fin. Pendant l'arrêt, aucune énergie de veille n'est dépensée. " +
                        "Il n'est réactivé que si l'application l'a arrêté ; si vous l'activez à la main, il reste activé pour cette période. Si la réactivation échoue 3 fois, vous êtes averti que le point d'accès est arrêté. " +
                        "Cela utilise un mécanisme non public d'Android 10 et ne fonctionne donc pas sur tous les téléphones (vérifiez sans l'arrêter avec « Vérifier que la bascule fonctionne » dans les paramètres).",
                ),
                listOf(
                    openSettingsApp() to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) },
                    allowWriteSettings() to { a -> SystemScreens.writeSettings(a) },
                ),
                tetherControlAdb(),
            ),
            Item(
                tr("アクセスポイントの帯域（2.4GHz／5GHz）", "Hotspot band (2.4 GHz/5 GHz)", "Bande du point d'accès (2,4 GHz/5 GHz)"), tags(manual(), verify()),
                { _ ->
                    tr(
                        "アプリからは読めません（PC の tools\\adb-setup.bat で表示します）", "Can't be read by the app (shown by tools\\adb-setup.bat on a PC)",
                        "Illisible par l'application (affichée par tools\\adb-setup.bat sur un PC)",
                    ) to Level.INFO
                },
                tr("比較テストで消費の少ない方を選ぶ", "Choose the one that uses less power, using a comparison test", "Choisissez celle qui consomme le moins, avec un test comparatif"),
                tr(
                    "5GHz は速く混雑しにくい一方、壁などで弱まりやすく、2.4GHz は遠くまで届きます。消費の差は端末によって違うので、比較テスト「アクセスポイントを 5GHz にする／2.4GHz にする」で実測して決めてください。" +
                        "帯域を変えると接続端末はつなぎ直しになり、5GHz に対応していない接続端末はつながらなくなります。",
                    "5 GHz is faster and less crowded but weakened more by walls; 2.4 GHz reaches further. The power difference depends on the phone, so measure it with the comparison test “Switch the hotspot to 5 GHz / 2.4 GHz”. " +
                        "Changing the band makes devices reconnect, and devices without 5 GHz support can't connect.",
                    "La bande 5 GHz est plus rapide et moins encombrée mais traverse moins bien les murs ; la bande 2,4 GHz porte plus loin. L'écart de consommation dépend du téléphone : mesurez-le avec le test comparatif « Passer le point d'accès en 5 GHz / 2,4 GHz ». " +
                        "Changer de bande oblige les appareils à se reconnecter, et ceux sans 5 GHz ne peuvent plus se connecter.",
                ),
                listOf(
                    tr("アクセスポイントの設定を開く", "Open hotspot settings", "Ouvrir les paramètres du point d'accès") to { a -> SystemScreens.hotspot(a) },
                    openExperiment() to { a -> a.startActivity(Intent(a, ExperimentActivity::class.java)) },
                ),
                "adb shell dumpsys wifi | findstr apBand\n" + tr("（0 = 2.4GHz、1 = 5GHz、-1 = 自動）", "(0 = 2.4 GHz, 1 = 5 GHz, -1 = auto)", "(0 = 2,4 GHz, 1 = 5 GHz, -1 = auto)"),
            ),
            Item(
                tr("テザリングのハードウェアアクセラレーション", "Tethering hardware acceleration", "Accélération matérielle du partage de connexion"), tags(manual(), adbSetup(), verify()),
                { c ->
                    when (Os.global(c, "tether_offload_disabled")) {
                        null, "0" -> tr("ON（既定）", "ON (default)", "ON (par défaut)") to Level.OK
                        else -> tr("OFF（開発者向けオプションで無効）", "OFF (disabled in Developer options)", "OFF (désactivée dans les options pour les développeurs)") to Level.INFO
                    }
                },
                tr("ON（対応している端末だけ効果があります）", "ON (effective only on supported phones)", "ON (efficace seulement sur les téléphones compatibles)"),
                tr(
                    "テザリングの中継を CPU ではなく通信用のチップに任せる機能で、対応していれば CPU の負担と消費が減ります。対応していない端末（オフロード用の HAL がない端末）では、ON にしても何も変わりません。" +
                        "アプリからは対応しているか読めないので、PC の tools\\adb-setup.bat で確認してください。効果は比較テスト「ハードウェアアクセラレーションを ON」で確かめられます。",
                    "Hands tethering traffic to the modem chip instead of the CPU; on supported phones this reduces CPU load and power use. On unsupported phones (no offload HAL), turning it on changes nothing. " +
                        "The app can't read whether it's supported, so check with tools\\adb-setup.bat on a PC. Measure the effect with the comparison test “Turn on tethering hardware acceleration”.",
                    "Confie le relais du partage à la puce modem plutôt qu'au processeur ; sur les téléphones compatibles, la charge du processeur et la consommation baissent. Sur les autres (sans HAL de délestage), l'activer ne change rien. " +
                        "L'application ne peut pas savoir si c'est pris en charge : vérifiez avec tools\\adb-setup.bat sur un PC. Mesurez l'effet avec le test comparatif « Activer l'accélération matérielle du partage de connexion ».",
                ),
                listOf(openDeveloper() to { a -> SystemScreens.developer(a) }),
                "adb shell settings put global tether_offload_disabled 0\n" +
                    tr(
                        "（対応の確認: adb shell lshal | findstr tetheroffload　まとめて: tools\\adb-setup.bat -Apply -EnableOffload）",
                        "(check support: adb shell lshal | findstr tetheroffload; all at once: tools\\adb-setup.bat -Apply -EnableOffload)",
                        "(vérifier la prise en charge : adb shell lshal | findstr tetheroffload ; en une fois : tools\\adb-setup.bat -Apply -EnableOffload)",
                    ),
            ),
            Item(
                tr("スマートプラグで充電の範囲を保つ", "Keep the charge in range with a smart plug", "Garder la charge dans une plage avec une prise connectée"),
                tags(apk(), verify() + Fmt.paren(tr("プラグ次第", "depends on the plug", "selon la prise"))),
                { c ->
                    val prefs = Prefs(c)
                    if (!prefs.bool(Defs.PLUG)) {
                        notUsed() to Level.INFO
                    } else {
                        val low = prefs.int(Defs.PLUG_LOW)
                        val high = prefs.int(Defs.PLUG_HIGH)
                        PlugUrls.label(prefs.text(Defs.PLUG_KIND)) + tr("・$low〜$high%", ", $low–$high%", ", $low–$high %") to Level.OK
                    }
                },
                tr(
                    "給電しながら長く運用するなら、40〜80% の範囲で使う", "If you run on a charger for long periods, keep it within 40–80%",
                    "Si vous fonctionnez longtemps sur chargeur, restez entre 40 et 80 %",
                ),
                tr(
                    "充電上限のない端末で、つなぎっぱなしの満充電と高温を避けるために、スマートプラグの ON／OFF で電池残量を範囲の中に保ちます。高温のときは給電を止めます。" +
                        "Shelly・Tasmota はアドレスを入れるだけで使え、そのほかのプラグや Home Assistant は ON／OFF の URL を指定します。" +
                        "プラグをこの端末のテザリングにつなぐ場合、テザリングを止めている間はプラグに届きません（プラグ側の状態のまま）。Shelly は OFF のあと自動で ON に戻すタイマーを付けるので、このアプリが止まっても電池切れになりません。",
                    "On phones without a charge limit, switching a smart plug on and off keeps the battery level within a range, avoiding constant full charge and heat. Charging also stops when hot. " +
                        "Shelly and Tasmota need only their address; for other plugs or Home Assistant, enter the ON and OFF URLs. " +
                        "If the plug is connected to this phone's hotspot, it can't be reached while the hotspot is off (it keeps its state). For Shelly, a timer turns it back on after OFF, so the battery doesn't run out even if this app stops.",
                    "Sur les téléphones sans limite de charge, allumer et éteindre une prise connectée garde le niveau de batterie dans une plage, évitant la charge complète permanente et la chaleur. La charge s'arrête aussi en cas de chaleur. " +
                        "Shelly et Tasmota ne demandent que leur adresse ; pour les autres prises ou Home Assistant, saisissez les URL ON et OFF. " +
                        "Si la prise est connectée au point d'accès de ce téléphone, elle est injoignable quand il est arrêté (elle garde son état). Pour Shelly, un minuteur la rallume après OFF : la batterie ne se vide pas même si l'application s'arrête.",
                ),
                listOf(openSettingsApp() to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
            ),
            Item(
                tr("外部への通知（ntfy・Discord・Slack）", "External notifications (ntfy, Discord, Slack)", "Notifications externes (ntfy, Discord, Slack)"), apk(),
                { c ->
                    val prefs = Prefs(c)
                    val d = ExtNotify.parse(prefs.text(Defs.EXT_URL))
                    when {
                        !prefs.bool(Defs.EXT_NOTIFY) -> notUsed() to Level.INFO
                        d == null -> tr("ON だが通知先の URL が正しくない", "ON, but the destination URL is invalid", "ON, mais l'URL de destination n'est pas valide") to Level.WARN
                        else -> d.label.let { tr("$it に送る", "Sends to $it", "Envoi vers $it") } to Level.OK
                    }
                },
                tr("離れた場所に置くなら ON", "ON if the phone is far from you", "ON si le téléphone est loin de vous"),
                tr(
                    "端末の画面を見なくても、回線・テザリングが止まっていた時間（復旧してから送ります）、温度・電池の警告、再起動を、スマートフォンや PC で受け取れます。" +
                        "ntfy はアプリを入れてトピックを購読するだけで使えます（トピック名は他人に推測されない長いものにしてください）。送るのは状態だけで、IP アドレス・SSID・接続端末の情報は送りません。",
                    "Without looking at the phone, you get on your phone or PC how long the line or hotspot was down (sent after recovery), temperature and battery alerts, and reboots. " +
                        "For ntfy, just install the app and subscribe to the topic (use a long topic name others can't guess). Only status is sent — never IP addresses, SSID or device details.",
                    "Sans regarder le téléphone, vous recevez sur votre smartphone ou PC la durée de coupure de la ligne ou du point d'accès (envoyée après le rétablissement), les alertes de température et de batterie, et les redémarrages. " +
                        "Pour ntfy, installez simplement l'application et abonnez-vous au sujet (choisissez un nom long, difficile à deviner). Seul l'état est envoyé — jamais d'adresse IP, de SSID ni d'informations sur les appareils.",
                ),
                listOf(openSettingsApp() to { a -> a.startActivity(Intent(a, SettingsActivity::class.java)) }),
            ),
        ),
        tr("同期・通知・通話", "Sync, notifications and calls", "Synchronisation, notifications et appels") to listOf(
            Item(
                tr("自動同期", "Auto-sync", "Synchronisation automatique"), manual(),
                { _ -> if (ContentResolver.getMasterSyncAutomatically()) "ON" to Level.INFO else "OFF" to Level.OK },
                tr(
                    "OFF（必要なアカウントだけ手動で同期）", "OFF (sync the accounts you need by hand)",
                    "OFF (synchronisez à la main les comptes nécessaires)",
                ),
                tr(
                    "アカウントの自動同期は、端末自身のバックグラウンド通信と電力消費の主な原因です。",
                    "Account auto-sync is a main cause of the phone's own background traffic and power use.",
                    "La synchronisation automatique des comptes est une cause principale du trafic en arrière-plan et de la consommation du téléphone.",
                ),
                listOf(tr("アカウントと同期を開く", "Open Accounts", "Ouvrir Comptes") to { a -> SystemScreens.sync(a) }),
            ),
            Item(
                tr("バックグラウンド通信の抑制", "Limit background traffic", "Limiter le trafic en arrière-plan"), tags(manual(), verify()),
                null,
                tr(
                    "通信量の多いアプリの「バックグラウンドデータ」をOFF", "Turn off “Background data” for apps that use a lot",
                    "Désactivez les « Données en arrière-plan » des applications gourmandes",
                ),
                tr(
                    "アプリ情報 → モバイルデータ → バックグラウンドデータ をOFFにします。データセーバーはテザリングを止めるので使いません。どのアプリが通信しているかはデータ使用量の画面で確認できます。",
                    "Turn off App info → Mobile data → Background data. Don't use Data Saver, as it stops tethering. See which apps use data on the Data usage screen.",
                    "Désactivez Infos de l'application → Données mobiles → Données en arrière-plan. N'utilisez pas l'économiseur de données, qui bloque le partage. L'écran de consommation des données montre quelles applications communiquent.",
                ),
                listOf(
                    openDataUsage() to { a -> SystemScreens.dataUsage(a) },
                    tr("アプリの整理を開く", "Open App list", "Ouvrir la liste des applications") to { a -> a.startActivity(Intent(a, AppsActivity::class.java)) },
                ),
            ),
            Item(
                tr("通知の抑制", "Fewer notifications", "Moins de notifications"), manual(),
                { c ->
                    val nm = c.getSystemService(NotificationManager::class.java)
                    val enabled = nm?.areNotificationsEnabled()
                    val alert = nm?.getNotificationChannel(Notifier.CH_ALERT)?.importance
                    when {
                        enabled == false -> tr("このアプリの通知がOFF", "This app's notifications are off", "Les notifications de cette application sont désactivées") to Level.WARN
                        alert == NotificationManager.IMPORTANCE_NONE -> c.getString(R.string.channel_alert).let {
                            tr("「$it」がOFF", "“$it” is off", "« $it » est désactivé")
                        } to Level.WARN
                        else -> tr("このアプリの警告は有効", "This app's alerts are on", "Les alertes de cette application sont activées") to Level.OK
                    }
                },
                getString(R.string.channel_alert).let {
                    tr(
                        "不要なアプリの通知はOFF、このアプリの「$it」はON", "Notifications of unneeded apps off; this app's “$it” on",
                        "Notifications des applications inutiles désactivées ; « $it » de cette application activé",
                    )
                },
                getString(R.string.channel_alert).let {
                    tr(
                        "運用の妨げになる通知を減らします。重要な警告（このアプリの「$it」）は残してください。",
                        "Reduce notifications that get in the way. Keep the important ones (this app's “$it”).",
                        "Réduisez les notifications gênantes. Gardez les importantes (« $it » de cette application).",
                    )
                },
                listOf(tr("このアプリの通知設定を開く", "Open this app's notification settings", "Ouvrir les paramètres de notification de cette application") to { a -> SystemScreens.appNotifications(a) }),
            ),
            Item(
                tr("通話・SMS", "Calls and SMS", "Appels et SMS"), tags(manual(), verify()),
                null,
                tr(
                    "電話・SMSのアプリは無効化しない。着信音などは音の設定で小さく", "Don't disable phone or SMS apps. Turn ringtones down in Sound settings",
                    "Ne désactivez pas les applications Téléphone ou SMS. Baissez les sonneries dans les paramètres Son",
                ),
                tr(
                    "緊急通報や回線維持への影響を避けるため、電話・SMS 関連のシステムアプリは止めません。データ専用SIMでは通話は使われません。通常のアプリだけで通話・SMSを完全に止めることはできません。",
                    "Phone and SMS system apps are left alone to avoid affecting emergency calls and the line. A data-only SIM doesn't use calls. A normal app can't fully block calls and SMS.",
                    "Les applications système Téléphone et SMS ne sont pas touchées, pour ne pas affecter les appels d'urgence et la ligne. Une SIM données uniquement n'utilise pas d'appels. Une application ordinaire ne peut pas bloquer complètement appels et SMS.",
                ),
                listOf(tr("音の設定を開く", "Open Sound settings", "Ouvrir les paramètres Son") to { a -> SystemScreens.sound(a) }),
            ),
            Item(
                tr("不要なアプリの整理", "Remove unneeded apps", "Supprimer les applications inutiles"), tags(apk(), adbSetup()),
                { c ->
                    val n = c.packageManager.getInstalledApplications(0).count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != c.packageName }
                    userApps(n) to Level.INFO
                },
                tr("使わないアプリはアンインストール", "Uninstall apps you don't use", "Désinstallez les applications inutilisées"),
                tr(
                    "ユーザーが入れたアプリだけを一覧にします（システムアプリの一括無効化はしません）。アンインストール・無効化はアプリ情報の画面から行います。",
                    "Lists only apps you installed (system apps are never disabled in bulk). Uninstall or disable them from their app info screen.",
                    "Liste seulement les applications que vous avez installées (les applications système ne sont jamais désactivées en bloc). Désinstallez-les ou désactivez-les depuis leurs informations.",
                ),
                listOf(openApps() to { a -> a.startActivity(Intent(a, AppsActivity::class.java)) }),
                tr("<パッケージ名>", "<package>", "<paquet>").let { pkg ->
                    "adb shell pm disable-user --user 0 $pkg\n" + tr("（戻す: ", "(undo: ", "(annuler : ") + "adb shell pm enable $pkg" + tr("）", ")", ")")
                },
            ),
            Item(
                tr("アプリのバックグラウンド制限", "App background restrictions", "Restrictions d'arrière-plan des applications"), adbSetup(),
                { c ->
                    val n = c.packageManager.getInstalledApplications(0).count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != c.packageName }
                    userApps(n) + Fmt.paren(
                        tr("制限の状態は PC の adb-setup で確認できます", "check the restrictions with adb-setup on a PC", "vérifiez les restrictions avec adb-setup sur un PC"),
                    ) to Level.INFO
                },
                tr(
                    "このアプリ以外をまとめて制限（必要なアプリは除外）", "Restrict all apps except this one (exclude the ones you need)",
                    "Restreindre toutes les applications sauf celle-ci (exclure celles dont vous avez besoin)",
                ),
                tr(
                    "このアプリ以外のユーザーアプリを「バックグラウンドでの動作を制限」し、使用頻度を「低い」（rare）にして、裏での動作と通信を減らします。" +
                        "アプリを開けば普通に使えます。裏で動いてほしいアプリ（VPN・遠隔操作・通知が必要なものなど）は除外してください。",
                    "Restricts background activity of user apps other than this one and sets their standby bucket to “rare”, reducing background work and traffic. " +
                        "Apps still work normally when opened. Exclude apps that must run in the background (VPN, remote control, apps whose notifications you need, etc.).",
                    "Restreint l'activité en arrière-plan des applications utilisateur autres que celle-ci et règle leur groupe de mise en veille sur « rare », pour réduire le travail et le trafic en arrière-plan. " +
                        "Les applications fonctionnent normalement une fois ouvertes. Excluez celles qui doivent tourner en arrière-plan (VPN, contrôle à distance, notifications nécessaires, etc.).",
                ),
                listOf(openApps() to { a -> a.startActivity(Intent(a, AppsActivity::class.java)) }),
                tr("<パッケージ名>", "<package>", "<paquet>").let { pkg ->
                    "tools\\adb-setup.bat -Apply -RestrictApps\n" +
                        tr("（除外: -KeepApps パッケージ名,…　戻す: -Restore）\n個別に:", "(exclude: -KeepApps package,…; undo: -Restore)\nIndividually:", "(exclure : -KeepApps paquet,… ; annuler : -Restore)\nIndividuellement :") +
                        "\nadb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore\nadb shell am set-standby-bucket $pkg rare"
                },
            ),
            Item(
                tr("使用状況へのアクセス（アプリ別の通信量）", "Usage access (data per app)", "Accès aux données d'utilisation (données par application)"), tags(apk(), adbSetup()),
                { c ->
                    onOff(
                        AppUsageProbe.hasAccess(c), okWhenOff = false,
                        onText = tr("許可", "Allowed", "Autorisé"), offText = tr("未許可", "Not allowed", "Non autorisé"),
                    )
                },
                tr("許可（読むだけ）", "Allowed (read-only)", "Autorisé (lecture seule)"),
                tr(
                    "「省電力の分析」で、アプリ別のモバイル通信量と、テザリング（接続端末）の通信量を分けて見るために使います。ほかのアプリには何もしません。",
                    "Used by “Power analysis” to show mobile data per app separately from hotspot (connected device) traffic. Nothing is done to other apps.",
                    "Utilisé par « Analyse de la consommation » pour afficher les données mobiles par application, séparément du trafic du point d'accès (appareils connectés). Rien n'est fait aux autres applications.",
                ),
                listOf(
                    tr("使用状況へのアクセスを開く", "Open Usage access", "Ouvrir l'accès aux données d'utilisation") to { a -> SystemScreens.usageAccess(a) },
                    tr("省電力の分析を開く", "Open Power analysis", "Ouvrir l'analyse de la consommation") to { a -> a.startActivity(Intent(a, AnalysisActivity::class.java)) },
                ),
                "adb shell appops set ${BuildConfig.APPLICATION_ID} GET_USAGE_STATS allow\n" +
                    tr("（tools\\adb-setup.bat -Apply でも許可します）", "(tools\\adb-setup.bat -Apply also allows it)", "(tools\\adb-setup.bat -Apply l'autorise aussi)"),
            ),
            Item(
                tr("接続端末の「従量制」設定", "“Metered” setting on connected devices", "Réglage « limité » sur les appareils connectés"),
                manual() + Fmt.paren(tr("接続端末", "connected devices", "appareils connectés")),
                null,
                tr("接続端末を「従量制」「省データモード」に", "Set connected devices to “metered” / Low Data Mode", "Réglez les appareils connectés sur « limité » / Mode données faibles"),
                tr(
                    "接続端末の OS 更新・クラウド同期は、テザリングの通信量と消費の大きな原因です。Windows・Mac・iPhone・Android の手順をまとめています。",
                    "System updates and cloud sync on connected devices are a major cause of hotspot data use and power consumption. Steps for Windows, Mac, iPhone and Android.",
                    "Les mises à jour du système et la synchronisation cloud des appareils connectés sont une cause majeure du trafic et de la consommation. Étapes pour Windows, Mac, iPhone et Android.",
                ),
                listOf(
                    tr("接続端末の設定ガイドを開く", "Open Client device guide", "Ouvrir le guide des appareils connectés") to { a -> a.startActivity(Intent(a, ClientGuideActivity::class.java)) },
                    tr("通信量（時間帯別）を開く", "Open Traffic by hour", "Ouvrir Trafic par heure") to { a -> a.startActivity(Intent(a, TrafficActivity::class.java)) },
                ),
            ),
        ),
        tr("更新管理", "Updates", "Mises à jour") to listOf(
            Item(
                tr("システムの自動更新（OS OTA）", "Automatic system updates (OTA)", "Mises à jour automatiques du système (OTA)"), tags(adbSetup(), manual(), verify()),
                { c ->
                    when (Os.global(c, "ota_disable_automatic_update")) {
                        "1" -> tr("自動更新しない", "No automatic updates", "Pas de mise à jour automatique") to Level.INFO
                        else -> tr("自動更新する（既定）", "Automatic updates (default)", "Mises à jour automatiques (par défaut)") to Level.INFO
                    }
                },
                tr(
                    "運用中は手動。月1回などメンテナンスモードで更新", "Manual while in service; update in maintenance mode, e.g. once a month",
                    "Manuel en service ; mettez à jour en mode maintenance, par exemple une fois par mois",
                ),
                tr(
                    "自動更新は運用中の突然の再起動につながります。止める場合も、セキュリティ更新を止めっぱなしにせず、定期的に「メンテナンスモード → 更新 → 再起動 → 起動後チェックの確認」を行ってください。この設定に従わない機種もあります。",
                    "Automatic updates can cause sudden reboots in service. If you turn them off, don't skip security updates forever: regularly do “maintenance mode → update → reboot → check the after-reboot check”. Some phones ignore this setting.",
                    "Les mises à jour automatiques peuvent provoquer des redémarrages soudains. Si vous les désactivez, n'abandonnez pas les mises à jour de sécurité : faites régulièrement « mode maintenance → mise à jour → redémarrage → contrôle de la vérification après redémarrage ». Certains téléphones ignorent ce réglage.",
                ),
                listOf(
                    tr("システムアップデートを開く", "Open System update", "Ouvrir Mise à jour du système") to { a -> SystemScreens.systemUpdate(a) },
                    openDeveloper() to { a -> SystemScreens.developer(a) },
                ),
                "adb shell settings put global ota_disable_automatic_update 1\n" +
                    tr("（戻す: … ota_disable_automatic_update 0）", "(undo: … ota_disable_automatic_update 0)", "(annuler : … ota_disable_automatic_update 0)"),
            ),
            Item(
                tr("Play ストアのアプリ自動更新", "Play Store auto-update", "Mises à jour automatiques du Play Store"), manual(),
                null,
                tr("「Wi-Fi経由のみ」または「自動更新しない」", "“Over Wi-Fi only” or “Don't auto-update apps”", "« Via le Wi-Fi uniquement » ou « Ne pas mettre à jour automatiquement les applications »"),
                tr(
                    "Play ストア → プロフィール → 設定 → ネットワーク設定 → アプリの自動更新。この端末はモバイル回線で通信するので「Wi-Fi経由のみ」なら実質止まります。定期的に手動で更新してください。",
                    "Play Store → profile → Settings → Network preferences → Auto-update apps. This phone uses the mobile line, so “Over Wi-Fi only” effectively stops it. Update by hand regularly.",
                    "Play Store → profil → Paramètres → Préférences réseau → Mise à jour automatique des applications. Ce téléphone utilise la ligne mobile : « Via le Wi-Fi uniquement » les arrête en pratique. Mettez à jour à la main régulièrement.",
                ),
                listOf(tr("Play ストアを開く", "Open Play Store", "Ouvrir le Play Store") to { a -> SystemScreens.playStore(a) }),
            ),
            Item(
                tr("Google Play システムアップデート", "Google Play system update", "Mise à jour du système Google Play"), tr("手動確認", "Manual check", "Vérification manuelle"),
                null,
                tr("定期的に状態を確認", "Check its state regularly", "Vérifiez son état régulièrement"),
                tr(
                    "OS が管理するため止められません。設定 → セキュリティ → Google Play システムアップデート で確認します。適用に再起動が必要な場合があります。",
                    "Managed by the system and can't be stopped. Check under Settings → Security → Google Play system update. Applying it may need a reboot.",
                    "Gérée par le système, elle ne peut pas être arrêtée. Vérifiez dans Paramètres → Sécurité → Mise à jour du système Google Play. Son application peut nécessiter un redémarrage.",
                ),
                listOf(openSecurity() to { a -> SystemScreens.security(a) }),
            ),
            Item(
                tr("このアプリの更新", "Updating this app", "Mise à jour de cette application"), "ADB",
                { _ -> BuildConfig.VERSION_NAME + Fmt.paren("${BuildConfig.VERSION_CODE}") to Level.INFO },
                tr("PCから上書きインストール", "Install over it from a PC", "Installer par-dessus depuis un PC"),
                tr(
                    "新しい APK を adb install -r で上書きします（同じ署名鍵が必要）。更新後は監視が自動で再開します。作業前にメンテナンスモードにしておくと誤検知の通知が出ません。",
                    "Install the new APK over the old one with adb install -r (same signing key required). Monitoring restarts automatically after the update. Turn on maintenance mode first to avoid false alerts.",
                    "Installez le nouvel APK par-dessus avec adb install -r (même clé de signature requise). La surveillance redémarre automatiquement après la mise à jour. Activez d'abord le mode maintenance pour éviter les fausses alertes.",
                ),
                adb = "adb install -r TetheringDedicated-v" + tr("<版>", "<version>", "<version>") + ".apk",
            ),
        ),
    )
}
