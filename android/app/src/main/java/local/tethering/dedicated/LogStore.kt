package local.tethering.dedicated

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ログに書くイベント名。保存する値なので言語によらず同じ（直近の記録・日次レポートが読み戻す）。
 * 画面・書き出しでは [display] で今の言語にする
 */
object Ev {
    const val START = "監視開始"
    const val STOP = "監視停止"
    const val BOOT = "端末起動"
    const val SUSPECT = "異常の疑い"
    const val RECHECK_BAD = "再確認でも異常"
    const val CONFIRMED = "異常を確定"
    const val CONTINUING = "異常が継続"
    const val RECOVERED = "復旧"
    const val BLIP = "一時的な異常（自然回復）"
    const val TETHER = "テザリング状態"
    const val POWER = "電源の変化"
    const val PROFILE = "プロファイル変更"
    const val ALERT = "警告"
    const val ALERT_CLEAR = "警告の解除"
    const val MAINT_ON = "メンテナンス開始"
    const val MAINT_OFF = "メンテナンス終了"
    const val SETTINGS = "設定変更"
    const val RESTORE = "設定の復元"
    const val BOOT_CHECK = "起動後チェック"
    const val EXPORT = "ログ出力"
    const val EXP_START = "比較テスト開始"
    const val EXP_SWITCH = "比較テストの切り替え"
    const val EXP_END = "比較テスト終了"
    const val PLACE = "設置場所"
    const val TRAFFIC = "待機中の通信"
    const val WEB = "管理画面"
    const val AUTO = "自動切り替え"
    const val SCHEDULE = "時間帯スケジュール"
    const val PLUG = "スマートプラグ"
    const val EXT = "外部への通知"
    const val DATA_CAP = "通信量の上限"

    /** 画面の「直近の記録」に出すもの（定期チェックの行は出さない） */
    val recentKinds = setOf(
        START, STOP, BOOT, SUSPECT, RECHECK_BAD, CONFIRMED, CONTINUING, RECOVERED, BLIP, TETHER,
        POWER, PROFILE, ALERT, ALERT_CLEAR, MAINT_ON, MAINT_OFF, SETTINGS, RESTORE, BOOT_CHECK,
        EXP_START, EXP_SWITCH, EXP_END, PLACE, TRAFFIC, WEB, AUTO, SCHEDULE, PLUG, EXT, DATA_CAP,
    )

    /** 接続性チェックの行（日次レポートの稼働率に使う） */
    val checkKinds: Set<String> get() = Trigger.entries.map { it.key }.toSet()

    /** テザリングの停止を通知した行の「処置」（日次レポートが異常の回数に数える。どの言語で書いた行も見分ける） */
    val NOTIFIED = Tx("通知", "Notified", "Notifié")

    private val names: Map<String, Tx> by lazy {
        mapOf(
            START to Tx(START, "Monitoring started", "Surveillance démarrée"),
            STOP to Tx(STOP, "Monitoring stopped", "Surveillance arrêtée"),
            BOOT to Tx(BOOT, "Phone started", "Démarrage du téléphone"),
            SUSPECT to Tx(SUSPECT, "Suspected problem", "Problème suspecté"),
            RECHECK_BAD to Tx(RECHECK_BAD, "Still failing on recheck", "Échec à la revérification"),
            CONFIRMED to Tx(CONFIRMED, "Problem confirmed", "Problème confirmé"),
            CONTINUING to Tx(CONTINUING, "Problem continues", "Le problème persiste"),
            RECOVERED to Tx(RECOVERED, "Recovered", "Rétabli"),
            BLIP to Tx(BLIP, "Brief problem (recovered by itself)", "Problème passager (rétabli seul)"),
            TETHER to Tx(TETHER, "Hotspot state", "État du point d'accès"),
            POWER to Tx(POWER, "Power changed", "Changement d'alimentation"),
            PROFILE to Tx(PROFILE, "Profile changed", "Changement de profil"),
            ALERT to Tx(ALERT, "Warning", "Avertissement"),
            ALERT_CLEAR to Tx(ALERT_CLEAR, "Warning cleared", "Avertissement levé"),
            MAINT_ON to Tx(MAINT_ON, "Maintenance started", "Maintenance démarrée"),
            MAINT_OFF to Tx(MAINT_OFF, "Maintenance ended", "Maintenance terminée"),
            SETTINGS to Tx(SETTINGS, "Settings changed", "Réglages modifiés"),
            RESTORE to Tx(RESTORE, "Settings restored", "Réglages restaurés"),
            BOOT_CHECK to Tx(BOOT_CHECK, "After-reboot check", "Vérification après redémarrage"),
            EXPORT to Tx(EXPORT, "Log exported", "Journal exporté"),
            EXP_START to Tx(EXP_START, "Comparison test started", "Test comparatif démarré"),
            EXP_SWITCH to Tx(EXP_SWITCH, "Comparison test switch", "Bascule du test comparatif"),
            EXP_END to Tx(EXP_END, "Comparison test ended", "Test comparatif terminé"),
            PLACE to Tx(PLACE, "Placement", "Emplacement"),
            TRAFFIC to Tx(TRAFFIC, "Idle traffic", "Trafic au repos"),
            WEB to Tx(WEB, "Local web page", "Page web locale"),
            AUTO to Tx(AUTO, "Automatic switching", "Bascule automatique"),
            SCHEDULE to Tx(SCHEDULE, "Quiet-hours schedule", "Planification"),
            PLUG to Tx(PLUG, "Smart plug", "Prise connectée"),
            EXT to Tx(EXT, "External notification", "Notification externe"),
            DATA_CAP to Tx(DATA_CAP, "Data cap", "Plafond de données"),
        )
    }

