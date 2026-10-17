package local.tethering.dedicated

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 省電力の分析（仕様 F-30 / F-36 / F-35 / F-31）。電力の記録（区間）などから集計する。Android に依存しない。
 * 消費（mAh）はバッテリー駆動中に計測できた区間だけに入っている。
 */

/** 区間の集まりの集計 */
class PowerGroup(val label: String) {
    var sec = 0L
    var mah = 0.0
    var kb = 0L
    var kbSec = 0L

    val hours: Double get() = sec / 3600.0
    val avgMa: Double? get() = if (sec > 0) mah / hours else null
    val mbPerHour: Double? get() = if (kbSec > 0) kb / 1024.0 / (kbSec / 3600.0) else null

    fun add(r: PowerRow) {
        sec += r.sec
        mah += r.mah ?: 0.0
        r.mobileKb?.let {
            kb += it
            kbSec += r.sec
        }
    }
}

/** 電波と消費の関係（仕様 F-30） */
object SignalPower {
    /** RSRP の区分（しきい値以上がその区分） */
    val BUCKETS = listOf(
        -90 to "−90 dBm 以上（良い）",
        -100 to "−90〜−100 dBm",
        -110 to "−100〜−110 dBm",
        Int.MIN_VALUE to "−110 dBm 未満（弱い）",
    )

    private fun measured(rows: List<PowerRow>) = rows.filter { it.onBattery && it.mah != null && it.sec > 0 }

    fun bucketOf(rsrp: Int): Int = BUCKETS.indexOfFirst { rsrp >= it.first }

    fun byRsrp(rows: List<PowerRow>): List<PowerGroup> {
        val groups = BUCKETS.map { PowerGroup(it.second) }
        for (r in measured(rows)) r.rsrp?.let { groups[bucketOf(it)].add(r) }
        return groups
    }

    fun byBand(rows: List<PowerRow>): List<PowerGroup> {
        val map = LinkedHashMap<String, PowerGroup>()
        for (r in measured(rows)) map.getOrPut(r.band ?: "不明") { PowerGroup(r.band ?: "不明") }.add(r)
        return map.values.sortedByDescending { it.sec }
    }

    /**
     * RSRP 1 dB あたりの消費の変化（mA/dB。時間で重み付けした最小二乗）。
     * 計測が合計 2 時間以上・RSRP の幅が 6 dB 以上ないときは null（推測しない）
     */
    fun slopeMaPerDb(rows: List<PowerRow>): Double? {
        val pts = measured(rows).filter { it.rsrp != null }
        val w = pts.sumOf { it.sec.toDouble() }
        if (w < 2 * 3600.0) return null
        val xs = pts.map { it.rsrp!!.toDouble() }
        if ((xs.maxOrNull() ?: 0.0) - (xs.minOrNull() ?: 0.0) < 6.0) return null
        val mx = pts.sumOf { it.rsrp!! * it.sec.toDouble() } / w
        val my = pts.sumOf { it.mah!! / (it.sec / 3600.0) * it.sec } / w
        var sxy = 0.0
        var sxx = 0.0
        for (p in pts) {
            val dx = p.rsrp!! - mx
            sxy += p.sec * dx * (p.mah!! / (p.sec / 3600.0) - my)
            sxx += p.sec * dx * dx
        }
        return if (sxx > 0) sxy / sxx else null
    }
}

/** 接続 0 台の待機電力と運用方針（仕様 F-36） */
object Standby {
    enum class Kind { IDLE, OFF, USED }

    enum class Advice {
        NEED_DATA,
        NEED_OFF,
        KEEP_ON,
        CONSIDER_OFF,
    }

    class Result(
        /** 記録の時間（給電中も含む）と、その内訳 */
        val recordedHours: Double,
        val idleHours: Double,
        val offHours: Double,
        val usedHours: Double,
        /** バッテリー駆動中に計測できた平均消費（計測がなければ null） */
        val idleMa: Double?,
        val offMa: Double?,
        val usedMa: Double?,
        val idleMeasuredH: Double,
        val offMeasuredH: Double,
        /** テザリング ON・接続 0 台で待機するための消費（テザリング OFF との差） */
        val costMa: Double?,
        val dailyCostMah: Double?,
        val dailyCostPct: Double?,
        /** 接続 0 台が続いた回数・最長・中央値（時間） */
        val stretches: Int,
        val longestIdleH: Double,
        val medianIdleH: Double?,
        val advice: Advice,
    ) {
        val idleShare: Double get() = if (recordedHours > 0) idleHours / recordedHours else 0.0

        /** 1 日あたりの接続 0 台の時間（記録の割合から） */
        val idleHoursPerDay: Double get() = idleShare * 24
    }

    /** テザリング ON・接続 0 台／テザリング OFF／接続あり。分からなければ null */
    fun kindOf(r: PowerRow): Kind? = when {
        r.tether == false -> Kind.OFF
        r.tether == true && r.clientsMax == 0 -> Kind.IDLE
        r.tether == true && (r.clientsMax ?: 0) >= 1 -> Kind.USED
        else -> null
    }

