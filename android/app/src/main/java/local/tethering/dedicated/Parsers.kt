package local.tethering.dedicated

/** 端末から読んだ生の値の解釈（Android に依存しないので PC 上の単体テストで確認できる） */
object Parsers {

    /**
     * /proc/net/arp から、指定インターフェースにつながっている端末の数を数える。
     * 形式: `IP address  HW type  Flags  HW address  Mask  Device`。
     * Flags 0x0（未解決）と 00:00:00:00:00:00 は除き、MAC アドレスで重複を除く（MAC 自体は保存しない）。
     */
    fun countArp(lines: List<String>, iface: String?): Int {
        val macs = HashSet<String>()
        for (line in lines.drop(1)) {
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 6) continue
            val flags = c[2]
            val mac = c[3].lowercase()
            val dev = c[5]
            if (flags == "0x0" || mac == "00:00:00:00:00:00") continue
            if (!matchesIface(dev, iface)) continue
            macs.add(mac)
        }
        return macs.size
    }

    /**
     * `ip neigh show` の出力から数える。
     * 例: `192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:ff REACHABLE`
     * FAILED / INCOMPLETE は数えない。
     */
    fun countNeigh(output: String, iface: String?): Int {
        val macs = HashSet<String>()
        for (raw in output.lines()) {
            val c = raw.trim().split(Regex("\\s+"))
            val devIdx = c.indexOf("dev")
            val macIdx = c.indexOf("lladdr")
            if (devIdx < 0 || devIdx + 1 >= c.size || macIdx < 0 || macIdx + 1 >= c.size) continue
            val state = c.last().uppercase()
            if (state == "FAILED" || state == "INCOMPLETE") continue
            if (!matchesIface(c[devIdx + 1], iface)) continue
            macs.add(c[macIdx + 1].lowercase())
        }
        return macs.size
    }

    private fun matchesIface(dev: String, iface: String?): Boolean =
        if (iface != null) dev == iface else Ifaces.isWifi(dev)

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
