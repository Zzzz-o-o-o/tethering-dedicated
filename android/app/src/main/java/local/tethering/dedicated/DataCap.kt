package local.tethering.dedicated

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar
import java.util.TimeZone

/**
 * 月の通信量の上限（v0.8.0）。判断（[cycle]・[decide]）は Android に依存しない。
 *
 * - 期間は毎月の「数え始める日」（1〜28 日）の 0:00 から、翌月の同じ日の 0:00 まで
 * - 上限の [WARN_PCT]% と 100% で 1 回ずつ知らせる（期間ごと。一度に 100% を超えたら 100% だけ）
 * - 「上限を超えたらテザリングを止める」なら 100% で止める（期間に 1 回だけ。止めたときに OFF だった・止めている間に利用者が
 *   手で ON にした場合は、その期間はもう止めない）
 * - このアプリが止めたときだけ、次の期間の始まり・設定の OFF・上限を上げたとき・監視の停止で再開する
 */
object DataCap {
    /** 前もって知らせる割合（%） */
    const val WARN_PCT = 80

    /** 1 GB（このアプリの通信量の表示と同じく 1024 進） */
    const val GB = 1024L * 1024L * 1024L

    /** 知らせた段階 */
    const val LEVEL_NONE = 0
    const val LEVEL_WARN = 1
    const val LEVEL_REACHED = 2

    /** [now] を含む期間の開始と終わり（壁時計のミリ秒）。[startDay] は 1〜28 */
    fun cycle(now: Long, startDay: Int, tz: TimeZone): Pair<Long, Long> {
        val day = startDay.coerceIn(1, 28)
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_MONTH, day)
        }
        if (c.timeInMillis > now) c.add(Calendar.MONTH, -1)
        val start = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return start to c.timeInMillis
    }

    /** 使った量の段階 */
    fun level(used: Long, limit: Long): Int = when {
        limit <= 0 -> LEVEL_NONE
        used >= limit -> LEVEL_REACHED
        used * 100 >= limit * WARN_PCT -> LEVEL_WARN
        else -> LEVEL_NONE
    }

    enum class Tether {
        /** 何もしない */
        NONE,

        /** テザリングを止める */
        STOP,

        /** 上限を超えたときにはもう OFF だった（止めない。この期間はもう止めない） */
        ALREADY_OFF,

        /** 止めていたテザリングを再開する */
        START,

        /** 止めていたテザリングが ON になった（利用者が手で ON にした。この期間はもう止めない） */
        CLEAR,
    }

    data class Input(
        val enabled: Boolean,
        val stopEnabled: Boolean,
        val limitBytes: Long,
        /** この期間に使った量。読めなければ null */
        val usedBytes: Long?,
        /** いまの期間の開始 */
        val cycleStart: Long,
        /** この期間に知らせた段階（[LEVEL_NONE] など。前の期間の記録なら [LEVEL_NONE] として渡す） */
        val notified: Int,
        /** このアプリが止めた期間の開始（0 = 止めていない） */
        val stoppedCycle: Long,
        /** 止める判断を済ませた期間の開始（0 = なし） */
        val handledCycle: Long,
        /** Wi-Fi テザリングが動作中か（取得できなければ null） */
        val tetherOn: Boolean?,
        /** メンテナンス中・比較テスト中（新たに止めない。知らせる・再開はする） */
        val hold: Boolean,
    )

    /** [notify] は新たに知らせる段階（なければ null） */
    data class Decision(val notify: Int?, val tether: Tether)

    fun decide(i: Input): Decision {
        val level = if (i.enabled && i.usedBytes != null) level(i.usedBytes, i.limitBytes) else LEVEL_NONE
        val notify = level.takeIf { it > i.notified && it != LEVEL_NONE }
        val tether = when {
            i.stoppedCycle != 0L -> when {
                // 設定を OFF にした・次の期間に入った・上限を上げて下回った → このアプリが止めていたので再開する
                !i.enabled || !i.stopEnabled || i.stoppedCycle != i.cycleStart -> Tether.START
                i.usedBytes != null && level != LEVEL_REACHED -> Tether.START
                i.tetherOn == true -> Tether.CLEAR
                else -> Tether.NONE
            }
            !i.enabled || !i.stopEnabled || level != LEVEL_REACHED || i.handledCycle == i.cycleStart -> Tether.NONE
            i.tetherOn == false -> Tether.ALREADY_OFF
            i.tetherOn == null || i.hold -> Tether.NONE
            else -> Tether.STOP
        }
        return Decision(notify, tether)
    }
}

/** この期間のモバイル回線の通信量を OS の記録から読む（設定 → データ使用量と同じ数え方。テザリングの分も含む）。UI スレッド以外で呼ぶ */
object MonthUsage {
    /** debug ビルドの試験用に、使った量を差し替える（リリース版では使わない） */
    @Volatile
    var debugOverride: Long? = null

    /** 読めなければ null（「使用状況へのアクセス」がない・この端末では読めない） */
    fun mobileBytes(ctx: Context, start: Long, end: Long): Long? {
        if (BuildConfig.DEBUG) debugOverride?.let { return it }
        if (!AppUsageProbe.hasAccess(ctx)) return null
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return null
        // Android 10 では subscriberId に null を渡すと、すべてのモバイル回線の合計になる
        return runCatching { nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end) }.getOrNull()
            ?.let { it.rxBytes + it.txBytes }
            ?.takeIf { it >= 0 }
    }
}
