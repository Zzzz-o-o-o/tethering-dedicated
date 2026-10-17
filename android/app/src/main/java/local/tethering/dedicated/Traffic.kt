package local.tethering.dedicated

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 使わない時間帯（例: 1-6 は 1:00〜6:00、23-5 は日をまたぐ、1:30-6:00 のように分も指定できる）。終わりの時刻は含まない。
 * 待機中の通信（仕様 F-33）と時間帯スケジュール（F-37）で共通に使う
 */
data class QuietHours(val startHour: Int, val endHour: Int, val startMinute: Int = 0, val endMinute: Int = 0) {
    private val startOfDay: Int get() = startHour * 60 + startMinute
    private val endOfDay: Int get() = endHour * 60 + endMinute

    val label: String get() = "$startHour:${pad(startMinute)}〜$endHour:${pad(endMinute)}"

    /** 終わりの時刻（例: 6:00） */
    val endLabel: String get() = "$endHour:${pad(endMinute)}"

    /** その日の分（0〜1439）が時間帯に入るか */
    fun containsMinute(minuteOfDay: Int): Boolean =
        if (startOfDay < endOfDay) minuteOfDay in startOfDay until endOfDay else minuteOfDay >= startOfDay || minuteOfDay < endOfDay

    /** 時間帯別の集計用。その時（0〜23）の半ば（h:30）が時間帯に入るか（「1-6」なら 1〜5 時） */
    fun contains(hour: Int): Boolean = containsMinute(hour * 60 + 30)

    /** [from, to) と重なる時間帯の [開始, 終了)（ミリ秒・古い順） */
    fun windowsOverlapping(from: Long, to: Long, tz: TimeZone): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        val day = Calendar.getInstance(tz).apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -1)
        }
        while (day.timeInMillis < to) {
            val start = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, startMinute)
            }.timeInMillis
            val end = (day.clone() as Calendar).apply {
                if (endOfDay <= startOfDay) add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, endHour)
                set(Calendar.MINUTE, endMinute)
            }.timeInMillis
            if (start < to && end > from) out.add(start to end)
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    /** [now] を含む時間帯の [開始, 終了)。時間帯の外なら null */
    fun windowAt(now: Long, tz: TimeZone): Pair<Long, Long>? = windowsOverlapping(now, now + 1, tz).firstOrNull { now >= it.first && now < it.second }

    /** [now] より後で、いちばん近い時間帯の開始 */
    fun nextStart(now: Long, tz: TimeZone): Long? = windowsOverlapping(now, now + 2 * DAY_MS, tz).map { it.first }.firstOrNull { it > now }

    companion object {
        private const val DAY_MS = 24 * 3_600_000L

        /** 区切りは「-」のほか、日本語入力で入りやすい「〜」「～」「ー」「−」も受け付ける。分の区切りは「:」「：」 */
        private val PATTERN = Regex("""^(\d{1,2})(?:[:：](\d{2}))?\s*[-〜~～ー−]\s*(\d{1,2})(?:[:：](\d{2}))?$""")

        private fun pad(m: Int) = m.toString().padStart(2, '0')

        /** 「1-6」「23-5」「1:30-6:00」など。空欄・不正な値は null */
        fun parse(s: String?): QuietHours? {
            val m = PATTERN.find(s?.trim().orEmpty()) ?: return null
            val a = m.groupValues[1].toInt()
            val am = m.groupValues[2].ifEmpty { "0" }.toInt()
            val b = m.groupValues[3].toInt()
            val bm = m.groupValues[4].ifEmpty { "0" }.toInt()
            if (a !in 0..23 || b !in 0..24 || am !in 0..59 || bm !in 0..59 || (b == 24 && bm != 0)) return null
            val end = b % 24
            if (a * 60 + am == end * 60 + bm) return null
            return QuietHours(a, end, am, bm)
        }
    }
}

/**
 * 待機中の通信の検知（仕様 F-33）。電力の区間（チェックなどで区切られる）の通信量から判定するので、判定のために端末を起こさない。
 *
 * - 接続端末なし（テザリング OFF、またはチェック時点で接続 0 台）の区間の通信 → この端末自身のアプリ・OS の通信。
 *   直近 60 分のうち 20 分以上が「接続端末なし」で、1 MB 以上かつ 1 時間あたり [Limits.idleMbPerHour] を超えたら知らせる
 * - 使わない時間帯の通信 → 接続端末の自動更新・同期など。時間帯が終わってから、その間の合計で知らせる（夜中に通知で起こさない）
 */
