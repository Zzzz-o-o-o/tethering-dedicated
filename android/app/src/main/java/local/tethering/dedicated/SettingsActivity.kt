package local.tethering.dedicated

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 設定の編集・バックアップ／復元（仕様 F-21）と、変更前の値への巻き戻し（F-23）。
 * 保存・復元・初期化の直前の設定は files/settings_before_change.json に残し、「直前の設定に戻す」で戻せる。
 */
class SettingsActivity : Activity() {
    companion object {
        private const val REQ_SAVE = 1
        private const val REQ_OPEN = 2
        private const val BEFORE_FILE = "settings_before_change.json"
        private const val MAX_BACKUP_BYTES = 256 * 1024
    }

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val intFields = LinkedHashMap<IntDef, EditText>()
    private val boolFields = LinkedHashMap<BoolDef, Switch>()
    private val textFields = LinkedHashMap<TextDef, EditText>()
    private lateinit var logSize: TextView
    private lateinit var undoButton: Button
    private lateinit var autoStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        val page = Ui.page(this)

        section(
            page, tr("チェックの間隔", "Check interval", "Intervalle de vérification"),
            listOf(Defs.INTERVAL_NORMAL, Defs.INTERVAL_SAVER, Defs.TIMEOUT), texts = listOf(Defs.RECHECK),
        )
        section(
            page, tr("インターネット到達確認", "Internet reachability check", "Test d'accès à Internet"),
            listOf(Defs.EXPECT_CODE), bools = listOf(Defs.HTTP), texts = listOf(Defs.URL), boolsFirst = true,
        )
        section(page, tr("テザリング", "Tethering", "Partage de connexion"), emptyList(), bools = listOf(Defs.TETHER_EXPECTED, Defs.CLIENTS, Defs.DEVICE_LIST, Defs.TETHER_ON_BOOT)).also { c ->
            Ui.spacer(c, 4)
            bootStatus = Ui.small(c, "")
            tetherControlButtons(c)
        }
        section(
            page, tr("温度・電池の警告", "Temperature and battery alerts", "Alertes de température et de batterie"),
            listOf(Defs.TEMP_WARN, Defs.TEMP_CRIT, Defs.THERMAL_WARN, Defs.BATT_LOW),
        )
        section(
            page, tr("通知", "Notifications", "Notifications"), emptyList(), bools = listOf(Defs.NOTIFY_ALERT, Defs.NOTIFY_RECOVERY, Defs.NOTIFY_BOOT),
        ).also { c ->
            Ui.button(
                c,
                tr(
                    "通知の詳細（音・重要度）をOSの設定で変える", "Change notification details (sound, importance) in system settings",
                    "Modifier les détails des notifications (son, importance) dans les paramètres du système",
                ),
            ) { SystemScreens.appNotifications(this) }
        }
        section(page, tr("起動後チェック", "After-reboot check", "Vérification après redémarrage"), listOf(Defs.BOOT_DELAY))
        section(page, tr("消費電力の計測", "Power measurement", "Mesure de la consommation"), listOf(Defs.BATT_CAPACITY, Defs.RUNTIME_ALERT))
        section(page, tr("比較テスト", "Comparison test", "Test comparatif"), listOf(Defs.EXP_PHASE, Defs.EXP_REPEATS, Defs.EXP_MIN_GAIN))
        section(
            page, tr("電波の悪化（設置場所）", "Signal degradation (placement)", "Dégradation du signal (emplacement)"),
            listOf(Defs.SIGNAL_WEAK_RSRP, Defs.SIGNAL_DROP_DB), bools = listOf(Defs.SIGNAL_ALERT), boolsFirst = true,
        )
        section(page, tr("待機中の通信", "Idle traffic", "Trafic au repos"), listOf(Defs.IDLE_TRAFFIC_MB))
        capSection(page)
        scheduleSection(page)
        section(page, tr("ローカル管理画面", "Local web page", "Page web locale"), listOf(Defs.WEB_PORT), bools = listOf(Defs.WEB), boolsFirst = true)
        section(
            page, tr("自動切り替え（バッテリー駆動中）", "Auto switching (on battery)", "Bascule automatique (sur batterie)"),
            listOf(Defs.AUTO_SAVER_PCT), bools = listOf(Defs.AUTO_SAVER, Defs.AUTO_DOZE), boolsFirst = true,
        ).also { c ->
            // Android 12 からは Doze の設定をアプリから変えられないので、選べないようにして理由を出す
            if (!AutoSwitch.dozeWorks) {
                boolFields[Defs.AUTO_DOZE]?.isEnabled = false
                Ui.small(
                    c,
                    tr(
                        "「Doze を早める」は Android 12 以降では使えません（Doze の設定が、アプリから変えられない場所に移ったため）。",
                        "“Enter Doze sooner” doesn't work on Android 12 and later (Doze settings moved where apps can't change them).",
                        "« Entrer plus tôt en Doze » ne fonctionne pas sur Android 12 et ultérieur (les réglages Doze ont été déplacés là où les applications ne peuvent pas les modifier).",
                    ),
                )
            }
            autoStatus = Ui.small(c, "")
            Ui.button(c, restoreAllLabel()) { confirmAutoRestore() }
        }
        section(
            page, tr("監視の通信を減らす", "Reduce monitoring traffic", "Réduire le trafic de surveillance"),
            listOf(Defs.IDLE_INTERVAL, Defs.IDLE_BATT), bools = listOf(Defs.SKIP_PROBE), boolsFirst = true,
        )
        plugSection(page)
        extSection(page)
        section(page, tr("ログ（操作ログと電力の記録）", "Logs (event log and power records)", "Journaux (événements et consommation)"), listOf(Defs.LOG_DAYS, Defs.LOG_MB)).also { c ->
            logSize = Ui.small(c, "")
            Ui.button(c, tr("ログを消去", "Clear logs", "Effacer les journaux")) { confirmClearLogs() }
        }

