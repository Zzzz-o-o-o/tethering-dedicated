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

    // 第 1 段階（仕様 11.5 で決めること）
    val BATT_CAPACITY = IntDef(
        "battery_capacity_mah", "電池容量（0 = 自動で推定）", 0, 0, 30_000, "mAh",
        "充電カウンタに対応した端末では自動で推定します。推定できない端末では仕様上の容量を入れてください",
    )
    val RUNTIME_ALERT = IntDef("runtime_alert_min", "推定残り時間の通知（0 = 通知しない）", 60, 0, 720, "分", "バッテリー駆動中に、推定残り時間がこれを下回ったら通知します")
    val EXP_PHASE = IntDef("exp_phase_min", "比較テストの 1 条件あたりの計測時間", 30, 5, 240, "分", "バッテリー駆動中の時間だけを数えます")
    val EXP_REPEATS = IntDef("exp_repeats", "比較テストの繰り返し（A→B を何回）", 2, 1, 4, "回", "2 回以上なら、毎回 B が少ないときだけ「効果あり」にします")
    val EXP_MIN_GAIN = IntDef("exp_min_gain_pct", "「効果あり」とする最小の削減率", 3, 1, 50, "%")

    // 第 2 段階
    val SIGNAL_WEAK_RSRP = IntDef("signal_weak_rsrp", "電波が弱いとみなす RSRP", -110, -140, -80, "dBm", "定期チェックで 2 回続けてこれを下回ったら通知します")
    val SIGNAL_DROP_DB = IntDef(
        "signal_drop_db", "設置時の記録からの悪化（0 = 比べない）", 10, 0, 40, "dB",
        "設置場所アシストで「ここに設置した」場所の RSRP より、これ以上悪い状態が 2 回続いたら通知します",
    )
    val IDLE_TRAFFIC_MB = IntDef(
        "idle_traffic_mb_h", "接続端末がないときの通信（0 = 通知しない）", 5, 0, 1000, "MB/時",
        "テザリング OFF、またはチェック時点で接続 0 台なのに、これを超える通信があったら通知します（この端末自身のアプリ・OS の通信）",
    )
    val QUIET_TRAFFIC_MB = IntDef(
        "quiet_traffic_mb", "使わない時間帯の通信の合計（0 = 通知しない）", 50, 0, 100_000, "MB",
        "下の時間帯が終わったあと、その間の通信がこれを超えていたら知らせます（接続端末の自動更新・同期の発見用）",
    )
    val WEB_PORT = IntDef("web_port", "管理画面のポート", 8080, 1024, 65535, "", "接続端末のブラウザで http://（この端末のアクセスポイントのアドレス）:ポート/ を開きます")

    val SIGNAL_ALERT = BoolDef("signal_alert", "電波の悪化を通知する", true, "定期チェックのときに判定します（判定のために端末を起こしません）")
    val WEB = BoolDef("web_enabled", "ローカル管理画面（読み取り専用）", true, "テザリングのアクセスポイント側だけで待ち受けます。モバイル回線側からは見えません")

    // 第 3 段階
    val AUTO_SAVER_PCT = IntDef(
        "auto_saver_pct", "自動で ON にする電池残量（100 = バッテリー駆動中は常に）", 100, 5, 100, "%",
        "これ以下になったら ON にします（戻すのは 5% 上がったとき、または給電したとき）",
    )
    val IDLE_INTERVAL = IntDef(
        "idle_interval_min", "待機中のチェック間隔（0 = 使わない）", 180, 0, 720, "分",
        "バッテリー駆動中に、接続 0 台が 30 分続いたとき、または下の残量以下のとき。状態の変化はこれまでどおりすぐ確認します",
    )
    val IDLE_BATT = IntDef("idle_batt_pct", "待機にする電池残量", 30, 5, 100, "%")

    val AUTO_SAVER = BoolDef(
        "auto_saver", "バッテリー駆動中はバッテリーセーバーを自動で ON", false,
        "給電すると元に戻します。ADB で「設定変更の権限」を付けたときだけ動きます（データセーバー・機内モード・モバイルデータは変えません）",
    )
    val AUTO_DOZE = BoolDef(
        "auto_doze", "バッテリー駆動中は Doze（省電力待機）を早める", false,
        "画面消灯・静止から約 10 分で深い省電力待機に入るようにします（標準は約 1 時間）。給電すると元に戻します",
    )
    val SKIP_PROBE = BoolDef(
        "skip_probe_busy", "接続端末の通信が流れているときは到達確認を省く", true,
        "OS の接続検証が OK で、前回のチェックから 256 KB 以上受信していれば、DNS・HTTP の確認を省きます",
    )

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
    val QUIET_HOURS = TextDef(
        "quiet_hours", "使わない時間帯（例: 1-6。空欄 = 設定しない）", "",
        "0〜23 時で「開始-終了」。23-5 のように日をまたいでもかまいません", ::checkQuietHours,
    )

    val ints = listOf(
        INTERVAL_NORMAL, INTERVAL_SAVER, TIMEOUT, EXPECT_CODE, TEMP_WARN, TEMP_CRIT, THERMAL_WARN, BATT_LOW, BOOT_DELAY, LOG_DAYS, LOG_MB,
        BATT_CAPACITY, RUNTIME_ALERT, EXP_PHASE, EXP_REPEATS, EXP_MIN_GAIN,
        SIGNAL_WEAK_RSRP, SIGNAL_DROP_DB, IDLE_TRAFFIC_MB, QUIET_TRAFFIC_MB, WEB_PORT,
        AUTO_SAVER_PCT, IDLE_INTERVAL, IDLE_BATT,
    )
    val bools = listOf(HTTP, TETHER_EXPECTED, CLIENTS, NOTIFY_ALERT, NOTIFY_RECOVERY, NOTIFY_BOOT, SIGNAL_ALERT, WEB, AUTO_SAVER, AUTO_DOZE, SKIP_PROBE)
    val texts = listOf(URL, RECHECK, QUIET_HOURS)

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

    fun checkQuietHours(s: String): String? =
        if (s.isNotBlank() && QuietHours.parse(s) == null) "「開始-終了」の形で 0〜23 時を入れてください（例: 1-6）。空欄なら設定しません" else null
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
