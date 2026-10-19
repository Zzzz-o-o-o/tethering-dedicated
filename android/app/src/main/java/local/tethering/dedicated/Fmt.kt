package local.tethering.dedicated

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 画面・ログ・通知で共通に使う表示の言い回し */
object Fmt {
    /** 取得できない値の表示 */
    val NA: String get() = tr("取得不可", "N/A", "Indisponible")

    fun sim(s: Int?): String = when (s) {
        null -> NA
        0 -> tr("不明", "Unknown", "Inconnu")
        1 -> tr("SIMなし", "No SIM", "Pas de SIM")
        2 -> tr("PINロック", "PIN locked", "Verrouillée (PIN)")
        3 -> tr("PUKロック", "PUK locked", "Verrouillée (PUK)")
        4 -> tr("ネットワークロック", "Network locked", "Verrouillée (réseau)")
        5 -> tr("使用可", "Ready", "Prête")
        6 -> tr("準備中", "Not ready", "Pas prête")
        7 -> tr("無効", "Disabled", "Désactivée")
        8 -> tr("読み取りエラー", "Card error", "Erreur de carte")
        9 -> tr("制限あり", "Restricted", "Restreinte")
        else -> tr("状態$s", "State $s", "État $s")
    }

    fun voice(s: Int?): String = when (s) {
        null -> NA
        LineInfo.STATE_IN_SERVICE -> tr("圏内", "In service", "En service")
        LineInfo.STATE_OUT_OF_SERVICE -> tr("圏外", "No service", "Hors service")
        LineInfo.STATE_EMERGENCY_ONLY -> tr("緊急通報のみ", "Emergency calls only", "Appels d'urgence uniquement")
        LineInfo.STATE_POWER_OFF -> tr("電源OFF", "Radio off", "Radio désactivée")
        else -> tr("状態$s", "State $s", "État $s")
    }

    fun rat(t: Int?): String = when (t) {
        null -> NA
        0 -> tr("未登録", "Not registered", "Non enregistré")
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
        else -> tr("種別$t", "Type $t", "Type $t")
    }

    fun dataState(s: Int?): String = when (s) {
        null -> NA
        0 -> tr("切断", "Disconnected", "Déconnecté")
        1 -> tr("接続中", "Connecting", "Connexion en cours")
        2 -> tr("接続", "Connected", "Connecté")
        3 -> tr("一時停止", "Suspended", "Suspendu")
        4 -> tr("切断中", "Disconnecting", "Déconnexion en cours")
        else -> tr("状態$s", "State $s", "État $s")
    }

    fun ap(s: Int?): String = when (s) {
        null -> NA
        TetherInfo.AP_DISABLING -> tr("停止中", "Stopping", "Arrêt en cours")
        TetherInfo.AP_DISABLED -> "OFF"
        TetherInfo.AP_ENABLING -> tr("起動中", "Starting", "Démarrage en cours")
        TetherInfo.AP_ENABLED -> "ON"
        TetherInfo.AP_FAILED -> tr("起動失敗", "Failed to start", "Échec du démarrage")
        else -> tr("状態$s", "State $s", "État $s")
    }

    fun thermal(s: Int?): String = when (s) {
        null -> NA
        0 -> tr("正常", "Normal", "Normal")
        1 -> tr("軽度", "Light", "Léger")
        2 -> tr("中程度", "Moderate", "Modéré")
        3 -> tr("重度", "Severe", "Sévère")
        4 -> tr("危険", "Critical", "Critique")
        5 -> tr("緊急", "Emergency", "Urgence")
        6 -> tr("停止寸前", "About to shut down", "Arrêt imminent")
        else -> tr("状態$s", "State $s", "État $s")
    }

    fun temp(c: Double?): String = c?.let { dec(it) + tr("℃", "°C", " °C") } ?: NA

    fun num(v: Number?, unit: String = ""): String = v?.let { "$it$unit" } ?: NA

    /** 電波の点数（0〜100） */
    fun points(v: Int): String = tr("$v 点", "$v pts", "$v pts")

    /** 百分率（フランス語は % の前に空白） */
    fun pct(v: Number?): String = v?.let { tr("$it%", "$it%", "$it %") } ?: NA

    fun db(v: Double?): String = v?.let { dec(it) + " dB" } ?: NA

    /** 小数の表示（表示言語の書式。フランス語は小数点がカンマ）。ログ・CSV に書く数値には使わない */
    fun dec(v: Double, digits: Int = 1): String = String.format(I18n.locale, "%.${digits}f", v)

    /** 通信量の表示（[v] は MB 単位。フランス語は Mo） */
    fun mb(v: Double): String = dec(v) + tr(" MB", " MB", " Mo")

    /** 通信量の表示（[bytes] はバイト。1 GB = 1024 MB。フランス語は Go） */
    fun gb(bytes: Long): String = dec(bytes / (1024.0 * 1024.0 * 1024.0)) + tr(" GB", " GB", " Go")

    fun onOff(b: Boolean?, on: String = "ON", off: String = "OFF"): String = when (b) {
        null -> NA
        true -> on
        false -> off
    }

    fun tethered(list: List<String>?): String = when {
        list == null -> NA
        list.isEmpty() -> tr("なし", "None", "Aucun")
        else -> list.map { Ifaces.label(it) }.distinct().joinToString(tr("・", ", ", ", "))
    }

    fun time(ms: Long): String = if (ms <= 0) "－" else SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(ms))

    fun hm(ms: Long): String = if (ms <= 0) "－" else SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(ms))

    fun dateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date(ms))

    fun fileStamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(ms))

    /** 経過時間（約X分・X時間Y分） */
    fun duration(ms: Long): String {
        val min = (ms + 30_000) / 60_000
        return when {
            min < 1 -> tr("1分未満", "under 1 min", "moins d'1 min")
            min < 60 -> tr("約${min}分", "about $min min", "environ $min min")
            else -> tr("${min / 60}時間${min % 60}分", "${min / 60} h ${min % 60} min", "${min / 60} h ${min % 60} min")
        }
    }

    /** 「約◯」の経過時間（推定残り時間など） */
    fun approx(ms: Long): String {
        val d = duration(ms)
        if (ms < 30_000) return d
        return tr("約" + d.removePrefix("約"), "about " + d.removePrefix("about "), "environ " + d.removePrefix("environ "))
    }

    fun bandwidths(list: List<Double>?): String = when {
        list == null -> NA
        list.size == 1 -> fmtMhz(list[0])
        else -> list.joinToString("+") { fmtMhz(it) } + tr("（キャリアアグリゲーション）", " (carrier aggregation)", " (agrégation de porteuses)")
    }

    private fun fmtMhz(v: Double): String =
        if (v % 1.0 == 0.0) "${v.toInt()} MHz" else dec(v) + " MHz"

    /** 全角の英数字・記号と全角スペースを半角にする（入力欄用） */
    fun halfWidth(s: String): String = buildString(s.length) {
        for (ch in s) {
            append(
                when (ch.code) {
                    in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    0x3000 -> ' '
                    else -> ch
                },
            )
        }
    }

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

    /** 「・」で並べる（日本語）／「, 」（英語・フランス語） */
    fun join(items: List<String>): String = items.joinToString(tr("・", ", ", ", "))

    /** かっこ書き（日本語は全角、英語・フランス語は半角で前に空白） */
    fun paren(s: String): String = tr("（$s）", " ($s)", " ($s)")
}
