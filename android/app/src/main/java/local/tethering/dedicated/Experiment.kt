package local.tethering.dedicated

import kotlin.math.abs

/**
 * 省電力設定の比較テスト（仕様 F-27）。条件 A（今の設定）と条件 B（試す設定）を A→B→A→B… と交互に、
 * バッテリー駆動中だけ同じ時間ずつ計測し、平均消費と通信の安定性を比べる。
 *
 * 第 1 段階では、OS の設定は利用者が手動で切り替える（アプリは切り替えの案内・計測・判定を行い、
 * 効果がなければ元に戻すよう知らせる）。本アプリ自身のチェック間隔だけは自動で切り替える。
 * Android に依存しないので PC 上の単体テストで確かめられる。
 */
data class Candidate(
    val key: String,
    val title: String,
    val labelA: String,
    val labelB: String,
    /** B に切り替える手順 */
    val toB: String,
    /** A に戻す手順 */
    val toA: String,
    /** アプリが自動で切り替える（本アプリの設定だけ） */
    val auto: Boolean = false,
)

object Candidates {
    const val KEY_INTERVAL = "app_interval"
    const val KEY_CUSTOM = "custom"

    /** 自動で試すときのチェック間隔の倍率 */
    const val INTERVAL_FACTOR = 4

    val all = listOf(
        Candidate(
            "bluetooth", "Bluetooth を OFF", "Bluetooth ON", "Bluetooth OFF",
            "クイック設定または 設定 → Bluetooth で OFF にしてください。", "Bluetooth を ON に戻してください。",
        ),
        Candidate(
            "location", "位置情報を OFF", "位置情報 ON", "位置情報 OFF",
            "クイック設定または 設定 → 位置情報 で OFF にしてください。", "位置情報を ON に戻してください。",
        ),
        Candidate(
            "battery_saver", "バッテリーセーバーを ON", "セーバー OFF", "セーバー ON",
            "設定 → 電池 → バッテリーセーバー を ON にしてください（データセーバーは ON にしないこと）。", "バッテリーセーバーを OFF に戻してください。",
        ),
        Candidate(
            "ap_band", "アクセスポイントの帯域を変える", "今の帯域", "別の帯域（2.4GHz ⇔ 5GHz）",
            "テザリング設定 → Wi-Fi アクセスポイント → AP の帯域 を切り替えてください（接続端末はつなぎ直しになります）。", "AP の帯域を元に戻してください。",
        ),
        Candidate(
            "volte", "VoLTE を OFF（データ専用 SIM）", "VoLTE ON", "VoLTE OFF",
            "設定 → ネットワークとインターネット → モバイル ネットワーク で VoLTE を OFF にしてください。", "VoLTE を ON に戻してください。",
        ),
        Candidate(
            "sync", "自動同期を OFF", "自動同期 ON", "自動同期 OFF",
            "設定 → アカウント →「アプリデータを自動的に同期する」を OFF にしてください。", "自動同期を ON に戻してください。",
        ),
        Candidate(
            KEY_INTERVAL, "このアプリのチェック間隔（自動）", "今の間隔", "間隔を ${INTERVAL_FACTOR} 倍",
            "（自動で切り替えます）", "（自動で元に戻します）", auto = true,
        ),
        Candidate(
            KEY_CUSTOM, "その他（自由に指定）", "今の設定", "試す設定",
            "試したい設定に切り替えてください。", "元の設定に戻してください。",
        ),
    )

    fun of(key: String): Candidate? = all.firstOrNull { it.key == key }
}

/** 1 つの条件（A または B）の 1 回分の計測結果 */
class PhaseResult(val condition: Char) {
    var sec = 0L
    var mah = 0.0
    /** 消費電力 × 時間（mW·s）。電圧が読めた区間だけ */
    var mwSec = 0.0
    var mwSecSpan = 0L
    var checks = 0
    var failures = 0
    var bytes = 0L
    var clientsMax: Int? = null
    var rsrpSum = 0L
    var rsrpN = 0

    val avgMa: Double? get() = if (sec > 0) mah / (sec / 3600.0) else null
    val avgMw: Double? get() = if (mwSecSpan > 0 && mwSecSpan * 10 >= sec * 9) mwSec / mwSecSpan else null
    val rsrpAvg: Double? get() = if (rsrpN > 0) rsrpSum.toDouble() / rsrpN else null
}

enum class Decision(val label: String) {
    ADOPT("効果あり：試した設定（B）のままにする"),
    REVERT("効果なし：元の設定（A）に戻す"),
    WORSE("通信が悪化：元の設定（A）に戻す"),
    INCONCLUSIVE("判定できない：元の設定（A）に戻す"),
    ABORTED("中止：元の設定（A）に戻す"),
}

data class Outcome(
    val avgA: Double?,
    val avgB: Double?,
    /** "mW" または（電圧が読めない機種では）"mA" */
    val unit: String,
    /** B にしたことでの削減率（%）。プラスが省電力 */
    val savingPct: Double?,
    val consistent: Boolean,
    val notes: List<String>,
    val decision: Decision,
)

