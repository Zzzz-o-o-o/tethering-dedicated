package local.tethering.dedicated

import java.net.URI

/** 数値の設定項目 */
class IntDef(
    val key: String,
    val label: String,
    val def: Int,
    val min: Int,
    val max: Int,
    val unit: String,
    val help: String = "",
)

/** ON/OFF の設定項目 */
class BoolDef(val key: String, val label: String, val def: Boolean, val help: String = "")

/** 文字列の設定項目。[check] は誤りの説明（正しければ null）を返す */
class TextDef(val key: String, val label: String, val def: String, val help: String, val check: (String) -> String?)

/**
 * 設定項目の一覧。設定画面・バックアップ／復元・入力チェックはすべてここを参照する。
 * 初期値は仕様「9. 実装前に決めること」への回答（README に根拠を記載）。
 */
object Defs {
    const val MODE_KEY = "mode"

    val INTERVAL_NORMAL = IntDef("interval_normal_min", "通常時のチェック間隔", 15, 5, 240, "分", "給電中（自動）または「通常」のとき")
    val INTERVAL_SAVER = IntDef("interval_saver_min", "省電力時のチェック間隔", 60, 15, 720, "分", "バッテリー駆動中（自動）または「省電力」のとき")
    val TIMEOUT = IntDef("timeout_sec", "チェック1段階あたりのタイムアウト", 10, 3, 60, "秒")
    val EXPECT_CODE = IntDef("expect_code", "到達確認で期待するHTTPステータス", 204, 100, 599, "", "generate_204 なら 204")
    val TEMP_WARN = IntDef("temp_warn_c", "電池温度の注意", 45, 30, 70, "℃")
    val TEMP_CRIT = IntDef("temp_crit_c", "電池温度の警告", 50, 30, 75, "℃", "注意より低いときは注意と同じ値として扱う")
    val THERMAL_WARN = IntDef("thermal_warn", "端末の熱状態の警告レベル", 2, 1, 6, "", "1=軽度 2=中程度 3=重度 4=危険 5=緊急 6=停止寸前")
    val BATT_LOW = IntDef("batt_low_pct", "電池残量の警告（バッテリー駆動中）", 20, 5, 50, "%")
    val BOOT_DELAY = IntDef("boot_delay_sec", "起動後チェックまでの待ち時間", 120, 30, 600, "秒", "回線とテザリングが落ち着くのを待つ")
    val LOG_DAYS = IntDef("log_days", "ログの保存期間", 30, 1, 365, "日")
    val LOG_MB = IntDef("log_mb", "ログの最大サイズ", 10, 1, 100, "MB")

    val HTTP = BoolDef("http_check", "外部サイトへの到達確認（HTTP）", true, "OFFにするとDNSまでの確認で判定する")
    val TETHER_EXPECTED = BoolDef("tether_expected", "Wi-Fiテザリングの停止を異常とする", true)
    val CLIENTS = BoolDef("count_clients", "接続端末数を数える（取得できる機種のみ）", true, "MACアドレスなどは記録せず、台数だけを数える")
    val NOTIFY_ALERT = BoolDef("notify_alert", "異常・警告を通知する", true)
    val NOTIFY_RECOVERY = BoolDef("notify_recovery", "復旧を通知する", true)
    val NOTIFY_BOOT = BoolDef("notify_boot", "起動後チェックの結果を通知する", true)

    val URL = TextDef(
        "check_url", "到達確認のURL", "http://connectivitycheck.gstatic.com/generate_204",
        "http または https。既定のURLは本文0バイトで通信量が最小", ::checkUrl,
    )
    val RECHECK = TextDef(
        "recheck_min", "異常時の再確認（分・カンマ区切り）", "1,3,5",
        "この順に再確認し、すべて異常なら通知する（1〜5回）", ::checkRecheck,
    )

    val ints = listOf(INTERVAL_NORMAL, INTERVAL_SAVER, TIMEOUT, EXPECT_CODE, TEMP_WARN, TEMP_CRIT, THERMAL_WARN, BATT_LOW, BOOT_DELAY, LOG_DAYS, LOG_MB)
    val bools = listOf(HTTP, TETHER_EXPECTED, CLIENTS, NOTIFY_ALERT, NOTIFY_RECOVERY, NOTIFY_BOOT)
    val texts = listOf(URL, RECHECK)

    fun checkUrl(s: String): String? {
        val uri = runCatching { URI(s.trim()) }.getOrNull() ?: return "URLの形式が正しくありません"
        if (uri.scheme != "http" && uri.scheme != "https") return "http:// または https:// で始めてください"
        if (uri.host.isNullOrEmpty()) return "ホスト名がありません"
        return null
    }

    fun parseRecheck(s: String): List<Int>? {
        val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 5) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums.takeIf { list -> list.all { it in 1..60 } }
    }

    fun checkRecheck(s: String): String? =
        if (parseRecheck(s) == null) "1〜60 の数字をカンマ区切りで1〜5個（例: 1,3,5）" else null
}

/** バックアップ（JSON）から読んだ値の検証。範囲外・型違いは採用せず、理由を返す */
object SettingsCodec {
    const val SCHEMA = 1

    class Parsed(val values: Map<String, Any>, val errors: List<String>, val unknown: List<String>)

    fun parse(raw: Map<String, Any?>): Parsed {
        val values = LinkedHashMap<String, Any>()
        val errors = ArrayList<String>()
        val unknown = ArrayList<String>()
        for ((k, v) in raw) {
            val i = Defs.ints.firstOrNull { it.key == k }
            val b = Defs.bools.firstOrNull { it.key == k }
            val t = Defs.texts.firstOrNull { it.key == k }
            when {
                k == Defs.MODE_KEY ->
                    if (v is String && BaseMode.entries.any { it.key == v }) values[k] = v
                    else errors.add("運用モード: 「$v」は使えない値です")
                i != null -> {
                    val n = toInt(v)
                    if (n == null || n !in i.min..i.max) errors.add("${i.label}: 「$v」は範囲外です（${i.min}〜${i.max}${i.unit}）")
                    else values[k] = n
                }
                b != null ->
                    if (v is Boolean) values[k] = v
                    else errors.add("${b.label}: 「$v」は true / false ではありません")
                t != null -> {
                    val reason = if (v is String) t.check(v.trim()) else "文字列ではありません"
                    if (v is String && reason == null) values[k] = v.trim()
                    else errors.add("${t.label}: $reason")
                }
                else -> unknown.add(k)
            }
        }
        return Parsed(values, errors, unknown)
    }

    private fun toInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is Number -> v.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /** 現在値と比べた変更点の説明（復元前の確認画面用） */
    fun describeChanges(current: Map<String, Any>, incoming: Map<String, Any>): List<String> {
        val out = ArrayList<String>()
        for ((k, v) in incoming) {
            val old = current[k]
            if (old == v) continue
            val label = when (k) {
                Defs.MODE_KEY -> "運用モード"
                else -> Defs.ints.firstOrNull { it.key == k }?.label
                    ?: Defs.bools.firstOrNull { it.key == k }?.label
                    ?: Defs.texts.firstOrNull { it.key == k }?.label
                    ?: k
            }
            out.add("$label: ${show(k, old)} → ${show(k, v)}")
        }
        return out
    }

    private fun show(key: String, v: Any?): String = when {
        v == null -> "（未設定）"
        key == Defs.MODE_KEY -> BaseMode.of(v as? String).label
        v is Boolean -> if (v) "ON" else "OFF"
        else -> v.toString()
    }
}
