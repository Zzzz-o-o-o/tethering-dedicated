package local.tethering.dedicated

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** 運用ダッシュボード（仕様 F-01）。取得できない値は「取得不可」と表示する */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_OPEN_TETHER = "open_tether"
        private const val REQ_PHONE = 1
        private const val REQ_EXPORT = 2
        private const val REQ_NOTIFY = 3
    }

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var live: Live? = null
    /** 表示中の「要確認」の中身（null = 次の表示で必ず描き直す） */
    private var bannerKey: String? = null
    private var recentKey = ""

    /** 画面表示中だけ 5 秒ごとに読む値（定期チェックとは別。通信はしない） */
    private class Live(val power: PowerInfo, val tether: TetherInfo, val line: LineInfo)

    private lateinit var chip: TextView
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var banner: LinearLayout

    private lateinit var vAp: TextView
    private lateinit var vShared: TextView
    private lateinit var vClients: TextView
    private lateinit var vAutoOff: TextView
    private lateinit var vSched: TextView
    private lateinit var vWeb: TextView

    private lateinit var vSim: TextView
    private lateinit var vOperator: TextView
    private lateinit var vData: TextView
    private lateinit var vVoice: TextView
    private lateinit var vSignal: TextView
    private lateinit var vQuality: TextView
    private lateinit var vBand: TextView
    private lateinit var vBandwidth: TextView
    private lateinit var vRoaming: TextView
    private lateinit var vMonth: TextView

    private lateinit var vReg: TextView
    private lateinit var vIp: TextView
    private lateinit var vDns: TextView
    private lateinit var vHttp: TextView
    private lateinit var vValidated: TextView
    private lateinit var vDiag: TextView

    private lateinit var vBattery: TextView
    private lateinit var vPlug: TextView
    private lateinit var vSmartPlug: TextView
    private lateinit var vTemp: TextView
    private lateinit var vThermal: TextView
    private lateinit var vSoc: TextView
    private lateinit var vCurrent: TextView
    private lateinit var vSaver: TextView
    private lateinit var vIdle: TextView
    private lateinit var vOpt: TextView
    private lateinit var vConsumption: TextView
    private lateinit var vRemaining: TextView

    private lateinit var vProfile: TextView
    private lateinit var vInterval: TextView
    private lateinit var vNext: TextView
    private lateinit var vAuto: TextView
    private lateinit var modeGroup: RadioGroup
    private val modeIds = HashMap<BaseMode, Int>()
    private lateinit var vMaint: TextView
    private lateinit var maintButton: Button
    private lateinit var recentBox: LinearLayout
    private lateinit var monitorButton: Button

    private val listener: (StateStore.State) -> Unit = { render() }
    private val ticker = Runnable { tick() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        build()
        if (prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_START)
        if (!openSetupOnce()) askPhonePermissionOnce()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        StateStore.listen(listener)
        tick()
    }

    override fun onPause() {
        resumed = false
        StateStore.unlisten(listener)
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_TETHER, false) == true) {
            i.removeExtra(EXTRA_OPEN_TETHER)
            SystemScreens.tether(this)
        }
    }

    private fun tick() {
        main.removeCallbacks(ticker)
        if (!resumed) return
        bg.execute {
            val l = runCatching { Live(PowerProbe.read(this), TetherProbe.read(this, countClients = false), LineProbe.read(this, null)) }.getOrNull()
            main.post {
                if (l != null) live = l
                render()
            }
        }
        main.postDelayed(ticker, 5_000L)
    }

    // ---- 画面の組み立て ----

    private fun build() {
        val page = Ui.page(this)

        Ui.card(page).also { c ->
            chip = Ui.chip(c, Overall.UNKNOWN.label, R.color.status_off)
            headline = Ui.text(c, "", 16f, bold = true)
            subline = Ui.small(c, "")
            Ui.button(c, tr("今すぐチェック", "Check now", "Vérifier maintenant")) { checkNow() }
        }

        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(banner)

        Ui.card(page).also { c ->
            Ui.heading(c, tr("テザリング", "Tethering", "Partage de connexion"))
            vAp = Ui.kv(c, tr("Wi-Fiテザリング", "Wi-Fi hotspot", "Point d'accès Wi-Fi"))
            vShared = Ui.kv(c, tr("共有中", "Sharing", "Partage"))
            vClients = Ui.kv(c, tr("接続端末", "Connected devices", "Appareils connectés"))
            vAutoOff = Ui.kv(c, tr("自動OFF設定", "Auto turn-off", "Arrêt automatique"))
            vSched = Ui.kv(c, tr("時間帯スケジュール", "Quiet-hours schedule", "Planification"))
            vWeb = Ui.kv(c, tr("管理画面（接続端末から）", "Local web page", "Page web locale"))
            Ui.buttonRow(
                c,
                tr("テザリングの詳細", "Hotspot details", "Détails") to { startActivity(Intent(this, TetherDetailsActivity::class.java)) },
                tr("テザリング設定を開く", "Open hotspot settings", "Paramètres du point d'accès") to { SystemScreens.tether(this) },
            )
        }

        Ui.card(page).also { c ->
            Ui.heading(c, tr("モバイル回線", "Mobile line", "Ligne mobile"))
            vSim = Ui.kv(c, "SIM")
            vOperator = Ui.kv(c, tr("接続先", "Network", "Réseau"))
            vData = Ui.kv(c, tr("データ", "Data", "Données"))
            vVoice = Ui.kv(c, tr("音声", "Voice", "Voix"))
            vSignal = Ui.kv(c, tr("電波", "Signal", "Signal"))
            vQuality = Ui.kv(c, tr("品質", "Quality", "Qualité"))
            vBand = Ui.kv(c, tr("バンド", "Band", "Bande"))
            vBandwidth = Ui.kv(c, tr("帯域幅", "Bandwidth", "Largeur de bande"))
            vRoaming = Ui.kv(c, tr("ローミング・機内モード", "Roaming, airplane mode", "Itinérance, mode avion"))
            vMonth = Ui.kv(c, tr("今月の通信量（上限）", "This month's data (cap)", "Données du mois (plafond)"))
            Ui.buttonRow(
                c,
                tr("設置場所アシスト", "Placement assistant", "Assistant d'emplacement") to { startActivity(Intent(this, PlacementActivity::class.java)) },
                tr("APN設定を開く", "Open APN settings", "Paramètres APN") to { SystemScreens.apn(this) },
            )
        }

        Ui.card(page).also { c ->
            Ui.heading(c, tr("接続性（段階ごとの判定）", "Connectivity (step by step)", "Connectivité (étape par étape)"))
            vReg = Ui.kv(c, tr("① 回線登録", "① Registration", "① Enregistrement"))
            vIp = Ui.kv(c, tr("② IP通信", "② IP connection", "② Connexion IP"))
            vDns = Ui.kv(c, "③ DNS")
            vHttp = Ui.kv(c, tr("④ インターネット", "④ Internet", "④ Internet"))
            vValidated = Ui.kv(c, tr("システムの接続検証", "System validation", "Validation du système"))
            vDiag = Ui.small(c, "")
        }

        Ui.card(page).also { c ->
            Ui.heading(c, tr("電源・温度", "Power and temperature", "Alimentation et température"))
            vConsumption = Ui.kv(c, tr("消費（直近）", "Consumption (recent)", "Consommation (récente)"))
            vRemaining = Ui.kv(c, tr("推定残り時間", "Estimated time left", "Autonomie estimée"))
            vBattery = Ui.kv(c, tr("電池", "Battery", "Batterie"))
            vPlug = Ui.kv(c, tr("給電", "Charger", "Alimentation"))
            vSmartPlug = Ui.kv(c, tr("スマートプラグ", "Smart plug", "Prise connectée"))
            vTemp = Ui.kv(c, tr("電池温度", "Battery temperature", "Température batterie"))
            vThermal = Ui.kv(c, tr("熱状態", "Thermal status", "État thermique"))
            vSoc = Ui.kv(c, tr("SoC温度（参考）", "SoC temperature (reference)", "Température SoC (indicative)"))
            vCurrent = Ui.kv(c, tr("電流（参考）", "Current (reference)", "Courant (indicatif)"))
            vSaver = Ui.kv(c, tr("バッテリーセーバー", "Battery Saver", "Économiseur de batterie"))
            vIdle = Ui.kv(c, tr("Doze（省電力待機）", "Doze (deep sleep)", "Doze (veille profonde)"))
            vOpt = Ui.kv(c, tr("電池の最適化", "Battery optimization", "Optimisation de la batterie"))
        }

        Ui.card(page).also { c ->
            Ui.heading(c, tr("運用プロファイル", "Operating profile", "Profil de fonctionnement"))
            vProfile = Ui.kv(c, tr("現在", "Current", "Actuel"))
            vInterval = Ui.kv(c, tr("チェック間隔", "Check interval", "Intervalle de vérification"))
            vNext = Ui.kv(c, tr("次回チェック", "Next check", "Prochaine vérification"))
            vAuto = Ui.kv(c, tr("自動切り替え", "Auto switching", "Bascule automatique"))
            modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val labels = mapOf(
                BaseMode.AUTO to tr(
                    "自動（給電中は通常・バッテリー駆動中は省電力）", "Auto (Normal while charging, Power saving on battery)",
                    "Auto (Normal en charge, Économie d'énergie sur batterie)",
                ),
                BaseMode.NORMAL to tr("通常（常に）", "Normal (always)", "Normal (toujours)"),
                BaseMode.SAVER to tr("省電力（常に）", "Power saving (always)", "Économie d'énergie (toujours)"),
            )
            for ((m, label) in labels) {
                val rb = RadioButton(this).apply { text = label }
                modeGroup.addView(rb)
                modeIds[m] = rb.id
            }
            modeGroup.check(modeIds.getValue(prefs.mode))
            modeGroup.setOnCheckedChangeListener { _, id -> onModeChanged(id) }
            c.addView(modeGroup)
            Ui.spacer(c, 6)
            vMaint = Ui.small(c, "")
            maintButton = Ui.button(c, maintStart()) { toggleMaintenance() }
        }

        Ui.card(page).also { c ->
            Ui.heading(c, tr("直近の記録", "Recent events", "Événements récents"))
            recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(recentBox)
        }

        Ui.card(page).also { c ->
            Ui.heading(c, tr("操作", "Actions", "Actions"))
            Ui.button(c, tr("はじめの設定", "Initial setup", "Configuration initiale")) { startActivity(Intent(this, SetupActivity::class.java)) }
            Ui.buttonRow(
                c,
                tr("比較テスト", "Comparison test", "Test comparatif") to { startActivity(Intent(this, ExperimentActivity::class.java)) },
                tr("日次レポート", "Daily report", "Rapport quotidien") to { startActivity(Intent(this, ReportActivity::class.java)) },
            )
            Ui.buttonRow(
                c,
                tr("通信量（時間帯別）", "Traffic by hour", "Trafic par heure") to { startActivity(Intent(this, TrafficActivity::class.java)) },
                tr("接続端末の設定", "Client device guide", "Guide des appareils") to { startActivity(Intent(this, ClientGuideActivity::class.java)) },
            )
            Ui.button(c, tr("省電力の分析", "Power analysis", "Analyse de la consommation")) { startActivity(Intent(this, AnalysisActivity::class.java)) }
            Ui.button(c, tr("ログをCSVで保存", "Save log as CSV", "Enregistrer le journal en CSV")) { exportLog() }
            Ui.buttonRow(
                c,
                tr("設定", "Settings", "Paramètres") to { startActivity(Intent(this, SettingsActivity::class.java)) },
                tr("設定ガイド", "Setup guide", "Guide de configuration") to { startActivity(Intent(this, GuideActivity::class.java)) },
            )
            Ui.button(c, tr("診断情報", "Diagnostics", "Diagnostic")) { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
            monitorButton = Ui.button(c, monitorStop()) { toggleMonitoring() }
            Ui.small(
                c,
                tr(
                    "このアプリがテザリングを切り替えるのは、設定で「起動時に Wi-Fiテザリングを ON にする」・時間帯スケジュール・「上限を超えたらテザリングを止める」を ON にしたときと、「はじめの設定」の「今すぐ ON にする」を押したときだけです。それ以外はOS標準のテザリング機能を変更せず、状態の確認・記録・通知だけを行います。",
                    "This app switches the hotspot only if you turn on “Turn on the Wi-Fi hotspot at startup”, the quiet-hours schedule or “Turn off the hotspot when the cap is exceeded” in Settings, or tap “Turn on now” in Initial setup. Otherwise it never changes the system's hotspot; it only checks, records and notifies.",
                    "Cette application ne commande le point d'accès que si vous activez « Activer le point d'accès Wi-Fi au démarrage », la planification ou « Arrêter le point d'accès quand le plafond est dépassé » dans les paramètres, ou si vous touchez « Activer maintenant » dans la configuration initiale. Sinon, elle ne modifie jamais le point d'accès du système : elle vérifie, enregistre et notifie seulement.",
                ),
            )
        }
    }

    private fun maintStart() = tr("メンテナンスモードを開始", "Start maintenance mode", "Démarrer le mode maintenance")

    private fun monitorStop() = tr("監視を停止", "Stop monitoring", "Arrêter la surveillance")

    private fun monitorStart() = tr("監視を開始", "Start monitoring", "Démarrer la surveillance")

    private fun openSettings() = tr("設定を開く", "Open settings", "Ouvrir les paramètres")

    private fun allow() = tr("許可する", "Allow", "Autoriser")

    private fun notUsed() = tr("使わない", "Not used", "Désactivé")

    /** 月の通信量の上限の表示（例: 12.3 GB / 50.0 GB（24%）） */
    private fun capText(c: StateStore.CapView): String {
        val used = c.usedBytes
        val main = when {
            !c.enabled -> notUsed()
            !c.readable -> Fmt.NA + Fmt.paren(tr("「使用状況へのアクセス」が必要", "needs “Usage access”", "nécessite « Accès aux données d'utilisation »"))
            used == null -> tr("読み取り中…", "Reading…", "Lecture…")
            else -> {
                val pct = if (c.limitBytes > 0) used * 100 / c.limitBytes else 0
                "${Fmt.gb(used)} / ${Fmt.gb(c.limitBytes)}" + Fmt.paren(Fmt.pct(pct))
            }
        }
        return main + if (c.stopped) " · " + tr("テザリング停止中", "hotspot off", "point d'accès arrêté") else ""
    }

    // ---- 表示の更新 ----

    private fun render() {
        val s = StateStore.state
        val lv = live
        val p = lv?.power ?: s.power
        val t = lv?.tether ?: s.tether
        val l = lv?.line ?: s.line
        val c = s.check
        val monitoring = prefs.monitoring
        val o = if (!monitoring) Overall.STOPPED else s.overall()

        chip.text = o.label
        Ui.setChipColor(chip, Ui.statusColor(o))
        val up = s.upstream
        headline.text = when {
            !monitoring -> tr("監視は停止しています", "Monitoring is stopped", "La surveillance est arrêtée")
            o == Overall.MAINT -> Fmt.hm(s.maintUntil).let {
                tr(
                    "メンテナンス中（〜$it）：自動の再確認と通知を止めています", "Maintenance (until $it): automatic rechecks and alerts are paused",
                    "Maintenance (jusqu'à $it) : revérifications et alertes automatiques suspendues",
                )
            }
            up != null && up.confirmed -> {
                val k = up.kind.label
                val since = Fmt.time(up.startedAt)
                tr("異常：$k（${since}から）", "Error: $k (since $since)", "Erreur : $k (depuis $since)")
            }
            up != null -> {
                val k = up.kind.label
                val n = "${up.rechecks}/${up.total}"
                tr("確認中：$k（再確認 $n）", "Checking: $k (recheck $n)", "Vérification : $k (revérification $n)")
            }
            s.tetherIssue != null -> s.tetherIssue.kind.label
            c != null -> c.diagnosis
            else -> tr("最初のチェックを実行しています…", "Running the first check…", "Première vérification en cours…")
        }
        subline.text = listOfNotNull(
            c?.let {
                val at = Fmt.time(it.at)
                val why = it.trigger.label
                tr("最終チェック $at（$why）", "Last check $at ($why)", "Dernière vérification $at ($why)")
            },
            if (monitoring && s.nextCheckAt > 0) Fmt.hm(s.nextCheckAt).let { tr("次回 $it", "Next $it", "Prochaine $it") } else null,
            if (s.checking) tr("実行中…", "Running…", "En cours…") else null,
        ).joinToString(" · ")

        renderBanner(p, t, l, monitoring)

        // テザリング（接続端末数は定期チェックのときだけ数える）
        vAp.text = when {
            t == null -> Fmt.NA
            t.apState != null -> Fmt.ap(t.apState) + if (t.apMode == 2) Fmt.paren(tr("ローカル専用", "local only", "local uniquement")) else ""
            else -> Fmt.onOff(t.wifiOn)
        }
        vShared.text = Fmt.tethered(t?.tethered)
        val counted = s.tether
        vClients.text = when {
            counted?.clients != null -> {
                val n = counted.clients
                val how = counted.clientsMethod
                tr("${n}台（${how}で推定）", "$n (estimated via $how)", "$n (estimé via $how)")
            }
            counted != null -> counted.clientsMethod
            else -> Fmt.NA
        }
        vAutoOff.text = when (t?.autoOff) {
            false -> "OFF ✓"
            true -> tr("ON（接続がないと停止）⚠", "ON (stops when nothing is connected) ⚠", "ON (s'arrête sans appareil connecté) ⚠")
            null -> if (TetherProbe.autoOffReadable) tr("未設定（AOSPの既定はON）", "Not set (AOSP default: ON)", "Non défini (AOSP par défaut : ON)") else TetherProbe.autoOffUnreadable()
        }
        vSched.text = s.sched?.let { sc ->
            when {
                sc.stoppedUntil != null -> sc.stoppedUntil.let { tr("停止中（$it に再開）", "Off (back on at $it)", "Arrêté (réactivation à $it)") }
                !sc.permitted -> tr("許可がないため動きません ⚠", "Not working: permission missing ⚠", "Inactive : autorisation manquante ⚠")
                sc.quiet == null -> tr("時間帯が未設定です ⚠", "Quiet hours not set ⚠", "Heures d'inactivité non définies ⚠")
                else -> {
                    val q = sc.quiet
                    tr("$q は止める（接続 0 台が 10 分続いたら）", "Off during $q (after 10 min with no devices)", "Arrêt pendant $q (après 10 min sans appareil)") +
                        (sc.note?.let { "\n$it" } ?: "")
                }
            }
        } ?: notUsed()
        vWeb.text = when {
            !prefs.bool(Defs.WEB) -> tr("OFF（設定）", "Off (Settings)", "Désactivée (paramètres)")
            s.web == null -> "－"
            else -> s.web.url ?: s.web.message
        }

        // モバイル回線
        vSim.text = l?.let { Fmt.sim(it.simState) + (it.simOperator?.let { op -> " · $op" } ?: "") } ?: Fmt.NA
        vOperator.text = l?.networkOperator ?: Fmt.NA
        vData.text = l?.let {
            buildString {
                append(Fmt.rat(it.dataRat)).append(" · ").append(Fmt.dataState(it.dataState))
                if (it.dataEnabled == false) append(" · ").append(tr("モバイルデータOFF", "Mobile data off", "Données mobiles désactivées"))
                if (!it.phonePermission) append(Fmt.paren(tr("権限なし", "no permission", "sans autorisation")))
            }
        } ?: Fmt.NA
        vVoice.text = l?.let {
            Fmt.voice(it.voiceState) + if (it.voiceState != LineInfo.STATE_IN_SERVICE && it.dataRegistered == true) {
                Fmt.paren(tr("データ専用SIMでは通常", "normal for a data-only SIM", "normal pour une SIM données uniquement"))
            } else {
                ""
            }
        } ?: Fmt.NA
        vSignal.text = l?.let {
            val strength = it.rsrp?.let { v -> "RSRP $v dBm" } ?: it.dbm?.let { v -> "$v dBm" } ?: Fmt.NA
            val bars = it.level?.let { lv2 -> " " + "■".repeat(lv2) + "□".repeat(4 - lv2) } ?: ""
            (it.signalType?.let { type -> "$type · " } ?: "") + strength + bars
        } ?: Fmt.NA
        vQuality.text = l?.let { "RSRQ ${Fmt.num(it.rsrq, " dB")} · SINR ${Fmt.db(it.sinr)}" } ?: Fmt.NA
        vBand.text = l?.let { line -> line.band?.let { b -> b + (line.earfcn?.let { e -> " · EARFCN $e" } ?: "") } ?: Fmt.NA } ?: Fmt.NA
        vBandwidth.text = Fmt.bandwidths(l?.bandwidthsMhz)
        vRoaming.text = l?.let {
            Fmt.onOff(it.roaming, tr("ローミング中", "Roaming", "En itinérance"), tr("なし", "No roaming", "Pas d'itinérance")) + " · " +
                tr("機内モード", "Airplane mode", "Mode avion") + " " + Fmt.onOff(it.airplane)
        } ?: Fmt.NA
        vMonth.text = s.cap?.let { c -> capText(c) } ?: notUsed()

        // 接続性
        vReg.text = c?.let { tri(it.registered) } ?: "－"
        vIp.text = c?.let {
            tri(it.ip) + when (it.route) {
                null -> Fmt.paren(tr("ネットワークなし", "no network", "aucun réseau"))
                ConnectivityProbe.ROUTE_CELLULAR -> ""
                else -> ConnectivityProbe.routeLabel(it.route).let { r -> Fmt.paren(tr("${r}経由", "via $r", "via $r")) }
            }
        } ?: "－"
        vDns.text = c?.let { tri(it.dns) } ?: "－"
        vHttp.text = c?.let {
            when (it.http) {
                Tri.OK -> "✓" + Fmt.paren("${it.httpCode}" + tr("・", ", ", ", ") + "${it.httpMs} ms")
                Tri.NG -> "✗" + (it.httpCode?.let { code -> Fmt.paren("HTTP $code") } ?: "")
                Tri.SKIP -> "－" + Fmt.paren(
                    when {
                        it.probeSkipped -> tr("通信が流れているため省略", "skipped: devices are using data", "ignoré : des appareils utilisent des données")
                        prefs.bool(Defs.HTTP) -> tr("前の段階で停止", "stopped at an earlier step", "arrêté à une étape précédente")
                        else -> tr("確認しない設定", "turned off in Settings", "désactivé dans les paramètres")
                    },
                )
                Tri.UNKNOWN -> Fmt.NA
            }
        } ?: "－"
        vValidated.text = c?.let { tri(it.validated) } ?: "－"
        vDiag.text = c?.let { it.diagnosis + if (it.detail.isNotEmpty()) "\n${it.detail}" else "" } ?: ""

        // 電源・温度（消費はバッテリー駆動中だけ計測し、データが足りない間は推測で出さない）
        val m = s.meter
        val onBattery = p?.plug == Plug.NONE
        vConsumption.text = when {
            m == null -> "－"
            !onBattery -> tr("給電中は計測しません", "Not measured while charging", "Non mesurée en charge")
            m.rateMa == null -> tr(
                "計測中（バッテリー駆動で約10分後から表示）", "Measuring (shown about 10 min after switching to battery)",
                "Mesure en cours (affichée environ 10 min après le passage sur batterie)",
            )
            else -> (m.rateMw?.let { "${it.toInt()} mW" + Fmt.paren("${m.rateMa.toInt()} mA") } ?: "${m.rateMa.toInt()} mA") +
                (m.method?.let { tr("・${it.label}", " · ${it.label}", " · ${it.label}") } ?: "")
        }
        vRemaining.text = when {
            m == null || !onBattery -> "－"
            m.rateMa == null -> tr("計測中", "Measuring", "Mesure en cours")
            m.remainingMin == null -> tr(
                "計算できません（電池容量が不明。設定で指定できます）", "Can't calculate (battery capacity unknown; you can set it in Settings)",
                "Calcul impossible (capacité de la batterie inconnue ; vous pouvez la définir dans les paramètres)",
            )
            else -> Fmt.approx(m.remainingMin * 60_000L)
        }
        vBattery.text = p?.let { Fmt.pct(it.levelPct) + if (it.charging == true) tr("・充電中", " · charging", " · en charge") else "" } ?: Fmt.NA
        vPlug.text = p?.plug?.label ?: Fmt.NA
        vSmartPlug.text = s.plug?.let { sp ->
            val w = sp.why.orEmpty()
            val target = when (sp.want) {
                true -> tr("給電 ON（$w）", "Charging ON ($w)", "Charge ON ($w)")
                false -> tr("給電 OFF（$w）", "Charging OFF ($w)", "Charge OFF ($w)")
                null -> tr("まだ切り替えていません", "Not switched yet", "Pas encore commutée")
            }
            target + if (sp.stuck) {
                "\n⚠ " + tr("給電の状態が目標と合いません", "Charging doesn't match the target", "La charge ne correspond pas à la cible")
            } else {
                "\n" + tr("範囲 ${sp.range}", "Range ${sp.range}", "Plage ${sp.range}")
            }
        } ?: notUsed()
        vTemp.text = Fmt.temp(p?.tempC)
        vThermal.text = Fmt.thermal(p?.thermalStatus)
        vSoc.text = p?.let { pw -> pw.socTempC?.let { Fmt.temp(it) + (pw.socZone?.let { z -> Fmt.paren(z) } ?: "") } } ?: Fmt.NA
        vCurrent.text = p?.currentMa?.let { "$it mA" } ?: Fmt.NA
        vSaver.text = Fmt.onOff(p?.powerSave)
        vIdle.text = Fmt.onOff(p?.deviceIdle, tr("待機中", "Sleeping", "En veille"), tr("なし", "No", "Non"))
        vOpt.text = Fmt.onOff(p?.batteryOptExempt, tr("除外済み ✓", "Excluded ✓", "Exclue ✓"), tr("対象 ⚠", "Optimized ⚠", "Optimisée ⚠"))

        // プロファイル
        vProfile.text = s.effective.label + Fmt.paren(s.mode.label.let { tr("モード: $it", "mode: $it", "mode : $it") })
        val n = s.intervalMin
        vInterval.text = when {
            s.idleReason != null -> s.idleReason.let { tr("${n}分（待機：$it）", "$n min (idle: $it)", "$n min (veille : $it)") }
            s.base == Effective.SAVER -> tr("${n}分（省電力）", "$n min (power saving)", "$n min (économie d'énergie)")
            else -> tr("${n}分（通常）", "$n min (normal)", "$n min (normal)")
        }
        vNext.text = if (monitoring && s.nextCheckAt > 0) Fmt.time(s.nextCheckAt) else "－"
        vAuto.text = s.auto?.let { a ->
            when {
                !a.enabled -> notUsed()
                !a.permitted -> tr("権限がありません（ADB で付与）", "No permission (grant it via ADB)", "Pas d'autorisation (à accorder via ADB)")
                a.saverOn || a.dozeOn -> Fmt.join(
                    listOfNotNull(
                        tr("バッテリーセーバー ON", "Battery Saver on", "Économiseur de batterie activé").takeIf { a.saverOn },
                        tr("Doze を早めている", "Doze earlier", "Doze avancé").takeIf { a.dozeOn },
                    ),
                ) + Fmt.paren(tr("自動", "auto", "auto"))
                else -> tr("待機中（バッテリー駆動で切り替え）", "Standing by (switches on battery)", "En attente (bascule sur batterie)")
            }
        } ?: "－"
        // 選択の正はサービスの状態ではなく保存値（サービスの反映待ちの間に選択が戻らないように）
        modeIds[prefs.mode]?.let { id -> if (modeGroup.checkedRadioButtonId != id) modeGroup.check(id) }
        val maint = s.maintUntil > System.currentTimeMillis()
        vMaint.text = if (maint) {
            Fmt.hm(s.maintUntil).let {
                tr(
                    "メンテナンス中：$it まで自動の再確認・通知を止めています", "Maintenance: automatic rechecks and alerts are paused until $it",
                    "Maintenance : revérifications et alertes automatiques suspendues jusqu'à $it",
                )
            }
        } else {
            tr(
                "設定変更や更新作業の間は、メンテナンスモードで誤検知の通知を止められます",
                "While you change settings or update apps, maintenance mode prevents false alerts",
                "Pendant que vous modifiez des réglages ou mettez à jour des applications, le mode maintenance évite les fausses alertes",
            )
        }
        maintButton.text = if (maint) tr("メンテナンスモードを終了", "End maintenance mode", "Terminer le mode maintenance") else maintStart()
        maintButton.isEnabled = monitoring

        renderRecent(s.recent)
        monitorButton.text = if (monitoring) monitorStop() else monitorStart()
    }

    private fun tri(t: Tri) = when (t) {
        Tri.OK -> "✓"
        Tri.NG -> "✗"
        Tri.SKIP -> "－"
        Tri.UNKNOWN -> Fmt.NA
    }

    /** 利用者の操作が必要な注意点だけを上にまとめる */
    private fun renderBanner(p: PowerInfo?, t: TetherInfo?, l: LineInfo?, monitoring: Boolean) {
        val items = ArrayList<Triple<String, String, () -> Unit>>()
        if (!monitoring) items.add(Triple(tr("監視を停止中です。", "Monitoring is stopped.", "La surveillance est arrêtée."), monitorStart()) { toggleMonitoring() })
        if (l != null && !l.phonePermission) {
            items.add(
                Triple(
                    tr(
                        "「電話」の権限がないため、データ網の種類・回線登録の一部が取得できません（発信・通話はしません）。",
                        "Without the “Phone” permission, the network type and part of the registration state can't be read (the app never makes calls).",
                        "Sans l'autorisation « Téléphone », le type de réseau et une partie de l'état d'enregistrement ne peuvent pas être lus (l'application ne passe jamais d'appel).",
                    ),
                    allow(),
                ) { requestPhonePermission() },
            )
        }
        // 通知が出せないと、異常を知らせられない（Android 13 からは通知に許可が要る）
        if (!NotifyPermission.enabled(this)) {
            items.add(
                Triple(
                    tr(
                        "通知が OFF です。テザリングの停止などの異常を知らせられません。",
                        "Notifications are off. The app can't tell you about problems such as the hotspot stopping.",
                        "Les notifications sont désactivées. L'application ne peut pas vous prévenir des problèmes, comme l'arrêt du point d'accès.",
                    ),
                    allow(),
                ) { requestNotifications() },
            )
        }
        if (p?.batteryOptExempt == false) {
            items.add(
                Triple(
                    tr(
                        "電池の最適化の対象です。画面消灯中にチェックが遅れることがあります。",
                        "The app is battery-optimized. Checks may be delayed while the screen is off.",
                        "L'application est soumise à l'optimisation de la batterie. Les vérifications peuvent être retardées écran éteint.",
                    ),
                    tr("除外を依頼", "Request exclusion", "Demander l'exclusion"),
                ) { SystemScreens.requestIgnoreBatteryOptimizations(this) },
            )
        }
        if (p?.dataSaver == true) {
            items.add(
                Triple(
                    tr(
                        "データセーバーがONです。ONのあいだテザリングは使えません。", "Data Saver is on. The hotspot can't be used while it's on.",
                        "L'économiseur de données est activé. Le point d'accès est inutilisable tant qu'il est activé.",
                    ),
                    openSettings(),
                ) { SystemScreens.dataUsage(this) },
            )
        }
        if (t?.autoOff == true) {
            items.add(
                Triple(
                    tr(
                        "アクセスポイントの自動OFFが有効です。接続がないとテザリングが止まります。",
                        "Hotspot auto turn-off is on. The hotspot stops when nothing is connected.",
                        "La désactivation automatique du point d'accès est activée. Il s'arrête quand aucun appareil n'est connecté.",
                    ),
                    openSettings(),
                ) { SystemScreens.hotspot(this) },
            )
        }
        val auto = StateStore.state.auto
        if (auto != null && auto.enabled && !auto.permitted) {
            items.add(
                Triple(
                    tr(
                        "自動切り替えが ON ですが、設定変更の権限がないため動きません（PC から ADB で付与してください）。",
                        "Auto switching is on but can't work without the settings permission (grant it from a PC via ADB).",
                        "La bascule automatique est activée mais ne peut pas fonctionner sans l'autorisation de modifier les paramètres (accordez-la depuis un PC via ADB).",
                    ),
                    tr("設定ガイドを開く", "Open Setup guide", "Ouvrir le guide de configuration"),
                ) { startActivity(Intent(this, GuideActivity::class.java)) },
            )
        }
        if (DeviceAlert.SIGNAL_WEAK in StateStore.state.alerts) {
            items.add(
                Triple(
                    tr(
                        "電波が悪化しています。置き場所を見直すと消費と発熱が減ります。", "The signal has degraded. A better spot reduces power use and heat.",
                        "Le signal s'est dégradé. Un meilleur emplacement réduit la consommation et la chaleur.",
                    ),
                    tr("設置場所アシスト", "Placement assistant", "Assistant d'emplacement"),
                ) { startActivity(Intent(this, PlacementActivity::class.java)) },
            )
        }
        val sched = StateStore.state.sched
        if (sched != null && sched.enabled && !sched.permitted) {
            items.add(
                Triple(
                    tr(
                        "時間帯スケジュールが ON ですが、「システム設定の変更」が許可されていないため、テザリングを止められません。",
                        "The quiet-hours schedule is on, but “Modify system settings” isn't allowed, so the hotspot can't be turned off.",
                        "La planification est activée, mais « Modifier les paramètres système » n'est pas autorisé : le point d'accès ne peut pas être arrêté.",
                    ),
                    allow(),
                ) { SystemScreens.writeSettings(this) },
            )
        }
        if (prefs.bool(Defs.TETHER_ON_BOOT) && !TetherControl.permitted(this)) {
            items.add(
                Triple(
                    tr(
                        "「起動時に Wi-Fiテザリングを ON にする」が ON ですが、「システム設定の変更」が許可されていないため、起動時に ON にできません。",
                        "“Turn on the Wi-Fi hotspot at startup” is on, but “Modify system settings” isn't allowed, so the hotspot can't be turned on at startup.",
                        "« Activer le point d'accès Wi-Fi au démarrage » est activé, mais « Modifier les paramètres système » n'est pas autorisé : le point d'accès ne peut pas être activé au démarrage.",
                    ),
                    allow(),
                ) { SystemScreens.writeSettings(this) },
            )
        }
        val cap = StateStore.state.cap
        if (cap != null && cap.enabled && !cap.readable) {
            items.add(
                Triple(
                    tr(
                        "月の通信量の上限が ON ですが、「使用状況へのアクセス」がないため通信量を読めません。",
                        "The monthly data cap is on, but data use can't be read without “Usage access”.",
                        "Le plafond de données mensuel est activé, mais la consommation ne peut pas être lue sans « Accès aux données d'utilisation ».",
                    ),
                    allow(),
                ) { SystemScreens.usageAccess(this) },
            )
        }
        if (prefs.bool(Defs.DATA_CAP) && prefs.bool(Defs.DATA_CAP_STOP) && !TetherControl.permitted(this)) {
            items.add(
                Triple(
                    tr(
                        "「上限を超えたらテザリングを止める」が ON ですが、「システム設定の変更」が許可されていないため止められません。",
                        "“Turn off the hotspot when the cap is exceeded” is on, but “Modify system settings” isn't allowed, so it can't be turned off.",
                        "« Arrêter le point d'accès quand le plafond est dépassé » est activé, mais « Modifier les paramètres système » n'est pas autorisé : il ne peut pas être arrêté.",
                    ),
                    allow(),
                ) { SystemScreens.writeSettings(this) },
            )
        }
        if (StateStore.state.plug?.stuck == true) {
            items.add(
                Triple(
                    tr(
                        "スマートプラグで給電を切り替えられません。プラグの電源・ネットワーク・ケーブルを確認してください。",
                        "Can't switch charging with the smart plug. Check the plug's power, its network and the cable.",
                        "Impossible de commuter la charge avec la prise connectée. Vérifiez l'alimentation de la prise, son réseau et le câble.",
                    ),
                    openSettings(),
                ) { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
        }
        val key = items.joinToString("|") { it.first }
        if (key == bannerKey) return
        bannerKey = key
        banner.removeAllViews()
        if (items.isEmpty()) return
        val c = Ui.card(banner)
        Ui.heading(c, tr("要確認", "Action needed", "Action requise"))
        for ((msg, label, action) in items) {
            Ui.text(c, "⚠ $msg", 13.5f, R.color.status_warn)
            Ui.button(c, label, action)
            Ui.spacer(c, 4)
        }
    }

    private fun renderRecent(list: List<RecentEvent>) {
        val shown = list.take(10)
        val key = shown.joinToString("|") { "${it.at}${it.kind}" }
        if (key == recentKey) return
        recentKey = key
        recentBox.removeAllViews()
        if (shown.isEmpty()) {
            Ui.small(recentBox, tr("まだ記録はありません", "No events yet", "Aucun événement pour l'instant"))
            return
        }
        for (e in shown) {
            val kind = Ev.display(e.kind)
            Ui.text(recentBox, "${Fmt.time(e.at)}  $kind" + if (e.text.isNotEmpty()) tr("：", ": ", " : ") + e.text else "", 13f)
        }
    }

    // ---- 操作 ----

    private fun checkNow() {
        if (!prefs.monitoring) {
            toast(tr("監視を開始すると実行できます", "Start monitoring first", "Démarrez d'abord la surveillance"))
            return
        }
        MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
        toast(tr("チェックを開始しました", "Check started", "Vérification lancée"))
    }

    private fun onModeChanged(id: Int) {
        val m = modeIds.entries.firstOrNull { it.value == id }?.key ?: return
        if (m == prefs.mode) return
        val old = prefs.mode
        prefs.mode = m
        LogStore.write(this, Ev.SETTINGS, StateStore.state, result = SettingsCodec.issue(Defs.modeLabel, "${old.label} → ${m.label}"))
        if (prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_SETTINGS)
    }

    private fun toggleMaintenance() {
        if (StateStore.state.maintUntil > System.currentTimeMillis()) {
            MonitorService.send(this, MonitorService.ACTION_MAINT, 0L)
            return
        }
        val labels = arrayOf(
            tr("30分", "30 min", "30 min"), tr("1時間", "1 hour", "1 heure"), tr("2時間", "2 hours", "2 heures"), tr("4時間", "4 hours", "4 heures"),
        )
        val minutes = intArrayOf(30, 60, 120, 240)
        AlertDialog.Builder(this)
            .setTitle(
                tr(
                    "メンテナンスモード（自動の再確認・通知を一時停止）", "Maintenance mode (pause automatic rechecks and alerts)",
                    "Mode maintenance (suspendre les revérifications et alertes automatiques)",
                ),
            )
            .setItems(labels) { _, which ->
                MonitorService.send(this, MonitorService.ACTION_MAINT, System.currentTimeMillis() + minutes[which] * 60_000L)
            }
            .setNegativeButton(Ui.cancel(), null)
            .show()
    }

    private fun toggleMonitoring() {
        if (prefs.monitoring) {
            AlertDialog.Builder(this)
                .setTitle(tr("監視を停止しますか？", "Stop monitoring?", "Arrêter la surveillance ?"))
                .setMessage(
                    tr(
                        "定期チェック・異常の通知・再起動後の自動開始が止まります。OSのテザリング機能には影響しません。",
                        "Scheduled checks, alerts and the automatic start after a reboot will stop. The system's hotspot is not affected.",
                        "Les vérifications planifiées, les alertes et le démarrage automatique après un redémarrage s'arrêteront. Le point d'accès du système n'est pas affecté.",
                    ),
                )
                .setPositiveButton(tr("停止する", "Stop", "Arrêter")) { _, _ ->
                    MonitorService.send(this, MonitorService.ACTION_STOP)
                    main.postDelayed({ render() }, 500L)
                }
                .setNegativeButton(Ui.cancel(), null)
                .show()
        } else {
            prefs.monitoring = true
            MonitorService.send(this, MonitorService.ACTION_START)
            render()
        }
    }

    private fun exportLog() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "tethering-log-${Fmt.fileStamp(System.currentTimeMillis())}.csv")
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (_: ActivityNotFoundException) {
            toast(Ui.noPicker())
        }
    }

    @Deprecated("Activity の旧 API（AndroidX を使わないため）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        bg.execute {
            val n = runCatching { contentResolver.openOutputStream(uri)?.use { LogStore.export(this, it) } ?: -1 }.getOrDefault(-1)
            main.post {
                if (n >= 0) {
                    LogStore.write(this, Ev.EXPORT, StateStore.state, result = tr("${n}行", "$n rows", "$n lignes"))
                    toast(Ui.exported(n))
                } else {
                    toast(Ui.exportFailed())
                }
            }
        }
    }

    /**
     * 新しく入れたときの初回だけ、はじめの設定を開く（「電話」の権限の説明もそこで行う）。
     * v0.7.0 までから更新した場合（「電話」の説明を済ませている）は開かない。開いたら true
     */
    private fun openSetupOnce(): Boolean {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (ui.getBoolean(SetupActivity.KEY_SHOWN, false)) return false
        val upgraded = ui.getBoolean("asked_phone", false)
        ui.edit().putBoolean(SetupActivity.KEY_SHOWN, true).putBoolean("asked_phone", true).apply()
        if (upgraded) return false
        startActivity(Intent(this, SetupActivity::class.java))
        return true
    }

    /** 初回だけ、OS の権限ダイアログの前に用途を説明する（OS の文言は「電話の発信と管理」で誤解されやすい） */
    private fun askPhonePermissionOnce() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (ui.getBoolean("asked_phone", false)) return
        ui.edit().putBoolean("asked_phone", true).apply()
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle(tr("「電話」の権限について", "About the “Phone” permission", "À propos de l'autorisation « Téléphone »"))
            .setMessage(
                tr(
                    "データ網の種類（LTE など）と回線の登録状態を読むために使います。\n\n" +
                        "次の画面で「電話の発信と管理」と表示されますが、このアプリは発信・通話・SMS を一切行いません。" +
                        "許可しなくても動作し、該当する項目は「取得不可」になります。",
                    "It's used to read the network type (LTE etc.) and the registration state of the line.\n\n" +
                        "The next screen says “make and manage phone calls”, but this app never makes calls or sends SMS. " +
                        "The app also works without it; the related items then show “N/A”.",
                    "Elle sert à lire le type de réseau (LTE, etc.) et l'état d'enregistrement de la ligne.\n\n" +
                        "L'écran suivant indique « passer et gérer des appels téléphoniques », mais cette application ne passe jamais d'appel et n'envoie jamais de SMS. " +
                        "Elle fonctionne aussi sans ; les éléments concernés affichent alors « Indisponible ».",
                ),
            )
            .setPositiveButton(tr("次へ", "Next", "Suivant")) { _, _ -> requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE) }
            .setNegativeButton(tr("あとで", "Later", "Plus tard"), null)
            .show()
    }

    private fun requestPhonePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE) ||
            !getSharedPreferences("ui", MODE_PRIVATE).getBoolean("phone_denied", false)
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
        } else {
            // 「今後表示しない」が選ばれた後はダイアログが出ないので、アプリ情報の権限画面を開く
            toast(tr("「権限」から「電話」を許可してください", "Allow “Phone” under “Permissions”", "Autorisez « Téléphone » dans « Autorisations »"))
            SystemScreens.open(this, SystemScreens.appDetails(packageName))
        }
    }

    /**
     * 通知の許可を求める（Android 13 用の版）。ダイアログを出せないとき（Android 12 以前・「今後表示しない」の後・
     * アプリの通知の設定で OFF にしたとき）は、アプリの通知の設定を開く
     */
    private fun requestNotifications() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val canAsk = NotifyPermission.requestable(this) && checkSelfPermission(NotifyPermission.PERMISSION) != PackageManager.PERMISSION_GRANTED &&
            (shouldShowRequestPermissionRationale(NotifyPermission.PERMISSION) || !ui.getBoolean("notify_asked", false))
        if (canAsk) {
            ui.edit().putBoolean("notify_asked", true).apply()
            requestPermissions(arrayOf(NotifyPermission.PERMISSION), REQ_NOTIFY)
        } else {
            SystemScreens.appNotifications(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PHONE && requestCode != REQ_NOTIFY) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_PHONE) getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("phone_denied", !granted).apply()
        // 監視に反映する（回線の情報を読み直し、通知を許可したなら常駐通知も出し直す）
        if (granted && prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
        // 空の文字列にすると、許可して注意点がなくなったときに「なし」と同じになり、前の表示が残ってしまう
        bannerKey = null
        tick()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