class TrafficWatch(private val cooldownMs: Long = 6 * HOUR) {
    data class Seg(val startMs: Long, val endMs: Long, val kb: Long, val idle: Boolean)

    data class Limits(val idleMbPerHour: Int, val quietMb: Int, val quiet: QuietHours?)

    sealed class Finding {
        data class Idle(val mb: Double, val mbPerHour: Double, val fromMs: Long, val toMs: Long) : Finding()

        data class Quiet(val quiet: QuietHours, val mb: Double, val idleMb: Double, val fromMs: Long, val toMs: Long, val coveredSec: Long) : Finding()
    }

    private class Acc(val from: Long, val to: Long) {
        var kb = 0.0
        var idleKb = 0.0
        var sec = 0L
    }

    private val recent = ArrayDeque<Seg>()
    private var pending: Acc? = null

    /** 最後に「接続端末なしの通信」を知らせた時刻（6 時間は重ねて知らせない） */
    var lastIdleAlertAt = 0L

    fun add(seg: Seg, l: Limits, tz: TimeZone): List<Finding> {
        if (seg.endMs <= seg.startMs || seg.kb < 0) return emptyList()
        val out = ArrayList<Finding>()
        recent.addLast(seg)
        while (recent.isNotEmpty() && recent.first().endMs < seg.endMs - 2 * HOUR) recent.removeFirst()
        if (l.idleMbPerHour > 0) idle(seg.endMs, l)?.let { out.add(it) }
        val q = l.quiet
        if (q == null) pending = null else quiet(seg, q, l, tz, out)
        return out
    }

    private fun idle(now: Long, l: Limits): Finding? {
        val from = now - HOUR
        var kb = 0.0
        var ms = 0L
        var first = now
        for (s in recent) {
            if (!s.idle) continue
            val ov = overlap(s.startMs, s.endMs, from, now)
            if (ov <= 0) continue
            kb += s.kb * ov / (s.endMs - s.startMs).toDouble()
            ms += ov
            first = minOf(first, maxOf(s.startMs, from))
        }
        if (ms < MIN_IDLE_MS || kb < MIN_IDLE_KB) return null
        val mbPerHour = kb / 1024 / (ms / HOUR.toDouble())
        if (mbPerHour < l.idleMbPerHour || now - lastIdleAlertAt < cooldownMs) return null
        lastIdleAlertAt = now
        return Finding.Idle(kb / 1024, mbPerHour, first, now)
    }

    private fun quiet(seg: Seg, q: QuietHours, l: Limits, tz: TimeZone, out: MutableList<Finding>) {
        for ((ws, we) in q.windowsOverlapping(seg.startMs, seg.endMs, tz)) {
            val p = pending
            if (p != null && p.from != ws) {
                finish(p, q, l, out)
                pending = null
            }
            val acc = pending ?: Acc(ws, we).also { pending = it }
            val ov = overlap(seg.startMs, seg.endMs, ws, we)
            val part = seg.kb * ov / (seg.endMs - seg.startMs).toDouble()
            acc.kb += part
            if (seg.idle) acc.idleKb += part
            acc.sec += ov / 1000
        }
        val p = pending ?: return
        if (seg.endMs >= p.to) {
            finish(p, q, l, out)
            pending = null
        }
    }

    private fun finish(p: Acc, q: QuietHours, l: Limits, out: MutableList<Finding>) {
        if (l.quietMb > 0 && p.kb / 1024 >= l.quietMb) out.add(Finding.Quiet(q, p.kb / 1024, p.idleKb / 1024, p.from, p.to, p.sec))
    }

    companion object {
        const val HOUR = 3_600_000L
        private const val MIN_IDLE_MS = 20 * 60_000L
        private const val MIN_IDLE_KB = 1024.0

        fun overlap(a0: Long, a1: Long, b0: Long, b1: Long): Long = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0)
    }
}

/**
 * テザリングの通信（モバイル回線以外の通信）から、接続端末の有無を補う（仕様 F-32 / F-33 / F-36）。
 * 接続端末の数はチェックのときにしか数えない（Android 10 は端末がつながったことをアプリに知らせない）ので、
 * 待機（チェック間隔が長い）中につながった端末を見落とすことがある。Wi-Fi に接続していないテザリング専用機では、
 * 全体の通信量からモバイル回線の分を引いた残りが、アクセスポイント側（接続端末とのあいだ）の通信になる
 */
object LanTraffic {
    /** 区間にこれ以上あれば、接続端末がいたとみなす */
    const val CLIENT_BYTES = 1024 * 1024L

