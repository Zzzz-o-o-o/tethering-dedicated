package local.tethering.dedicated

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * はじめの設定（v0.8.0）。PC を使わず APK だけで導入した人が、必要な許可と設定を上から順に済ませられるようにする。
 * どの項目も OS の画面を開くか、このアプリの設定を変えるだけ（OS の設定は変えない）。新しく入れたときの初回の起動で開き、メイン画面からも開ける
 */
class SetupActivity : Activity() {
    companion object {
        /** 初回の起動で開いたか（ui の SharedPreferences） */
        const val KEY_SHOWN = "setup_shown"
        private const val REQ_PHONE = 1
    }

    private enum class Level(val mark: String, val color: Int) {
        OK("✓", R.color.status_ok),
        TODO("⚠", R.color.status_warn),
        OPTIONAL("・", R.color.text_secondary),
    }

    private class Step(val status: TextView, val read: (Context) -> Pair<String, Level>)

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val steps = ArrayList<Step>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(
                c,
                tr(
                    "PC は要りません。上から順にボタンを押し、OS の画面で許可したら「戻る」でこの画面に戻ってください。あとからメイン画面の「はじめの設定」でも開けます。",
                    "No PC is needed. Tap the buttons from the top; when a system screen opens, allow it there and press Back to return here. You can open this screen again from “Initial setup” on the main screen.",
                    "Aucun PC n'est nécessaire. Touchez les boutons dans l'ordre ; quand un écran du système s'ouvre, autorisez-y l'accès puis appuyez sur Retour pour revenir ici. Vous pouvez rouvrir cet écran avec « Configuration initiale » sur l'écran principal.",
                ),
                13.5f,
            )
        }

        step(
            page, 1, tr("「電話」の権限", "“Phone” permission", "Autorisation « Téléphone »"),
            tr(
                "回線の種類（LTE など）と電波を読むために使います。OS の画面には「電話の発信と管理」と出ますが、発信・通話・SMS はしません。",
                "Used to read the network type (LTE etc.) and the signal. The system screen says “make and manage phone calls”, but the app never makes calls or sends SMS.",
                "Sert à lire le type de réseau (LTE, etc.) et le signal. L'écran du système indique « passer et gérer des appels », mais l'application ne passe jamais d'appel et n'envoie pas de SMS.",
            ),
            { c ->
                if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) done() else notYet()
            },
            listOf(allow() to { requestPhone() }),
        )
        step(
            page, 2, tr("電池の最適化の対象外にする", "Exclude from battery optimization", "Exclure de l'optimisation de la batterie"),
            tr(
                "画面が消えていても、チェックが遅れないようにします。常時の電力消費は増えません。",
                "Keeps checks on time while the screen is off. It doesn't increase power use.",
                "Les vérifications restent à l'heure écran éteint. La consommation n'augmente pas.",
            ),
            { c ->
                if (c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true) done() else notYet()
            },
            listOf(tr("除外を依頼する", "Request exclusion", "Demander l'exclusion") to { SystemScreens.requestIgnoreBatteryOptimizations(this) }),
        )
        step(
            page, 3, tr("「システム設定の変更」を許可する", "Allow “Modify system settings”", "Autoriser « Modifier les paramètres système »"),
            tr(
                "このアプリがテザリングを ON・OFF するときに使います（起動時の ON・時間帯スケジュール・通信量の上限）。開いた画面で「システム設定の変更を許可」を ON にしてください。",
                "Used when the app turns the hotspot on or off (at startup, quiet-hours schedule, data cap). On the screen that opens, turn on “Allow modifying system settings”.",
                "Utilisée quand l'application active ou arrête le point d'accès (au démarrage, planification, plafond de données). Sur l'écran qui s'ouvre, activez « Autoriser la modification des paramètres système ».",
            ),
            { c ->
                when {
                    !TetherControl.permitted(c) -> notYet()
                    else -> TetherControl.probe(c).let { r ->
                        if (r.ok) {
                            tr("許可済み（このアプリから切り替えられます）", "Allowed (this app can switch the hotspot)", "Autorisé (l'application peut commander le point d'accès)") to Level.OK
                        } else {
                            tr("許可済みですが、切り替えられません（${r.text}）", "Allowed, but switching isn't possible (${r.text})", "Autorisé, mais bascule impossible (${r.text})") to Level.TODO
                        }
                    }
                }
            },
            listOf(tr("許可する（OS の画面）", "Allow (system screen)", "Autoriser (écran du système)") to { SystemScreens.writeSettings(this) }),
        )
        step(
            page, 4, tr("アクセスポイントの自動 OFF を解除する", "Turn off the hotspot's auto turn-off", "Désactiver l'arrêt automatique du point d'accès"),
            tr(
                "接続がしばらくないとテザリングが自動で止まる設定を OFF にします。開いた画面の「アクセスポイントを自動的に OFF にする」（機種によって名前が違います）を OFF にしてください。アクセスポイントの名前とパスワードも、この画面で確認・変更できます。",
                "Turns off the setting that stops the hotspot when nothing has been connected for a while. On the screen that opens, turn off “Turn off hotspot automatically” (the name varies by phone). The hotspot name and password are on the same screen.",
                "Désactive le réglage qui arrête le point d'accès sans appareil connecté pendant un moment. Sur l'écran qui s'ouvre, désactivez « Désactiver automatiquement le point d'accès » (le nom varie selon le téléphone). Le nom et le mot de passe du point d'accès se trouvent sur le même écran.",
            ),
            { c ->
                when (Os.global(c, TetherProbe.KEY_SOFT_AP_TIMEOUT)) {
                    "0" -> "OFF" to Level.OK
                    null -> tr("未設定（多くの端末では ON）", "Not set (ON on most phones)", "Non défini (ON sur la plupart des téléphones)") to Level.TODO
                    else -> "ON" to Level.TODO
                }
            },
            listOf(tr("アクセスポイントの設定を開く", "Open hotspot settings", "Ouvrir les paramètres du point d'accès") to { SystemScreens.hotspot(this) }),
        )
        step(
            page, 5, Defs.TETHER_ON_BOOT.label,
            tr(
                "再起動のあとも、自動で Wi-Fiテザリングを ON にします（PIN などの画面ロックの解除前でも）。3 の許可が必要です。",
                "Turns the Wi-Fi hotspot on automatically after every reboot (even before a PIN or other screen lock is unlocked). Needs step 3.",
                "Active automatiquement le point d'accès Wi-Fi après chaque redémarrage (même avant le déverrouillage d'un code PIN ou autre). Nécessite l'étape 3.",
            ),
            { c -> if (Prefs(c).bool(Defs.TETHER_ON_BOOT)) "ON" to Level.OK else "OFF" to Level.TODO },
            listOf(tr("ON にする", "Turn on", "Activer") to { setBootOn(true) }, tr("OFF にする", "Turn off", "Désactiver") to { setBootOn(false) }),
        )
        step(
            page, 6, tr("Wi-Fiテザリングを ON にする", "Turn on the Wi-Fi hotspot", "Activer le point d'accès Wi-Fi"),
            tr(
                "いま ON にします。接続する端末では、4 の画面で確かめたアクセスポイントの名前とパスワードを選んで入れてください。",
                "Turns it on now. On the devices you connect, choose the hotspot name and enter the password shown on the screen in step 4.",
                "L'active maintenant. Sur les appareils à connecter, choisissez le nom du point d'accès et saisissez le mot de passe indiqués sur l'écran de l'étape 4.",
            ),
            { c ->
                when (TetherProbe.read(c, countClients = false).wifiOn) {
                    true -> "ON" to Level.OK
                    false -> "OFF" to Level.TODO
                    null -> Fmt.NA to Level.OPTIONAL
                }
            },
            listOf(tr("今すぐ ON にする", "Turn on now", "Activer maintenant") to { turnOnNow() }),
        )
        step(
            page, 7, tr("使用状況へのアクセス（任意）", "Usage access (optional)", "Accès aux données d'utilisation (facultatif)"),
            tr(
                "月の通信量の上限と、アプリ別の通信量（省電力の分析）に使います。開いた一覧から「テザリング専用化」を選び、許可を ON にしてください。",
                "Used for the monthly data cap and the data use per app (Power analysis). In the list that opens, choose “Tethering Dedicated” and turn the permission on.",
                "Sert au plafond de données mensuel et à la consommation par application (Analyse de la consommation). Dans la liste qui s'ouvre, choisissez « Tethering Dedicated » et activez l'autorisation.",
            ),
            { c -> if (AppUsageProbe.hasAccess(c)) done() else optional() },
            listOf(tr("許可する（OS の画面）", "Allow (system screen)", "Autoriser (écran du système)") to { SystemScreens.usageAccess(this) }),
        )
        step(
            page, 8, tr("月の通信量の上限（任意）", "Monthly data cap (optional)", "Plafond de données mensuel (facultatif)"),
            tr(
                "料金プランに上限があるなら、設定の「月の通信量の上限」で決めると、80% と 100% で知らせます。",
                "If your plan has a data cap, set it under “Monthly data cap” in Settings to be notified at 80% and 100%.",
                "Si votre forfait a un plafond, réglez-le dans « Plafond de données mensuel » des paramètres pour être averti à 80 % et 100 %.",
            ),
            { c ->
                val p = Prefs(c)
                if (p.bool(Defs.DATA_CAP)) Fmt.gb(p.int(Defs.DATA_CAP_GB) * DataCap.GB) to Level.OK else tr("使わない", "Not used", "Désactivé") to Level.OPTIONAL
            },
            listOf(tr("設定を開く", "Open Settings", "Ouvrir les paramètres") to { startActivity(Intent(this, SettingsActivity::class.java)) }),
        )
        Ui.card(page).also { c ->
            Ui.text(c, tr("PC から行う追加の設定（任意）", "Extra setup from a PC (optional)", "Configuration supplémentaire depuis un PC (facultatif)"), 15.5f, bold = true)
            Ui.small(
                c,
                tr(
                    "PC の ADB が必要なのは、バッテリー駆動中にバッテリーセーバー・Doze を自動で切り替える機能だけです。省電力のための OS の設定は「設定ガイド」から 1 つずつ変えられます（PC の tools\\adb-setup.bat を使うと、まとめて変えて元に戻せます）。",
                    "A PC with ADB is needed only for switching Battery Saver and Doze automatically on battery. The power-saving system settings can be changed one by one from the “Setup guide” (with tools\\adb-setup.bat on a PC, they can be changed and undone all at once).",
                    "Un PC avec ADB n'est nécessaire que pour la bascule automatique de l'économiseur de batterie et de Doze sur batterie. Les réglages d'économie d'énergie du système peuvent être modifiés un par un depuis le « Guide de configuration » (avec tools\\adb-setup.bat sur un PC, ils peuvent être modifiés et annulés en une fois).",
                ),
            )
            Ui.button(c, tr("設定ガイドを開く", "Open Setup guide", "Ouvrir le guide de configuration")) { startActivity(Intent(this, GuideActivity::class.java)) }
        }
        Ui.button(page, tr("完了（メイン画面へ）", "Done (to the main screen)", "Terminé (vers l'écran principal)")) { finish() }
        Ui.spacer(page, 10)
    }

    override fun onResume() {
        super.onResume()
        // OS の画面から戻ってきたら、状態を読み直す
        refresh()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun allow() = tr("許可する", "Allow", "Autoriser")

    private fun done() = tr("済み", "Done", "Fait") to Level.OK

    private fun notYet() = tr("まだ", "Not yet", "Pas encore") to Level.TODO

    private fun optional() = tr("まだ（任意）", "Not yet (optional)", "Pas encore (facultatif)") to Level.OPTIONAL

    private fun step(
        page: LinearLayout,
        n: Int,
        title: String,
        why: String,
        read: (Context) -> Pair<String, Level>,
        actions: List<Pair<String, () -> Unit>>,
    ) {
        val c = Ui.card(page)
        Ui.text(c, "$n. $title", 15.5f, bold = true)
        val status = Ui.text(c, tr("確認中…", "Checking…", "Vérification…"), 14f)
        Ui.small(c, why)
        // ON と OFF のように対になるボタンは横に並べる
        if (actions.size == 2) Ui.buttonRow(c, actions[0], actions[1]) else for ((label, action) in actions) Ui.button(c, label, action)
        steps.add(Step(status, read))
    }

    private fun refresh() {
        val list = steps.toList()
        bg.execute {
            val results = list.map { s -> runCatching { s.read(this) }.getOrElse { Fmt.NA to Level.OPTIONAL } }
            main.post {
                if (isDestroyed) return@post
                list.forEachIndexed { i, s ->
                    val (text, level) = results[i]
                    s.status.text = tr("${level.mark} 現在: $text", "${level.mark} Now: $text", "${level.mark} Actuel : $text")
                    s.status.setTextColor(getColor(level.color))
                }
            }
        }
    }

    private fun requestPhone() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) return refresh()
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE) || !ui.getBoolean("phone_denied", false)) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
        } else {
            // 「今後表示しない」が選ばれた後はダイアログが出ないので、アプリ情報の権限画面を開く
            toast(tr("「権限」から「電話」を許可してください", "Allow “Phone” under “Permissions”", "Autorisez « Téléphone » dans « Autorisations »"))
            SystemScreens.open(this, SystemScreens.appDetails(packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PHONE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("phone_denied", !granted).apply()
        if (granted && prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
        refresh()
    }

    private fun setBootOn(on: Boolean) {
        if (on && !TetherControl.permitted(this)) {
            toast(tr("先に 3 の「システム設定の変更」を許可してください", "Allow “Modify system settings” in step 3 first", "Autorisez d'abord « Modifier les paramètres système » (étape 3)"))
            SystemScreens.writeSettings(this)
            return
        }
        if (prefs.bool(Defs.TETHER_ON_BOOT) == on) return refresh()
        val before = prefs.values()
        prefs.put(Defs.TETHER_ON_BOOT, on)
        val changes = SettingsCodec.describeChanges(before, before + (Defs.TETHER_ON_BOOT.key to on))
        LogStore.write(this, Ev.SETTINGS, StateStore.state, result = tr("${changes.size}件", "${changes.size} change(s)", "${changes.size} modification(s)"), detail = changes.joinToString(" / "))
        if (prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_SETTINGS)
        toast(tr("保存しました", "Saved", "Enregistré"))
        refresh()
    }

    private fun turnOnNow() {
        if (!TetherControl.permitted(this)) {
            // 許可がなければ、OS のテザリングの画面から ON にしてもらう
            toast(tr("OS の画面で「Wi-Fi アクセスポイント」を ON にしてください", "Turn on “Wi-Fi hotspot” on the system screen", "Activez « Point d'accès Wi-Fi » sur l'écran du système"))
            SystemScreens.tether(this)
            return
        }
        TetherControl.start(this) { r ->
            if (isDestroyed) return@start
            toast(
                if (r.ok) {
                    tr("ON にするよう依頼しました", "Asked the system to turn it on", "Activation demandée au système")
                } else {
                    tr("ON にできませんでした（${r.text}）", "Couldn't turn it on (${r.text})", "Activation impossible (${r.text})")
                },
            )
            main.postDelayed({ if (!isDestroyed) refresh() }, 3_000L)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
