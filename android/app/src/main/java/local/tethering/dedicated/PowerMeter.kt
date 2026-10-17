package local.tethering.dedicated

import android.content.Context
import android.net.TrafficStats
import android.os.PowerManager
import android.os.SystemClock

/**
 * 消費電力の計測（仕様 F-25）。時間を「区間」に分け、区間ごとの消費と条件を PowerStore に記録する。
 *
 * 区間は、もともと起きているタイミング（定期チェック・給電の変化・テザリングの ON/OFF・比較テストの切り替え）で
 * 区切るので、計測のために端末を余計に起こすことはない。電流は OS が送る電池のブロードキャストのついでに読む。
 * メインスレッドから呼ぶ。
 */
class PowerMeter(private val ctx: Context, private val prefs: Prefs) {
    /** 閉じた区間（比較テストへ渡す） */
    data class Closed(
        val onBattery: Boolean,
        val durationSec: Long,
        val consumption: Consumption?,
        val bytes: Long?,
        val clientsMax: Int?,
        val rsrp: Int?,
        /** 区間中の比較テストの条件（切り替え待ち・テストなしなら null） */
        val condition: Char?,
        val startWall: Long,
        val endWall: Long,
        /** 区間中のテザリングの ON/OFF（ON/OFF が変わると区間を分けるので一定） */
        val tether: Boolean?,
    )

    private class Open(
        val start: Reading,
        val startWall: Long,
        val startBytes: Long?,
        val profile: String,
        val tether: Boolean?,
        val experiment: String?,
        val condition: Char?,
    ) {
        var clientsMax: Int? = null
        var screenMs = 0L
    }

    private var open: Open? = null
    private val currents = ArrayList<CurrentSample>()
    private var screenOn = false
    private var screenSince = 0L

    /** 直近 2 時間の（区間の終わり, 消費）。残り時間の推定に使う */
    private val history = ArrayDeque<Pair<Long, Consumption>>()
    private var capacityAuto: Int? = null
    private var counterWorked: Boolean? = null

    var last: Consumption? = null
        private set

    private fun now() = SystemClock.elapsedRealtime()

    private fun reading(p: PowerInfo?, onBattery: Boolean) =
        Reading(now(), p?.counterUah, p?.levelPct, p?.voltageMv, onBattery)