    /** 区間の途中でこれ以上たまったら、接続端末を数え直す（電池の放送のついでに見るので、端末を余計に起こさない） */
    const val RECOUNT_BYTES = 2 * 1024 * 1024L

    /** 数え直しの最短間隔（数えられない種類の接続でチェックを繰り返さないように） */
    const val RECOUNT_GAP_MS = 30 * 60_000L

    /** TrafficStats の値から（取得できないときは負の値が入る）。モバイル回線以外の送受信の合計 */
    fun bytes(totalRx: Long, totalTx: Long, mobileRx: Long, mobileTx: Long): Long? {
        if (totalRx < 0 || totalTx < 0 || mobileRx < 0 || mobileTx < 0) return null
        return (totalRx + totalTx - mobileRx - mobileTx).coerceAtLeast(0)
    }

    /** 区間の接続端末数（最大）を補う。テザリング ON で 0 台と数えていても、テザリングの通信があれば 1 台 */
    fun clients(clientsMax: Int?, tetherOn: Boolean?, lanBytes: Long?): Int? =
        if (clientsMax == 0 && tetherOn == true && lanBytes != null && lanBytes >= CLIENT_BYTES) 1 else clientsMax

    /** 0 台と数えているのにテザリングの通信が流れている → チェックで数え直す */
    fun shouldRecount(tetherOn: Boolean?, clients: Int?, lanBytes: Long?, sinceLastRecountMs: Long): Boolean =
        tetherOn == true && clients == 0 && lanBytes != null && lanBytes >= RECOUNT_BYTES && sinceLastRecountMs >= RECOUNT_GAP_MS
}

/** 通信量の時間帯別の集計（仕様 F-33）。電力の記録（区間）の通信量を、時間の重なりで各時間帯に按分する */
object TrafficMath {
    class Hour(val hour: Int) {
        var kb = 0.0

        /** うち接続端末なし（テザリング OFF、またはチェック時点で 0 台）の区間 */
        var idleKb = 0.0
    }

    class Day(val day: String, val hours: List<Hour>) {
        val kb: Double get() = hours.sumOf { it.kb }
        val idleKb: Double get() = hours.sumOf { it.idleKb }

        fun kbIn(q: QuietHours): Double = hours.filter { q.contains(it.hour) }.sumOf { it.kb }
    }

    fun isIdle(r: PowerRow): Boolean = r.tether == false || r.clientsMax == 0

    private fun parser(tz: TimeZone) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).apply { timeZone = tz }

    private fun parseMs(f: SimpleDateFormat, s: String): Long? = runCatching { f.parse(s)?.time }.getOrNull()

    /** ある日（yyyyMMdd）の 24 時間分。[rows] は前後の日の区間を含んでいてよい（その日にかかる分だけ数える） */
    fun day(rows: List<PowerRow>, day: String, tz: TimeZone): Day {
        val f = parser(tz)
        val dayText = "${day.take(4)}-${day.substring(4, 6)}-${day.substring(6, 8)}"
        val hours = List(24) { Hour(it) }
        val start = Calendar.getInstance(tz).apply {
            clear()
            set(day.take(4).toInt(), day.substring(4, 6).toInt() - 1, day.substring(6, 8).toInt(), 0, 0, 0)
        }
        val bounds = LongArray(25) { h -> (start.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, h) }.timeInMillis }
        for (r in rows) {
            val kb = r.mobileKb ?: continue
            // 前後の日の区間も渡されるので、その日にかからないものは日付の文字列だけで先に除く
            if (r.end.take(10) < dayText || r.start.take(10) > dayText) continue
            val s = parseMs(f, r.start) ?: continue
            val e = parseMs(f, r.end) ?: continue
            if (e <= s || e <= bounds[0] || s >= bounds[24]) continue
            val idle = isIdle(r)
            for (h in 0 until 24) {
                val ov = TrafficWatch.overlap(s, e, bounds[h], bounds[h + 1])
                if (ov <= 0) continue
                val part = kb * ov / (e - s).toDouble()
                hours[h].kb += part
                if (idle) hours[h].idleKb += part
            }
        }
        return Day(day, hours)
    }

    /** 時間帯別の CSV の見出しと行 */
    val CSV_HEADER = listOf("日付", "時", "モバイル通信KB", "うち接続端末なしKB")

    fun csvRows(d: Day): List<List<String>> = d.hours.map { h ->
        listOf(
            "${d.day.take(4)}-${d.day.substring(4, 6)}-${d.day.substring(6, 8)}", h.hour.toString(),
            String.format(Locale.US, "%.0f", h.kb), String.format(Locale.US, "%.0f", h.idleKb),
        )
    }
}