    /** 保存されたイベント名を、今の言語の表示にする（知らない名前はそのまま） */
    fun display(kind: String): String {
        Trigger.entries.firstOrNull { it.key == kind }?.let { return it.label }
        return names[kind]?.get() ?: kind
    }
}

/**
 * 状態変化・異常・復旧処理・温度・電池を CSV で記録する（仕様 F-20）。
 * 1 日 1 ファイル（files/logs/log-yyyyMMdd.csv）。保存期間と合計サイズを超えた古いファイルから消す。
 * SSID・接続端末の識別情報・電話番号・IP アドレスは記録しない。
 */
object LogStore {
    /** 保存するファイルの見出し（言語によらず同じ。列の位置と見出しの行を見分けるのに使う） */
    val HEADER = listOf(
        "日時", "イベント", "プロファイル", "電源", "電池%", "電池温度℃", "熱状態", "SoC温度℃",
        "SIM", "音声", "データ網", "データ通信", "RSRP(dBm)", "RSRQ(dB)", "SINR(dB)", "電波レベル", "バンド", "帯域(MHz)",
        "Wi-Fiテザリング", "共有中", "接続端末", "回線登録", "IP", "DNS", "HTTP", "応答(ms)", "システム検証",
        "異常", "処置", "結果", "詳細",
    )
    val IDX_EVENT = HEADER.indexOf("イベント")
    val IDX_TEMP = HEADER.indexOf("電池温度℃")
    val IDX_RSRP = HEADER.indexOf("RSRP(dBm)")
    val IDX_ANOMALY = HEADER.indexOf("異常")
    val IDX_ACTION = HEADER.indexOf("処置")
    val IDX_RESULT = HEADER.indexOf("結果")

    /** 書き出す CSV の見出し（今の言語） */
    val exportHeader: List<String>
        get() = listOf(
            tr("日時", "Time", "Date et heure"), tr("イベント", "Event", "Événement"), tr("プロファイル", "Profile", "Profil"),
            tr("電源", "Power", "Alimentation"), tr("電池%", "Battery %", "Batterie %"), tr("電池温度℃", "Battery temp ℃", "Temp. batterie ℃"),
            tr("熱状態", "Thermal status", "État thermique"), tr("SoC温度℃", "SoC temp ℃", "Temp. SoC ℃"),
            "SIM", tr("音声", "Voice", "Voix"), tr("データ網", "Data network", "Réseau de données"), tr("データ通信", "Data", "Données"),
            "RSRP(dBm)", "RSRQ(dB)", "SINR(dB)", tr("電波レベル", "Signal level", "Niveau du signal"), tr("バンド", "Band", "Bande"),
            tr("帯域(MHz)", "Bandwidth (MHz)", "Largeur de bande (MHz)"), tr("Wi-Fiテザリング", "Wi-Fi hotspot", "Point d'accès Wi-Fi"),
            tr("共有中", "Sharing", "Partage"), tr("接続端末", "Devices", "Appareils"), tr("回線登録", "Registered", "Enregistré"),
            "IP", "DNS", "HTTP", tr("応答(ms)", "Response (ms)", "Réponse (ms)"), tr("システム検証", "System validation", "Validation système"),
            tr("異常", "Problem", "Problème"), tr("処置", "Action", "Action"), tr("結果", "Result", "Résultat"), tr("詳細", "Details", "Détails"),
        )

    /** 保存された 1 行を、書き出し用に今の言語にする（イベント名） */
    fun exportRow(c: List<String>): List<String> = c.mapIndexed { i, v -> if (i == IDX_EVENT) Ev.display(v) else v }

    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var csv: DailyCsv? = null

    private fun csv(ctx: Context): DailyCsv = csv ?: synchronized(this) {
        csv ?: DailyCsv(File(ctx.applicationContext.filesDir, "logs"), "log-", HEADER).also { csv = it }
    }

