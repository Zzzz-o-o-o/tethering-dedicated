package local.tethering.dedicated

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 画面・ログ・通知で共通に使う表示の言い回し */
object Fmt {
    const val NA = "取得不可"

    fun sim(s: Int?): String = when (s) {
        null -> NA
        0 -> "不明"
        1 -> "SIMなし"
        2 -> "PINロック"
        3 -> "PUKロック"
        4 -> "ネットワークロック"
        5 -> "使用可"
        6 -> "準備中"
        7 -> "無効"
        8 -> "読み取りエラー"
        9 -> "制限あり"
        else -> "状態$s"
    }

    fun voice(s: Int?): String = when (s) {
        null -> NA
        LineInfo.STATE_IN_SERVICE -> "圏内"
        LineInfo.STATE_OUT_OF_SERVICE -> "圏外"
        LineInfo.STATE_EMERGENCY_ONLY -> "緊急通報のみ"
        LineInfo.STATE_POWER_OFF -> "電源OFF"
        else -> "状態$s"
    }

    fun rat(t: Int?): String = when (t) {
        null -> NA
        0 -> "未登録"
        1 -> "GPRS"
        2 -> "EDGE"
        3 -> "UMTS"
        8 -> "HSDPA"
        9 -> "HSUPA"
        10 -> "HSPA"
        13 -> "LTE"
        15 -> "HSPA+"
        16 -> "GSM"
        17 -> "TD-SCDMA"
        18 -> "IWLAN"
        19 -> "LTE-CA"
        20 -> "5G"
        else -> "種別$t"
    }

    fun dataState(s: Int?): String = when (s) {
        null -> NA
        0 -> "切断"
        1 -> "接続中"
        2 -> "接続"
        3 -> "一時停止"
        4 -> "切断中"
        else -> "状態$s"
    }

    fun ap(s: Int?): String = when (s) {
        null -> NA
        TetherInfo.AP_DISABLING -> "停止中"
        TetherInfo.AP_DISABLED -> "OFF"
        TetherInfo.AP_ENABLING -> "起動中"
        TetherInfo.AP_ENABLED -> "ON"
        TetherInfo.AP_FAILED -> "起動失敗"
        else -> "状態$s"
    }

    fun thermal(s: Int?): String = when (s) {
        null -> NA
        0 -> "正常"
        1 -> "軽度"
        2 -> "中程度"
        3 -> "重度"
        4 -> "危険"
        5 -> "緊急"
        6 -> "停止寸前"
        else -> "状態$s"
    }

    fun temp(c: Double?): String = c?.let { String.format(Locale.US, "%.1f℃", it) } ?: NA

    fun num(v: Number?, unit: String = ""): String = v?.let { "$it$unit" } ?: NA

    fun db(v: Double?): String = v?.let { String.format(Locale.US, "%.1f dB", it) } ?: NA

    fun onOff(b: Boolean?, on: String = "ON", off: String = "OFF"): String = when (b) {
        null -> NA
        true -> on
        false -> off
    }

    fun tethered(list: List<String>?): String = when {
        list == null -> NA
        list.isEmpty() -> "なし"
        else -> list.map { Ifaces.label(it) }.distinct().joinToString("・")
    }

    fun time(ms: Long): String = if (ms <= 0) "－" else SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(ms))

    fun hm(ms: Long): String = if (ms <= 0) "－" else SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(ms))

    fun dateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date(ms))

    fun fileStamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(ms))

    /** 経過時間（約X分・X時間Y分） */
    fun duration(ms: Long): String {
        val min = (ms + 30_000) / 60_000
        return when {
            min < 1 -> "1分未満"
            min < 60 -> "約${min}分"
            else -> "${min / 60}時間${min % 60}分"
        }
    }

    fun bandwidths(list: List<Double>?): String = when {
        list == null -> NA
        list.size == 1 -> fmtMhz(list[0])
        else -> list.joinToString("+") { fmtMhz(it) } + "（キャリアアグリゲーション）"
    }

    private fun fmtMhz(v: Double): String =
        if (v % 1.0 == 0.0) "${v.toInt()} MHz" else String.format(Locale.US, "%.1f MHz", v)

    /** 電波の要約（例: LTE B3（1.7GHz帯） · RSRP −95 dBm） */
    fun signalSummary(l: LineInfo?): String {
        if (l == null) return NA
        val parts = ArrayList<String>()
        l.signalType?.let { parts.add(it) }
        l.band?.let { parts.add(it) }
        when {
            l.rsrp != null -> parts.add("RSRP ${l.rsrp} dBm")
            l.dbm != null -> parts.add("${l.dbm} dBm")
        }
        return if (parts.isEmpty()) NA else parts.joinToString(" · ")
    }
}
