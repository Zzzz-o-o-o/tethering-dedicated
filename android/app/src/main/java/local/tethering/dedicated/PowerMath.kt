package local.tethering.dedicated

import kotlin.math.abs
import kotlin.math.pow

/**
 * 消費電力の計算（仕様 F-25 / F-26）。Android に依存しないので PC 上の単体テストで確かめられる。
 *
 * 計測はバッテリー駆動中だけ行う（給電中は電池への出入りの差しか分からず、消費を測れないため）。
 * 方法の優先順位: ① 充電カウンタの減り（クーロンカウンタ。最も正確）② 電流の平均 ③ 電池残量（%）の変化。
 */
enum class Method(
    /** 電力の記録（CSV）に保存する値。言語によらず同じ（読み戻すため） */
    val key: String,
    private val tx: Tx,
) {
    COUNTER("充電カウンタ", Tx("充電カウンタ", "Charge counter", "Compteur de charge")),
    CURRENT("電流の平均", Tx("電流の平均", "Average current", "Courant moyen")),
    LEVEL("電池残量の変化", Tx("電池残量の変化", "Battery level change", "Variation du niveau de batterie"));

    val label: String get() = tx.get()
}

/** 区間の始まり・終わりの読み取り値（時刻は端末の経過時間 ms） */
data class Reading(
    val atMs: Long,
    val counterUah: Long?,
    val levelPct: Int?,
    val voltageMv: Int?,
    val onBattery: Boolean,
)

/** 電流の読み取り。向き（符号）は機種によって違うので大きさだけを使う */
data class CurrentSample(val atMs: Long, val ma: Int)

data class Consumption(
    val method: Method,
    val durationSec: Long,
    val mah: Double,
    val avgMa: Double,
    val avgMw: Double?,
)

object PowerMath {
    /** 短すぎる区間は誤差が大きいので計算しない */
    const val MIN_SEGMENT_SEC = 60L

    /** 平均 5 A を超える消費はありえないので、読み取りの誤りとみなす */
    private const val MAX_PLAUSIBLE_MA = 5000.0

    /** 充電カウンタを µAh にそろえる（mAh で返す機種がある）。未対応・ありえない値は null */
    fun normalizeCounter(raw: Int): Long? = when {
        raw == Int.MIN_VALUE || raw <= 0 -> null
        raw < 50_000 -> raw * 1000L
        else -> raw.toLong()
    }

    /**
     * 1 区間の消費。区間はバッテリー駆動のまま始まったもの（給電の変化で区間を区切るので途中で変わらない）。
     * 計算できなければ null（推測で埋めない）。
     */
    fun consumption(start: Reading, end: Reading, currents: List<CurrentSample>, capacityMah: Int?): Consumption? {
        val durMs = end.atMs - start.atMs
        val durSec = durMs / 1000
        if (!start.onBattery || durSec < MIN_SEGMENT_SEC) return null
        val hours = durMs / 3_600_000.0
        val volts = listOfNotNull(start.voltageMv, end.voltageMv).takeIf { it.isNotEmpty() }?.average()

        fun result(method: Method, mah: Double): Consumption? {
            val ma = mah / hours
            if (ma <= 0.0 || ma > MAX_PLAUSIBLE_MA) return null
            return Consumption(method, durSec, mah, ma, volts?.let { ma * it / 1000.0 })
        }

        val c0 = start.counterUah
        val c1 = end.counterUah
        // 増えた・変わらない場合は、カウンタが更新されない機種とみなして次の方法へ
        if (c0 != null && c1 != null && c0 > c1) {
            result(Method.COUNTER, (c0 - c1) / 1000.0)?.let { return it }
        }
        timeWeightedMa(currents, start.atMs, end.atMs)?.let { ma ->
            result(Method.CURRENT, ma * hours)?.let { return it }
        }
        val l0 = start.levelPct
        val l1 = end.levelPct
        if (l0 != null && l1 != null && capacityMah != null && capacityMah > 0 && l0 > l1) {
            result(Method.LEVEL, (l0 - l1) * capacityMah / 100.0)?.let { return it }
        }
        return null
    }

    /** 区間内の電流サンプルを時間で重み付けして平均する（2 点以上が必要） */
    fun timeWeightedMa(samples: List<CurrentSample>, fromMs: Long, toMs: Long): Double? {
        val s = samples.filter { it.atMs in fromMs..toMs }.sortedBy { it.atMs }
        if (s.size < 2) return null
        var area = 0.0
        var span = 0L
        for (i in 1 until s.size) {
            val dt = s[i].atMs - s[i - 1].atMs
            if (dt <= 0) continue
            area += (abs(s[i].ma) + abs(s[i - 1].ma)) / 2.0 * dt
            span += dt
        }
        return if (span > 0) area / span else null
    }

    /** 残量（mAh）。充電カウンタを優先し、なければ残量 % と容量から */
    fun remainingMah(counterUah: Long?, levelPct: Int?, capacityMah: Int?): Double? = when {
        counterUah != null -> counterUah / 1000.0
        levelPct != null && capacityMah != null && capacityMah > 0 -> levelPct * capacityMah / 100.0
        else -> null
    }

    /** 充電カウンタと残量 % から電池容量を推定する（残量が少ないと誤差が大きいので 20 % 以上のときだけ） */
    fun capacityFrom(counterUah: Long?, levelPct: Int?): Int? {
        if (counterUah == null || levelPct == null || levelPct < 20 || levelPct > 100) return null
        return (counterUah / 1000.0 / (levelPct / 100.0)).toInt().takeIf { it in 300..30_000 }
    }

    /**
     * 直近の消費ペース（mA）。新しい区間ほど重く扱う（半減期 30 分）。
     * 直近 2 時間に合計 10 分以上の計測がなければ null（推測で表示しない）。
     * [segments] は（区間の終わりの時刻 ms, 消費）。
     */
    fun recentRateMa(
        segments: List<Pair<Long, Consumption>>,
        nowMs: Long,
        windowMs: Long = 2 * 3_600_000L,
        halfLifeMs: Long = 30 * 60_000L,
        minSec: Long = 600,
    ): Double? {
        val recent = segments.filter { (endMs, _) -> nowMs - endMs in 0..windowMs }
        if (recent.sumOf { it.second.durationSec } < minSec) return null
        var wSum = 0.0
        var acc = 0.0
        for ((endMs, c) in recent) {
            val w = c.durationSec * 0.5.pow((nowMs - endMs).toDouble() / halfLifeMs)
            acc += c.avgMa * w
            wSum += w
        }
        return if (wSum > 0) acc / wSum else null
    }

    fun remainingMinutes(remainingMah: Double, rateMa: Double): Int? =
        if (rateMa <= 0.0) null else (remainingMah / rateMa * 60).toInt()
}
