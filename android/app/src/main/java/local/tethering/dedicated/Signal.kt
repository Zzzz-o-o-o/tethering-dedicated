package local.tethering.dedicated

import kotlin.math.roundToInt

/**
 * 電波の良し悪し（仕様 F-29）。RSRP（基地局からの電波の強さ）と SINR（雑音に対する品質）から 0〜100 の点数にする。
 * 電波が良いほど端末の送信電力が下がり、同じ通信量でも消費と発熱が減る。
 */
object SignalQuality {
    enum class Grade(private val tx: Tx) {
        EXCELLENT(Tx("とても良い", "Excellent", "Excellent")),
        GOOD(Tx("良い", "Good", "Bon")),
        FAIR(Tx("普通", "Fair", "Moyen")),
        POOR(Tx("弱い", "Weak", "Faible")),
        BAD(Tx("とても弱い", "Very weak", "Très faible"));

        val label: String get() = tx.get()
    }

    /** 点数（0〜100）。RSRP は −120→0・−80→1、SINR は −5→0・25→1 に直して 6:4 で合わせる。どちらもなければ null */
    fun score(rsrp: Int?, sinr: Double?): Int? {
        val r = rsrp?.let { (it + 120).coerceIn(0, 40) / 40.0 }
        val s = sinr?.let { (it + 5).coerceIn(0.0, 30.0) / 30.0 }
        val v = when {
            r != null && s != null -> 0.6 * r + 0.4 * s
            r != null -> r
            s != null -> s
            else -> return null
        }
        return (v * 100).roundToInt()
    }

    fun grade(score: Int): Grade = when {
        score >= 80 -> Grade.EXCELLENT
        score >= 60 -> Grade.GOOD
        score >= 40 -> Grade.FAIR
        score >= 20 -> Grade.POOR
        else -> Grade.BAD
    }

    /** 音の高さ（点数が高いほど高い音。0 点 = 300 Hz 〜 100 点 = 1500 Hz） */
    fun toneHz(score: Int): Int = 300 + score.coerceIn(0, 100) * 12
}

/**
 * 運用中の電波の悪化（仕様 F-29）。定期チェックのときだけ判定する（判定のために端末を起こさない）。
 * 2 回続けて悪いときに通知し、十分に良くなったら解除する（しきい値付近でばたつかないよう、解除側に余裕を持たせる）。
 */
class SignalWatch {
    /** [baselineRsrp] は「ここに設置した」場所の記録（なければ絶対値のしきい値だけで判定） */
    data class Limits(val enabled: Boolean, val weakRsrp: Int, val dropDb: Int, val baselineRsrp: Int?)

    var active = false
        private set
    private var streak = 0

    /** 通知するなら true、解除するなら false、変化がなければ null。RSRP が取れないときは判定しない（推測しない） */
    fun evaluate(rsrp: Int?, l: Limits): Boolean? {
        if (!l.enabled) {
            streak = 0
            if (!active) return null
            active = false
            return false
        }
        if (rsrp == null) return null
        if (isBad(rsrp, l)) {
            streak++
            if (active || streak < 2) return null
            active = true
            return true
        }
        streak = 0
        if (!active || !isRecovered(rsrp, l)) return null
        active = false
        return false
    }

    fun reset() {
        active = false
        streak = 0
    }

    companion object {
        fun dropped(rsrp: Int, l: Limits): Boolean = l.baselineRsrp != null && l.dropDb > 0 && rsrp <= l.baselineRsrp - l.dropDb

        fun isBad(rsrp: Int, l: Limits): Boolean = rsrp < l.weakRsrp || dropped(rsrp, l)

        fun isRecovered(rsrp: Int, l: Limits): Boolean =
            rsrp >= l.weakRsrp + 5 && (l.baselineRsrp == null || l.dropDb <= 0 || rsrp > l.baselineRsrp - l.dropDb + 3)
    }
}

/** 設置場所アシストで取った 1 回分の値 */
data class SignalSample(val rsrp: Int?, val sinr: Double?, val rsrq: Int?, val band: String?)

/** 設置場所の記録（仕様 F-29）。値は記録中に取れたサンプルの中央値（一瞬の揺れに引きずられないように） */
data class Place(
    val at: Long,
    val name: String,
    val samples: Int,
    val rsrp: Int?,
    val sinr: Double?,
    val rsrq: Int?,
    val band: String?,
    val score: Int?,
)

/** 設置場所の記録の集計と保存形式（Android に依存しない） */
object Places {
    const val MAX = 30
    const val MIN_SAMPLES = 5

    fun cleanName(s: String): String = s.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(30)

    fun summarize(name: String, at: Long, samples: List<SignalSample>): Place? {
        val ok = samples.filter { it.rsrp != null }
        if (ok.size < MIN_SAMPLES) return null
        val rsrp = median(ok.map { it.rsrp!!.toDouble() })?.roundToInt()
        val sinr = median(ok.mapNotNull { it.sinr })?.let { (it * 10).roundToInt() / 10.0 }
        val rsrq = median(ok.mapNotNull { it.rsrq?.toDouble() })?.roundToInt()
        val band = ok.mapNotNull { it.band }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return Place(at, cleanName(name).ifEmpty { tr("場所", "Spot", "Emplacement") }, ok.size, rsrp, sinr, rsrq, band, SignalQuality.score(rsrp, sinr))
    }

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** 点数のいちばん高い記録（おすすめの置き場所） */
    fun best(list: List<Place>): Place? = list.filter { it.score != null }.maxByOrNull { it.score!! }

    /** 1 行 1 件の CSV（名前の改行は取り除いてある） */
    fun encode(list: List<Place>): String = list.joinToString("\n") { p ->
        Csv.line(
            listOf(
                p.at.toString(), cleanName(p.name), p.samples.toString(), p.rsrp?.toString(), p.sinr?.toString(),
                p.rsrq?.toString(), p.band, p.score?.toString(),
            ),
        )
    }

    fun decode(s: String?): List<Place> = s.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val c = Csv.parseLine(line)
        if (c.size < 8) return@mapNotNull null
        Place(
            at = c[0].toLongOrNull() ?: return@mapNotNull null,
            name = c[1],
            samples = c[2].toIntOrNull() ?: 0,
            rsrp = c[3].toIntOrNull(),
            sinr = c[4].toDoubleOrNull(),
            rsrq = c[5].toIntOrNull(),
            band = c[6].takeIf { it.isNotEmpty() },
            score = c[7].toIntOrNull(),
        )
    }
}

/** 設置場所の記録の保存先（運用状態なので設定のバックアップには含めない） */
object PlaceStore {
    fun list(prefs: Prefs): List<Place> = Places.decode(prefs.placesText)

    fun add(prefs: Prefs, p: Place) {
        prefs.placesText = Places.encode((list(prefs) + p).takeLast(Places.MAX))
    }

    fun remove(prefs: Prefs, at: Long) {
        prefs.placesText = Places.encode(list(prefs).filter { it.at != at })
        if (prefs.placeBaselineAt == at) prefs.placeBaselineAt = 0L
    }

    /** 「ここに設置した」場所（電波の悪化を判定する基準） */
    fun baseline(prefs: Prefs): Place? = prefs.placeBaselineAt.takeIf { it > 0 }?.let { at -> list(prefs).firstOrNull { it.at == at } }

    fun setBaseline(prefs: Prefs, at: Long) {
        prefs.placeBaselineAt = at
    }
}
