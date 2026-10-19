package local.tethering.dedicated

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import java.io.OutputStream
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 通信量（時間帯別）の画面（仕様 F-33）。電力の記録（区間ごとのモバイル通信量・接続端末数）から集計する。
 * 使っていないはずの時間の通信（接続端末なし＝この端末自身、使わない時間帯＝接続端末の自動更新など）を見つけるためのもの。
 */
class TrafficActivity : Activity() {
    companion object {
        private const val DAYS = 7
        private const val REQ_CSV = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var box: LinearLayout
    private var days: List<TrafficMath.Day> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(
                c,
                tr(
                    "モバイル回線の通信量を時間帯別に集計します。使っていないはずの時間に流れている通信を見つけるためのものです。",
                    "Mobile data use by hour, to find traffic at times when nothing should be using the hotspot.",
                    "Consommation de données mobiles par heure, pour repérer le trafic aux heures où rien ne devrait utiliser le point d'accès.",
                ),
                13.5f,
            )
            Ui.small(
                c,
                tr(
                    "橙色は「接続端末なし」（テザリング OFF、またはチェック時点で接続 0 台）の通信で、この端末自身のアプリ・OS によるものです。" +
                        "通信量はチェックなどで区切った区間ごとの値を時間で按分した目安です。",
                    "Orange is traffic with “no devices” (hotspot off, or no devices at check time), which comes from this phone's own apps and system. " +
                        "Amounts are estimates: each segment between checks is spread over its hours.",
                    "L'orange correspond au trafic « sans appareil » (point d'accès arrêté, ou aucun appareil lors de la vérification), qui provient des applications et du système de ce téléphone. " +
                        "Les quantités sont des estimations : chaque segment entre deux vérifications est réparti sur ses heures.",
                ),
            )
            Ui.buttonRow(
                c,
                tr("接続端末の設定", "Client device guide", "Guide des appareils") to { startActivity(Intent(this, ClientGuideActivity::class.java)) },
                tr("時間帯別 CSV 保存", "Save hourly CSV", "CSV par heure") to { saveCsv() },
            )
        }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        Ui.small(box, tr("集計しています…", "Calculating…", "Calcul en cours…"))
        bg.execute {
            val result = runCatching { load() }.getOrElse { emptyList() }
            main.post {
                days = result
                render()
            }
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

    /** 直近 [DAYS] 日分（新しい順）。日をまたぐ区間を正しく分けるため、翌日のファイルの区間も渡す */
    private fun load(): List<TrafficMath.Day> {
        val tz = TimeZone.getDefault()
        val names = PowerStore.days(this).take(DAYS + 1)
        val rows = names.flatMap { PowerStore.rowsOfDay(this, it) }
        return names.take(DAYS).map { TrafficMath.day(rows, it, tz) }
    }

    private fun mb(kb: Double) = Fmt.mb(kb / 1024)

    /** 日付の短い表示（日本語・英語は 月/日、フランス語は 日/月） */
    private fun label(day: String): String {
        val m = day.substring(4, 6).trimStart('0')
        val d = day.substring(6, 8).trimStart('0')
        return tr("$m/$d", "$m/$d", "$d/$m")
    }

    private fun render() {
        box.removeAllViews()
        if (days.isEmpty() || days.all { it.kb <= 0 }) {
            Ui.card(box).also {
                Ui.small(
                    it,
                    tr(
                        "まだ記録がありません（チェックの区切りごとに記録します）。", "No records yet (recorded at each check).",
                        "Aucun enregistrement pour l'instant (enregistré à chaque vérification).",
                    ),
                )
            }
            return
        }
        val quiet = QuietHours.parse(Prefs(this).text(Defs.QUIET_HOURS))
        val today = days.first()
        Ui.card(box).also { c ->
            val d = label(today.day)
            Ui.heading(c, tr("$d の時間帯別", "$d by hour", "$d par heure"))
            val total = mb(today.kb)
            val idle = mb(today.idleKb)
            Ui.text(c, tr("合計 $total（うち接続端末なし $idle）", "Total $total ($idle with no devices)", "Total $total (dont $idle sans appareil)"), 14f)
            chart(c, today.hours.map { it.kb - it.idleKb to it.idleKb })
            val peak = mb(today.hours.maxOf { it.kb })
            Ui.small(c, tr("いちばん多い時間帯: $peak／時", "Busiest hour: $peak/h", "Heure la plus chargée : $peak/h"))
        }
        if (days.size > 1) {
            Ui.card(box).also { c ->
                val n = days.size
                Ui.heading(c, tr("時間帯ごとの平均（直近 $n 日）", "Average by hour (last $n days)", "Moyenne par heure ($n derniers jours)"))
                val avg = (0 until 24).map { h -> days.sumOf { it.hours[h].kb - it.hours[h].idleKb } / days.size to days.sumOf { it.hours[h].idleKb } / days.size }
                chart(c, avg)
                Ui.small(
                    c,
                    tr(
                        "毎日決まった時間に多い通信は、接続端末やアプリの自動更新・バックアップの可能性があります。",
                        "Traffic that peaks at the same time every day may be automatic updates or backups on connected devices or apps.",
                        "Un trafic élevé chaque jour à la même heure peut venir de mises à jour ou de sauvegardes automatiques des appareils connectés ou des applications.",
                    ),
                )
            }
        }
        Ui.card(box).also { c ->
            Ui.heading(c, tr("日ごとの合計", "Totals by day", "Totaux par jour"))
            for (d in days) {
                val parts = mutableListOf(
                    mb(d.kb).let { tr("合計 $it", "Total $it", "Total $it") },
                    mb(d.idleKb).let { tr("接続端末なし $it", "No devices $it", "Sans appareil $it") },
                )
                if (quiet != null) parts.add("${quiet.label} ${mb(d.kbIn(quiet))}")
                Ui.kv(c, label(d.day)).text = parts.joinToString("\n")
            }
            if (quiet == null) {
                Ui.small(
                    c,
                    tr(
                        "設定で「使わない時間帯」を決めると、その時間帯の通信も集計し、多いときに知らせます。",
                        "Set “Quiet hours” in Settings to also total the traffic during those hours and be notified when it's high.",
                        "Définissez « Heures d'inactivité » dans les paramètres pour totaliser aussi le trafic de ces heures et être alerté s'il est élevé.",
                    ),
                )
            }
        }
        Ui.card(box).also { c ->
            Ui.heading(c, tr("減らすには", "How to reduce it", "Comment le réduire"))
            Ui.small(
                c,
                tr(
                    "・接続端末なしの通信が多い → この端末のアプリ（設定ガイドの「アプリのバックグラウンド制限」「自動同期」「Play ストアの自動更新」）\n" +
                        "・使わない時間帯に多い → 接続端末の OS 更新・クラウド同期（上の「接続端末の設定」）",
                    "• Lots of traffic with no devices → this phone's apps (“App background restrictions”, “Auto-sync” and “Play Store auto-update” in the Setup guide)\n" +
                        "• Lots of traffic during quiet hours → system updates and cloud sync on connected devices (“Client device guide” above)",
                    "• Beaucoup de trafic sans appareil → les applications de ce téléphone (« Restrictions d'arrière-plan des applications », « Synchronisation automatique » et « Mises à jour automatiques du Play Store » dans le guide de configuration)\n" +
                        "• Beaucoup de trafic pendant les heures d'inactivité → mises à jour et synchronisation cloud des appareils connectés (« Guide des appareils » ci-dessus)",
                ),
            )
            Ui.button(c, tr("設定ガイドを開く", "Open Setup guide", "Ouvrir le guide de configuration")) { startActivity(Intent(this, GuideActivity::class.java)) }
        }
    }

    private fun chart(parent: LinearLayout, values: List<Pair<Double, Double>>) {
        val v = BarChartView(this).apply { this.values = values }
        parent.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150)))
    }

    private fun saveCsv() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "tethering-traffic-${Fmt.fileStamp(System.currentTimeMillis())}.csv")
        try {
            startActivityForResult(i, REQ_CSV)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, Ui.noPicker(), Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity の旧 API（AndroidX を使わないため）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_CSV || resultCode != RESULT_OK || uri == null) return
        val snapshot = days
        bg.execute {
            val n = runCatching { contentResolver.openOutputStream(uri)?.use { writeCsv(it, snapshot) } ?: -1 }.getOrDefault(-1)
            main.post { Toast.makeText(this, if (n >= 0) Ui.exported(n) else Ui.exportFailed(), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun writeCsv(out: OutputStream, list: List<TrafficMath.Day>): Int {
        var n = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFF")
            w.write(Csv.line(TrafficMath.CSV_HEADER))
            w.write("\n")
            for (d in list.sortedBy { it.day }) {
                for (row in TrafficMath.csvRows(d)) {
                    w.write(Csv.line(row))
                    w.write("\n")
                    n++
                }
            }
        }
        return n
    }
}

/** 24 本の積み上げ棒グラフ（下が接続端末なし＝橙、上がそれ以外＝緑）。外部ライブラリを使わない */
class BarChartView(ctx: Context) : View(ctx) {
    var values: List<Pair<Double, Double>> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    private val pOther = Paint().apply { color = ctx.getColor(R.color.brand) }
    private val pIdle = Paint().apply { color = ctx.getColor(R.color.status_warn) }
    private val pAxis = Paint().apply {
        color = ctx.getColor(R.color.card_stroke)
        strokeWidth = sp(1f)
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.text_secondary)
        textSize = sp(10f)
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        val labelH = sp(14f)
        val chartH = height - labelH
        val max = values.maxOf { it.first + it.second }.takeIf { it > 0 } ?: 1.0
        val slot = width / n.toFloat()
        val bw = slot * 0.7f
        for ((i, v) in values.withIndex()) {
            val x = i * slot + (slot - bw) / 2
            val hIdle = (v.second / max * chartH).toFloat()
            val hOther = (v.first / max * chartH).toFloat()
            if (hIdle > 0) canvas.drawRect(x, chartH - hIdle, x + bw, chartH, pIdle)
            if (hOther > 0) canvas.drawRect(x, chartH - hIdle - hOther, x + bw, chartH - hIdle, pOther)
            if (i % 6 == 0) canvas.drawText(tr("${i}時", "$i:00", "${i} h"), x, height - sp(2f), pText)
        }
        canvas.drawLine(0f, chartH, width.toFloat(), chartH, pAxis)
    }
}
