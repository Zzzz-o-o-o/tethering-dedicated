package local.tethering.dedicated

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 診断情報（仕様 F-24）。この端末で「何が取得できて、何が取得不可か」を確認するための報告。
 * 電話番号・IMEI・SSID・MAC アドレス・IP アドレスは含めない。
 */
class DiagnosticsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        val c = Ui.card(page)
        Ui.small(
            c,
            tr(
                "対象端末での取得可否の確認（要検証の項目）や、問い合わせ・記録に使えます。個人を特定する情報は含みません。",
                "Use this to see what this phone can report, or attach it when asking for help. It contains no personally identifying information.",
                "Permet de voir ce que ce téléphone peut indiquer, ou de le joindre à une demande d'aide. Ne contient aucune information personnelle identifiante.",
            ),
        )
        Ui.buttonRow(c, tr("コピー", "Copy", "Copier") to { copy() }, tr("共有", "Share", "Partager") to { share() })
        val body: TextView = Ui.mono(c, tr("作成中…", "Preparing…", "Préparation…"))
        bg.execute {
            val text = runCatching { Diagnostics.build(this) }.getOrElse {
                val x = it.javaClass.simpleName
                tr("作成に失敗しました: $x", "Couldn't create it: $x", "Échec de la création : $x")
            }
            main.post {
                report = text
                body.text = text
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

    private fun copy() {
        if (report.isEmpty()) return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr("診断情報", "Diagnostics", "Diagnostic"), report))
        Toast.makeText(this, tr("コピーしました", "Copied", "Copié"), Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        if (report.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
        startActivity(Intent.createChooser(i, tr("診断情報を共有", "Share diagnostics", "Partager le diagnostic")))
    }
}

object Diagnostics {
    /** 「項目: 値」 */
    private fun kv(k: String, v: String): String = SettingsCodec.issue(k, v)

    private fun used() = tr("使う", "on", "activé")

    private fun unused() = tr("使わない", "off", "désactivé")

    private fun use(b: Boolean) = Fmt.onOff(b, used(), unused())

    private fun notSet() = tr("未設定", "not set", "non défini")

    private fun allowed(b: Boolean) = if (b) tr("許可", "allowed", "autorisé") else tr("未許可", "not allowed", "non autorisé")

    private fun minutes(n: Int) = tr("${n}分", "$n min", "$n min")

    private fun noneText() = tr("なし", "none", "aucune")

    fun build(ctx: Context): String {
        val s = StateStore.state
        val prefs = Prefs(ctx)
        val p = PowerProbe.read(ctx)
        val t = TetherProbe.read(ctx, countClients = true, exclude = PlugUrls.exclude(prefs))
        val l = LineProbe.read(ctx, null)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val c = s.check
        return buildString {
            appendLine(tr("# テザリング専用化 診断情報", "# Tethering Dedicated diagnostics", "# Diagnostic de Tethering Dedicated"))
            appendLine(kv(tr("作成", "Created", "Créé"), Fmt.dateTime(System.currentTimeMillis())))
            // どの APK（Android 10〜14 用）かも分かるように
            appendLine(
                kv(
                    tr("アプリ", "App", "Application"),
                    "${BuildConfig.VERSION_NAME} ${BuildConfig.FLAVOR}" + Fmt.paren("${BuildConfig.VERSION_CODE}, targetSdk ${ctx.applicationInfo.targetSdkVersion}"),
                ),
            )
            appendLine(
                kv(
                    tr("端末", "Phone", "Téléphone"),
                    "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}" + Fmt.paren("API ${Build.VERSION.SDK_INT}"),
                ),
            )
            appendLine(kv(tr("ビルド", "Build", "Build"), Build.DISPLAY))
            appendLine()
            appendLine(tr("## 権限・許可", "## Permissions", "## Autorisations"))
            appendLine(kv(tr("電話の状態", "Phone state", "État du téléphone"), granted(ctx, Manifest.permission.READ_PHONE_STATE)))
            if (AutoRevoke.applies) {
                appendLine(
                    kv(
                        tr("使わないときの権限の削除", "Remove permissions if unused", "Suppression des autorisations si inutilisée"),
                        if (AutoRevoke.exempt(ctx)) tr("OFF（外れない）", "off (kept)", "désactivée (conservées)") else tr("ON（しばらく開かないと外れる）", "on (removed if not opened for a while)", "activée (retirées sans ouverture pendant un moment)"),
                    ),
                )
            }
            appendLine(
                kv(
                    tr("電池の最適化", "Battery optimization", "Optimisation de la batterie"),
                    Fmt.onOff(pm?.isIgnoringBatteryOptimizations(ctx.packageName), tr("除外済み", "excluded", "exclue"), tr("対象", "optimized", "optimisée")),
                ),
            )
            // Android 14 では、新しく入れたアプリに正確なアラームが既定で許可されない（電池の最適化の対象外なら使える）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine(
                    kv(
                        tr("正確なアラーム", "Exact alarms", "Alarmes exactes"),
                        if (ExactAlarms.usable(ctx)) {
                            tr("使える", "available", "disponibles")
                        } else {
                            tr("使えない（定期チェックが数分遅れることがある）", "not available (scheduled checks may run a few minutes late)", "indisponibles (les vérifications planifiées peuvent avoir quelques minutes de retard)")
                        },
                    ),
                )
            }
            appendLine(
                kv(
                    tr("通知", "Notifications", "Notifications"),
                    Fmt.onOff(nm?.areNotificationsEnabled(), tr("有効", "enabled", "activées"), tr("無効", "disabled", "désactivées")),
                ),
            )
            appendLine()
            appendLine(tr("## 監視", "## Monitoring", "## Surveillance"))
            appendLine(
                listOf(
                    kv(tr("状態", "Status", "État"), if (prefs.monitoring) s.overall().label else tr("停止中", "stopped", "arrêtée")),
                    kv(tr("モード", "Mode", "Mode"), prefs.mode.label),
                    kv(tr("実効", "Effective", "Effectif"), s.effective.label),
                    kv(tr("間隔", "Interval", "Intervalle"), minutes(s.intervalMin)),
                ).joinToString(" / "),
            )
            val maint = if (prefs.maintenanceActive()) Fmt.hm(prefs.maintUntil).let { tr("〜$it", "until $it", "jusqu'à $it") } else noneText()
            appendLine(kv(tr("次回", "Next", "Prochaine"), Fmt.time(s.nextCheckAt)) + " / " + kv(tr("メンテナンス", "Maintenance", "Maintenance"), maint))
            s.upstream?.let {
                val since = Fmt.time(it.startedAt)
                val st = if (it.confirmed) {
                    tr("確定", "confirmed", "confirmé")
                } else {
                    "${it.rechecks}/${it.total}".let { n -> tr("再確認 $n", "recheck $n", "revérification $n") }
                }
                appendLine(
                    kv(tr("回線の異常", "Line problem", "Problème de ligne"), it.kind.label + Fmt.paren(tr("$since〜・$st", "since $since, $st", "depuis $since, $st"))),
                )
            }
            s.tetherIssue?.let {
                val st = if (it.confirmed) tr("確定", "confirmed", "confirmé") else tr("確認中", "checking", "vérification")
                appendLine(kv(tr("テザリングの異常", "Hotspot problem", "Problème du point d'accès"), it.kind.label + Fmt.paren(st)))
            }
            if (s.alerts.isNotEmpty()) appendLine(kv(tr("警告", "Alerts", "Alertes"), s.alerts.joinToString { it.label }))
            appendLine()
            appendLine(tr("## 取得の可否（この端末）", "## What this phone reports", "## Ce que ce téléphone indique"))
            appendLine(
                kv(tr("Wi-Fi AP 状態", "Wi-Fi AP state", "État du point d'accès Wi-Fi"), Fmt.ap(t.apState)) +
                    Fmt.paren((t.apMode?.toString() ?: "－").let { tr("モード $it", "mode $it", "mode $it") }),
            )
            appendLine(kv(tr("共有中", "Sharing", "Partage"), Fmt.tethered(t.tethered)))
            appendLine(
                kv(tr("接続端末数", "Connected devices", "Appareils connectés"), t.clients?.let { tr("${it}台", "$it", "$it") } ?: "－") +
                    Fmt.paren(kv(tr("方法", "method", "méthode"), t.clientsMethod)),
            )
            appendLine(
                kv(
                    tr("アクセスポイント自動OFF", "Hotspot auto-off", "Arrêt automatique du point d'accès") +
                        if (TetherProbe.autoOffReadable) Fmt.paren(TetherProbe.KEY_SOFT_AP_TIMEOUT) else "",
                    if (TetherProbe.autoOffReadable) Os.global(ctx, TetherProbe.KEY_SOFT_AP_TIMEOUT) ?: notSet() else TetherProbe.autoOffUnreadable(),
                ),
            )
            val lvl = Fmt.pct(p.levelPct)
            val temp = Fmt.temp(p.tempC)
            val mv = Fmt.num(p.voltageMv, " mV")
            appendLine(
                kv(
                    tr("電池", "Battery", "Batterie"),
                    tr(
                        "$lvl / 給電 ${p.plug.label} / 温度 $temp / 電圧 $mv", "$lvl / charger ${p.plug.label} / temperature $temp / voltage $mv",
                        "$lvl / alimentation ${p.plug.label} / température $temp / tension $mv",
                    ),
                ),
            )
            appendLine(kv(tr("電流", "Current", "Courant"), p.currentMa?.let { "$it mA" } ?: Fmt.NA))
            appendLine(kv(tr("熱状態", "Thermal status", "État thermique"), Fmt.thermal(p.thermalStatus)))
            appendLine(kv(tr("SoC温度", "SoC temperature", "Température SoC"), p.socTempC?.let { Fmt.temp(it) + Fmt.paren(p.socZone.orEmpty()) } ?: Fmt.NA))
            appendLine(
                listOf(
                    kv(tr("データセーバー", "Data Saver", "Économiseur de données"), Fmt.onOff(p.dataSaver)),
                    kv(tr("バッテリーセーバー", "Battery Saver", "Économiseur de batterie"), Fmt.onOff(p.powerSave)),
                    kv("Doze", Fmt.onOff(p.deviceIdle)),
                ).joinToString(" / "),
            )
            appendLine(
                listOf(
                    kv("SIM", Fmt.sim(l.simState)),
                    kv(tr("事業者", "Carrier", "Opérateur"), l.simOperator ?: Fmt.NA),
                    kv(tr("接続先", "Network", "Réseau"), l.networkOperator ?: Fmt.NA),
                ).joinToString(" / "),
            )
            appendLine(
                listOf(
                    kv(tr("音声", "Voice", "Voix"), Fmt.voice(l.voiceState)),
                    kv(tr("データ網", "Data network", "Réseau de données"), Fmt.rat(l.dataRat)),
                    kv(tr("データ", "Data", "Données"), Fmt.dataState(l.dataState)),
                    kv(tr("モバイルデータ", "Mobile data", "Données mobiles"), Fmt.onOff(l.dataEnabled)),
                ).joinToString(" / "),
            )
            appendLine(
                kv(tr("電波", "Signal", "Signal"), l.signalType ?: Fmt.NA) +
                    " / RSRP ${Fmt.num(l.rsrp)} / RSRQ ${Fmt.num(l.rsrq)} / SINR ${Fmt.db(l.sinr)} / " + tr("レベル", "level", "niveau") + " ${Fmt.num(l.level)}",
            )
            appendLine(
                listOf(
                    kv("EARFCN", Fmt.num(l.earfcn)),
                    kv(tr("バンド", "Band", "Bande"), l.band ?: Fmt.NA),
                    kv(tr("帯域幅", "Bandwidth", "Largeur de bande"), Fmt.bandwidths(l.bandwidthsMhz)),
                ).joinToString(" / "),
            )
            appendLine(
                kv(tr("機内モード", "Airplane mode", "Mode avion"), Fmt.onOff(l.airplane)) + " / " +
                    kv(tr("ローミング", "Roaming", "Itinérance"), Fmt.onOff(l.roaming)),
            )
            appendLine()
            appendLine(tr("## 消費電力の計測", "## Power measurement", "## Mesure de la consommation"))
            val m = s.meter
            val usable = Fmt.onOff(m?.counterSupported, tr("使えた", "yes", "oui"), tr("使えない", "no", "non"))
            appendLine(
                kv(tr("充電カウンタ", "Charge counter", "Compteur de charge"), p.counterUah?.let { "$it µAh" } ?: tr("未対応", "not supported", "non pris en charge")) +
                    Fmt.paren(kv(tr("区間の計算で使えたか", "usable for segments", "utilisable pour les segments"), usable)),
            )
            val setting = prefs.int(Defs.BATT_CAPACITY).takeIf { it > 0 }?.let { "$it mAh" } ?: tr("自動", "auto", "auto")
            appendLine(
                kv(tr("電池容量", "Battery capacity", "Capacité de la batterie"), m?.capacityMah?.let { "$it mAh" } ?: Fmt.NA) +
                    Fmt.paren(tr("設定 $setting", "setting $setting", "réglage $setting")),
            )
            appendLine(
                kv(
                    tr("直近の消費", "Recent consumption", "Consommation récente"),
                    (m?.rateMa?.let { "%.0f mA".format(it) } ?: "－") + (m?.rateMw?.let { " / %.0f mW".format(it) } ?: ""),
                ) + Fmt.paren(m?.method?.label ?: "－"),
            )
            appendLine(kv(tr("推定残り時間", "Estimated time left", "Autonomie estimée"), m?.remainingMin?.let { Fmt.duration(it * 60_000L) } ?: "－"))
            // 接続端末を数え損ねたときの補い（LanTraffic）に使う値
            appendLine(
                kv(
                    tr(
                        "テザリングの通信（いまの区間・モバイル回線以外の送受信）", "Hotspot traffic (current segment, non-mobile send/receive)",
                        "Trafic du point d'accès (segment actuel, émission/réception hors mobile)",
                    ),
                    m?.lanBytes?.let { Fmt.mb(it / 1048576.0) } ?: Fmt.NA,
                ),
            )
            s.experiment?.let {
                val st = "${it.condition} ${it.measuredSec / 60}/${it.phaseSec / 60}"
                val wait = if (it.waitingSwitch) Fmt.paren(tr("切り替え待ち", "waiting for switch", "en attente du changement")) else ""
                appendLine(
                    kv(tr("比較テスト", "Comparison test", "Test comparatif"), it.title) + " / " +
                        tr("条件 ${st}分", "condition $st min", "condition $st min") + wait,
                )
            }
            appendLine()
            appendLine(tr("## 設置場所・通信量・管理画面", "## Placement, traffic, web page", "## Emplacement, trafic, page web"))
            val base = PlaceStore.baseline(prefs)
            val ref = base?.let { "${it.name} RSRP ${Fmt.num(it.rsrp)}" } ?: noneText()
            val n = PlaceStore.list(prefs).size
            appendLine(
                kv(tr("電波の点数", "Signal score", "Score du signal"), SignalQuality.score(l.rsrp ?: l.dbm, l.sinr)?.let { Fmt.points(it) } ?: Fmt.NA) + " / " +
                    kv(tr("設置場所の記録", "Placement records", "Emplacements enregistrés"), tr("${n}件", "$n", "$n")) +
                    Fmt.paren(kv(tr("基準", "reference", "référence"), ref)),
            )
            val idleMb = prefs.int(Defs.IDLE_TRAFFIC_MB)
            val quietLabel = QuietHours.parse(prefs.text(Defs.QUIET_HOURS))?.label ?: notSet()
            val quietMb = prefs.int(Defs.QUIET_TRAFFIC_MB)
            appendLine(
                kv(
                    tr("待機中の通信の通知", "Idle traffic alerts", "Alertes de trafic au repos"),
                    tr(
                        "接続端末なし $idleMb MB/時 / 使わない時間帯 $quietLabel（$quietMb MB）",
                        "no devices $idleMb MB/h / quiet hours $quietLabel ($quietMb MB)",
                        "sans appareil $idleMb Mo/h / heures d'inactivité $quietLabel ($quietMb Mo)",
                    ),
                ),
            )
            // 管理画面の URL はこの端末のアクセスポイントのアドレス（接続端末の情報ではない）
            appendLine(kv(tr("管理画面", "Local web page", "Page web locale"), if (!prefs.bool(Defs.WEB)) "OFF" else s.web?.let { it.url ?: it.message } ?: "－"))
            appendLine()
            appendLine(tr("## 自動化（第 3 段階）", "## Automation (phase 3)", "## Automatisation (phase 3)"))
            val pct = Fmt.pct(prefs.int(Defs.AUTO_SAVER_PCT))
            appendLine(
                kv(
                    tr("自動切り替え", "Auto switching", "Bascule automatique"),
                    tr("バッテリーセーバー", "Battery Saver", "économiseur de batterie") + " " + use(prefs.bool(Defs.AUTO_SAVER)) +
                        Fmt.paren(tr("残量 $pct 以下", "at or below $pct", "à $pct ou moins")) + " / " +
                        tr("Doze を早める", "earlier Doze", "Doze avancé") + " " + use(prefs.bool(Defs.AUTO_DOZE)) +
                        if (AutoSwitch.dozeWorks) "" else Fmt.paren(tr("Android 12 以降は使えない", "not available on Android 12+", "indisponible sur Android 12+")),
                ),
            )
            appendLine(
                kv(
                    tr("設定変更の権限", "Settings permission", "Autorisation de modifier les paramètres"),
                    if (AutoSwitch.permitted(ctx)) tr("付与済み", "granted", "accordée") else noneText(),
                ) + " / " + kv(
                    tr("自動で変えている", "changed automatically", "modifié automatiquement"),
                    tr("バッテリーセーバー", "Battery Saver", "économiseur") + " ${Fmt.onOff(prefs.autoSaverApplied)}" + tr("・", ", ", ", ") +
                        "Doze ${Fmt.onOff(prefs.autoDozeApplied)}",
                ),
            )
            appendLine(
                "global low_power = ${Os.global(ctx, AutoSwitch.KEY_SAVER) ?: notSet()} / device_idle_constants = ${Os.global(ctx, AutoSwitch.KEY_DOZE) ?: notSet()}",
            )
            val idle = s.idleReason ?: tr("していない", "no", "non")
            val idleSet = "${minutes(prefs.int(Defs.IDLE_INTERVAL))}" + tr("・残量 ", ", battery ", ", batterie ") + Fmt.pct(prefs.int(Defs.IDLE_BATT))
            val skipped = if (s.check?.probeSkipped == true) Fmt.paren(tr("直近のチェックで省略", "skipped at the last check", "ignoré à la dernière vérification")) else ""
            appendLine(
                kv(tr("待機", "Idle", "Veille"), idle) + Fmt.paren(tr("設定 $idleSet", "setting $idleSet", "réglage $idleSet")) + " / " +
                    kv(tr("到達確認の省略", "skip reachability check", "ignorer le test d'accès"), use(prefs.bool(Defs.SKIP_PROBE))) + skipped,
            )
            appendLine(
                kv(tr("使用状況へのアクセス", "Usage access", "Accès aux données d'utilisation"), allowed(AppUsageProbe.hasAccess(ctx))) + " / " +
                    kv(
                        tr("優先ネットワーク", "preferred network", "réseau préféré"),
                        Os.preferredNetworkMode(ctx)?.let { NetworkModes.label(it) + Fmt.paren("$it") } ?: Fmt.NA,
                    ),
            )
            appendLine()
            appendLine(tr("## 第 4 段階", "## Phase 4", "## Phase 4"))
            // テザリングは止めずに、切り替えられるかだけを確かめる
            appendLine(
                kv(
                    tr("テザリングの切り替え", "Hotspot switching", "Bascule du point d'accès"),
                    tr("システム設定の変更", "Modify system settings", "Modifier les paramètres système") + " " + allowed(TetherControl.permitted(ctx)) +
                        " / " + tr("確認", "check", "test") + " " + TetherControl.probe(ctx).text,
                ),
            )
            // 起動時に読むのは、ロック解除前でも読める写しのほう
            appendLine(
                kv(tr("起動時のテザリング ON", "Hotspot on at startup", "Point d'accès activé au démarrage"), use(prefs.bool(Defs.TETHER_ON_BOOT))) +
                    Fmt.paren(kv(tr("起動時に読む写し", "copy read at startup", "copie lue au démarrage"), use(BootTether.enabledCopy(ctx)))) + " / " +
                    kv(tr("前回の起動時", "last startup", "dernier démarrage"), BootTether.lastResult(ctx) ?: Fmt.NA),
            )
            val capDay = prefs.int(Defs.DATA_CAP_DAY)
            val capStopped = if (prefs.capStoppedCycle != 0L) tr("はい", "yes", "oui") else tr("いいえ", "no", "non")
            appendLine(
                kv(tr("月の通信量の上限", "Monthly data cap", "Plafond de données mensuel"), use(prefs.bool(Defs.DATA_CAP))) +
                    Fmt.paren(Fmt.gb(prefs.int(Defs.DATA_CAP_GB) * DataCap.GB) + tr("・毎月${capDay}日から", ", from day $capDay", ", à partir du $capDay")) + " / " +
                    kv(tr("今月", "this month", "ce mois-ci"), s.cap?.usedBytes?.let { Fmt.gb(it) } ?: Fmt.NA) + " / " +
                    kv(tr("止める", "turn off", "arrêter"), use(prefs.bool(Defs.DATA_CAP_STOP))) + " / " +
                    kv(tr("上限で止めている", "turned off by the cap", "arrêté par le plafond"), capStopped) +
                    (BootTether.blockedUntil(ctx)?.let { " / " + kv(tr("起動時の ON をしない期限", "no startup ON until", "pas d'activation au démarrage avant"), Fmt.dateTime(it).take(10)) } ?: ""),
            )
            val quiet = QuietHours.parse(prefs.text(Defs.QUIET_HOURS))
            val stopped = if (prefs.schedStoppedWindow != 0L) {
                val since = Fmt.time(prefs.schedStoppedAt)
                tr("はい（${since}から）", "yes (since $since)", "oui (depuis $since)")
            } else {
                tr("いいえ", "no", "non")
            }
            appendLine(
                kv(
                    tr("時間帯スケジュール", "Quiet-hours schedule", "Planification"),
                    use(prefs.bool(Defs.TETHER_SCHEDULE)) + Fmt.paren(quiet?.label ?: tr("時間帯の未設定", "no quiet hours", "heures non définies")),
                ) + " / " + kv(tr("止めている", "turned off", "arrêté"), stopped) + (s.sched?.note?.let { " / $it" } ?: ""),
            )
            appendLine(
                kv(
                    tr("ハードウェアアクセラレーション", "Hardware acceleration", "Accélération matérielle"),
                    "global tether_offload_disabled = " + (Os.global(ctx, "tether_offload_disabled") ?: tr("未設定（既定は有効）", "not set (enabled by default)", "non défini (activée par défaut)")),
                ) + Fmt.paren(
                    tr(
                        "端末が対応しているかはアプリから読めません。PC の adb-setup で確認できます",
                        "the app can't read whether the phone supports it; check with adb-setup on a PC",
                        "l'application ne peut pas savoir si le téléphone la prend en charge ; vérifiez avec adb-setup sur un PC",
                    ),
                ),
            )
            val dest = ExtNotify.parse(prefs.text(Defs.EXT_URL))
            val pending = s.ext?.pending ?: 0
            appendLine(
                kv(tr("外部への通知", "External notifications", "Notifications externes"), use(prefs.bool(Defs.EXT_NOTIFY))) + " / " +
                    tr("送り先", "destination", "destination") + " " + (dest?.label ?: notSet()) + " / " +
                    tr("未送信 ${pending}件", "unsent $pending", "non envoyées $pending") +
                    (s.ext?.lastResult?.let { " / " + tr("直近 $it", "last $it", "dernier $it") } ?: ""),
            )
            val kind = prefs.text(Defs.PLUG_KIND)
            val excluded = PlugUrls.ipv4(kind, prefs.text(Defs.PLUG_HOST), prefs.text(Defs.PLUG_ON_URL)) != null
            val low = prefs.int(Defs.PLUG_LOW)
            val high = prefs.int(Defs.PLUG_HIGH)
            val hot = "${prefs.int(Defs.PLUG_HOT)}" + tr("℃", "°C", " °C")
            val flip = minutes(prefs.int(Defs.PLUG_FLIPBACK))
            val ex = if (excluded && prefs.bool(Defs.PLUG)) tr("あり", "yes", "oui") else tr("なし", "no", "non")
            appendLine(
                kv(tr("スマートプラグ", "Smart plug", "Prise connectée"), use(prefs.bool(Defs.PLUG))) + " / " + PlugUrls.label(kind) + " / " +
                    tr(
                        "範囲 $low〜$high% / 高温 $hot / 自動で ON に戻す $flip / 接続端末の数から除く: $ex",
                        "range $low–$high% / hot $hot / auto back ON $flip / excluded from device count: $ex",
                        "plage $low–$high % / chaleur $hot / rallumage auto $flip / exclue du nombre d'appareils : $ex",
                    ),
            )
            s.plug?.let {
                val target = it.want?.let { w -> if (w) "ON" else "OFF" } ?: tr("未定", "none", "aucune")
                val why = it.why?.let { w -> Fmt.paren(w) } ?: ""
                val last = it.lastResult ?: "－"
                val stuck = if (it.stuck) " / " + tr("給電の状態が合わない", "charging doesn't match", "la charge ne correspond pas") else ""
                appendLine(
                    kv(
                        tr("スマートプラグの状態", "Smart plug state", "État de la prise"),
                        tr("目標 $target$why / 直近 $last", "target $target$why / last $last", "cible $target$why / dernier $last") + stuck,
                    ),
                )
            }
            appendLine()
            appendLine(tr("## 直近の接続性チェック", "## Last connectivity check", "## Dernière vérification de connectivité"))
            if (c == null) {
                appendLine(tr("まだ実行していません", "Not run yet", "Pas encore effectuée"))
            } else {
                appendLine(Fmt.dateTime(c.at) + Fmt.paren(c.trigger.label))
                val http = "${c.httpCode ?: "－"}" + tr("・", ", ", ", ") + "${c.httpMs ?: "－"} ms"
                appendLine(
                    tr("回線登録", "Registration", "Enregistrement") + " ${c.registered.mark} / IP ${c.ip.mark} / DNS ${c.dns.mark} / HTTP ${c.http.mark}" +
                        Fmt.paren(http) + " / " + tr("システム検証", "system validation", "validation système") + " ${c.validated.mark}",
                )
                appendLine(kv(tr("判定", "Diagnosis", "Diagnostic"), c.diagnosis))
                if (c.detail.isNotEmpty()) appendLine(kv(tr("詳細", "Details", "Détails"), c.detail))
            }
            appendLine()
            appendLine(tr("## OS の設定値（読み取りのみ）", "## System settings (read-only)", "## Paramètres du système (lecture seule)"))
            for (
                key in listOf(
                    "wifi_scan_always_enabled", "ble_scan_always_enabled", "stay_on_while_plugged_in", "ota_disable_automatic_update",
                    "mobile_data_always_on", "tether_offload_disabled", Settings.Global.AIRPLANE_MODE_ON,
                )
            ) {
                appendLine("global $key = ${Os.global(ctx, key) ?: notSet()}")
            }
            for (
                key in listOf(
                    "doze_enabled", "doze_always_on", "doze_pulse_on_pick_up", "doze_pulse_on_double_tap", "doze_tap_gesture",
                    "wake_gesture_enabled", "double_tap_to_wake", "location_mode",
                )
            ) {
                appendLine("secure $key = ${Os.secure(ctx, key) ?: notSet()}")
            }
            appendLine("system screen_off_timeout = ${Os.system(ctx, Settings.System.SCREEN_OFF_TIMEOUT) ?: notSet()}")
            appendLine()
            appendLine(tr("## アプリの設定", "## App settings", "## Paramètres de l'application"))
            // 通知先・プラグの URL はトークン・パスワードを含みうるので出さない
            val secret = Defs.secretKeys
            prefs.values().forEach { (k, v) ->
                appendLine(
                    "$k = " + if (k in secret) {
                        if ((v as? String).isNullOrEmpty()) tr("（空欄）", "(empty)", "(vide)") else tr("（設定あり・非表示）", "(set, hidden)", "(défini, masqué)")
                    } else {
                        v
                    },
                )
            }
            appendLine()
            appendLine(tr("## 最新のログ（末尾）", "## Latest log (last lines)", "## Dernier journal (fin)"))
            LogStore.tail(ctx, 15).forEach { appendLine(it) }
        }
    }

    private fun granted(ctx: Context, perm: String) = allowed(ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED)
}
