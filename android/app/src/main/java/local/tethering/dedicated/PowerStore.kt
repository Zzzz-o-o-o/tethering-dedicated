package local.tethering.dedicated

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * 電力の記録（仕様 F-25）。1 区間 1 行を files/power/power-yyyyMMdd.csv に書く。
 * 保存期間とサイズの上限はログと同じ設定を使う。
 */
object PowerStore {
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var csv: DailyCsv? = null

    private fun csv(ctx: Context): DailyCsv = csv ?: synchronized(this) {
        csv ?: DailyCsv(File(ctx.applicationContext.filesDir, "power"), "power-", PowerRow.HEADER).also { csv = it }
    }

    fun append(ctx: Context, row: PowerRow) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val days = prefs.int(Defs.LOG_DAYS)
        val maxBytes = prefs.int(Defs.LOG_MB) * 1024L * 1024L
        val line = Csv.line(row.fields())
        val now = System.currentTimeMillis()
        io.execute { runCatching { csv(app).append(line, now, days, maxBytes) } }
    }

    /** ある日（yyyyMMdd）の区間。UI スレッド以外で呼ぶ */
    fun rowsOfDay(ctx: Context, yyyyMMdd: String): List<PowerRow> =
        io.submit<List<PowerRow>> { csv(ctx).rowsOfDay(yyyyMMdd).mapNotNull { PowerRow.parse(it) } }.get()

    /** 記録のある日付（yyyyMMdd、新しい順） */
    fun days(ctx: Context): List<String> =
        io.submit<List<String>> { csv(ctx).files().map { it.name.removePrefix("power-").removeSuffix(".csv") }.reversed() }.get()

    fun export(ctx: Context, out: OutputStream): Int = io.submit<Int> { csv(ctx).export(out) }.get()

    fun clear(ctx: Context) {
        io.submit { csv(ctx).clear() }.get()
    }

    fun totalBytes(ctx: Context): Long = csv(ctx).totalBytes()
}
