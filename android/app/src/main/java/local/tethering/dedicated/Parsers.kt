package local.tethering.dedicated

/** 端末から読んだ生の値の解釈（Android に依存しないので PC 上の単体テストで確認できる） */
object Parsers {

    /**
     * 近隣テーブルの 1 件（IP アドレスごと。v0.10.1。切断した端末を数えないための確かめ（[NeighborCheck]）に使う）。
     * [state] は ip neigh の表記（REACHABLE・STALE・DELAY・PROBE・FAILED など）。/proc/net/arp は状態が分からないので、
     * 解決済みは [NeighborCheck.VALID]、固定は PERMANENT、未解決は FAILED
     */
    data class Entry(val ip: String, val mac: String, val state: String)

    /**
     * `ip neigh show` の出力（netlink も同じ形の行にする）から、指定インターフェースの MAC アドレスのあるエントリを読む。
     * 例: `192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:ff REACHABLE`（FAILED・INCOMPLETE の行には、ふつう MAC がない）
     */
    fun entries(output: String, iface: String?): List<Entry> {
        val list = ArrayList<Entry>()
        for (raw in output.lines()) {
            val c = raw.trim().split(Regex("\\s+"))
            val devIdx = c.indexOf("dev")
            val macIdx = c.indexOf("lladdr")
            if (devIdx < 0 || devIdx + 1 >= c.size || macIdx < 0 || macIdx + 1 >= c.size) continue
            if (!matchesIface(c[devIdx + 1], iface)) continue
            list.add(Entry(c[0], c[macIdx + 1].lowercase(), c.last().uppercase()))
        }
        return list
    }

