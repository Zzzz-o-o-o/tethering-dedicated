package local.tethering.dedicated

import java.util.Locale

/**
 * 電力の記録の 1 行（1 区間）。区間はチェック・給電の変化・テザリングの ON/OFF・比較テストの切り替えで区切る。
 * 消費（mAh 以降）はバッテリー駆動中で、計算できた区間だけに入る。
 */
data class PowerRow(
    val start: String,
    val end: String,
    val sec: Long,
    val onBattery: Boolean,
    val method: Method?,
    val levelStart: Int?,
    val levelEnd: Int?,
    val mah: Double?,
    val avgMa: Double?,
    val avgMv: Int?,
    val avgMw: Double?,
    val tempC: Double?,
    val screenSec: Long,
    val tether: Boolean?,
    val clientsMax: Int?,
    val rsrp: Int?,
    val sinr: Double?,
    val band: String?,
    val mobileKb: Long?,
    val profile: String,
    val experiment: String?,
    val condition: String?,
) {
    fun fields(): List<String?> = listOf(
        start, end, sec.toString(), if (onBattery) BATTERY else POWERED, method?.label,
        levelStart?.toString(), levelEnd?.toString(), mah?.let { f(it, 1) }, avgMa?.let { f(it, 0) }, avgMv?.toString(),
        avgMw?.let { f(it, 0) }, tempC?.let { f(it, 1) }, screenSec.toString(), tether?.let { if (it) "ON" else "OFF" },
        clientsMax?.toString(), rsrp?.toString(), sinr?.let { f(it, 1) }, band, mobileKb?.toString(), profile, experiment, condition,
    )

    /** 区間の終わりの日付（yyyyMMdd） */
    val day: String get() = end.take(10).replace("-", "")

    companion object {
        const val BATTERY = "バッテリー駆動"
        const val POWERED = "給電中"

        val HEADER = listOf(
            "開始", "終了", "秒", "電源", "方法", "電池%開始", "電池%終了", "消費mAh", "平均電流mA", "平均電圧mV",
            "平均消費mW", "電池温度℃", "画面点灯秒", "テザリング", "接続端末最大", "RSRP(dBm)", "SINR(dB)", "バンド",
            "モバイル通信KB", "プロファイル", "比較テスト", "条件",
        )

        private fun f(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

        fun parse(c: List<String>): PowerRow? {
            if (c.size < HEADER.size) return null
            fun s(i: Int) = c[i].takeIf { it.isNotEmpty() }
            return PowerRow(
                start = c[0], end = c[1], sec = c[2].toLongOrNull() ?: return null, onBattery = c[3] == BATTERY,
                method = Method.entries.firstOrNull { it.label == c[4] }, levelStart = c[5].toIntOrNull(), levelEnd = c[6].toIntOrNull(),
                mah = c[7].toDoubleOrNull(), avgMa = c[8].toDoubleOrNull(), avgMv = c[9].toIntOrNull(), avgMw = c[10].toDoubleOrNull(),
                tempC = c[11].toDoubleOrNull(), screenSec = c[12].toLongOrNull() ?: 0, tether = s(13)?.let { it == "ON" },
                clientsMax = c[14].toIntOrNull(), rsrp = c[15].toIntOrNull(), sinr = c[16].toDoubleOrNull(), band = s(17),
                mobileKb = c[18].toLongOrNull(), profile = c[19], experiment = s(20), condition = s(21),
            )
        }
    }
}

/** 操作ログのうち、接続性チェックの行から日次レポートに使う値 */
data class CheckRow(val ok: Boolean, val tempC: Double?, val rsrp: Int?)

data class DailyReport(
    val day: String,
    val batterySec: Long,
    val poweredSec: Long,
    val measuredSec: Long,
    val avgMw: Double?,
    val avgMa: Double?,
    /** 満充電から、この日の平均ペースで使い続けた場合の稼働時間 */
    val fullRuntimeH: Double?,
    val mobileMb: Double?,
    val tetherSec: Long,
    val clientsMax: Int?,
    val screenSec: Long,
    val checks: Int,
    val checksOk: Int,
    val anomalies: Int,
    val tempMaxC: Double?,
    val rsrpAvg: Double?,
)

/** 日次レポートの集計（仕様 F-28）。Android に依存しない */
object Reports {
    fun daily(day: String, power: List<PowerRow>, checks: List<CheckRow>, anomalies: Int, capacityMah: Int?): DailyReport {
        val measured = power.filter { it.onBattery && it.avgMa != null && it.mah != null }
        val measuredSec = measured.sumOf { it.sec }
        val avgMa = if (measuredSec > 0) measured.sumOf { it.mah!! } / (measuredSec / 3600.0) else null
        val withMw = measured.filter { it.avgMw != null }
        val mwSec = withMw.sumOf { it.sec }
        // 電圧が読めた区間が大半のときだけ mW を出す
        val avgMw = if (mwSec > 0 && mwSec * 10 >= measuredSec * 9) withMw.sumOf { it.avgMw!! * it.sec } / mwSec else null
        val kb = power.mapNotNull { it.mobileKb }
        val rsrpRows = power.filter { it.rsrp != null && it.sec > 0 }
        val rsrpSec = rsrpRows.sumOf { it.sec }
        val temps = power.mapNotNull { it.tempC } + checks.mapNotNull { it.tempC }
        return DailyReport(
            day = day,
            batterySec = power.filter { it.onBattery }.sumOf { it.sec },
            poweredSec = power.filter { !it.onBattery }.sumOf { it.sec },
            measuredSec = measuredSec,
            avgMw = avgMw,
            avgMa = avgMa,
            fullRuntimeH = if (avgMa != null && capacityMah != null && capacityMah > 0) capacityMah / avgMa else null,
            mobileMb = if (kb.isEmpty()) null else kb.sum() / 1024.0,
            tetherSec = power.filter { it.tether == true }.sumOf { it.sec },
            clientsMax = power.mapNotNull { it.clientsMax }.maxOrNull(),
            screenSec = power.sumOf { it.screenSec },
            checks = checks.size,
            checksOk = checks.count { it.ok },
            anomalies = anomalies,
            tempMaxC = temps.maxOrNull(),
            rsrpAvg = if (rsrpSec > 0) rsrpRows.sumOf { it.rsrp!!.toDouble() * it.sec } / rsrpSec else null,
        )
    }

    val CSV_HEADER = listOf(
        "日付", "バッテリー駆動(時間)", "給電(時間)", "計測できた時間(時間)", "平均消費mW", "平均電流mA", "満充電からの推定稼働(時間)",
        "モバイル通信MB", "テザリングON(時間)", "接続端末最大", "画面点灯(分)", "チェック回数", "チェック成功", "異常(確定)",
        "電池温度最大℃", "RSRP平均(dBm)",
    )

    fun csvFields(r: DailyReport): List<String?> {
        fun h(sec: Long) = String.format(Locale.US, "%.2f", sec / 3600.0)
        fun f(v: Double?, d: Int) = v?.let { String.format(Locale.US, "%.${d}f", it) }
        return listOf(
            "${r.day.take(4)}-${r.day.substring(4, 6)}-${r.day.substring(6, 8)}", h(r.batterySec), h(r.poweredSec), h(r.measuredSec),
            f(r.avgMw, 0), f(r.avgMa, 0), f(r.fullRuntimeH, 1), f(r.mobileMb, 1), h(r.tetherSec), r.clientsMax?.toString(),
            String.format(Locale.US, "%.0f", r.screenSec / 60.0), r.checks.toString(), r.checksOk.toString(), r.anomalies.toString(),
            f(r.tempMaxC, 1), f(r.rsrpAvg, 0),
        )
    }
}
