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
import java.util.Locale
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
            Ui.text(c, "モバイル回線の通信量を時間帯別に集計します。使っていないはずの時間に流れている通信を見つけるためのものです。", 13.5f)
            Ui.small(
                c,
                "橙色は「接続端末なし」（テザリング OFF、またはチェック時点で接続 0 台）の通信で、この端末自身のアプリ・OS によるものです。" +
                    "通信量はチェックなどで区切った区間ごとの値を時間で按分した目安です。",
            )
            Ui.buttonRow(
                c,
                "接続端末の設定" to { startActivity(Intent(this, ClientGuideActivity::class.java)) },
                "時間帯別 CSV 保存" to { saveCsv() },
            )
        }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        Ui.small(box, "集計しています…")
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

    private fun mb(kb: Double) = String.format(Locale.US, "%.1f MB", kb / 1024)

    private fun label(day: String) = "${day.substring(4, 6).trimStart('0')}/${day.substring(6, 8).trimStart('0')}"

    private fun render() {
        box.removeAllViews()
        if (days.isEmpty() || days.all { it.kb <= 0 }) {
            Ui.card(box).also { Ui.small(it, "まだ記録がありません（チェックの区切りごとに記録します）。") }
            return
        }
        val quiet = QuietHours.parse(Prefs(this).text(Defs.QUIET_HOURS))
        val today = days.first()
        Ui.card(box).also { c ->
            Ui.heading(c, "${label(today.day)} の時間帯別")
            Ui.text(c, "合計 ${mb(today.kb)}（うち接続端末なし ${mb(today.idleKb)}）", 14f)
            chart(c, today.hours.map { it.kb - it.idleKb to it.idleKb })
            Ui.small(c, "いちばん多い時間帯: ${mb(today.hours.maxOf { it.kb })}／時")
        }
        if (days.size > 1) {
            Ui.card(box).also { c ->
                Ui.heading(c, "時間帯ごとの平均（直近 ${days.size} 日）")
                val avg = (0 until 24).map { h -> days.sumOf { it.hours[h].kb - it.hours[h].idleKb } / days.size to days.sumOf { it.hours[h].idleKb } / days.size }
                chart(c, avg)
                Ui.small(c, "毎日決まった時間に多い通信は、接続端末やアプリの自動更新・バックアップの可能性があります。")
            }
        }
        Ui.card(box).also { c ->
            Ui.heading(c, "日ごとの合計")
            for (d in days) {
                val parts = mutableListOf("合計 ${mb(d.kb)}", "接続端末なし ${mb(d.idleKb)}")
                if (quiet != null) parts.add("${quiet.label} ${mb(d.kbIn(quiet))}")
                Ui.kv(c, label(d.day)).text = parts.joinToString("\n")
            }
            if (quiet == null) Ui.small(c, "設定で「使わない時間帯」を決めると、その時間帯の通信も集計し、多いときに知らせます。")
        }
        Ui.card(box).also { c ->
            Ui.heading(c, "減らすには")
            Ui.small(
                c,
                "・接続端末なしの通信が多い → この端末のアプリ（設定ガイドの「アプリのバックグラウンド制限」「自動同期」「Play ストアの自動更新」）\n" +
                    "・使わない時間帯に多い → 接続端末の OS 更新・クラウド同期（上の「接続端末の設定」）",
            )
            Ui.button(c, "設定ガイドを開く") { startActivity(Intent(this, GuideActivity::class.java)) }
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
            Toast.makeText(this, "保存先を選ぶ画面を開けませんでした", Toast.LENGTH_SHORT).show()
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
            main.post { Toast.makeText(this, if (n >= 0) "${n}行を書き出しました" else "書き出しに失敗しました", Toast.LENGTH_SHORT).show() }
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
            if (i % 6 == 0) canvas.drawText("${i}時", x, height - sp(2f), pText)
        }
        canvas.drawLine(0f, chartH, width.toFloat(), chartH, pAxis)
    }
}
