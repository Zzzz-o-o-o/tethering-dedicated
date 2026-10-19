package local.tethering.dedicated

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.LinearLayout
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 省電力の分析（仕様 F-30 / F-36 / F-35）。記録から集計して、省電力の判断材料を出す（集計のための常駐処理はない）。
 * 消費はバッテリー駆動中に計測できた区間だけ。アプリ別の通信量は OS の記録から読む（使用状況へのアクセスが必要）。
 */
class AnalysisActivity : Activity() {
    companion object {
        private const val DAYS = 7
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var powerBox: LinearLayout
    private lateinit var appBox: LinearLayout
    private var appDays = 1

    private class PowerData(
        val bySignal: List<PowerGroup>,
        val byBand: List<PowerGroup>,
        val slope: Double?,
        val measuredH: Double,
        val standby: Standby.Result,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(
                c,
                tr(
                    "記録から、省電力の判断材料を出します（直近 $DAYS 日）。", "Information from the records to help you save power (last $DAYS days).",
                    "Éléments tirés des enregistrements pour vous aider à économiser l'énergie ($DAYS derniers jours).",
                ),
                13.5f,
            )
            Ui.small(
                c,
                tr(
                    "消費はバッテリー駆動中に計測できた区間だけです（給電中は消費を測れません）。条件（接続端末の使い方・通信量）が違うと比べにくいので、目安として見てください。",
                    "Consumption covers only segments measured on battery (it can't be measured while charging). Conditions (use of connected devices, data use) vary, so treat these as rough guides.",
                    "La consommation ne porte que sur les segments mesurés sur batterie (elle ne peut pas être mesurée en charge). Les conditions (usage des appareils connectés, données) varient : considérez ces valeurs comme indicatives.",
                ),
            )
        }
        powerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(powerBox)
        appBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(appBox)
        Ui.small(powerBox, tr("集計しています…", "Calculating…", "Calcul en cours…"))
        bg.execute {
            val d = runCatching { loadPower() }.getOrNull()
            main.post { renderPower(d) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 使用状況へのアクセスを許可して戻ってきたときにも読み直す
        loadApps()
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

    private fun loadPower(): PowerData {
        val rows = PowerStore.days(this).take(DAYS + 1).flatMap { PowerStore.rowsOfDay(this, it) }
        val capacity = Prefs(this).int(Defs.BATT_CAPACITY).takeIf { it > 0 } ?: StateStore.state.meter?.capacityMah
        val measured = rows.filter { it.onBattery && it.mah != null }.sumOf { it.sec } / 3600.0
        return PowerData(
            SignalPower.byRsrp(rows), SignalPower.byBand(rows), SignalPower.slopeMaPerDb(rows), measured,
            Standby.analyze(rows, TimeZone.getDefault(), capacity),
        )
    }

    private fun hours(h: Double) = if (h <= 0) tr("0分", "0 min", "0 min") else Fmt.duration((h * 3_600_000).toLong())

    private fun ma(v: Double?) = v?.let { String.format(Locale.US, "%.0f mA", it) } ?: tr("未計測", "Not measured", "Non mesuré")

    private fun mb(bytes: Long) = Fmt.mb(bytes / 1024.0 / 1024.0)

    /** 整数に丸めた数値（表示の言語の書式） */
    private fun f0(v: Double) = String.format(I18n.locale, "%.0f", v)

    private fun renderPower(d: PowerData?) {
        powerBox.removeAllViews()
        if (d == null) {
            Ui.card(powerBox).also { Ui.small(it, tr("集計できませんでした", "Couldn't calculate", "Calcul impossible")) }
            return
        }
        Ui.card(powerBox).also { c ->
            Ui.heading(c, tr("電波と消費", "Signal and consumption", "Signal et consommation"))
            if (d.measuredH < 0.25) {
                Ui.small(
                    c,
                    tr(
                        "バッテリー駆動中の計測がまだありません。給電を外して使うと、電波の強さ・バンドごとの消費が分かります。",
                        "Nothing measured on battery yet. Running on battery shows consumption by signal strength and band.",
                        "Aucune mesure sur batterie pour l'instant. En fonctionnant sur batterie, vous verrez la consommation selon la force du signal et la bande.",
                    ),
                )
            } else {
                val h = hours(d.measuredH)
                Ui.small(
                    c,
                    tr(
                        "バッテリー駆動中に計測できた $h の内訳（時間・平均消費・通信量）", "Breakdown of $h measured on battery (time, average consumption, data)",
                        "Répartition des $h mesurées sur batterie (durée, consommation moyenne, données)",
                    ),
                )
                for (g in d.bySignal.filter { it.sec > 0 }) Ui.kv(c, g.label).text = line(g)
                if (d.byBand.size > 1 || d.byBand.firstOrNull()?.label != SignalPower.UNKNOWN_BAND) {
                    Ui.spacer(c, 6)
                    Ui.small(c, tr("バンド別", "By band", "Par bande"))
                    for (g in d.byBand) Ui.kv(c, g.label).text = line(g)
                }
                d.slope?.let { s ->
                    val delta = -10 * s
                    Ui.spacer(c, 4)
                    Ui.text(
                        c,
                        if (delta > 0) {
                            val x = f0(delta)
                            tr(
                                "RSRP が 10 dB 下がる（電波が悪くなる）と、平均の消費は約 $x mA 増えています（目安）。",
                                "When RSRP drops by 10 dB (weaker signal), average consumption rises by about $x mA (rough guide).",
                                "Quand le RSRP baisse de 10 dB (signal plus faible), la consommation moyenne augmente d'environ $x mA (indicatif).",
                            )
                        } else {
                            tr(
                                "この記録では、電波が悪いときに消費が増える傾向は見えません（接続端末の使い方など、ほかの条件の影響が大きい可能性があります）。",
                                "These records show no trend of higher consumption with a weaker signal (other conditions, such as the use of connected devices, may matter more).",
                                "Ces enregistrements ne montrent pas de hausse de consommation quand le signal faiblit (d'autres conditions, comme l'usage des appareils connectés, peuvent peser davantage).",
                            )
                        },
                        13.5f,
                    )
                }
            }
            Ui.button(c, tr("設置場所アシストを開く", "Open Placement assistant", "Ouvrir l'assistant d'emplacement")) {
                startActivity(Intent(this, PlacementActivity::class.java))
            }
        }

        val s = d.standby
        Ui.card(powerBox).also { c ->
            Ui.heading(c, tr("接続 0 台の待機と運用方針", "Standby with no devices, and how to run", "Veille sans appareil et mode d'utilisation"))
            if (s.recordedHours < 0.5) {
                Ui.small(
                    c,
                    tr(
                        "まだ記録が足りません（チェックの区切りごとに記録します）。", "Not enough records yet (recorded at each check).",
                        "Pas encore assez d'enregistrements (enregistré à chaque vérification).",
                    ),
                )
            } else {
                val total = hours(s.recordedHours)
                val idle = hours(s.idleHours)
                val used = hours(s.usedHours)
                val off = hours(s.offHours)
                Ui.kv(c, tr("記録", "Recorded", "Enregistré")).text = tr(
                    "$total\n接続 0 台 $idle\n接続あり $used\nテザリング OFF $off", "$total\nNo devices $idle\nDevices connected $used\nHotspot off $off",
                    "$total\nSans appareil $idle\nAvec appareils $used\nPoint d'accès arrêté $off",
                )
                val perDay = hours(s.idleHoursPerDay)
                Ui.kv(c, tr("接続 0 台の時間", "Time with no devices", "Temps sans appareil")).text =
                    tr("1 日あたり約 $perDay", "About $perDay per day", "Environ $perDay par jour") + if (s.stretches > 0) {
                        val n = s.stretches
                        val longest = hours(s.longestIdleH)
                        "\n" + tr("$n 回・最長 $longest", "$n times, longest $longest", "$n fois, la plus longue $longest")
                    } else {
                        ""
                    }
                Ui.kv(c, tr("消費（接続 0 台）", "Consumption (no devices)", "Consommation (sans appareil)")).text = ma(s.idleMa)
                Ui.kv(c, tr("消費（テザリング OFF）", "Consumption (hotspot off)", "Consommation (point d'accès arrêté)")).text = ma(s.offMa)
                Ui.kv(c, tr("消費（接続あり）", "Consumption (devices connected)", "Consommation (appareils connectés)")).text = ma(s.usedMa)
                Ui.kv(c, tr("待機のための消費", "Cost of standing by", "Coût de la veille")).text = s.dailyCostMah?.let { mah ->
                    val x = f0(mah)
                    tr("1 日あたり約 $x mAh", "About $x mAh per day", "Environ $x mAh par jour") + (
                        s.dailyCostPct?.let { p ->
                            val y = f0(p)
                            tr("（電池の約 $y%）", " (about $y% of the battery)", " (environ $y % de la batterie)")
                        } ?: ""
                        )
                } ?: "－"
            }
            Ui.spacer(c, 4)
            Ui.text(c, advice(s), 13.5f, if (s.advice == Standby.Advice.CONSIDER_OFF) R.color.status_warn else R.color.text_primary)
            Ui.button(c, tr("比較テストで待機電力を測る", "Measure standby power with a comparison test", "Mesurer la veille avec un test comparatif")) {
                startActivity(Intent(this, ExperimentActivity::class.java))
            }
        }
    }

    private fun line(g: PowerGroup): String =
        tr("${hours(g.hours)}・${ma(g.avgMa)}", "${hours(g.hours)} · ${ma(g.avgMa)}", "${hours(g.hours)} · ${ma(g.avgMa)}") +
            (g.mbPerHour?.let { Fmt.mb(it).let { x -> "\n" + tr("通信 $x/時", "Data $x/h", "Données $x/h") } } ?: "")

    private fun advice(s: Standby.Result): String = when (s.advice) {
        Standby.Advice.NEED_DATA -> tr(
            "バッテリー駆動で接続 0 台の時間が 30 分以上記録されると、待機の消費を計算できます。",
            "Standby consumption can be calculated once 30 minutes or more with no devices have been recorded on battery.",
            "La consommation en veille pourra être calculée dès que 30 minutes ou plus sans appareil auront été enregistrées sur batterie.",
        )
        Standby.Advice.NEED_OFF -> tr(
            "テザリング OFF のときの消費がまだ計測できていないので、待機のための消費（差）を出せません。比較テスト「使わないときにテザリングを止める」で測れます。",
            "Consumption with the hotspot off hasn't been measured yet, so the cost of standing by (the difference) can't be shown. The comparison test “Turn off the hotspot when unused” measures it.",
            "La consommation point d'accès arrêté n'a pas encore été mesurée : le coût de la veille (la différence) ne peut pas être calculé. Le test comparatif « Arrêter le point d'accès inutilisé » permet de la mesurer.",
        )
        Standby.Advice.KEEP_ON -> tr(
            "待機のための消費は小さいので、常時待機（テザリング ON のまま）で問題ありません。",
            "The cost of standing by is small, so keeping the hotspot on all the time is fine.",
            "Le coût de la veille est faible : garder le point d'accès toujours activé ne pose pas de problème.",
        )
        Standby.Advice.CONSIDER_OFF -> {
            val p = f0(s.dailyCostPct ?: 0.0)
            tr(
                "接続 0 台の時間に、1 日あたり電池の約 $p% を待機に使っています。長く使わない時間帯があるなら、設定の「使わない時間帯はテザリングを止める」で、" +
                    "その時間帯だけテザリングを止めて自動で再開できます。OS の「アクセスポイントを自動的に OFF」を使う場合は、再開に端末での操作が必要で、" +
                    "設定の「Wi-Fiテザリングの停止を異常とする」を OFF にしてください。",
                "While no device is connected, standing by uses about $p% of the battery per day. If there are long periods when you don't use the hotspot, " +
                    "“Turn off the hotspot during quiet hours” in Settings can turn it off during those hours and back on automatically. " +
                    "If you use the system's automatic hotspot turn-off instead, turning it back on needs action on the phone, and you should turn off " +
                    "“Treat a stopped Wi-Fi hotspot as an error” in Settings.",
                "Sans appareil connecté, la veille consomme environ $p % de la batterie par jour. S'il y a de longues périodes sans utilisation, " +
                    "« Arrêter le point d'accès pendant les heures d'inactivité » (paramètres) peut l'arrêter pendant ces heures et le réactiver automatiquement. " +
                    "Si vous utilisez plutôt la désactivation automatique du point d'accès du système, sa réactivation demande une action sur le téléphone, " +
                    "et désactivez « Considérer l'arrêt du point d'accès Wi-Fi comme une erreur » dans les paramètres.",
            )
        }
    }

    // ---- アプリ別の通信（仕様 F-35） ----

    private fun loadApps() {
        val days = appDays
        bg.execute {
            val access = AppUsageProbe.hasAccess(this)
            val now = System.currentTimeMillis()
            val sum = if (access) runCatching { AppUsageProbe.summary(this, now - days * 24 * 3_600_000L, now) }.getOrNull() else null
            main.post { renderApps(access, sum, days) }
        }
    }

    private fun renderApps(access: Boolean, sum: AppTraffic.Summary?, days: Int) {
        appBox.removeAllViews()
        val c = Ui.card(appBox)
        Ui.heading(c, tr("端末自身の通信の内訳（アプリ別）", "This phone's own traffic by app", "Trafic propre de ce téléphone par application"))
        if (!access) {
            Ui.text(
                c,
                tr(
                    "アプリ別のモバイル通信量を見るには「使用状況へのアクセス」の許可が必要です（読むだけで、ほかのアプリには何もしません）。",
                    "Seeing mobile data per app requires the “Usage access” permission (read-only; nothing is done to other apps).",
                    "Pour voir les données mobiles par application, l'autorisation « Accès aux données d'utilisation » est nécessaire (lecture seule ; rien n'est fait aux autres applications).",
                ),
                13.5f,
            )
            Ui.button(c, tr("使用状況へのアクセスを開く", "Open Usage access", "Ouvrir l'accès aux données d'utilisation")) { SystemScreens.usageAccess(this) }
            Ui.small(
                c,
                tr(
                    "PC から許可する場合（tools\\adb-setup.bat -Apply でも許可します）:", "To allow it from a PC (tools\\adb-setup.bat -Apply also does this):",
                    "Pour l'autoriser depuis un PC (tools\\adb-setup.bat -Apply le fait aussi) :",
                ),
            )
            Ui.mono(c, "adb shell appops set ${BuildConfig.APPLICATION_ID} GET_USAGE_STATS allow")
            return
        }
        Ui.buttonRow(
            c,
            tr("直近 24 時間", "Last 24 hours", "Dernières 24 h") to { appDays = 1; loadApps() },
            tr("直近 7 日", "Last 7 days", "7 derniers jours") to { appDays = 7; loadApps() },
        )
        if (sum == null) {
            Ui.small(c, tr("OS の記録を読めませんでした", "Couldn't read the system records", "Impossible de lire les enregistrements du système"))
            return
        }
        Ui.small(
            c,
            if (days == 1) {
                tr("直近 24 時間のモバイル回線の通信量", "Mobile data in the last 24 hours", "Données mobiles des dernières 24 heures")
            } else {
                tr("直近 $days 日のモバイル回線の通信量", "Mobile data in the last $days days", "Données mobiles des $days derniers jours")
            },
        )
        Ui.kv(c, tr("テザリング", "Tethering", "Partage de connexion")).text = mb(sum.tetheringBytes)
        Ui.kv(c, tr("この端末自身", "This phone itself", "Ce téléphone")).text = mb(sum.ownBytes)
        // ごく少ないもの（50 KB 未満）は並べない
        val own = sum.rows.filter { it.kind != AppTraffic.Kind.TETHERING && it.total >= 50 * 1024 }.take(15)
        if (own.isEmpty()) {
            Ui.small(c, tr("この端末自身の目立った通信はありません", "No notable traffic from this phone itself", "Aucun trafic notable de ce téléphone"))
            return
        }
        for (r in own) {
            Ui.spacer(c, 6)
            val tag = when {
                AppTraffic.isCandidate(r) -> tr("　⚠ 制限の候補", "  ⚠ restriction candidate", "  ⚠ à restreindre")
                AppTraffic.needsManual(r) -> tr("　⚠ 手動で対策", "  ⚠ fix manually", "  ⚠ à traiter manuellement")
                else -> ""
            }
            Ui.text(c, r.label + tag, 14.5f, if (tag.isNotEmpty()) R.color.status_warn else R.color.text_primary, bold = true)
            val counts = Fmt.join(
                listOfNotNull(
                    r.resumed.takeIf { it > 0 }?.let { tr("前面 $it 回", "$it in foreground", "$it au premier plan") },
                    r.services.takeIf { it > 0 }?.let { tr("常駐サービス $it 回", "$it service starts", "$it démarrages de service") },
                ),
            )
            val kind = r.kind.takeIf { it == AppTraffic.Kind.USER_APP || it == AppTraffic.Kind.SYSTEM_APP }?.let { " · ${it.label}" } ?: ""
            val t = mb(r.total)
            val b = mb(r.bgBytes)
            Ui.small(
                c,
                tr("合計 $t（裏 $b）", "Total $t (background $b)", "Total $t (arrière-plan $b)") + kind + if (counts.isNotEmpty()) " · $counts" else "",
            )
            when {
                AppTraffic.isCandidate(r) -> Ui.small(
                    c,
                    tr(
                        "裏での通信が多いユーザーアプリです。PC から tools\\adb-setup.bat -Apply -RestrictApps でまとめて制限できます。",
                        "A user app with lots of background traffic. You can restrict such apps together from a PC with tools\\adb-setup.bat -Apply -RestrictApps.",
                        "Application utilisateur avec beaucoup de trafic en arrière-plan. Vous pouvez restreindre ces applications d'un coup depuis un PC avec tools\\adb-setup.bat -Apply -RestrictApps.",
                    ),
                )
                AppTraffic.needsManual(r) -> Ui.small(
                    c,
                    tr(
                        "システムアプリのため一括制限の対象外です。自動同期や Play ストアの自動更新の OFF、アプリ情報 → モバイルデータ → バックグラウンドデータの OFF で減らせることがあります。",
                        "System apps aren't restricted in bulk. Turning off auto-sync, Play Store auto-update, or App info → Mobile data → Background data may reduce it.",
                        "Les applications système ne sont pas restreintes en bloc. Désactiver la synchronisation automatique, les mises à jour automatiques du Play Store, ou Infos de l'application → Données mobiles → Données en arrière-plan peut réduire ce trafic.",
                    ),
                )
            }
            if (r.pkg != null && (r.kind == AppTraffic.Kind.USER_APP || r.kind == AppTraffic.Kind.SYSTEM_APP)) {
                Ui.button(c, tr("アプリ情報を開く", "Open app info", "Ouvrir les infos de l'application")) { SystemScreens.open(this, SystemScreens.appDetails(r.pkg)) }
            }
        }
        Ui.spacer(c, 6)
        Ui.small(
            c,
            tr(
                "OS の通信量の記録（2 時間ごとにまとめられる）から読んだ値です。「裏」は画面に出ていないときの通信です。",
                "Values come from the system's data usage records (grouped every 2 hours). “Background” is traffic while the app isn't on screen.",
                "Valeurs issues des relevés de consommation du système (regroupés toutes les 2 heures). « Arrière-plan » désigne le trafic quand l'application n'est pas à l'écran.",
            ),
        )
    }
}
