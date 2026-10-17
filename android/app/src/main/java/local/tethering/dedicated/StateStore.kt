package local.tethering.dedicated

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** 監視サービスが持つ最新の状態。同じプロセスの画面はここを見て表示する */
object StateStore {
    private const val MAX_RECENT = 30

    data class EpisodeView(
        val kind: Anomaly,
        val startedAt: Long,
        val rechecks: Int,
        val total: Int,
        val confirmed: Boolean,
    )

    /** 消費電力の計測（仕様 F-25 / F-26） */
    data class MeterView(
        val onBattery: Boolean,
        /** 直近の消費ペース。計測が足りなければ null */
        val rateMa: Double?,
        val rateMw: Double?,
        val method: Method?,
        val remainingMin: Int?,
        val capacityMah: Int?,
        val counterSupported: Boolean?,
    )

    /** 比較テストの進み具合（仕様 F-27） */
    data class ExperimentView(
        val title: String,
        val labelA: String,
        val labelB: String,
        val index: Int,
        val total: Int,
        val condition: Char,
        val measuredSec: Long,
        val phaseSec: Long,
        val waitingSwitch: Boolean,
        val instruction: String,
        val auto: Boolean,
    )

    /** 自動切り替え（仕様 F-42）の状態 */
    data class AutoView(
        /** 設定で自動切り替えを使う */
        val enabled: Boolean,
        /** ADB で設定変更の権限が付いている */
        val permitted: Boolean,
        val saverOn: Boolean,
        val dozeOn: Boolean,
    )

    /** 時間帯スケジュール（仕様 F-37）の状態 */
    data class SchedView(
        val enabled: Boolean,
        /** 「システム設定の変更」が許可されている */
        val permitted: Boolean,
        /** 使わない時間帯（例: 1:00〜6:00）。未設定なら null */
        val quiet: String?,
        /** このアプリがテザリングを止めている間の再開時刻（例: 6:00） */
        val stoppedUntil: String?,
        /** 止めるのを待っている理由・失敗の説明 */
        val note: String?,
    )

    /** スマートプラグ（仕様 F-45）の状態 */
    data class PlugView(
        /** 給電の目標（null = まだ決めていない） */
        val want: Boolean?,
        val why: String?,
        /** 給電を始める〜止める残量（例: 40〜80%） */
        val range: String,
        val lastResult: String?,
        /** 送り直しても給電の状態が目標と合わない */
        val stuck: Boolean,
    )

    /** 外部への通知（仕様 F-44）の状態 */
    data class ExtView(
        /** 送り先（例: ntfy（ntfy.sh））。URL が正しくなければ null */
        val dest: String?,
        val pending: Int,
        val lastResult: String?,
    )

    data class State(
        val meter: MeterView? = null,
        val auto: AutoView? = null,
        val sched: SchedView? = null,
        val plug: PlugView? = null,
        val ext: ExtView? = null,
        /** 待機中（チェック間隔を長くしている）の理由（仕様 F-32）。待機していなければ null */
        val idleReason: String? = null,
        val experiment: ExperimentView? = null,
        val running: Boolean = false,
        val checking: Boolean = false,
        val power: PowerInfo? = null,
        val tether: TetherInfo? = null,
        val line: LineInfo? = null,
        val check: CheckResult? = null,
        val mode: BaseMode = BaseMode.AUTO,
        val base: Effective = Effective.NORMAL,
        val effective: Effective = Effective.NORMAL,
        val intervalMin: Int = Defs.INTERVAL_NORMAL.def,
        val nextCheckAt: Long = 0L,
        val maintUntil: Long = 0L,
        val upstream: EpisodeView? = null,
        val tetherIssue: EpisodeView? = null,
        val alerts: Set<DeviceAlert> = emptySet(),
        val recent: List<RecentEvent> = emptyList(),
        /** ローカル管理画面（仕様 F-43）の待ち受けの状態 */
        val web: WebStatus? = null,
    ) {
        fun overall(now: Long = System.currentTimeMillis()): Overall = when {
            !running -> Overall.STOPPED
            maintUntil > now -> Overall.MAINT
            upstream?.confirmed == true || tetherIssue?.confirmed == true -> Overall.ERROR
            upstream != null || tetherIssue != null || alerts.isNotEmpty() || plug?.stuck == true -> Overall.WARN
            power?.dataSaver == true -> Overall.WARN
            check == null -> Overall.UNKNOWN
            check.failure != null -> Overall.WARN
            else -> Overall.OK
        }
    }

    @Volatile
    var state = State()
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(f: (State) -> State) {
        synchronized(this) { state = f(state) }
        if (Looper.myLooper() == Looper.getMainLooper()) notifyListeners() else main.post { notifyListeners() }
    }

    fun addRecent(e: RecentEvent) = update { it.copy(recent = (listOf(e) + it.recent).take(MAX_RECENT)) }

    fun listen(l: (State) -> Unit) {
        listeners.add(l)
    }

    fun unlisten(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        val s = state
        listeners.forEach { it(s) }
    }
}
