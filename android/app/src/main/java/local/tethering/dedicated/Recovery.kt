package local.tethering.dedicated

/**
 * 異常の段階的な確認（仕様 F-13）。
 *
 * 1. 異常を見つけたら、すぐには通知せず決まった間隔で再確認する
 * 2. 再確認のたびに状態を取り直す。2 回目ではシステムにネットワークの再検証を依頼する（低リスク）
 * 3. 決まった回数続いたら「確定」として 1 回だけ通知し、通常のチェック間隔に戻る
 * 4. 確定後も続く場合は [reminderMs] ごとにだけ再通知する
 *
 * 機内モード切替や再起動のような、通信を止めうる操作はしない。回数に上限があるので無限ループしない。
 */
class RecoveryMachine(
    private val delaysMin: () -> List<Int>,
    private val reminderMs: Long = 6 * 60 * 60 * 1000L,
) {
    class Episode(var kind: Anomaly, val startedAt: Long) {
        var rechecks = 0
        var confirmed = false
        var lastNotifiedAt = 0L
    }

    sealed class Step {
        object Ok : Step()

        /** 初めて異常を見つけた → [nextDelayMs] 後に再確認 */
        class Suspect(val episode: Episode, val nextDelayMs: Long) : Step()

        /** 再確認でも異常 → [nextDelayMs] 後にもう一度。[revalidate] ならネットワーク再検証を依頼 */
        class Recheck(val episode: Episode, val nextDelayMs: Long, val revalidate: Boolean) : Step()

        /** 規定回数続いたので確定（通知する） */
        class Confirmed(val episode: Episode) : Step()

        /** 確定後も異常のまま。[remind] なら再通知 */
        class StillBad(val episode: Episode, val remind: Boolean) : Step()

        /** 正常に戻った */
        class Recovered(val episode: Episode, val durationMs: Long) : Step()
    }

    var episode: Episode? = null
        private set

    /** 進行中で、まだ確定していない（短い間隔で再確認している）か */
    val rechecking: Boolean get() = episode?.let { !it.confirmed } ?: false

    fun onResult(now: Long, failure: Anomaly?): Step {
        val ep = episode
        if (failure == null) {
            if (ep == null) return Step.Ok
            episode = null
            return Step.Recovered(ep, now - ep.startedAt)
        }
        val delays = delaysMin().ifEmpty { listOf(1) }
        if (ep == null) {
            val e = Episode(failure, now)
            episode = e
            return Step.Suspect(e, delays[0] * MINUTE)
        }
        // 途中で原因の段階が変わっても（例: データ未確立 → DNS 失敗）回数は引き継ぐ
        ep.kind = failure
        if (ep.confirmed) {
            val remind = now - ep.lastNotifiedAt >= reminderMs
            if (remind) ep.lastNotifiedAt = now
            return Step.StillBad(ep, remind)
        }
        ep.rechecks++
        return if (ep.rechecks < delays.size) {
            Step.Recheck(ep, delays[ep.rechecks] * MINUTE, revalidate = ep.rechecks == 1)
        } else {
            ep.confirmed = true
            ep.lastNotifiedAt = now
            Step.Confirmed(ep)
        }
    }

    /** 別の通知（起動後チェックなど）で知らせ済みなので、確定済みとして扱う（同じ内容を重ねて通知しない） */
    fun markNotified(now: Long) {
        episode?.let {
            it.confirmed = true
            it.lastNotifiedAt = now
        }
    }

    fun reset() {
        episode = null
    }

    companion object {
        const val MINUTE = 60_000L
    }
}

/** 温度・熱状態・電池残量の警告。しきい値付近で通知がばたつかないよう、解除側に余裕（ヒステリシス）を持たせる */
class AlertTracker {
    val active = LinkedHashSet<DeviceAlert>()

    data class Change(val alert: DeviceAlert, val entered: Boolean)

    data class Limits(val tempWarnC: Int, val tempCritC: Int, val thermalWarn: Int, val battLowPct: Int, val runtimeAlertMin: Int = 0)

    /** [remainingMin] は推定残り時間（計測データが足りなければ null で、そのときは通知しない） */
    fun evaluate(p: PowerInfo, l: Limits, remainingMin: Int? = null): List<Change> {
        val changes = ArrayList<Change>()
        val crit = maxOf(l.tempCritC, l.tempWarnC)
        val t = p.tempC
        update(DeviceAlert.TEMP_CRIT, t != null && t >= crit, t != null && t < crit - 2, changes)
        val warnOn = t != null && t >= l.tempWarnC && DeviceAlert.TEMP_CRIT !in active
        val warnOff = t != null && (t < l.tempWarnC - 2 || DeviceAlert.TEMP_CRIT in active)
        update(DeviceAlert.TEMP_WARN, warnOn, warnOff, changes)
        val ts = p.thermalStatus
        update(DeviceAlert.THERMAL, ts != null && ts >= l.thermalWarn, ts != null && ts < l.thermalWarn, changes)
        val pct = p.levelPct
        val discharging = p.plug == Plug.NONE
        update(
            DeviceAlert.BATT_LOW,
            pct != null && discharging && pct <= l.battLowPct,
            p.plug.powered == true || (pct != null && pct >= l.battLowPct + 5),
            changes,
        )
        val rt = remainingMin
        update(
            DeviceAlert.RUNTIME_LOW,
            l.runtimeAlertMin > 0 && discharging && rt != null && rt <= l.runtimeAlertMin,
            p.plug.powered == true || l.runtimeAlertMin == 0 || (rt != null && rt >= l.runtimeAlertMin + 30),
            changes,
        )
        return changes
    }

