package local.tethering.dedicated

import java.util.TimeZone

/**
 * 時間帯スケジュール（仕様 F-37）の判断。Android に依存しない。
 *
 * - 使わない時間帯に入ったら、接続 0 台（数えられたとき）が 10 分続くのを待ってテザリングを止める。止めるのは 1 つの時間帯につき 1 回だけ
 *   （利用者が手で ON に戻したら、その時間帯はもう止めない）
 * - このアプリが止めたときだけ、時間帯の終わりに再開する（手動で OFF にしていた・再起動で止まっていた場合は再開しない）
 * - スケジュールを OFF にしたら、止めていたテザリングはすぐ再開する
 */
object TetherSchedule {
    enum class Act {
        /** 何もしない */
        NONE,

        /** テザリングを止める */
        STOP,

        /** 接続端末がいる・台数が分からないので、止めるのを待つ */
        WAIT,

        /** 時間帯の始まりにすでに OFF だった（止めない・再開もしない） */
        ALREADY_OFF,

        /** 止めていたテザリングを再開する */
        START,

        /** 止めていたテザリングが ON に戻った（再開できた、または利用者が ON にした） */
        CLEAR,
    }

    data class Input(
        val enabled: Boolean,
        val quiet: QuietHours?,
        val now: Long,
        /** Wi-Fi テザリングが動作中か（取得できなければ null） */
        val tetherOn: Boolean?,
        val clients: Int?,
        /** テザリング ON・接続 0 台が続いている時間（ミリ秒）。そうでなければ null */
        val zeroForMs: Long?,
        /** このアプリが止めた時間帯の開始（0 = 止めていない） */
        val stoppedWindow: Long,
        /** 止める判断を済ませた時間帯の開始（0 = なし） */
        val handledWindow: Long,
        /** メンテナンス中・比較テスト中（新たに止めない。再開はする） */
        val hold: Boolean,
    )

    data class Decision(val act: Act, val window: Pair<Long, Long>? = null)

    fun decide(i: Input, tz: TimeZone): Decision {
        val w = if (i.enabled) i.quiet?.windowAt(i.now, tz) else null
        if (i.stoppedWindow != 0L) {
            return when {
                i.tetherOn == true -> Decision(Act.CLEAR, w)
                w == null || w.first != i.stoppedWindow -> Decision(Act.START, w)
                else -> Decision(Act.NONE, w)
            }
        }
        if (w == null || i.hold || i.handledWindow == w.first) return Decision(Act.NONE, w)
        return when {
            i.tetherOn == false -> Decision(Act.ALREADY_OFF, w)
            i.tetherOn == null -> Decision(Act.NONE, w)
            i.clients == null || i.clients > 0 -> Decision(Act.WAIT, w)
            // 接続端末が一時的に離れただけ・テザリングを ON にした直後は止めない
            i.zeroForMs == null || i.zeroForMs < MIN_ZERO_MS -> Decision(Act.WAIT, w)
            else -> Decision(Act.STOP, w)
        }
    }

    /** 止めるのは、テザリング ON のまま接続 0 台がこれだけ続いたとき */
    const val MIN_ZERO_MS = 10 * 60_000L

    /**
     * 次に判断し直す時刻（壁時計のミリ秒）。止めている間は時間帯の終わり、そうでなければ次の時間帯の始まり。
     * 使わないときは null
     */
    fun nextDue(enabled: Boolean, quiet: QuietHours?, now: Long, stoppedWindow: Long, tz: TimeZone): Long? {
        if (!enabled || quiet == null) return null
        if (stoppedWindow != 0L) {
            // 設定が変わって止めた時間帯が見つからなければ、すぐ判断し直す（再開する）
            return quiet.windowsOverlapping(stoppedWindow, stoppedWindow + 1, tz).firstOrNull { it.first == stoppedWindow }?.second ?: now
        }
        val w = quiet.windowAt(now, tz)
        return if (w != null) w.second else quiet.nextStart(now, tz)
    }

    /** 再開に失敗したときのやり直しの間隔（分）。使い切ったらあきらめて、テザリング停止の異常として知らせる */
    val RETRY_MIN = listOf(1, 5, 15)
}
