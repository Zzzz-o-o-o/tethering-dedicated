package local.tethering.dedicated

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
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
    const val EXP_START = "比較テスト開始"
    const val EXP_SWITCH = "比較テストの切り替え"
    const val EXP_END = "比較テスト終了"

    /** 画面の「直近の記録」に出すもの（定期チェックの行は出さない） */
    val recentKinds = setOf(
        START, STOP, BOOT, SUSPECT, RECHECK_BAD, CONFIRMED, CONTINUING, RECOVERED, BLIP, TETHER,
        POWER, PROFILE, ALERT, ALERT_CLEAR, MAINT_ON, MAINT_OFF, SETTINGS, RESTORE, BOOT_CHECK,
        EXP_START, EXP_SWITCH, EXP_END,
    )

    /** 接続性チェックの行（日次レポートの稼働率に使う） */
    val checkKinds: Set<String> get() = Trigger.entries.map { it.label }.toSet()
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
    val IDX_EVENT = HEADER.indexOf("イベント")
    val IDX_TEMP = HEADER.indexOf("電池温度℃")
    val IDX_RSRP = HEADER.indexOf("RSRP(dBm)")
    val IDX_ANOMALY = HEADER.indexOf("異常")
    val IDX_ACTION = HEADER.indexOf("処置")
    val IDX_RESULT = HEADER.indexOf("結果")

    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var csv: DailyCsv? = null

    private fun csv(ctx: Context): DailyCsv = csv ?: synchronized(this) {
        csv ?: DailyCsv(File(ctx.applicationContext.filesDir, "logs"), "log-", HEADER).also { csv = it }
    }

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
        val now = System.currentTimeMillis()
        val line = Csv.line(fields(now, event, s, includeCheck, anomaly, action, result, detail))
        io.execute { runCatching { csv(app).append(line, now, days, maxBytes) } }
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

    /** すべてのログを 1 つの CSV に書き出す（BOM 付き UTF-8）。戻り値は行数。UI スレッド以外で呼ぶ */
    fun export(ctx: Context, out: OutputStream): Int = io.submit<Int> { csv(ctx).export(out) }.get()

    /** ログを全部消す。UI スレッド以外で呼ぶ */
    fun clear(ctx: Context) {
        io.submit { csv(ctx).clear() }.get()
    }

    fun totalBytes(ctx: Context): Long = csv(ctx).totalBytes()

    /** 記録のある日付（yyyyMMdd、新しい順）。UI スレッド以外で呼ぶ */
    fun days(ctx: Context): List<String> =
        io.submit<List<String>> { csv(ctx).files().map { it.name.removePrefix("log-").removeSuffix(".csv") }.reversed() }.get()

    /** ある日（yyyyMMdd）の行。UI スレッド以外で呼ぶ */
    fun rowsOfDay(ctx: Context, yyyyMMdd: String): List<List<String>> = io.submit<List<List<String>>> { csv(ctx).rowsOfDay(yyyyMMdd) }.get()

    /** 直近 2 日分のログから、画面に出す記録を新しい順に読む */
    fun loadRecent(ctx: Context, max: Int, cb: (List<RecentEvent>) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val out = ArrayList<RecentEvent>()
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            val store = csv(app)
            loop@ for (f in store.files().takeLast(2).reversed()) {
                for (c in store.rowsOf(f).asReversed()) {
                    if (c[IDX_EVENT] !in Ev.recentKinds) continue
                    val at = runCatching { fmt.parse(c[0])?.time }.getOrNull() ?: continue
                    val text = listOf(c[IDX_ANOMALY], c[IDX_RESULT], c[IDX_ACTION]).filter { it.isNotBlank() }.joinToString(" · ")
                    out.add(RecentEvent(at, c[IDX_EVENT], text))
                    if (out.size >= max) break@loop
                }
            }
            Handler(Looper.getMainLooper()).post { cb(out) }
        }
    }

    /** 診断情報用に、最新ファイルの末尾を返す。UI スレッド以外で呼ぶ */
    fun tail(ctx: Context, n: Int): List<String> = io.submit<List<String>> { csv(ctx).tail(n) }.get()
}