    fun write(
        ctx: Context,
        event: String,
        s: StateStore.State,
        includeCheck: Boolean = false,
        anomaly: String? = null,
        action: String? = null,
        result: String? = null,
        detail: String? = null,
    ) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val days = prefs.int(Defs.LOG_DAYS)
        val maxBytes = prefs.int(Defs.LOG_MB) * 1024L * 1024L
        val now = System.currentTimeMillis()
        val line = Csv.line(fields(now, event, s, includeCheck, anomaly, action, result, detail))
        io.execute { runCatching { csv(app).append(line, now, days, maxBytes) } }
    }

    fun fields(
        at: Long,
        event: String,
        s: StateStore.State,
        includeCheck: Boolean,
        anomaly: String?,
        action: String?,
        result: String?,
        detail: String?,
    ): List<String?> {
        val p = s.power
        val t = s.tether
        val l = s.line
        val c = if (includeCheck) s.check else null
        return listOf(
            Fmt.dateTime(at), event, s.effective.label,
            p?.plug?.label, p?.levelPct?.toString(), p?.tempC?.let { f1(it) }, p?.thermalStatus?.let { Fmt.thermal(it) }, p?.socTempC?.let { f1(it) },
            l?.let { Fmt.sim(it.simState) }, l?.let { Fmt.voice(it.voiceState) }, l?.let { Fmt.rat(it.dataRat) }, l?.let { Fmt.dataState(it.dataState) },
            l?.rsrp?.toString(), l?.rsrq?.toString(), l?.sinr?.let { f1(it) }, l?.level?.toString(), l?.band,
            l?.bandwidthsMhz?.joinToString("+") { f1(it) },
            t?.let { Fmt.ap(it.apState) }, t?.let { Fmt.tethered(it.tethered) }, t?.clients?.toString(),
            c?.registered?.let { tri(it) }, c?.ip?.let { tri(it) }, c?.dns?.let { tri(it) }, c?.http?.let { tri(it) },
            c?.httpMs?.toString(), c?.validated?.let { tri(it) },
            anomaly, action, result, detail,
        )
    }

    private fun tri(t: Tri): String = when (t) {
        Tri.OK -> "OK"
        Tri.NG -> "NG"
        Tri.SKIP -> tr("未実施", "Skipped", "Non effectué")
        Tri.UNKNOWN -> Fmt.NA
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    /** すべてのログを 1 つの CSV に書き出す（BOM 付き UTF-8）。戻り値は行数。UI スレッド以外で呼ぶ */
    fun export(ctx: Context, out: OutputStream): Int = io.submit<Int> { csv(ctx).export(out, exportHeader, ::exportRow) }.get()

    /** ログを全部消す。UI スレッド以外で呼ぶ */
    fun clear(ctx: Context) {
        io.submit { csv(ctx).clear() }.get()
    }

    fun totalBytes(ctx: Context): Long = csv(ctx).totalBytes()

    /** 記録のある日付（yyyyMMdd、新しい順）。UI スレッド以外で呼ぶ */
    fun days(ctx: Context): List<String> =
        io.submit<List<String>> { csv(ctx).files().map { it.name.removePrefix("log-").removeSuffix(".csv") }.reversed() }.get()

    /** ある日（yyyyMMdd）の行。UI スレッド以外で呼ぶ */
    fun rowsOfDay(ctx: Context, yyyyMMdd: String): List<List<String>> = io.submit<List<List<String>>> { csv(ctx).rowsOfDay(yyyyMMdd) }.get()

    /** 直近 2 日分のログから、画面に出す記録を新しい順に読む */
    fun loadRecent(ctx: Context, max: Int, cb: (List<RecentEvent>) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val out = ArrayList<RecentEvent>()
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            val store = csv(app)
            loop@ for (f in store.files().takeLast(2).reversed()) {
                for (c in store.rowsOf(f).asReversed()) {
                    if (c[IDX_EVENT] !in Ev.recentKinds) continue
                    val at = runCatching { fmt.parse(c[0])?.time }.getOrNull() ?: continue
                    val text = listOf(c[IDX_ANOMALY], c[IDX_RESULT], c[IDX_ACTION]).filter { it.isNotBlank() }.joinToString(" · ")
                    out.add(RecentEvent(at, c[IDX_EVENT], text))
                    if (out.size >= max) break@loop
                }
            }
            Handler(Looper.getMainLooper()).post { cb(out) }
        }
    }

    /** 診断情報用に、最新ファイルの末尾を返す。UI スレッド以外で呼ぶ */
    fun tail(ctx: Context, n: Int): List<String> = io.submit<List<String>> { csv(ctx).tail(n) }.get()
}
