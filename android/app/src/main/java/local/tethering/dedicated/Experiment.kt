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

    /** 接続 0 台のときにテザリングを止めた場合の待機電力を測る（仕様 F-36）。この比較テストの間はテザリングの停止を異常としない */
    const val KEY_TETHER_OFF = "tether_off"

    /** 自動で試すときのチェック間隔の倍率 */
    const val INTERVAL_FACTOR = 4

    /** 候補の一覧（表示の言語で作る） */
    val all: List<Candidate> get() = listOf(
        Candidate(
            "bluetooth", tr("Bluetooth を OFF", "Turn off Bluetooth", "Désactiver le Bluetooth"),
            tr("Bluetooth ON", "Bluetooth on", "Bluetooth activé"), tr("Bluetooth OFF", "Bluetooth off", "Bluetooth désactivé"),
            tr(
                "クイック設定または 設定 → Bluetooth で OFF にしてください。", "Turn it off in Quick Settings or Settings → Bluetooth.",
                "Désactivez-le dans les réglages rapides ou dans Paramètres → Bluetooth.",
            ),
            tr("Bluetooth を ON に戻してください。", "Turn Bluetooth back on.", "Réactivez le Bluetooth."),
        ),
        Candidate(
            "location", tr("位置情報を OFF", "Turn off Location", "Désactiver la localisation"),
            tr("位置情報 ON", "Location on", "Localisation activée"), tr("位置情報 OFF", "Location off", "Localisation désactivée"),
            tr(
                "クイック設定または 設定 → 位置情報 で OFF にしてください。", "Turn it off in Quick Settings or Settings → Location.",
                "Désactivez-la dans les réglages rapides ou dans Paramètres → Localisation.",
            ),
            tr("位置情報を ON に戻してください。", "Turn Location back on.", "Réactivez la localisation."),
        ),
        Candidate(
            "battery_saver", tr("バッテリーセーバーを ON", "Turn on Battery Saver", "Activer l'économiseur de batterie"),
            tr("セーバー OFF", "Saver off", "Économiseur désactivé"), tr("セーバー ON", "Saver on", "Économiseur activé"),
            tr(
                "設定 → 電池 → バッテリーセーバー を ON にしてください（データセーバーは ON にしないこと）。",
                "Turn on Settings → Battery → Battery Saver (don't turn on Data Saver).",
                "Activez Paramètres → Batterie → Économiseur de batterie (n'activez pas l'économiseur de données).",
            ),
            tr("バッテリーセーバーを OFF に戻してください。", "Turn Battery Saver back off.", "Désactivez à nouveau l'économiseur de batterie."),
        ),
        // 帯域ごとの消費の比較（仕様 F-38）。アプリからは今の帯域を読めないので、今の帯域に合わせて選んでもらう
        Candidate(
            "ap_5g", tr("アクセスポイントを 5GHz にする（今は 2.4GHz）", "Switch the hotspot to 5 GHz (now 2.4 GHz)", "Passer le point d'accès en 5 GHz (actuellement 2,4 GHz)"),
            tr("2.4GHz", "2.4 GHz", "2,4 GHz"), tr("5GHz", "5 GHz", "5 GHz"),
            apBand(tr("5.0 GHz", "5.0 GHz", "5,0 GHz")), apBack(tr("2.4 GHz", "2.4 GHz", "2,4 GHz")),
        ),
        Candidate(
            "ap_24g", tr("アクセスポイントを 2.4GHz にする（今は 5GHz）", "Switch the hotspot to 2.4 GHz (now 5 GHz)", "Passer le point d'accès en 2,4 GHz (actuellement 5 GHz)"),
            tr("5GHz", "5 GHz", "5 GHz"), tr("2.4GHz", "2.4 GHz", "2,4 GHz"),
            apBand(tr("2.4 GHz", "2.4 GHz", "2,4 GHz")), apBack(tr("5.0 GHz", "5.0 GHz", "5,0 GHz")),
        ),
        Candidate(
            "tether_offload",
            tr(
                "テザリングのハードウェアアクセラレーションを ON（対応端末のみ）", "Turn on tethering hardware acceleration (supported phones only)",
                "Activer l'accélération matérielle du partage de connexion (téléphones compatibles)",
            ),
            tr("アクセラレーション OFF", "Acceleration off", "Accélération désactivée"), tr("アクセラレーション ON", "Acceleration on", "Accélération activée"),
            tr(
                "開発者向けオプション →「テザリング ハードウェア アクセラレーション」を ON にしてから、テザリングを OFF → ON してください。",
                "Turn on Developer options → “Tethering hardware acceleration”, then turn the hotspot off and on again.",
                "Activez Options pour les développeurs → « Accélération matérielle pour le partage de connexion », puis désactivez et réactivez le point d'accès.",
            ),
            tr(
                "「テザリング ハードウェア アクセラレーション」を OFF に戻し、テザリングを OFF → ON してください。",
                "Turn “Tethering hardware acceleration” back off, then turn the hotspot off and on again.",
                "Désactivez à nouveau « Accélération matérielle pour le partage de connexion », puis désactivez et réactivez le point d'accès.",
            ),
        ),
        Candidate(
            "volte", tr("VoLTE を OFF（データ専用 SIM）", "Turn off VoLTE (data-only SIM)", "Désactiver la VoLTE (SIM données uniquement)"),
            tr("VoLTE ON", "VoLTE on", "VoLTE activée"), tr("VoLTE OFF", "VoLTE off", "VoLTE désactivée"),
            tr(
                "設定 → ネットワークとインターネット → モバイル ネットワーク で VoLTE を OFF にしてください。",
                "Turn off VoLTE in Settings → Network & internet → Mobile network.",
                "Désactivez la VoLTE dans Paramètres → Réseau et Internet → Réseau mobile.",
            ),
            tr("VoLTE を ON に戻してください。", "Turn VoLTE back on.", "Réactivez la VoLTE."),
        ),
        Candidate(
            "sync", tr("自動同期を OFF", "Turn off auto-sync", "Désactiver la synchronisation automatique"),
            tr("自動同期 ON", "Auto-sync on", "Synchro auto activée"), tr("自動同期 OFF", "Auto-sync off", "Synchro auto désactivée"),
            tr(
                "設定 → アカウント →「アプリデータを自動的に同期する」を OFF にしてください。",
                "Turn off Settings → Accounts → “Automatically sync app data”.",
                "Désactivez Paramètres → Comptes → « Synchroniser automatiquement les données des applications ».",
            ),
            tr("自動同期を ON に戻してください。", "Turn auto-sync back on.", "Réactivez la synchronisation automatique."),
        ),
        Candidate(
            KEY_TETHER_OFF,
            tr(
                "使わないときにテザリングを止める（待機電力の測定）", "Turn off the hotspot when unused (standby power test)",
                "Arrêter le point d'accès inutilisé (mesure de la consommation en veille)",
            ),
            tr("テザリング ON（接続 0 台）", "Hotspot on (no devices)", "Point d'accès activé (aucun appareil)"),
            tr("テザリング OFF", "Hotspot off", "Point d'accès désactivé"),
            tr(
                "接続端末をすべて切断してから、Wi-Fi テザリングを OFF にしてください（このテストの間は停止の通知を出しません）。",
                "Disconnect all devices, then turn off the Wi-Fi hotspot (no hotspot alerts are shown during this test).",
                "Déconnectez tous les appareils, puis désactivez le point d'accès Wi-Fi (aucune alerte d'arrêt pendant ce test).",
            ),
            tr("Wi-Fi テザリングを ON に戻してください。", "Turn the Wi-Fi hotspot back on.", "Réactivez le point d'accès Wi-Fi."),
        ),
        Candidate(
            KEY_INTERVAL,
            tr("このアプリのチェック間隔（自動）", "This app's check interval (automatic)", "Intervalle de vérification de cette application (automatique)"),
            tr("今の間隔", "Current interval", "Intervalle actuel"),
            tr("間隔を ${INTERVAL_FACTOR} 倍", "Interval × $INTERVAL_FACTOR", "Intervalle × $INTERVAL_FACTOR"),
            tr("（自動で切り替えます）", "(switched automatically)", "(changement automatique)"),
            tr("（自動で元に戻します）", "(restored automatically)", "(rétabli automatiquement)"), auto = true,
        ),
        Candidate(
            KEY_CUSTOM, tr("その他（自由に指定）", "Other (your choice)", "Autre (au choix)"),
            tr("今の設定", "Current setting", "Réglage actuel"), tr("試す設定", "Setting to test", "Réglage à tester"),
            tr("試したい設定に切り替えてください。", "Switch to the setting you want to test.", "Passez au réglage à tester."),
            tr("元の設定に戻してください。", "Switch back to your original setting.", "Revenez à votre réglage d'origine."),
        ),
    )

    private fun apBand(band: String) = tr(
        "テザリング設定 → Wi-Fi アクセスポイント → AP の帯域 を「$band」にしてください（接続端末はつなぎ直しになります）。",
        "Set Hotspot settings → Wi-Fi hotspot → AP Band to “$band” (connected devices will reconnect).",
        "Réglez Point d'accès → Point d'accès Wi-Fi → Bande du point d'accès sur « $band » (les appareils connectés se reconnecteront).",
    )

    private fun apBack(band: String) = tr("AP の帯域を「$band」に戻してください。", "Set the AP Band back to “$band”.", "Remettez la bande du point d'accès sur « $band ».")

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

