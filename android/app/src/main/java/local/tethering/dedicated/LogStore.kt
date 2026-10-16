package local.tethering.dedicated

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** ログに書くイベント名 */
object Ev {
    const val START = "監視開始"
    const val STOP = "監視停止"
    const val BOOT = "端末起動"
    const val SUSPECT = "異常の疑い"
    const val RECHECK_BAD = "再確認でも異常"
    const val CONFIRMED = "異常を確定"
    const val CONTINUING = "異常が継続"
    const val RECOVERED = "復旧"
    const val BLIP = "一時的な異常（自然回復）"
    const val TETHER = "テザリング状態"
    const val POWER = "電源の変化"
    const val PROFILE = "プロファイル変更"
    const val ALERT = "警告"
    const val ALERT_CLEAR = "警告の解除"
    const val MAINT_ON = "メンテナンス開始"
    const val MAINT_OFF = "メンテナンス終了"
    const val SETTINGS = "設定変更"
    const val RESTORE = "設定の復元"
    const val BOOT_CHECK = "起動後チェック"
    const val EXPORT = "ログ出力"

    /** 画面の「直近の記録」に出すもの（定期チェックの行は出さない） */
    val recentKinds = setOf(
        START, STOP, BOOT, SUSPECT, RECHECK_BAD, CONFIRMED, CONTINUING, RECOVERED, BLIP, TETHER,
        POWER, PROFILE, ALERT, ALERT_CLEAR, MAINT_ON, MAINT_OFF, SETTINGS, RESTORE, BOOT_CHECK,
    )
}

/**
 * 状態変化・異常・復旧処理・温度・電池を CSV で記録する（仕様 F-20）。
 * 1 日 1 ファイル（files/logs/log-yyyyMMdd.csv）。保存期間と合計サイズを超えた古いファイルから消す。
 * SSID・接続端末の識別情報・電話番号・IP アドレスは記録しない。
 */
object LogStore {
    val HEADER = listOf(
        "日時", "イベント", "プロファイル", "電源", "電池%", "電池温度℃", "熱状態", "SoC温度℃",
        "SIM", "音声", "データ網", "データ通信", "RSRP(dBm)", "RSRQ(dB)", "SINR(dB)", "電波レベル", "バンド", "帯域(MHz)",
        "Wi-Fiテザリング", "共有中", "接続端末", "回線登録", "IP", "DNS", "HTTP", "応答(ms)", "システム検証",
        "異常", "処置", "結果", "詳細",
    )
    private val IDX_ANOMALY = HEADER.indexOf("異常")
    private val IDX_ACTION = HEADER.indexOf("処置")
    private val IDX_RESULT = HEADER.indexOf("結果")
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val io = Executors.newSingleThreadExecutor()
    private var prunedDay: String? = null
    private var appendsSincePrune = 0