    private const val MIN_MEASURED_H = 0.5
    private const val GAP_MS = 2 * 60_000L

    fun analyze(rows: List<PowerRow>, tz: TimeZone, capacityMah: Int?): Result {
        val time = LongArray(3)
        val measured = Kind.entries.associateWith { PowerGroup(it.name) }
        for (r in rows) {
            val k = kindOf(r) ?: continue
            time[k.ordinal] += r.sec
            if (r.onBattery && r.mah != null && r.sec > 0) measured.getValue(k).add(r)
        }
        val total = time.sum()
        val idleM = measured.getValue(Kind.IDLE)
        val offM = measured.getValue(Kind.OFF)
        val idleMa = idleM.avgMa
        val offMa = offM.avgMa
        val idleMeasured = idleM.sec
        val offMeasured = offM.sec
        // 「接続 0 台」が続いた時間（区間をつなげる）
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).apply { timeZone = tz }
        fun ms(s: String) = runCatching { f.parse(s)?.time }.getOrNull()
        val spans = rows.filter { kindOf(it) == Kind.IDLE }.mapNotNull { r -> ms(r.start)?.let { s -> ms(r.end)?.let { e -> s to e } } }.sortedBy { it.first }
        val stretches = ArrayList<Long>()
        var cur: Pair<Long, Long>? = null
        for (s in spans) {
            val c = cur
            cur = if (c != null && s.first - c.second <= GAP_MS) c.first to maxOf(c.second, s.second) else {
                if (c != null) stretches.add(c.second - c.first)
                s
            }
        }
        cur?.let { stretches.add(it.second - it.first) }
        val hours = stretches.map { it / 3_600_000.0 }.sorted()
        val median = if (hours.isEmpty()) null else if (hours.size % 2 == 1) hours[hours.size / 2] else (hours[hours.size / 2 - 1] + hours[hours.size / 2]) / 2

        val share = if (total > 0) time[Kind.IDLE.ordinal] / total.toDouble() else 0.0
        val cost = if (idleMa != null && offMa != null && idleMeasured / 3600.0 >= MIN_MEASURED_H && offMeasured / 3600.0 >= MIN_MEASURED_H) {
            (idleMa - offMa).coerceAtLeast(0.0)
        } else {
            null
        }
        val daily = cost?.let { it * share * 24 }
        val pct = daily?.let { d -> capacityMah?.takeIf { it > 0 }?.let { d / it * 100 } }
        val advice = when {
            idleMeasured / 3600.0 < MIN_MEASURED_H -> Advice.NEED_DATA
            cost == null -> Advice.NEED_OFF
            pct != null && pct >= 10 && (median ?: 0.0) >= 2.0 -> Advice.CONSIDER_OFF
            else -> Advice.KEEP_ON
        }
        return Result(
            recordedHours = total / 3600.0,
            idleHours = time[Kind.IDLE.ordinal] / 3600.0, offHours = time[Kind.OFF.ordinal] / 3600.0, usedHours = time[Kind.USED.ordinal] / 3600.0,
            idleMa = idleMa, offMa = offMa, usedMa = measured.getValue(Kind.USED).avgMa,
            idleMeasuredH = idleMeasured / 3600.0, offMeasuredH = offMeasured / 3600.0, costMa = cost,
            dailyCostMah = daily, dailyCostPct = pct, stretches = stretches.size,
            longestIdleH = hours.maxOrNull() ?: 0.0, medianIdleH = median, advice = advice,
        )
    }
}

/** アプリ別のモバイル通信（仕様 F-35）の集計 */
object AppTraffic {
    const val UID_TETHERING = -5
    const val UID_REMOVED = -4

    /** 1 つの UID のモバイル通信（前面・裏） */
    data class UidBytes(val uid: Int, val fgBytes: Long, val bgBytes: Long)

    data class AppInfo(val label: String, val pkg: String?, val system: Boolean, val others: Int = 0)

    enum class Kind(val label: String) {
        TETHERING("テザリング（接続端末）"),
        OS("Android システム"),
        SYSTEM_APP("システムアプリ"),
        USER_APP("ユーザーアプリ"),
        REMOVED("削除済みのアプリ"),
        SELF("このアプリ"),
    }

    data class Row(
        val uid: Int,
        val label: String,
        val pkg: String?,
        val kind: Kind,
        val fgBytes: Long,
        val bgBytes: Long,
        /** 前面に出た回数・常駐サービスを始めた回数（使用状況の記録から） */
        val resumed: Int,
        val services: Int,
    ) {
        val total: Long get() = fgBytes + bgBytes
    }

    class Summary(val tetheringBytes: Long, val ownBytes: Long, val rows: List<Row>)