        Ui.button(page, tr("保存", "Save", "Enregistrer")) { save() }
        Ui.spacer(page, 10)

        Ui.card(page).also { c ->
            Ui.heading(c, tr("バックアップと復元", "Backup and restore", "Sauvegarde et restauration"))
            Ui.small(
                c,
                tr(
                    "設定（運用モードを含む）を JSON ファイルに書き出し、別の端末や再インストール後に読み込めます。ログや監視の状態は含みません。",
                    "Writes the settings (including the operating mode) to a JSON file that you can load on another phone or after reinstalling. Logs and monitoring state are not included.",
                    "Écrit les paramètres (mode de fonctionnement compris) dans un fichier JSON que vous pouvez charger sur un autre téléphone ou après réinstallation. Les journaux et l'état de la surveillance ne sont pas inclus.",
                ),
            )
            Ui.button(c, tr("設定をファイルに保存", "Save settings to a file", "Enregistrer les paramètres dans un fichier")) { saveToFile() }
            Ui.button(c, restoreFileLabel()) { openFile() }
            undoButton = Ui.button(c, undoLabel()) { undo() }
            Ui.button(c, resetLabel()) { confirmReset() }
        }
        refreshFooter()
    }

    private fun restoreAllLabel() = tr("自動で変えた設定をすべて元に戻す", "Restore everything changed automatically", "Rétablir tout ce qui a été modifié automatiquement")

    private fun restoreFileLabel() = tr("ファイルから復元", "Restore from a file", "Restaurer depuis un fichier")

    private fun undoLabel() = tr("直前の設定に戻す", "Undo last change", "Annuler la dernière modification")

    private fun resetLabel() = tr("初期値に戻す", "Reset to defaults", "Rétablir les valeurs par défaut")

    private fun sending() = tr("送っています…", "Sending…", "Envoi…")

    private fun result(ok: Boolean) = if (ok) tr("成功", "Succeeded", "Réussi") else tr("失敗", "Failed", "Échec")

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

    override fun onResume() {
        super.onResume()
        // 「システム設定の変更」の許可画面から戻ってきたときにも表示を更新する
        refreshPhase4()
    }

    private fun section(
        page: LinearLayout,
        title: String,
        ints: List<IntDef>,
        bools: List<BoolDef> = emptyList(),
        texts: List<TextDef> = emptyList(),
        boolsFirst: Boolean = false,
    ): LinearLayout {
        val c = Ui.card(page)
        Ui.heading(c, title)
        if (boolsFirst) bools.forEach { addBool(c, it) }
        ints.forEach { addInt(c, it) }
        texts.forEach { addText(c, it) }
        if (!boolsFirst) bools.forEach { addBool(c, it) }
        return c
    }

    private fun addInt(c: LinearLayout, d: IntDef) {
        // 英語・フランス語は項目名にかっこ書きが多いので、範囲は「·」で区切る
        Ui.text(c, d.label + tr("（${d.range}）", " · ${d.range}", " · ${d.range}"), 14f)
        if (d.help.isNotEmpty()) Ui.small(c, d.help)
        intFields[d] = Ui.numberField(c, prefs.int(d), signed = d.min < 0)
    }

    private fun addText(c: LinearLayout, d: TextDef) {
        Ui.text(c, d.label, 14f)
        if (d.help.isNotEmpty()) Ui.small(c, d.help)
        textFields[d] = Ui.textField(c, prefs.text(d))
    }

    // ---- 第 4 段階 ----

    private lateinit var bootStatus: TextView
    private lateinit var schedStatus: TextView
    private lateinit var capStatus: TextView

    /** 月の通信量の上限（v0.8.0） */
    private fun capSection(page: LinearLayout) {
        val c = Ui.card(page)
        Ui.heading(c, tr("月の通信量の上限", "Monthly data cap", "Plafond de données mensuel"))
        addBool(c, Defs.DATA_CAP)
        addInt(c, Defs.DATA_CAP_GB)
        addInt(c, Defs.DATA_CAP_DAY)
        addBool(c, Defs.DATA_CAP_STOP)
        Ui.spacer(c, 4)
        capStatus = Ui.small(c, "")
        Ui.button(
            c,
            tr(
                "「使用状況へのアクセス」を許可する（OS の画面）", "Allow “Usage access” (system screen)",
                "Autoriser « Accès aux données d'utilisation » (écran du système)",
            ),
        ) { SystemScreens.usageAccess(this) }
    }

    private fun capStatusText(): String {
        val readable = AppUsageProbe.hasAccess(this)
        val lines = ArrayList<String>()
        lines.add(
            SettingsCodec.issue(
                tr("「使用状況へのアクセス」", "“Usage access”", "« Accès aux données d'utilisation »"),
                if (readable) {
                    tr("許可済み", "allowed", "autorisé")
                } else {
                    tr("未許可（通信量を読むのに必要）", "not allowed (needed to read data use)", "non autorisé (nécessaire pour lire la consommation)")
                },
            ),
        )
        val cap = StateStore.state.cap
        val used = cap?.usedBytes
        if (cap != null && used != null) {
            val from = Fmt.dateTime(cap.cycleStart).take(10)
            val to = Fmt.dateTime(cap.cycleEnd - 1).take(10)
            val pct = if (cap.limitBytes > 0) used * 100 / cap.limitBytes else 0
            lines.add(
                tr("今月: ${Fmt.gb(used)} / ${Fmt.gb(cap.limitBytes)}（${pct}%・$from〜$to）", "This month: ${Fmt.gb(used)} / ${Fmt.gb(cap.limitBytes)} ($pct%, $from – $to)", "Ce mois-ci : ${Fmt.gb(used)} / ${Fmt.gb(cap.limitBytes)} ($pct %, $from – $to)"),
            )
        }
        if (cap?.stopped == true) {
            val back = Fmt.dateTime(cap.cycleEnd).take(10)
            lines.add(tr("いま止めています（$back に再開）", "Off now (back on $back)", "Arrêté actuellement (réactivation le $back)"))
        }
        if (prefs.bool(Defs.DATA_CAP_STOP) && !TetherControl.permitted(this)) {
            lines.add(
                SettingsCodec.issue(
                    tr("「システム設定の変更」", "“Modify system settings”", "« Modifier les paramètres système »"),
                    tr("未許可（止めるのに必要）", "not allowed (needed to turn it off)", "non autorisé (nécessaire pour l'arrêter)"),
                ),
            )
        }
        return lines.joinToString("\n")
    }
    private lateinit var plugStatus: TextView
    private lateinit var extStatus: TextView
    private lateinit var plugKind: RadioGroup
    private val plugKindIds = HashMap<Int, String>()

    /** 使わない時間帯（仕様 F-33 の通信の通知と、F-37 の時間帯スケジュールで共通） */
    private fun scheduleSection(page: LinearLayout) {
        val c = Ui.card(page)
        Ui.heading(
            c,
            tr(
                "使わない時間帯（通信の通知・テザリングの停止）", "Quiet hours (traffic alerts, hotspot off)",
                "Heures d'inactivité (alertes de trafic, arrêt du point d'accès)",
            ),
        )
        addText(c, Defs.QUIET_HOURS)
        addInt(c, Defs.QUIET_TRAFFIC_MB)
        addBool(c, Defs.TETHER_SCHEDULE)
        Ui.spacer(c, 4)
        schedStatus = Ui.small(c, "")
        tetherControlButtons(c)
    }

    /** 「システム設定の変更」の許可と、切り替えられるかの確認（起動時の ON と時間帯スケジュールで共通） */
    private fun tetherControlButtons(c: LinearLayout) {
        Ui.button(
            c,
            tr(
                "「システム設定の変更」を許可する（OS の画面）", "Allow “Modify system settings” (system screen)",
                "Autoriser « Modifier les paramètres système » (écran du système)",
            ),
        ) { SystemScreens.writeSettings(this) }
        Ui.button(
            c,
            tr(
                "切り替えられるか確認する（テザリングは止めません）", "Check that switching works (the hotspot isn't turned off)",
                "Vérifier que la bascule fonctionne (le point d'accès n'est pas arrêté)",
            ),
        ) {
            val r = TetherControl.probe(this)
            AlertDialog.Builder(this)
                .setTitle(
                    if (r.ok) {
                        tr("このアプリからテザリングを切り替えられます", "This app can switch the hotspot", "Cette application peut commander le point d'accès")
                    } else {
                        tr("切り替えられません", "Switching isn't possible", "Bascule impossible")
                    },
                )
                .setMessage(
                    if (r.ok) {
                        tr(
                            "起動時の ON と時間帯スケジュールを使えます。", "You can use turning it on at startup and the quiet-hours schedule.",
                            "Vous pouvez utiliser l'activation au démarrage et la planification.",
                        )
                    } else {
                        r.text
                    },
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** スマートプラグ（仕様 F-45） */
    private fun plugSection(page: LinearLayout) {
        val c = Ui.card(page)
        Ui.heading(c, tr("スマートプラグ（給電の切り替え）", "Smart plug (charging control)", "Prise connectée (pilotage de la charge)"))
        addBool(c, Defs.PLUG)
        Ui.text(c, Defs.PLUG_KIND.label, 14f)
        plugKind = RadioGroup(this)
        val current = prefs.text(Defs.PLUG_KIND)
        for ((key, label) in PlugUrls.kinds) {
            val rb = RadioButton(this).apply { text = label }
            plugKind.addView(rb)
            plugKindIds[rb.id] = key
            if (key == current) plugKind.check(rb.id)
        }
        c.addView(plugKind)
        addText(c, Defs.PLUG_HOST)
        addText(c, Defs.PLUG_ON_URL)
        addText(c, Defs.PLUG_OFF_URL)
        listOf(Defs.PLUG_LOW, Defs.PLUG_HIGH, Defs.PLUG_HOT, Defs.PLUG_FLIPBACK).forEach { addInt(c, it) }
        plugStatus = Ui.small(c, "")
        Ui.buttonRow(
            c,
            tr("ON にしてみる", "Try ON", "Essayer ON") to { plugTest(true) },
            tr("OFF にしてみる", "Try OFF", "Essayer OFF") to { plugTest(false) },
        )
        Ui.small(
            c,
            tr(
                "入力中の内容で 1 回だけ送ります（保存はしません）。連携が ON のときは、数分で目標の状態に戻します。",
                "Sends once with what you've entered (not saved). If the feature is on, the plug is set back to its target state within minutes.",
                "Envoie une fois avec ce que vous avez saisi (sans enregistrer). Si la fonction est activée, la prise revient à l'état visé en quelques minutes.",
            ),
        )
    }

    private fun selectedPlugKind(): String = plugKindIds[plugKind.checkedRadioButtonId] ?: prefs.text(Defs.PLUG_KIND)

    private fun field(d: TextDef): String = Fmt.halfWidth(textFields[d]?.text?.toString().orEmpty()).trim()

    private fun plugTest(on: Boolean) {
        val req = PlugUrls.request(selectedPlugKind(), field(Defs.PLUG_HOST), field(Defs.PLUG_ON_URL), field(Defs.PLUG_OFF_URL), on, 0)
        if (req == null) {
            toast(tr("プラグのアドレス（または URL）を入れてください", "Enter the plug's address (or URLs)", "Saisissez l'adresse de la prise (ou les URL)"))
            return
        }
        toast(sending())
        bg.execute {
            val r = WebClient.send(req.method, req.target, emptyList(), null, null, 10_000)
            main.post {
                val onOff = if (on) "ON" else "OFF"
                val res = result(r.ok)
                AlertDialog.Builder(this)
                    .setTitle(tr("スマートプラグを $onOff：$res", "Smart plug $onOff: $res", "Prise connectée $onOff : $res"))
                    .setMessage(
                        "${req.method} ${req.target.host}: ${r.text}" + if (r.ok) {
                            "\n" + tr(
                                "給電の変化は、ダッシュボードの「給電」で確かめてください。", "Check the change on the main screen under “Charger”.",
                                "Vérifiez le changement sur l'écran principal, à la ligne « Alimentation ».",
                            )
                        } else {
                            ""
                        },
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /** 外部への通知（仕様 F-44） */
    private fun extSection(page: LinearLayout) {
        val c = Ui.card(page)
        Ui.heading(c, extTitle())
        addBool(c, Defs.EXT_NOTIFY)
        addText(c, Defs.EXT_URL)
        addBool(c, Defs.EXT_RECOVERY)
        addBool(c, Defs.EXT_ALERT)
        extStatus = Ui.small(c, "")
        Ui.button(c, tr("テスト送信（入力中の URL に 1 件送る）", "Send test (one message to the URL entered)", "Envoyer un test (un message à l'URL saisie)")) {
            val url = field(Defs.EXT_URL)
            val dest = ExtNotify.parse(url)
            if (dest == null) {
                toast(ExtNotify.check(url) ?: enterUrl())
                return@button
            }
            toast(sending())
            bg.execute {
                val r = ExtNotify.sendTest(dest, Build.MODEL ?: "Android", System.currentTimeMillis())
                main.post {
                    val res = result(r.ok)
                    AlertDialog.Builder(this)
                        .setTitle(tr("テスト送信：$res", "Test: $res", "Test : $res"))
                        .setMessage(SettingsCodec.issue(dest.label, r.text))
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun extTitle() = tr("外部への通知", "External notifications", "Notifications externes")

    private fun enterUrl() = tr("通知先の URL を入れてください", "Enter the destination URL", "Saisissez l'URL de destination")

    private fun refreshPhase4() {
        if (!::schedStatus.isInitialized) return
        val s = StateStore.state
        val permitted = TetherControl.permitted(this)
        val permName = tr("「システム設定の変更」", "“Modify system settings”", "« Modifier les paramètres système »")
        val allowed = tr("許可済み", "allowed", "autorisé")
        val bootPerm = if (permitted) {
            allowed
        } else {
            tr("未許可（起動時の ON には許可が必要）", "not allowed (required to turn it on at startup)", "non autorisé (nécessaire pour l'activation au démarrage)")
        }
        capStatus.text = capStatusText()
        bootStatus.text = SettingsCodec.issue(permName, bootPerm) +
            (BootTether.lastResult(this)?.let { "\n" + tr("前回の起動時: $it", "Last startup: $it", "Dernier démarrage : $it") } ?: "")
        val perm = if (permitted) {
            allowed
        } else {
            tr("未許可（時間帯スケジュールには許可が必要）", "not allowed (required for the quiet-hours schedule)", "non autorisé (nécessaire pour la planification)")
        }
        schedStatus.text = SettingsCodec.issue(permName, perm) +
            (s.sched?.stoppedUntil?.let { "\n" + tr("いま止めています（$it に再開）", "Off now (back on at $it)", "Arrêté actuellement (réactivation à $it)") } ?: "") +
            (s.sched?.note?.let { "\n$it" } ?: "")
        plugStatus.text = s.plug?.let { p ->
            val target = p.want?.let { if (it) "ON" else "OFF" } ?: tr("未定（範囲の中）", "none (within range)", "aucune (dans la plage)")
            val why = p.why?.let { Fmt.paren(it) } ?: ""
            tr("目標: $target$why・範囲 ${p.range}", "Target: $target$why · range ${p.range}", "Cible : $target$why · plage ${p.range}") +
                (p.lastResult?.let { "\n" + tr("直近: $it", "Last: $it", "Dernier : $it") } ?: "") +
                if (p.stuck) "\n⚠ " + tr("給電の状態が目標と合いません", "Charging doesn't match the target", "La charge ne correspond pas à la cible") else ""
        } ?: tr("連携: OFF", "Feature: OFF", "Fonction : désactivée")
        extStatus.text = s.ext?.let { e ->
            val dest = e.dest ?: tr("URL が正しくありません", "invalid URL", "URL non valide")
            tr("送り先: $dest", "Destination: $dest", "Destination : $dest") +
                (if (e.pending > 0) e.pending.let { tr("・未送信 ${it}件", " · $it unsent", " · $it non envoyé(s)") } else "") +
                (e.lastResult?.let { "\n" + tr("直近: $it", "Last: $it", "Dernier : $it") } ?: "")
        } ?: tr("外部への通知: OFF", "External notifications: OFF", "Notifications externes : désactivées")
    }

    private fun addBool(c: LinearLayout, d: BoolDef) {
        val s = Switch(this).apply {
            text = d.label
            isChecked = prefs.bool(d)
        }
        c.addView(s)
        if (d.help.isNotEmpty()) Ui.small(c, d.help)
        boolFields[d] = s
    }

    private fun refreshAuto() {
        val permitted = AutoSwitch.permitted(this)
        val applied = listOfNotNull(
            tr("バッテリーセーバー", "Battery Saver", "Économiseur de batterie").takeIf { prefs.autoSaverApplied },
            "Doze".takeIf { prefs.autoDozeApplied },
        )
        autoStatus.text = (
            if (permitted) {
                tr("設定変更の権限: 付与済み", "Settings permission: granted", "Autorisation de modifier les paramètres : accordée")
            } else {
                tr(
                    "設定変更の権限: なし（PC から tools\\adb-setup.bat -Apply -AllowAutoSwitch で付与すると動きます）",
                    "Settings permission: none (grant it from a PC with tools\\adb-setup.bat -Apply -AllowAutoSwitch)",
                    "Autorisation de modifier les paramètres : non (accordez-la depuis un PC avec tools\\adb-setup.bat -Apply -AllowAutoSwitch)",
                )
            }
            ) + "\n" + SettingsCodec.issue(
            tr("いま自動で変えているもの", "Changed automatically now", "Modifié automatiquement maintenant"),
            Fmt.join(applied.ifEmpty { listOf(tr("なし", "none", "rien")) }),
        )
    }

    private fun confirmAutoRestore() {
        AlertDialog.Builder(this)
            .setTitle(tr("自動で変えた設定をすべて元に戻しますか？", "Restore everything changed automatically?", "Rétablir tout ce qui a été modifié automatiquement ?"))
            .setMessage(
                tr(
                    "バッテリーセーバーと Doze の設定を、自動で変える前の値に戻し、自動切り替えを OFF にします。",
                    "Battery Saver and Doze settings go back to their values from before the automatic change, and auto switching is turned off.",
                    "Les réglages de l'économiseur de batterie et de Doze reprennent leurs valeurs d'avant la modification automatique, et la bascule automatique est désactivée.",
                ),
            )
            .setPositiveButton(tr("元に戻す", "Restore", "Rétablir")) { _, _ ->
                if (prefs.monitoring) {
                    MonitorService.send(this, MonitorService.ACTION_AUTO_RESTORE)
                } else {
                    prefs.put(Defs.AUTO_SAVER, false)
                    prefs.put(Defs.AUTO_DOZE, false)
                    AutoSwitch(this, prefs).restoreAll()
                }
                boolFields[Defs.AUTO_SAVER]?.isChecked = false
                boolFields[Defs.AUTO_DOZE]?.isChecked = false
                toast(tr("元に戻しました", "Restored", "Rétabli"))
                main.postDelayed({ refreshAuto() }, 500L)
            }
            .setNegativeButton(Ui.cancel(), null)
            .show()
    }

    private fun refreshFooter() {
        refreshAuto()
        refreshPhase4()
        // 保存した設定をサービスが反映したあとの状態でも表示し直す
        main.postDelayed({ refreshPhase4() }, 800L)
        bg.execute {
            val bytes = LogStore.totalBytes(this) + PowerStore.totalBytes(this)
            val hasBefore = File(filesDir, BEFORE_FILE).exists()
            main.post {
                val size = Fmt.dec(bytes / 1024.0) + tr(" KB", " KB", " Ko")
                logSize.text = tr("現在のログ: $size", "Current logs: $size", "Journaux actuels : $size")
                undoButton.isEnabled = hasBefore
            }
        }
    }

    // ---- 保存 ----

    private fun collect(): Pair<Map<String, Any>, List<String>> {
        val values = LinkedHashMap<String, Any>()
        val errors = ArrayList<String>()
        for ((d, e) in intFields) {
            val n = e.text.toString().trim().toIntOrNull()
            if (n == null || n !in d.min..d.max) {
                val r = d.range
                errors.add(SettingsCodec.issue(d.label, tr("$r で入力してください", "enter $r", "saisissez $r")))
            } else {
                values[d.key] = n
            }
        }
        for ((d, s) in boolFields) values[d.key] = s.isChecked
        for ((d, e) in textFields) {
            // 全角の数字・記号でも受け付ける（例: １－６）
            val v = Fmt.halfWidth(e.text.toString()).trim()
            val reason = d.check(v)
            if (reason != null) errors.add(SettingsCodec.issue(d.label, reason)) else values[d.key] = v
        }
        values[Defs.PLUG_KIND.key] = selectedPlugKind()
        // 連携を ON にするなら、送り先がないと動かない
        if (values[Defs.PLUG.key] == true) {
            val kind = selectedPlugKind()
            val ok = if (kind == PlugUrls.CUSTOM) {
                (values[Defs.PLUG_ON_URL.key] as? String).orEmpty().isNotEmpty() && (values[Defs.PLUG_OFF_URL.key] as? String).orEmpty().isNotEmpty()
            } else {
                (values[Defs.PLUG_HOST.key] as? String).orEmpty().isNotEmpty()
            }
            if (!ok) {
                errors.add(
                    SettingsCodec.issue(
                        tr("スマートプラグ", "Smart plug", "Prise connectée"),
                        if (kind == PlugUrls.CUSTOM) {
                            tr("ON と OFF の URL を入れてください", "enter the ON and OFF URLs", "saisissez les URL ON et OFF")
                        } else {
                            tr("プラグのアドレスを入れてください", "enter the plug's address", "saisissez l'adresse de la prise")
                        },
                    ),
                )
            }
        }
        if (values[Defs.EXT_NOTIFY.key] == true && (values[Defs.EXT_URL.key] as? String).orEmpty().isEmpty()) {
            errors.add(SettingsCodec.issue(extTitle(), tr("通知先の URL を入れてください", "enter the destination URL", "saisissez l'URL de destination")))
        }
        return values to errors
    }

    private fun save() {
        val (values, errors) = collect()
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(tr("入力を確認してください", "Please check your input", "Vérifiez votre saisie"))
                .setMessage(errors.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val changes = SettingsCodec.describeChanges(prefs.values(), values)
        if (changes.isEmpty()) {
            toast(tr("変更はありません", "No changes", "Aucune modification"))
            return
        }
        applyValues(values, Ev.SETTINGS, changes)
        toast(tr("保存しました", "Saved", "Enregistré"))
    }

    /** 変更前の設定を残してから反映する（巻き戻し用） */
    private fun applyValues(values: Map<String, Any>, event: String, changes: List<String>) {
        writeBefore()
        prefs.apply(values)
        val n = changes.size
        LogStore.write(this, event, StateStore.state, result = tr("${n}件", "$n change(s)", "$n modification(s)"), detail = changes.joinToString(" / "))
        notifyService()
        refreshFooter()
    }

    private fun writeBefore() {
        // 端末内の巻き戻し用なので、通知先の URL なども含める
        runCatching { File(filesDir, BEFORE_FILE).writeText(prefs.toJson(includeSecrets = true).toString(2), Charsets.UTF_8) }
    }

    private fun notifyService() {
        if (prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_SETTINGS)
    }

    // ---- バックアップ・復元 ----

    private fun saveToFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "tethering-settings-${Fmt.fileStamp(System.currentTimeMillis())}.json")
        launch(i, REQ_SAVE)
    }

    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        launch(i, REQ_OPEN)
    }

    private fun launch(i: Intent, req: Int) {
        try {
            startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            toast(tr("ファイルを選ぶ画面を開けませんでした", "Couldn't open the file picker", "Impossible d'ouvrir le sélecteur de fichiers"))
        }
    }

    @Deprecated("Activity の旧 API（AndroidX を使わないため）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_SAVE -> bg.execute {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(prefs.toJson().toString(2).toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
                main.post {
                    toast(if (ok) tr("設定を保存しました", "Settings saved", "Paramètres enregistrés") else tr("保存に失敗しました", "Couldn't save", "Échec de l'enregistrement"))
                }
            }
            REQ_OPEN -> bg.execute {
                val parsed = runCatching { parseBackup(readText(uri)) }
                main.post {
                    parsed.onSuccess { confirmRestore(it, restoreFileLabel()) }
                        .onFailure { e ->
                            val m = e.message ?: e.javaClass.simpleName
                            toast(tr("読み込めませんでした：$m", "Couldn't load: $m", "Chargement impossible : $m"))
                        }
                }
            }
        }
    }

    private fun readText(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val buf = input.readBytes()
            require(buf.size <= MAX_BACKUP_BYTES) { tr("ファイルが大きすぎます", "The file is too large", "Le fichier est trop volumineux") }
            buf
        } ?: throw IllegalStateException(tr("ファイルを開けません", "Can't open the file", "Impossible d'ouvrir le fichier"))
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun parseBackup(text: String): SettingsCodec.Parsed =
        SettingsCodec.parse(Prefs.readBackup(JSONObject(text), packageName))

    private fun confirmRestore(p: SettingsCodec.Parsed, title: String) {
        val changes = SettingsCodec.describeChanges(prefs.values(), p.values)
        val msg = buildString {
            val n = changes.size
            if (changes.isEmpty()) {
                append(tr("現在の設定と同じです。", "Same as the current settings.", "Identique aux paramètres actuels."))
            } else {
                append(
                    tr("次の${n}件を変更します。", "The following $n setting(s) will change.", "Les $n paramètre(s) suivant(s) vont changer.") +
                        "\n\n" + changes.joinToString("\n"),
                )
            }
            if (p.errors.isNotEmpty()) {
                append("\n\n" + tr("採用しない値（範囲外など）:", "Values not applied (out of range, etc.):", "Valeurs non appliquées (hors limites, etc.) :"))
                append("\n" + p.errors.joinToString("\n"))
            }
            if (p.unknown.isNotEmpty()) {
                append("\n\n" + tr("不明な項目（無視）: ", "Unknown items (ignored): ", "Éléments inconnus (ignorés) : ") + p.unknown.joinToString(", "))
            }
        }
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg).setNegativeButton(Ui.cancel(), null)
        if (changes.isNotEmpty()) {
            b.setPositiveButton(tr("変更する", "Apply", "Appliquer")) { _, _ ->
                applyValues(p.values, Ev.RESTORE, changes)
                toast(tr("反映しました", "Applied", "Appliqué"))
                recreate()
            }
        }
        b.show()
    }

    private fun undo() {
        bg.execute {
            val parsed = runCatching { parseBackup(File(filesDir, BEFORE_FILE).readText(Charsets.UTF_8)) }
            main.post {
                parsed.onSuccess { confirmRestore(it, undoLabel()) }.onFailure { toast(tr("戻せる設定がありません", "Nothing to undo", "Rien à annuler")) }
            }
        }
    }

    private fun confirmReset() {
        val defaults = LinkedHashMap<String, Any>()
        defaults[Defs.MODE_KEY] = BaseMode.AUTO.key
        Defs.ints.forEach { defaults[it.key] = it.def }
        Defs.bools.forEach { defaults[it.key] = it.def }
        Defs.texts.forEach { defaults[it.key] = it.def }
        val changes = SettingsCodec.describeChanges(prefs.values(), defaults)
        if (changes.isEmpty()) {
            toast(tr("すでに初期値です", "Already at defaults", "Déjà aux valeurs par défaut"))
            return
        }
        val n = changes.size
        AlertDialog.Builder(this)
            .setTitle(resetLabel())
            .setMessage(
                tr(
                    "次の${n}件を初期値に戻します（「直前の設定に戻す」で取り消せます）。",
                    "The following $n setting(s) will go back to their defaults (you can revert with “Undo last change”).",
                    "Les $n paramètre(s) suivant(s) reprendront leur valeur par défaut (annulable avec « Annuler la dernière modification »).",
                ) + "\n\n" + changes.joinToString("\n"),
            )
            .setPositiveButton(tr("戻す", "Reset", "Rétablir")) { _, _ ->
                writeBefore()
                prefs.resetToDefaults()
                LogStore.write(
                    this, Ev.SETTINGS, StateStore.state, result = tr("初期値に戻した", "Reset to defaults", "Valeurs par défaut rétablies"),
                    detail = changes.joinToString(" / "),
                )
                notifyService()
                recreate()
            }
            .setNegativeButton(Ui.cancel(), null)
            .show()
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle(tr("ログを消去しますか？", "Clear the logs?", "Effacer les journaux ?"))
            .setMessage(
                tr(
                    "端末内の操作ログと電力の記録（CSV）をすべて削除します。日次レポートも消えます。必要なら先に CSV で書き出してください。",
                    "Deletes all event logs and power records (CSV) on the phone. Daily reports disappear too. Export them as CSV first if you need them.",
                    "Supprime tous les journaux d'événements et relevés de consommation (CSV) du téléphone. Les rapports quotidiens disparaissent aussi. Exportez-les d'abord en CSV si nécessaire.",
                ),
            )
            .setPositiveButton(tr("消去する", "Clear", "Effacer")) { _, _ ->
                bg.execute {
                    runCatching { LogStore.clear(this) }
                    runCatching { PowerStore.clear(this) }
                    main.post {
                        val text = tr("ログを消去しました", "Logs cleared", "Journaux effacés")
                        LogStore.write(this, Ev.SETTINGS, StateStore.state, result = tr("ログを消去", "Logs cleared", "Journaux effacés"))
                        toast(text)
                        refreshFooter()
                    }
                }
            }
            .setNegativeButton(Ui.cancel(), null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
