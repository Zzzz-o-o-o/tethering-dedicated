package local.tethering.dedicated

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.Toast
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors

/** 1 日分の日次レポートを記録から作る（画面とローカル管理画面で共通）。UI スレッド以外で呼ぶ */
object DailyReports {
    fun of(ctx: Context, day: String, power: List<PowerRow> = PowerStore.rowsOfDay(ctx, day), logRows: List<List<String>> = LogStore.rowsOfDay(ctx, day)): DailyReport {
        val capacity = Prefs(ctx).int(Defs.BATT_CAPACITY).takeIf { it > 0 } ?: StateStore.state.meter?.capacityMah
        val checks = logRows.filter { it[LogStore.IDX_EVENT] in Ev.checkKinds }.map {
            CheckRow(it[LogStore.IDX_ANOMALY].isEmpty(), it[LogStore.IDX_TEMP].toDoubleOrNull(), it[LogStore.IDX_RSRP].toIntOrNull())
        }
        val notified = Ev.NOTIFIED.all
        val anomalies = logRows.count {
            it[LogStore.IDX_EVENT] == Ev.CONFIRMED || (it[LogStore.IDX_EVENT] == Ev.TETHER && it[LogStore.IDX_ACTION] in notified)
        }
        return Reports.daily(day, power, checks, anomalies, capacity)
    }
}

/** 日次レポート（仕様 F-28）。記録から集計して表示する（集計のための常駐処理はない） */
class ReportActivity : Activity() {
    companion object {
        private const val REQ_DAILY = 1
        private const val REQ_SEGMENTS = 2
        private const val SHOW_DAYS = 14
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(c, tr("1 日ごとの消費・通信量・稼働状況です。", "Consumption, data use and uptime per day.", "Consommation, données et disponibilité par jour."), 13.5f)
            Ui.small(
                c,
                tr(
                    "平均消費は、バッテリー駆動中に計測できた時間の平均です（給電中は消費を測れません）。" +
                        "「満充電からの推定」は、その日の平均ペースで満充電から使い続けた場合の目安です。",
                    "Average consumption covers the time measured on battery (it can't be measured while charging). " +
                        "“Estimate from full charge” shows how long a full charge would last at that day's average rate.",
                    "La consommation moyenne porte sur le temps mesuré sur batterie (elle ne peut pas être mesurée en charge). " +
                        "« Estimation depuis une charge complète » indique combien de temps durerait une charge complète au rythme moyen de la journée.",
                ),
            )
            Ui.buttonRow(
                c,
                tr("日別 CSV 保存", "Save daily CSV", "CSV par jour") to { save(REQ_DAILY, "tethering-daily") },
                tr("区間別 CSV 保存", "Save segment CSV", "CSV par segment") to { save(REQ_SEGMENTS, "tethering-power") },
            )
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(listBox)
        Ui.small(listBox, tr("集計しています…", "Calculating…", "Calcul en cours…"))
        bg.execute {
            val reports = runCatching { build(SHOW_DAYS) }.getOrElse { emptyList() }
            main.post { render(reports) }
        }
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

    /** 記録のある日を新しい順に集計する。UI スレッド以外で呼ぶ */
    private fun build(maxDays: Int?): List<DailyReport> {
        val days = (PowerStore.days(this) + LogStore.days(this)).distinct().sortedDescending().let { if (maxDays != null) it.take(maxDays) else it }
        return days.map { DailyReports.of(this, it) }
    }

    private fun render(reports: List<DailyReport>) {
        listBox.removeAllViews()
        if (reports.isEmpty()) {
            Ui.small(listBox, tr("まだ記録がありません。", "No records yet.", "Aucun enregistrement pour l'instant."))
            return
        }
        for (r in reports) {
            val c = Ui.card(listBox)
            Ui.heading(c, "${r.day.take(4)}-${r.day.substring(4, 6)}-${r.day.substring(6, 8)}")
            Ui.kv(c, tr("電池駆動 / 給電", "On battery / charging", "Sur batterie / en charge")).text = "${hm(r.batterySec)} / ${hm(r.poweredSec)}"
            Ui.kv(c, tr("平均消費", "Average consumption", "Consommation moyenne")).text = when {
                r.avgMa == null -> tr("計測なし（バッテリー駆動の記録がない）", "Not measured (no time on battery)", "Non mesurée (aucun temps sur batterie)")
                r.avgMw != null -> "${f0(r.avgMw)} mW" + Fmt.paren("${f0(r.avgMa)} mA")
                else -> "${f0(r.avgMa)} mA"
            } + if (r.avgMa != null && r.measuredSec < r.batterySec) {
                hm(r.measuredSec).let { tr("・計測 $it", " · measured $it", " · mesuré $it") }
            } else {
                ""
            }
            Ui.kv(c, tr("満充電からの推定", "Estimate from full charge", "Estimation depuis une charge complète")).text =
                r.fullRuntimeH?.let { Fmt.duration((it * 3_600_000).toLong()) } ?: Fmt.NA
            Ui.kv(c, tr("モバイル通信量", "Mobile data", "Données mobiles")).text = r.mobileMb?.let { Fmt.mb(it) } ?: Fmt.NA
            Ui.kv(c, tr("テザリング ON", "Hotspot on", "Point d'accès activé")).text = hm(r.tetherSec)
            Ui.kv(c, tr("接続端末（最大）", "Connected devices (max)", "Appareils connectés (max)")).text =
                r.clientsMax?.let { tr("${it}台", "$it", "$it") } ?: Fmt.NA
            Ui.kv(c, tr("画面点灯", "Screen on", "Écran allumé")).text = f0(r.screenSec / 60.0).let { tr("${it}分", "$it min", "$it min") }
            Ui.kv(c, tr("チェック", "Checks", "Vérifications")).text = if (r.checks == 0) {
                "－"
            } else {
                val ok = "${r.checksOk}/${r.checks}"
                val pct = f0(r.checksOk * 100.0 / r.checks)
                val a = r.anomalies
                tr("$ok 成功（$pct%）・異常 ${a}回", "$ok OK ($pct%), $a problem(s)", "$ok OK ($pct %), $a problème(s)")
            }
            Ui.kv(c, tr("電池温度（最大）", "Battery temperature (max)", "Température batterie (max)")).text = Fmt.temp(r.tempMaxC)
            Ui.kv(c, tr("RSRP（平均）", "RSRP (average)", "RSRP (moyenne)")).text = r.rsrpAvg?.let { "${f0(it)} dBm" } ?: Fmt.NA
        }
    }

    private fun hm(sec: Long) = if (sec <= 0) tr("0分", "0 min", "0 min") else Fmt.duration(sec * 1000)

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)

    private fun save(req: Int, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "$name-${Fmt.fileStamp(System.currentTimeMillis())}.csv")
        try {
            startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, Ui.noPicker(), Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity の旧 API（AndroidX を使わないため）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        bg.execute {
            val n = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    if (requestCode == REQ_DAILY) writeDaily(out) else PowerStore.export(this, out)
                } ?: -1
            }.getOrDefault(-1)
            main.post { Toast.makeText(this, if (n >= 0) Ui.exported(n) else Ui.exportFailed(), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun writeDaily(out: OutputStream): Int {
        val reports = build(null).sortedBy { it.day }
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFF")
            w.write(Csv.line(Reports.CSV_HEADER))
            w.write("\n")
            for (r in reports) {
                w.write(Csv.line(Reports.csvFields(r)))
                w.write("\n")
            }
        }
        return reports.size
    }
}