class ExperimentRun(
    val candidate: Candidate,
    val phaseSec: Long,
    val repeats: Int,
    val minGainPct: Int,
    val startedAt: Long,
) {
    val phases: List<PhaseResult> = (0 until repeats * 2).map { PhaseResult(if (it % 2 == 0) 'A' else 'B') }

    /** 計測中の条件の番号（0 始まり）。phases.size に達したら終了 */
    var index = 0

    /** 手動の切り替え待ち（利用者が「切り替えた」を押すまで計測しない） */
    var waitingSwitch = false

    val finished: Boolean get() = index >= phases.size
    val current: PhaseResult? get() = phases.getOrNull(index)
    val condition: Char? get() = current?.condition

    /** 計測中（切り替え待ちでなく、終わってもいない） */
    val measuring: Boolean get() = !finished && !waitingSwitch

    val remainingSec: Long get() = current?.let { (phaseSec - it.sec).coerceAtLeast(0) } ?: 0

    val phaseComplete: Boolean get() = current?.let { it.sec >= phaseSec } ?: false

    /**
     * 今、端末が試す設定（B）になっているはずか。中止したときに「元に戻して」と知らせるかどうかに使う。
     * B を計測中、または A への切り替え待ち（まだ B のまま）なら true。
     */
    val settingIsB: Boolean
        get() = when {
            finished -> phases.lastOrNull()?.condition == 'B'
            waitingSwitch -> condition == 'A'
            else -> condition == 'B'
        }

    /** バッテリー駆動中に計測した区間を、今の条件に足す */
    fun addSegment(c: Consumption, bytes: Long?, clientsMax: Int?, rsrp: Int?) {
        val p = current ?: return
        if (!measuring) return
        p.sec += c.durationSec
        p.mah += c.mah
        c.avgMw?.let {
            p.mwSec += it * c.durationSec
            p.mwSecSpan += c.durationSec
        }
        if (bytes != null && bytes > 0) p.bytes += bytes
        if (clientsMax != null) p.clientsMax = maxOf(p.clientsMax ?: 0, clientsMax)
        if (rsrp != null) {
            p.rsrpSum += rsrp
            p.rsrpN++
        }
    }

    fun addCheck(ok: Boolean) {
        val p = current ?: return
        if (!measuring) return
        p.checks++
        if (!ok) p.failures++
    }

    /** 次の条件へ進む。終われば null、続くなら次の条件（'A' / 'B'） */
    fun advance(): Char? {
        if (finished) return null
        index++
        if (finished) return null
        waitingSwitch = !candidate.auto
        return condition
    }

    fun confirmSwitch() {
        waitingSwitch = false
    }

    fun outcome(aborted: Boolean = false): Outcome {
        val a = phases.filter { it.condition == 'A' }
        val b = phases.filter { it.condition == 'B' }
        val useMw = phases.filter { it.sec > 0 }.all { it.avgMw != null } && phases.any { it.sec > 0 }
        val unit = if (useMw) "mW" else "mA"
        fun avgOf(list: List<PhaseResult>): Double? {
            val measured = list.filter { it.sec > 0 }
            val sec = measured.sumOf { it.sec }
            if (sec == 0L) return null
            return if (useMw) measured.sumOf { it.mwSec } / measured.sumOf { it.mwSecSpan }.coerceAtLeast(1)
            else measured.sumOf { it.mah } / (sec / 3600.0)
        }
        fun phaseAvg(p: PhaseResult): Double? = if (useMw) p.avgMw else p.avgMa
        val avgA = avgOf(a)
        val avgB = avgOf(b)
        val saving = if (avgA != null && avgB != null && avgA > 0) (avgA - avgB) / avgA * 100 else null

        val notes = ArrayList<String>()
        // 条件の違い（参考値になる理由）
        val bytesA = a.sumOf { it.bytes } / (a.sumOf { it.sec }.coerceAtLeast(1) / 3600.0)
        val bytesB = b.sumOf { it.bytes } / (b.sumOf { it.sec }.coerceAtLeast(1) / 3600.0)
        val mbPerH = 1_000_000.0
        if (maxOf(bytesA, bytesB) > 5 * mbPerH && maxOf(bytesA, bytesB) > 2 * minOf(bytesA, bytesB)) {
            notes.add("通信量が条件で大きく違います（A ${"%.0f".format(bytesA / mbPerH)} MB/h・B ${"%.0f".format(bytesB / mbPerH)} MB/h）")
        }
        val rA = a.mapNotNull { it.rsrpAvg }.takeIf { it.isNotEmpty() }?.average()
        val rB = b.mapNotNull { it.rsrpAvg }.takeIf { it.isNotEmpty() }?.average()
        if (rA != null && rB != null && abs(rA - rB) >= 6) {
            notes.add("電波が条件で違います（RSRP A ${"%.0f".format(rA)}・B ${"%.0f".format(rB)} dBm）")
        }
        val cA = a.mapNotNull { it.clientsMax }.maxOrNull()
        val cB = b.mapNotNull { it.clientsMax }.maxOrNull()
        if (cA != null && cB != null && cA != cB) notes.add("接続端末数が条件で違います（最大 A ${cA}台・B ${cB}台）")

        val measuredEnough = phases.all { it.sec * 2 >= phaseSec }
        // 複数回なら「B のどの回も、A のどの回より少ない」ときだけ一貫しているとみなす
        val consistent = if (repeats >= 2) {
            val maxB = b.mapNotNull { phaseAvg(it) }.maxOrNull()
            val minA = a.mapNotNull { phaseAvg(it) }.minOrNull()
            maxB != null && minA != null && maxB < minA
        } else {
            saving != null && saving >= minGainPct
        }
        val failA = a.sumOf { it.failures }
        val failB = b.sumOf { it.failures }
        val worse = failB > failA

        val decision = when {
            aborted -> Decision.ABORTED
            worse -> Decision.WORSE
            !measuredEnough || saving == null -> Decision.INCONCLUSIVE
            saving >= minGainPct && consistent -> Decision.ADOPT
            else -> Decision.REVERT
        }
        if (worse) notes.add(0, "B の間にインターネット確認の失敗が増えました（A ${failA}回・B ${failB}回）")
        if (!measuredEnough && !aborted) notes.add("計測時間が足りない条件があります")
        return Outcome(avgA, avgB, unit, saving, consistent, notes, decision)
    }
}