    /**
     * /proc/net/arp から読む。形式: `IP address  HW type  Flags  HW address  Mask  Device`。
     * Flags 0x0 は未解決、0x4 の付いたものは固定、ほかは解決済み（STALE か REACHABLE かは分からない）。00:00:00:00:00:00 は除く
     */
    fun arpEntries(lines: List<String>, iface: String?): List<Entry> {
        val list = ArrayList<Entry>()
        for (line in lines.drop(1)) {
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 6) continue
            val mac = c[3].lowercase()
            if (mac == "00:00:00:00:00:00" || !matchesIface(c[5], iface)) continue
            val flags = c[2].removePrefix("0x").toIntOrNull(16) ?: 0
            val state = when {
                flags == 0 -> "FAILED"
                flags and 0x4 != 0 -> "PERMANENT"
                else -> NeighborCheck.VALID
            }
            list.add(Entry(c[0], mac, state))
        }
        return list
    }

    /**
     * /proc/net/arp から、確かめずに数える（Flags 0x0 は除き、MAC アドレスで重複を除く。MAC 自体は保存しない）。
     * [exclude] の IP アドレス（常時つながるスマートプラグなど）は数えない
     */
    fun countArp(lines: List<String>, iface: String?, exclude: Set<String> = emptySet()): Int =
        arpEntries(lines, iface).filter { it.state != "FAILED" && it.ip !in exclude }.map { it.mac }.toSet().size

    /** `ip neigh show` の出力から、確かめずに数える。FAILED / INCOMPLETE と、[exclude] の IP アドレスは数えない */
    fun countNeigh(output: String, iface: String?, exclude: Set<String> = emptySet()): Int =
        entries(output, iface).filter { it.state != "FAILED" && it.state != "INCOMPLETE" && it.ip !in exclude }.map { it.mac }.toSet().size

    private fun matchesIface(dev: String, iface: String?): Boolean =
        if (iface != null) dev == iface else Ifaces.isWifi(dev)

    /** 接続端末の一覧の 1 台（v0.9.0。画面に出すだけで、記録しない）。[ip] は IPv4 があればそれ */
    data class Neighbor(val ip: String, val mac: String, val state: State) {
        enum class State {
            /** 最近やりとりがあった */
            REACHABLE,

            /** しばらくやりとりがない（v0.10.1 からは、答えるか確かめている途中。答えればつながっている、答えなければ一覧から消える） */
            IDLE,

            /** 応答がない（離れた） */
            GONE,
        }
    }

    /**
     * エントリを MAC アドレスごとにまとめる（IPv4 と IPv6 の行は同じ端末）。状態はいちばん良いもの（REACHABLE > IDLE > GONE）。
     * INCOMPLETE（解決前）は除く。/proc/net/arp の解決済みは REACHABLE、未解決は GONE（状態が分からないため）。
     * IPv6 のエントリしかなく、最近の確認もない端末は出さない（v0.10.1。確かめるのは IPv4 だけなので、切断した端末の
     * IPv6 の古いエントリが「確認中」のまま残らないように。数えるときと同じ）
     */
    fun neighborsOf(entries: List<Entry>): List<Neighbor> {
        val v4 = entries.filter { !it.ip.contains(':') && it.state != "INCOMPLETE" }.map { it.mac }.toSet()
        val byMac = LinkedHashMap<String, Neighbor>()
        for (e in entries) {
            val state = when (e.state) {
                "INCOMPLETE" -> continue
                "REACHABLE", "PERMANENT", "NOARP", NeighborCheck.VALID -> Neighbor.State.REACHABLE
                "FAILED" -> Neighbor.State.GONE
                else -> Neighbor.State.IDLE
            }
            if (state != Neighbor.State.REACHABLE && e.mac !in v4) continue
            addNeighbor(byMac, Neighbor(e.ip, e.mac, state))
        }
        return byMac.values.toList()
    }

    /** `ip neigh show` の出力から、指定インターフェースの端末を MAC アドレスごとにまとめる（[neighborsOf]） */
    fun neighbors(output: String, iface: String?): List<Neighbor> = neighborsOf(entries(output, iface))

    /** /proc/net/arp から（[neighborsOf]） */
    fun arpNeighbors(lines: List<String>, iface: String?): List<Neighbor> = neighborsOf(arpEntries(lines, iface))

    private fun addNeighbor(byMac: MutableMap<String, Neighbor>, n: Neighbor) {
        val old = byMac[n.mac]
        val v4 = !n.ip.contains(':')
        byMac[n.mac] = if (old == null) {
            n
        } else {
            // 表示には IPv4 の住所を使い、状態は良いほうをとる
            val ip = if (v4 && old.ip.contains(':')) n.ip else old.ip
            Neighbor(ip, n.mac, if (n.state.ordinal < old.state.ordinal) n.state else old.state)
        }
    }

    /** thermal_zone の temp を ℃ にする（ミリ度・0.1 度・度の機種がある）。ありえない値は null */
    fun thermalC(raw: Long): Double? {
        val c = when {
            raw >= 1000 || raw <= -1000 -> raw / 1000.0
            raw >= 200 || raw <= -200 -> raw / 10.0
            else -> raw.toDouble()
        }
        return c.takeIf { it in -20.0..150.0 }
    }

    /** 温度センサーのうち SoC（CPU）のものか */
    fun isSocZone(type: String): Boolean {
        val t = type.lowercase()
        if (t.contains("batt") || t.contains("charger") || t.contains("usb") || t.contains("pa_") || t.contains("wifi")) {
            return false
        }
        return t.contains("cpu") || t.contains("soc") || t.contains("tsens") || t.contains("mtktscpu")
    }

    /**
     * BATTERY_PROPERTY_CURRENT_NOW を mA にする。仕様はマイクロアンペアだが、mA で返す機種もあるため、
     * 絶対値が 1 万未満なら mA とみなす。Int.MIN_VALUE と 0 は未対応として null。
     */
    fun currentMa(raw: Int): Int? = when {
        raw == Int.MIN_VALUE || raw == 0 -> null
        kotlin.math.abs(raw) >= 10_000 -> raw / 1000
        else -> raw
    }

    /** LTE の SINR（RSSNR）。Android 10 は 0.1dB 単位、Android 11 以降は dB 単位で返る */
    fun lteSinrDb(raw: Int, sdkInt: Int): Double? {
        if (raw == Int.MAX_VALUE) return null
        return if (sdkInt <= 29) {
            if (raw in -200..300) raw / 10.0 else null
        } else {
            if (raw in -20..30) raw.toDouble() else null
        }
    }

    /** 値が有効範囲内なら返す（CellInfo.UNAVAILABLE = Int.MAX_VALUE は範囲外） */
    fun inRange(v: Int, range: IntRange): Int? = v.takeIf { it in range }
}
