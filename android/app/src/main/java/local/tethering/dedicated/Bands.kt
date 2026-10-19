package local.tethering.dedicated

import java.util.Locale

/** LTE の下り EARFCN からバンドと周波数を求める（3GPP TS 36.101 表 5.7.3-1） */
object Bands {
    private class Range(val band: Int, val fDlLowMhz: Double, val nOffs: Int, val last: Int)

    private val lte = listOf(
        Range(1, 2110.0, 0, 599),
        Range(2, 1930.0, 600, 1199),
        Range(3, 1805.0, 1200, 1949),
        Range(4, 2110.0, 1950, 2399),
        Range(5, 869.0, 2400, 2649),
        Range(7, 2620.0, 2750, 3449),
        Range(8, 925.0, 3450, 3799),
        Range(9, 1844.9, 3800, 4149),
        Range(10, 2110.0, 4150, 4749),
        Range(11, 1475.9, 4750, 4949),
        Range(12, 729.0, 5010, 5179),
        Range(13, 746.0, 5180, 5279),
        Range(14, 758.0, 5280, 5379),
        Range(17, 734.0, 5730, 5849),
        Range(18, 860.0, 5850, 5999),
        Range(19, 875.0, 6000, 6149),
        Range(20, 791.0, 6150, 6449),
        Range(21, 1495.9, 6450, 6599),
        Range(25, 1930.0, 8040, 8689),
        Range(26, 859.0, 8690, 9039),
        Range(28, 758.0, 9210, 9659),
        Range(32, 1452.0, 9920, 10359),
        Range(38, 2570.0, 37750, 38249),
        Range(39, 1880.0, 38250, 38649),
        Range(40, 2300.0, 38650, 39649),
        Range(41, 2496.0, 39650, 41589),
        Range(42, 3400.0, 41590, 43589),
        Range(43, 3600.0, 43590, 45589),
        Range(46, 5150.0, 46790, 54539),
        Range(66, 2110.0, 66436, 67335),
        Range(71, 617.0, 68586, 68935),
    )

    /** 国内での呼び方（例: B3 は「1.7GHz帯」） */
    private val japanNames = mapOf(
        1 to "2.1GHz",
        3 to "1.7GHz",
        8 to "900MHz",
        11 to "1.5GHz",
        18 to "800MHz",
        19 to "800MHz",
        21 to "1.5GHz",
        26 to "800MHz",
        28 to "700MHz",
        41 to "2.5GHz",
        42 to "3.5GHz",
    )

    data class LteBand(val band: Int, val dlMhz: Double) {
        val label: String
            get() {
                val name = japanNames[band] ?: String.format(Locale.US, "%.0fMHz", dlMhz)
                val spaced = name.replace("GHz", " GHz").replace("MHz", " MHz")
                return tr("B$band（${name}帯）", "B$band ($spaced)", "B$band (${spaced.replace('.', ',')})")
            }
    }

    /** 保存された表示名（言語によって形が違う）から、バンドの番号だけを取り出す（例: B41） */
    fun key(label: String?): String? = label?.let { Regex("""^B\d+""").find(it)?.value }

    fun lteBand(earfcn: Int): LteBand? {
        val r = lte.firstOrNull { earfcn in it.nOffs..it.last } ?: return null
        return LteBand(r.band, r.fDlLowMhz + 0.1 * (earfcn - r.nOffs))
    }
}