    fun write(
        ctx: Context,
        event: String,
        s: StateStore.State,
        includeCheck: Boolean = false,
        anomaly: String? = null,
        action: String? = null,
        result: String? = null,
        detail: String? = null,
    ) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val days = prefs.int(Defs.LOG_DAYS)
        val maxBytes = prefs.int(Defs.LOG_MB) * 1024L * 1024L
        val line = Csv.line(fields(System.currentTimeMillis(), event, s, includeCheck, anomaly, action, result, detail))
        io.execute { runCatching { append(app, line, days, maxBytes) } }
    }

    fun fields(
        at: Long,
        event: String,
        s: StateStore.State,
        includeCheck: Boolean,
        anomaly: String?,
        action: String?,
        result: String?,
        detail: String?,
    ): List<String?> {
        val p = s.power
        val t = s.tether
        val l = s.line
        val c = if (includeCheck) s.check else null
        return listOf(
            Fmt.dateTime(at), event, s.effective.label,
            p?.plug?.label, p?.levelPct?.toString(), p?.tempC?.let { f1(it) }, p?.thermalStatus?.let { Fmt.thermal(it) }, p?.socTempC?.let { f1(it) },
            l?.let { Fmt.sim(it.simState) }, l?.let { Fmt.voice(it.voiceState) }, l?.let { Fmt.rat(it.dataRat) }, l?.let { Fmt.dataState(it.dataState) },
            l?.rsrp?.toString(), l?.rsrq?.toString(), l?.sinr?.let { f1(it) }, l?.level?.toString(), l?.band,
            l?.bandwidthsMhz?.joinToString("+") { f1(it) },
            t?.let { Fmt.ap(it.apState) }, t?.let { Fmt.tethered(it.tethered) }, t?.clients?.toString(),
            c?.registered?.let { tri(it) }, c?.ip?.let { tri(it) }, c?.dns?.let { tri(it) }, c?.http?.let { tri(it) },
            c?.httpMs?.toString(), c?.validated?.let { tri(it) },
            anomaly, action, result, detail,
        )
    }

    private fun tri(t: Tri): String = when (t) {
        Tri.OK -> "OK"
        Tri.NG -> "NG"
        Tri.SKIP -> "未実施"
        Tri.UNKNOWN -> "取得不可"
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun dir(ctx: Context) = File(ctx.filesDir, "logs").apply { mkdirs() }

    private fun dayName(ms: Long) = "log-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms)) + ".csv"

    private fun logFiles(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.startsWith("log-") && f.name.endsWith(".csv") }?.sortedBy { it.name }.orEmpty()

    private fun append(ctx: Context, line: String, days: Int, maxBytes: Long) {
        val now = System.currentTimeMillis()
        val day = dayName(now)
        val f = File(dir(ctx), day)
        val isNew = !f.exists()
        FileOutputStream(f, true).bufferedWriter(Charsets.UTF_8).use { w ->
            if (isNew) {
                w.write(Csv.line(HEADER))
                w.write("\n")
            }
            w.write(line)
            w.write("\n")
        }
        appendsSincePrune++
        if (prunedDay != day || appendsSincePrune >= 200) {
            prune(ctx, days, maxBytes, now)
            prunedDay = day
            appendsSincePrune = 0
        }
    }

    private fun prune(ctx: Context, days: Int, maxBytes: Long, now: Long) {
        val keepFrom = dayName(now - (days - 1) * DAY_MS)
        logFiles(ctx).filter { it.name < keepFrom }.forEach { it.delete() }
        val remain = logFiles(ctx).toMutableList()
        var total = remain.sumOf { it.length() }
        while (total > maxBytes && remain.size > 1) {
            val oldest = remain.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    /** すべてのログを 1 つの CSV に書き出す（Excel で文字化けしないよう BOM 付き UTF-8）。戻り値は行数。UI スレッド以外で呼ぶ */
    fun export(ctx: Context, out: OutputStream): Int {
        val app = ctx.applicationContext
        return io.submit<Int> {
            var rows = 0
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("\uFEFF")
                w.write(Csv.line(HEADER))
                w.write("\n")
                for (file in logFiles(app)) {
                    file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEachIndexed { i, s ->
                            if (s.isBlank() || (i == 0 && s.startsWith(HEADER[0] + ","))) return@forEachIndexed
                            w.write(s)
                            w.write("\n")
                            rows++
                        }
                    }
                }
            }
            rows
        }.get()
    }

    /** ログを全部消す。UI スレッド以外で呼ぶ */
    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        io.submit { logFiles(app).forEach { it.delete() } }.get()
    }

    fun totalBytes(ctx: Context): Long = logFiles(ctx.applicationContext).sumOf { it.length() }

    /** 直近 2 日分のログから、画面に出す記録を新しい順に読む */
    fun loadRecent(ctx: Context, max: Int, cb: (List<RecentEvent>) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val out = ArrayList<RecentEvent>()
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            loop@ for (f in logFiles(app).takeLast(2).reversed()) {
                val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
                for (s in lines.asReversed()) {
                    if (s.isBlank() || s.startsWith(HEADER[0] + ",")) continue
                    val c = Csv.parseLine(s)
                    if (c.size < HEADER.size || c[1] !in Ev.recentKinds) continue
                    val at = runCatching { fmt.parse(c[0])?.time }.getOrNull() ?: continue
                    val text = listOf(c[IDX_ANOMALY], c[IDX_RESULT], c[IDX_ACTION]).filter { it.isNotBlank() }.joinToString(" · ")
                    out.add(RecentEvent(at, c[1], text))
                    if (out.size >= max) break@loop
                }
            }
            Handler(Looper.getMainLooper()).post { cb(out) }
        }
    }

    /** 診断情報用に、最新ファイルの末尾を返す。UI スレッド以外で呼ぶ */
    fun tail(ctx: Context, n: Int): List<String> {
        val app = ctx.applicationContext
        return io.submit<List<String>> {
            val f = logFiles(app).lastOrNull() ?: return@submit emptyList()
            runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).filter { it.isNotBlank() }.takeLast(n)
        }.get()
    }
}