    private fun mobileBytes(): Long? {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        return if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) null else rx + tx
    }

    /** 区間を始める（監視の開始時や、区間を閉じた直後） */
    fun begin(p: PowerInfo?, tetherOn: Boolean?, profile: String, experiment: String?, condition: Char?) {
        val onBattery = p?.plug == Plug.NONE
        open = Open(reading(p, onBattery), System.currentTimeMillis(), mobileBytes(), profile, tetherOn, experiment, condition)
        currents.clear()
        screenOn = runCatching { ctx.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() ?: false
        screenSince = now()
        p?.let { learnCapacity(it) }
    }

    fun noteClients(n: Int?) {
        val o = open ?: return
        if (n != null) o.clientsMax = maxOf(o.clientsMax ?: 0, n)
    }

    fun onScreen(on: Boolean) {
        val o = open ?: return
        val t = now()
        if (screenOn) o.screenMs += t - screenSince
        screenOn = on
        screenSince = t
    }

    /** 電池のブロードキャストのついでに電流を読む（バッテリー駆動中だけ） */
    fun addCurrent(p: PowerInfo) {
        val o = open ?: return
        if (!o.start.onBattery || p.plug != Plug.NONE) return
        val ma = p.currentMa ?: return
        currents.add(CurrentSample(now(), ma))
        if (currents.size > 2000) currents.subList(0, 1000).clear()
        learnCapacity(p)
    }

    /** 開いている区間がバッテリー駆動中で、比較テストの条件 [condition] のものなら、その経過秒数 */
    fun openBatterySec(condition: Char?): Long {
        val o = open ?: return 0
        if (!o.start.onBattery || condition == null || o.condition != condition) return 0
        return (now() - o.start.atMs) / 1000
    }

    /**
     * 区間を閉じて記録する（続けて [begin] で次の区間を始めること）。
     * [p] は閉じる時点の電源の状態（できるだけ新しく読んだもの）。区間がなければ null。
     */
    fun close(p: PowerInfo?, line: LineInfo?, clients: Int?): Closed? {
        val o = open ?: return null
        noteClients(clients)
        onScreen(screenOn) // 点灯中ならここまでを足す
        val end = reading(p, o.start.onBattery)
        val durSec = (end.atMs - o.start.atMs) / 1000
        val c = PowerMath.consumption(o.start, end, currents, capacity())
        // 充電カウンタは「実際に妥当な量だけ減った」ことを確かめてから信用する（値だけでは単位も正しさも分からない）。
        // 一度確かめたら、更新の遅い区間があっても切り替えない
        if (o.start.onBattery && o.start.counterUah != null && end.counterUah != null) {
            if (c?.method == Method.COUNTER) counterWorked = true
            else if (counterWorked == null && durSec >= 600) counterWorked = false
        }
        if (c != null) {
            last = c
            history.addLast(end.atMs to c)
            while (history.isNotEmpty() && end.atMs - history.first().first > 2 * 3_600_000L) history.removeFirst()
        }
        val bytes = mobileBytes()?.let { b -> o.startBytes?.let { s -> (b - s).takeIf { it >= 0 } } }
        val endWall = System.currentTimeMillis()
        if (durSec > 0) {
            PowerStore.append(
                ctx,
                PowerRow(
                    start = Fmt.dateTime(o.startWall), end = Fmt.dateTime(endWall), sec = durSec,
                    onBattery = o.start.onBattery, method = c?.method, levelStart = o.start.levelPct, levelEnd = end.levelPct,
                    mah = c?.mah, avgMa = c?.avgMa, avgMv = listOfNotNull(o.start.voltageMv, end.voltageMv).takeIf { it.isNotEmpty() }?.average()?.toInt(),
                    avgMw = c?.avgMw, tempC = p?.tempC, screenSec = o.screenMs / 1000, tether = o.tether, clientsMax = o.clientsMax,
                    rsrp = line?.rsrp, sinr = line?.sinr, band = line?.band, mobileKb = bytes?.let { it / 1024 }, profile = o.profile,
                    experiment = o.experiment, condition = o.condition?.toString(),
                ),
            )
        }
        open = null
        return Closed(o.start.onBattery, durSec, c, bytes, o.clientsMax, line?.rsrp, o.condition, o.startWall, endWall, o.tether)
    }

    private fun learnCapacity(p: PowerInfo) {
        PowerMath.capacityFrom(p.counterUah, p.levelPct)?.let { capacityAuto = it }
    }

    /** 電池容量（設定があればそれ、なければ確かめ済みの充電カウンタからの推定） */
    fun capacity(): Int? = prefs.int(Defs.BATT_CAPACITY).takeIf { it > 0 } ?: capacityAuto?.takeIf { counterWorked == true }

    /** 画面・通知に出す計測の状態 */
    fun view(p: PowerInfo?): StateStore.MeterView {
        val onBattery = p?.plug == Plug.NONE
        val t = now()
        // まだ閉じていない区間も、ここまでの分を含める（バッテリー駆動中はチェック間隔が 60 分と長いため）
        val o = open
        val partial = if (onBattery && o != null && o.start.onBattery) {
            PowerMath.consumption(o.start, reading(p, true), currents, capacity())?.let { t to it }
        } else {
            null
        }
        val rate = if (onBattery) PowerMath.recentRateMa(history.toList() + listOfNotNull(partial), t) else null
        // 一定の値を返すだけの充電カウンタ（例: エミュレーターの 10000）もあるので、実際に減ったと確かめたときだけ残量に使う
        val counter = p?.counterUah?.takeIf { counterWorked == true }
        val remaining = if (rate != null) PowerMath.remainingMah(counter, p?.levelPct, capacity())?.let { PowerMath.remainingMinutes(it, rate) } else null
        val volts = p?.voltageMv
        return StateStore.MeterView(
            onBattery = onBattery,
            rateMa = rate,
            rateMw = if (rate != null && volts != null) rate * volts / 1000.0 else null,
            method = if (rate != null) last?.method else null,
            remainingMin = remaining,
            capacityMah = capacity(),
            // バッテリー駆動の区間で確かめるまでは「不明」（null）
            counterSupported = counterWorked,
        )
    }
}