    fun summarize(list: List<UidBytes>, selfUid: Int, info: (Int) -> AppInfo?, events: Map<String, IntArray>): Summary {
        val rows = list.filter { it.fgBytes + it.bgBytes > 0 }.map { u ->
            val i = info(u.uid)
            val kind = when {
                u.uid == UID_TETHERING -> Kind.TETHERING
                u.uid == UID_REMOVED -> Kind.REMOVED
                u.uid == selfUid -> Kind.SELF
                u.uid in 0 until 10_000 -> Kind.OS
                i?.system == true -> Kind.SYSTEM_APP
                i != null -> Kind.USER_APP
                else -> Kind.OS
            }
            val label = when {
                kind == Kind.TETHERING || kind == Kind.REMOVED -> kind.label
                u.uid == 0 -> "Android システム（カーネル・root）"
                u.uid == 1000 -> "Android システム"
                kind == Kind.OS -> "Android システム（UID ${u.uid}）"
                else -> (i?.label ?: "UID ${u.uid}") + if ((i?.others ?: 0) > 0) "（ほか ${i!!.others} 個）" else ""
            }
            val ev = i?.pkg?.let { events[it] }
            Row(u.uid, label, i?.pkg, kind, u.fgBytes, u.bgBytes, ev?.getOrNull(0) ?: 0, ev?.getOrNull(1) ?: 0)
        }.sortedByDescending { it.total }
        val tether = rows.filter { it.kind == Kind.TETHERING }.sumOf { it.total }
        return Summary(tether, rows.filter { it.kind != Kind.TETHERING }.sumOf { it.total }, rows)
    }

    /** 制限の候補：裏での通信が多いユーザーアプリ（ADB の -RestrictApps で制限できる） */
    fun isCandidate(r: Row, minBgBytes: Long = 1024 * 1024): Boolean = r.kind == Kind.USER_APP && r.bgBytes >= minBgBytes

    /**
     * 手動での対策が必要なもの：通信の多いシステムアプリ（自動同期・Play ストアの自動更新・バックグラウンドデータ）。
     * 常駐サービスを持つアプリ（Google Play 開発者サービスなど）の通信は OS の記録では「前面」に数えられるので、合計で判定する
     * （専用機では、画面で使う通信はほとんどない）
     */
    fun needsManual(r: Row, minBytes: Long = 1024 * 1024): Boolean = r.kind == Kind.SYSTEM_APP && r.total >= minBytes
}

/** 回線方式（仕様 F-31）。優先ネットワークの種類（RILConstants の値）を読みやすくする */
object NetworkModes {
    private const val NR = "5G"
    private const val LTE = "4G"
    private const val G3 = "3G"
    private const val G2 = "2G"
    private const val CDMA = "CDMA"

    /** 値 → 使う方式。Android 10 の RILConstants.NETWORK_MODE_* に合わせる */
    private val TECH: Map<Int, List<String>> = mapOf(
        0 to listOf(G3, G2), 1 to listOf(G2), 2 to listOf(G3), 3 to listOf(G3, G2), 4 to listOf(CDMA), 5 to listOf(CDMA), 6 to listOf(CDMA),
        7 to listOf(G3, G2, CDMA), 8 to listOf(LTE, CDMA), 9 to listOf(LTE, G3, G2), 10 to listOf(LTE, G3, G2, CDMA), 11 to listOf(LTE),
        12 to listOf(LTE, G3), 13 to listOf(G3), 14 to listOf(G3), 15 to listOf(LTE, G3), 16 to listOf(G3, G2), 17 to listOf(LTE, G3, G2),
        18 to listOf(G3, G2), 19 to listOf(LTE, G3), 20 to listOf(LTE, G3, G2), 21 to listOf(G3, G2, CDMA), 22 to listOf(LTE, G3, G2, CDMA),
        23 to listOf(NR), 24 to listOf(NR, LTE), 25 to listOf(NR, LTE, CDMA), 26 to listOf(NR, LTE, G3, G2), 27 to listOf(NR, LTE, G3, G2, CDMA),
        28 to listOf(NR, LTE, G3), 29 to listOf(NR, LTE, G3), 30 to listOf(NR, LTE, G3, G2), 31 to listOf(NR, LTE, G3),
        32 to listOf(NR, LTE, G3, G2), 33 to listOf(NR, LTE, G3, G2, CDMA),
    )

    fun label(mode: Int): String {
        val t = TECH[mode] ?: return "種類 $mode"
        return t.joinToString("/") + if (t.size > 1) "（自動）" else "のみ"
    }

    fun hasNr(mode: Int): Boolean = TECH[mode]?.contains(NR) == true

    fun hasLte(mode: Int): Boolean = TECH[mode]?.contains(LTE) == true

    /** 設定値の文字列（「9」「9,9」など。複数の SIM スロット分がカンマで並ぶ機種がある）の最初の値 */
    fun parse(s: String?): Int? = s?.split(',')?.firstOrNull()?.trim()?.toIntOrNull()
}