enum class Decision(private val tx: Tx) {
    ADOPT(Tx("効果あり：試した設定（B）のままにする", "Effective: keep the tested setting (B)", "Efficace : garder le réglage testé (B)")),
    REVERT(Tx("効果なし：元の設定（A）に戻す", "No effect: go back to the original setting (A)", "Sans effet : revenir au réglage d'origine (A)")),
    WORSE(Tx("通信が悪化：元の設定（A）に戻す", "Connection got worse: go back to the original setting (A)", "Connexion dégradée : revenir au réglage d'origine (A)")),
    INCONCLUSIVE(Tx("判定できない：元の設定（A）に戻す", "Inconclusive: go back to the original setting (A)", "Non concluant : revenir au réglage d'origine (A)")),
    ABORTED(Tx("中止：元の設定（A）に戻す", "Stopped: go back to the original setting (A)", "Arrêté : revenir au réglage d'origine (A)"));

    val label: String get() = tx.get()
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
            val x = "%.0f".format(bytesA / mbPerH)
            val y = "%.0f".format(bytesB / mbPerH)
            notes.add(
                tr(
                    "通信量が条件で大きく違います（A $x MB/h・B $y MB/h）", "Data use differs a lot between conditions (A $x MB/h, B $y MB/h)",
                    "La consommation de données diffère beaucoup entre les conditions (A $x Mo/h, B $y Mo/h)",
                ),
            )
        }
        val rA = a.mapNotNull { it.rsrpAvg }.takeIf { it.isNotEmpty() }?.average()
        val rB = b.mapNotNull { it.rsrpAvg }.takeIf { it.isNotEmpty() }?.average()
        if (rA != null && rB != null && abs(rA - rB) >= 6) {
            val x = "%.0f".format(rA)
            val y = "%.0f".format(rB)
            notes.add(
                tr(
                    "電波が条件で違います（RSRP A $x・B $y dBm）", "Signal differs between conditions (RSRP A $x, B $y dBm)",
                    "Le signal diffère entre les conditions (RSRP A $x, B $y dBm)",
                ),
            )
        }
        val cA = a.mapNotNull { it.clientsMax }.maxOrNull()
        val cB = b.mapNotNull { it.clientsMax }.maxOrNull()
        if (cA != null && cB != null && cA != cB) {
            notes.add(
                tr(
                    "接続端末数が条件で違います（最大 A ${cA}台・B ${cB}台）", "The number of devices differs between conditions (max A $cA, B $cB)",
                    "Le nombre d'appareils diffère entre les conditions (max A $cA, B $cB)",
                ),
            )
        }

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
        if (worse) {
            notes.add(
                0,
                tr(
                    "B の間にインターネット確認の失敗が増えました（A ${failA}回・B ${failB}回）", "More internet check failures during B (A $failA, B $failB)",
                    "Plus d'échecs du test Internet pendant B (A $failA, B $failB)",
                ),
            )
        }
        if (!measuredEnough && !aborted) {
            notes.add(tr("計測時間が足りない条件があります", "Some conditions weren't measured long enough", "Certaines conditions n'ont pas été mesurées assez longtemps"))
        }
        return Outcome(avgA, avgB, unit, saving, consistent, notes, decision)
    }
}
