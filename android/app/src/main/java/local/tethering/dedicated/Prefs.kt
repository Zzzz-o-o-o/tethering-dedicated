package local.tethering.dedicated

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定（バックアップ対象）と運用状態（対象外）の保存先。
 * 値は読むたびに範囲へ丸めるので、壊れた値が残っていても動作がおかしくならない。
 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state: SharedPreferences = app.getSharedPreferences("state", Context.MODE_PRIVATE)

    fun int(d: IntDef): Int = sp.getInt(d.key, d.def).coerceIn(d.min, d.max)
    fun bool(d: BoolDef): Boolean = sp.getBoolean(d.key, d.def)
    fun text(d: TextDef): String = sp.getString(d.key, null)?.takeIf { d.check(it) == null } ?: d.def

    fun put(d: IntDef, v: Int) = sp.edit().putInt(d.key, v.coerceIn(d.min, d.max)).apply()
    fun put(d: BoolDef, v: Boolean) {
        sp.edit().putBoolean(d.key, v).apply()
        if (d == Defs.TETHER_ON_BOOT) syncBootTether()
    }
    fun put(d: TextDef, v: String) {
        if (d.check(v) == null) sp.edit().putString(d.key, v.trim()).apply()
    }

    var mode: BaseMode
        get() = BaseMode.of(sp.getString(Defs.MODE_KEY, null))
        set(v) = sp.edit().putString(Defs.MODE_KEY, v.key).apply()

    val recheckDelaysMin: List<Int> get() = Defs.parseRecheck(text(Defs.RECHECK)) ?: listOf(1, 3, 5)

    val limits: AlertTracker.Limits
        get() = AlertTracker.Limits(int(Defs.TEMP_WARN), int(Defs.TEMP_CRIT), int(Defs.THERMAL_WARN), int(Defs.BATT_LOW), int(Defs.RUNTIME_ALERT))

    /** 比較テストの状態（JSON）と結果の履歴。運用状態なのでバックアップの対象外 */
    var experimentJson: String?
        get() = state.getString("experiment", null)
        set(v) = state.edit().apply { if (v == null) remove("experiment") else putString("experiment", v) }.apply()

    var experimentHistoryJson: String?
        get() = state.getString("experiment_history", null)
        set(v) = state.edit().putString("experiment_history", v).apply()

    /** 設置場所の記録（仕様 F-29）。1 行 1 件 */
    var placesText: String?
        get() = state.getString("places", null)
        set(v) = state.edit().putString("places", v).apply()

    /** 「ここに設置した」場所の記録時刻（0 = 未設定） */
    var placeBaselineAt: Long
        get() = state.getLong("place_baseline", 0L)
        set(v) = state.edit().putLong("place_baseline", v).apply()

    /** 待機中の通信（仕様 F-33）を最後に通知した時刻（再起動しても通知が続けて出ないように残す） */
    var trafficIdleAlertAt: Long
        get() = state.getLong("traffic_idle_alert_at", 0L)
        set(v) = state.edit().putLong("traffic_idle_alert_at", v).apply()

    // 自動切り替え（仕様 F-42）。いま自動で変えているかと、変える前の値。
    // OS の設定を変えた直後にアプリが止まっても元に戻せるよう、すぐにファイルへ書く（commit）
    @set:SuppressLint("ApplySharedPref")
    var autoSaverApplied: Boolean
        get() = state.getBoolean("auto_saver_applied", false)
        set(v) {
            state.edit().putBoolean("auto_saver_applied", v).commit()
        }

    @set:SuppressLint("ApplySharedPref")
    var autoSaverOriginal: String?
        get() = state.getString("auto_saver_original", null)
        set(v) {
            state.edit().putString("auto_saver_original", v).commit()
        }

    @set:SuppressLint("ApplySharedPref")
    var autoDozeApplied: Boolean
        get() = state.getBoolean("auto_doze_applied", false)
        set(v) {
            state.edit().putBoolean("auto_doze_applied", v).commit()
        }

    @set:SuppressLint("ApplySharedPref")
    var autoDozeOriginal: String?
        get() = state.getString("auto_doze_original", null)
        set(v) {
            state.edit().putString("auto_doze_original", v).commit()
        }

    // 時間帯スケジュール（仕様 F-37）。このアプリがテザリングを止めた時間帯の開始（0 = 止めていない）。
    // 止めた直後にアプリが止まっても、次の起動で再開できるよう、すぐにファイルへ書く（commit）
    @set:SuppressLint("ApplySharedPref")
    var schedStoppedWindow: Long
        get() = state.getLong("sched_stopped_window", 0L)
        set(v) {
            state.edit().putLong("sched_stopped_window", v).commit()
        }

    /** テザリングを止めた時刻（停止していた時間の記録用） */
    var schedStoppedAt: Long
        get() = state.getLong("sched_stopped_at", 0L)
        set(v) = state.edit().putLong("sched_stopped_at", v).apply()

    /** 止める判断を済ませた時間帯の開始（同じ時間帯に 2 回止めない） */
    var schedHandledWindow: Long
        get() = state.getLong("sched_handled_window", 0L)
        set(v) = state.edit().putLong("sched_handled_window", v).apply()

    // テザリングの連続稼働時間（v0.9.0）。アプリが更新・再起動しても続けて数えられるよう保存する
    /** アクセスポイントが ON になった時刻（0 = OFF・不明） */
    var apOnSince: Long
        get() = state.getLong("ap_on_since", 0L)
        set(v) = state.edit().putLong("ap_on_since", v).apply()

    /** [apOnSince] が ON になった時刻そのものか（false なら、監視を始めた時刻で、それより前から ON） */
    var apOnExact: Boolean
        get() = state.getBoolean("ap_on_exact", false)
        set(v) = state.edit().putBoolean("ap_on_exact", v).apply()

    /** アクセスポイントが OFF になった時刻（0 = ON・不明） */
    var apOffAt: Long
        get() = state.getLong("ap_off_at", 0L)
        set(v) = state.edit().putLong("ap_off_at", v).apply()

    /** 前回の停止の始まりと長さ（0 = なし・不明） */
    var apLastOffStart: Long
        get() = state.getLong("ap_last_off_start", 0L)
        set(v) = state.edit().putLong("ap_last_off_start", v).apply()

    var apLastOffMs: Long
        get() = state.getLong("ap_last_off_ms", 0L)
        set(v) = state.edit().putLong("ap_last_off_ms", v).apply()

    // 月の通信量の上限（v0.8.0）。知らせた段階は、記録した期間の開始（capCycle）と組で使う
    var capCycle: Long
        get() = state.getLong("cap_cycle", 0L)
        set(v) = state.edit().putLong("cap_cycle", v).apply()

    var capNotified: Int
        get() = state.getInt("cap_notified", 0)
        set(v) = state.edit().putInt("cap_notified", v).apply()

    /** このアプリが上限でテザリングを止めた期間の開始（0 = 止めていない）。止めた直後にアプリが止まっても再開できるよう、すぐ書く */
    @set:SuppressLint("ApplySharedPref")
    var capStoppedCycle: Long
        get() = state.getLong("cap_stopped_cycle", 0L)
        set(v) {
            state.edit().putLong("cap_stopped_cycle", v).commit()
        }

    var capStoppedAt: Long
        get() = state.getLong("cap_stopped_at", 0L)
        set(v) = state.edit().putLong("cap_stopped_at", v).apply()

    /** 止める判断を済ませた期間の開始（同じ期間に 2 回止めない） */
    var capHandledCycle: Long
        get() = state.getLong("cap_handled_cycle", 0L)
        set(v) = state.edit().putLong("cap_handled_cycle", v).apply()

    /** 外部への通知（仕様 F-44）で送れなかったもの（JSON） */
    var extQueueJson: String?
        get() = state.getString("ext_queue", null)
        set(v) = state.edit().putString("ext_queue", v).apply()

    /** スマートプラグ（仕様 F-45）の給電の目標（「1:LOW」など。null = なし） */
    @set:SuppressLint("ApplySharedPref")
    var plugWant: String?
        get() = state.getString("plug_want", null)
        set(v) {
            state.edit().apply { if (v == null) remove("plug_want") else putString("plug_want", v) }.commit()
        }

    // ---- 運用状態（バックアップ・初期化の対象外） ----

    /** 監視を続けるか（利用者が「停止」したら false。再起動後に自動で始めるかもこれで決まる） */
    var monitoring: Boolean
        get() = state.getBoolean("monitoring", true)
        set(v) = state.edit().putBoolean("monitoring", v).apply()

    /** メンテナンスモードの終了時刻（0 なら OFF） */
    var maintUntil: Long
        get() = state.getLong("maint_until", 0L)
        set(v) = state.edit().putLong("maint_until", v).apply()

    var nextCheckAt: Long
        get() = state.getLong("next_check_at", 0L)
        set(v) = state.edit().putLong("next_check_at", v).apply()

    var lastBootCheckAt: Long
        get() = state.getLong("last_boot_check_at", 0L)
        set(v) = state.edit().putLong("last_boot_check_at", v).apply()

    fun maintenanceActive(now: Long = System.currentTimeMillis()): Boolean = maintUntil > now

    // ---- バックアップ・復元（仕様 F-21 / F-23） ----

    /** 現在の設定値（既定値のままの項目も含む） */
    fun values(): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m[Defs.MODE_KEY] = mode.key
        Defs.ints.forEach { m[it.key] = int(it) }
        Defs.bools.forEach { m[it.key] = bool(it) }
        Defs.texts.forEach { m[it.key] = text(it) }
        return m
    }

    fun apply(values: Map<String, Any>) {
        val e = sp.edit()
        for ((k, v) in values) {
            when (v) {
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.apply()
        syncBootTether()
    }

    fun resetToDefaults() {
        sp.edit().clear().apply()
        syncBootTether()
    }

    /** 起動時のテザリング ON は、ロック解除前でも読める写しを使う（BootTether）。設定を変えたらすぐ写す */
    private fun syncBootTether() = BootTether.sync(app, bool(Defs.TETHER_ON_BOOT))

    /** [includeSecrets] は端末内の巻き戻し用だけ true（ファイルに書き出す設定には、通知先の URL などを含めない） */
    fun toJson(includeSecrets: Boolean = false): JSONObject {
        val settings = JSONObject()
        val secret = Defs.secretKeys
        values().forEach { (k, v) -> if (includeSecrets || k !in secret) settings.put(k, v) }
        return JSONObject()
            .put("app", app.packageName)
            .put("schema", SettingsCodec.SCHEMA)
            .put("version", BuildConfig.VERSION_NAME)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            .put("settings", settings)
    }

    companion object {
        /** バックアップファイルを読む。形式が違えば理由つきで例外 */
        fun readBackup(json: JSONObject, packageName: String): Map<String, Any?> {
            val appId = json.optString("app")
            require(appId.isEmpty() || appId == packageName) {
                tr("別のアプリのバックアップです（$appId）", "This is a backup of another app ($appId)", "Sauvegarde d'une autre application ($appId)")
            }
            val schema = json.optInt("schema", 0)
            require(schema in 1..SettingsCodec.SCHEMA) {
                tr("対応していない形式です（schema=$schema）", "Unsupported format (schema=$schema)", "Format non pris en charge (schema=$schema)")
            }
            val s = json.optJSONObject("settings")
                ?: throw IllegalArgumentException(tr("settings がありません", "No \"settings\" found", "« settings » introuvable"))
            val out = LinkedHashMap<String, Any?>()
            s.keys().forEach { k -> out[k] = s.get(k) }
            return out
        }
    }
}