    private fun update(a: DeviceAlert, on: Boolean, off: Boolean, out: MutableList<Change>) {
        if (a !in active && on) {
            active.add(a)
            out.add(Change(a, true))
        } else if (a in active && off) {
            active.remove(a)
            out.add(Change(a, false))
        }
    }
}

/** 運用プロファイル（仕様 F-02 / F-15） */
enum class BaseMode(val key: String, private val tx: Tx) {
    AUTO("auto", Tx("自動", "Auto", "Auto")),
    NORMAL("normal", Tx("通常", "Normal", "Normal")),
    SAVER("saver", Tx("省電力", "Power saving", "Économie d'énergie"));

    val label: String get() = tx.get()

    companion object {
        fun of(key: String?): BaseMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

enum class Effective(private val tx: Tx) {
    NORMAL(Tx("通常", "Normal", "Normal")),
    SAVER(Tx("省電力", "Power saving", "Économie d'énergie")),
    MAINTENANCE(Tx("メンテナンス", "Maintenance", "Maintenance")),
    RECOVERY(Tx("復旧", "Recovery", "Rétablissement"));

    val label: String get() = tx.get()
}

object Profiles {
    /** 給電状態と選択中のモードから、通常／省電力のどちらで動くか */
    fun base(mode: BaseMode, powered: Boolean?): Effective = when (mode) {
        BaseMode.NORMAL -> Effective.NORMAL
        BaseMode.SAVER -> Effective.SAVER
        // 給電状態が分からないときは電池を守る側に倒す
        BaseMode.AUTO -> if (powered == true) Effective.NORMAL else Effective.SAVER
    }

    /** 画面に出す実際のプロファイル（メンテナンス・復旧が優先） */
    fun effective(base: Effective, maintenance: Boolean, recovering: Boolean): Effective = when {
        maintenance -> Effective.MAINTENANCE
        recovering -> Effective.RECOVERY
        else -> base
    }

    fun intervalMin(base: Effective, normalMin: Int, saverMin: Int): Int =
        if (base == Effective.SAVER) saverMin else normalMin
}

/** 接続性チェックの段階ごとの結果から、最初につまずいた段階を原因として選ぶ（仕様 F-12） */
object Diagnosis {
    fun failure(simReady: Boolean?, registered: Tri, ip: Tri, dns: Tri, http: Tri): Anomaly? = when {
        simReady == false -> Anomaly.SIM
        registered == Tri.NG -> Anomaly.NO_SERVICE
        ip == Tri.NG -> Anomaly.NO_DATA
        dns == Tri.NG -> Anomaly.DNS
        http == Tri.NG -> Anomaly.INTERNET
        else -> null
    }

    fun text(failure: Anomaly?, line: LineInfo?, httpCode: Int?, httpDetail: String?): String = when (failure) {
        null -> tr("インターネットに正常に到達できます", "The internet is reachable", "Internet est accessible")
        Anomaly.SIM -> tr(
            "SIMを認識していません（挿入・接触・PINロックを確認）",
            "The SIM is not detected (check that it is inserted, its contacts and the PIN lock)",
            "La SIM n'est pas détectée (vérifiez son insertion, ses contacts et le code PIN)",
        )
        Anomaly.NO_SERVICE -> when {
            line?.airplane == true -> tr("機内モードがONです", "Airplane mode is on", "Le mode avion est activé")
            else -> tr(
                "モバイル回線に登録できていません（圏外・回線停止・APN不一致の可能性）",
                "Not registered on the mobile network (no coverage, suspended line or wrong APN)",
                "Non enregistré sur le réseau mobile (pas de couverture, ligne suspendue ou APN incorrect)",
            )
        }
        Anomaly.NO_DATA -> when {
            line?.dataEnabled == false -> tr("モバイルデータ通信がOFFです", "Mobile data is off", "Les données mobiles sont désactivées")
            else -> tr(
                "回線には登録済みですが、データ通信（IP）が確立していません（APN・データ設定を確認）",
                "Registered on the network, but no data connection (IP) (check the APN and data settings)",
                "Enregistré sur le réseau, mais sans connexion de données (IP) (vérifiez l'APN et les paramètres de données)",
            )
        }
        Anomaly.DNS -> tr(
            "IP通信はありますが、DNSで名前を解決できません",
            "There is an IP connection, but DNS lookups fail",
            "La connexion IP fonctionne, mais la résolution DNS échoue",
        )
        Anomaly.INTERNET -> when {
            httpCode != null -> tr(
                "外部サイトの応答が想定と違います（HTTP $httpCode：認証画面や通信制限の可能性）",
                "Unexpected response from the test site (HTTP $httpCode: possibly a login page or a data limit)",
                "Réponse inattendue du site de test (HTTP $httpCode : page de connexion ou limite de données possible)",
            )
            else -> tr(
                "DNSは成功しましたが、外部サイトに到達できません",
                "DNS works, but the test site can't be reached",
                "Le DNS fonctionne, mais le site de test est inaccessible",
            ) + (httpDetail?.let { tr("（$it）", " ($it)", " ($it)") } ?: "")
        }
        Anomaly.TETHER_OFF -> tr("Wi-Fiテザリングが停止しています", "The Wi-Fi hotspot is off", "Le point d'accès Wi-Fi est arrêté")
        Anomaly.TETHER_FAILED -> tr("Wi-Fiテザリングの起動に失敗しました", "The Wi-Fi hotspot failed to start", "Le point d'accès Wi-Fi n'a pas pu démarrer")
    }
}
