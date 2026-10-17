package local.tethering.dedicated

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import java.util.concurrent.Executors

/**
 * 監視の本体（仕様 F-10〜F-16 / F-19 / F-22）。
 * 状態の取得 → 判定（段階的な再確認）→ 通知・ログ → 次回の予約 を行う。
 *
 * メインスレッドから呼ぶ。端末への問い合わせと通信はバックグラウンドの 1 本のスレッドで順に行うので、
 * チェックが重なることはない（実行中に来た要求は 1 件だけ保留し、終わってから実行する）。
 */
class Monitor(private val ctx: Context) {
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val upstream = RecoveryMachine({ prefs.recheckDelaysMin })
    private val tether = RecoveryMachine({ listOf(1) })
    private val alerts = AlertTracker()

    private var started = false
    private var closed = false
    private var checking = false
    private var queued: Trigger? = null

    private var bootPending = false
    private var bootDue: Long? = null
    private var upstreamDue: Long? = null
    private var tetherDue: Long? = null
    private var lastCheckElapsed = 0L

    private var serviceState: ServiceState? = null
    private var lastInService: Boolean? = null
    private var lastDataConnected: Boolean? = null
    private var lastValidated: Boolean? = null
    private var lastEventCheck = 0L
    private var eventLock: PowerManager.WakeLock? = null

    private var lastPlug: Plug? = null
    private var lastBase: Effective? = null
    private var lastNotified: Pair<String, String>? = null

    /** 消費電力の計測（仕様 F-25 / F-26） */
    private val meter = PowerMeter(ctx, prefs)

    /** 実行中の比較テスト（仕様 F-27） */
    private var exp: ExperimentRun? = null

    /** 比較テスト（本アプリのチェック間隔）で一時的に掛ける倍率。メモリにだけ持つので、アプリが止まれば自然に元に戻る */
    private var intervalFactor = 1

    // ---- 開始・停止 ----

    fun ensureStarted(boot: Boolean, initialCheck: Boolean = true) {
        if (!started) {
            started = true
            StateStore.update { it.copy(running = true) }
            publish()
            val s = StateStore.state
            meter.begin(s.power, s.tether?.wifiOn, s.effective.label, null, null)
            // 保存された比較テストが残っている＝前回は途中で止まった。中断として扱い、元の設定に戻すよう知らせる
            ExperimentStore.load(prefs)?.let { interrupted(it) }
            LogStore.loadRecent(ctx, 20) { list ->
                StateStore.update { s ->
                    s.copy(recent = (s.recent + list).distinctBy { it.at to it.kind }.sortedByDescending { it.at }.take(30))
                }
            }
            log(Ev.START)
            recent(Ev.START, "")
            if (boot) markBoot() else if (initialCheck) requestCheck(Trigger.START)
        } else if (boot) {
            markBoot()
        }
    }

    /** 利用者が監視を止めた */
    fun stop() {
        val s = StateStore.state
        recordSegment(s.power, s.line, s.tether, transition = { if (exp != null) finishExperiment(aborted = true) })
        log(Ev.STOP)
        Scheduler.cancel(ctx)
        for (id in listOf(Notifier.ID_UPSTREAM, Notifier.ID_TETHER, Notifier.ID_BOOT)) Notifier.cancel(ctx, id)
        DeviceAlert.entries.forEach { Notifier.cancel(ctx, Notifier.deviceId(it)) }
        close()
    }

    /** サービス終了時の後片付け（予約済みのアラームは残すので、プロセスが落ちても再開する） */
    fun close() {
        if (closed) return
        closed = true
        // 計測中の区間をできるだけ残す（比較テストの保存はそのまま残し、次の起動時に中断として扱う）
        runCatching { StateStore.state.let { meter.close(it.power, it.line, it.tether?.clients) } }
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        WakeLocks.release(eventLock)
        StateStore.update { it.copy(running = false, checking = false, nextCheckAt = 0L) }
    }

    private fun markBoot() {
        // 起動直後（30 分以内）だけを本物の起動とみなす（他アプリから送られた QUICKBOOT で誤動作しないように）
        if (bootPending || SystemClock.elapsedRealtime() > 30 * 60_000L) return
        bootPending = true
        val delaySec = prefs.int(Defs.BOOT_DELAY)
        bootDue = SystemClock.elapsedRealtime() + delaySec * 1000L
        log(Ev.BOOT, action = "${delaySec}秒後に起動後チェック")
        recent(Ev.BOOT, "${delaySec}秒後に起動後チェック")
        scheduleNext()
    }

    // ---- チェックの実行 ----

    fun onAlarm() {
        if (closed) return
        val now = System.currentTimeMillis()
        if (prefs.maintUntil != 0L) {
            if (prefs.maintenanceActive(now)) {
                scheduleNext()
                return
            }
            endMaintenance(auto = true)
            return
        }
        val el = SystemClock.elapsedRealtime()
        val trigger = when {
            bootPending && (bootDue ?: 0L) <= el + 1_000L -> Trigger.BOOT
            upstream.rechecking || tether.rechecking -> Trigger.RECHECK
            else -> Trigger.SCHEDULED
        }
        requestCheck(trigger)
    }

    fun requestCheck(trigger: Trigger) {
        if (closed) return
        if (checking) {
            val q = queued
            queued = if (q == null || priority(trigger) > priority(q)) trigger else q
            return
        }
        checking = true
        StateStore.update { it.copy(checking = true) }
        val wl = WakeLocks.acquire(ctx, "check", 3 * 60_000L)
        val svc = serviceState
        val countClients = prefs.bool(Defs.CLIENTS)
        worker.execute {
            val result = runCatching {
                val p = PowerProbe.read(ctx)
                val t = TetherProbe.read(ctx, countClients)
                val l = LineProbe.read(ctx, svc)
                Probe(p, t, l, ConnectivityProbe.run(ctx, prefs, l, trigger))
            }
            main.post {
                checking = false
                if (!closed) {
                    result.onSuccess { handle(trigger, it) }.onFailure { e ->
                        StateStore.update { it.copy(checking = false) }
                        log(trigger.label, result = "チェックに失敗", detail = e.javaClass.simpleName)
                        scheduleNext()
                    }
                }
                WakeLocks.release(wl)
                queued?.let { q ->
                    queued = null
                    requestCheck(q)
                }
            }
        }
    }

    private class Probe(val power: PowerInfo, val tether: TetherInfo, val line: LineInfo, val check: CheckResult)

    private fun priority(t: Trigger) = when (t) {
        Trigger.BOOT -> 5
        Trigger.MANUAL -> 4
        Trigger.RECHECK -> 3
        Trigger.EVENT -> 2
        Trigger.SCHEDULED -> 1
        Trigger.START -> 0
    }

    private fun handle(trigger: Trigger, pr: Probe) {
        val r = pr.check
        lastCheckElapsed = SystemClock.elapsedRealtime()
        StateStore.update { it.copy(checking = false, power = pr.power, tether = pr.tether, line = pr.line, check = r) }
        noteProfileChange(pr.power.plug)
        publish() // ログの「プロファイル」欄が、取得したばかりの給電状態を反映するように
        log(trigger.label, includeCheck = true, anomaly = r.failure?.label, result = r.diagnosis, detail = r.detail.ifEmpty { null })
        recordSegment(pr.power, pr.line, pr.tether, check = r)
        if (!prefs.maintenanceActive(r.at)) {
            handleUpstream(r)
            handleTether(r.at, pr.tether)
        }
        handleAlerts(pr.power)
        if (trigger == Trigger.BOOT) bootSummary(pr)
        publish()
        scheduleNext()
        refreshNotification()
    }

    // ---- 回線・インターネットの段階的な確認（仕様 F-13） ----

    /** 予定の再確認時刻にまだ達していないか（アラームの誤差として 2 秒の余裕を見る） */
    private fun notYetDue(due: Long?): Boolean = due != null && SystemClock.elapsedRealtime() < due - 2_000L

    private fun handleUpstream(r: CheckResult) {
        val failure = r.failure?.takeIf { it.upstream }
        // 「一定時間待って再確認する」（仕様 F-13）。予定の再確認時刻より前の観測（手動チェック・状態変化など）は、
        // 異常が続いていても回数に数えない（続けてチェックしただけで確定してしまわないように）。復旧はすぐ反映する
        val ep = upstream.episode
        if (ep != null && !ep.confirmed && failure != null && notYetDue(upstreamDue)) return
        val total = prefs.recheckDelaysMin.size
        when (val step = upstream.onResult(r.at, failure)) {
            is RecoveryMachine.Step.Ok -> upstreamDue = null
            is RecoveryMachine.Step.Suspect -> {
                upstreamDue = SystemClock.elapsedRealtime() + step.nextDelayMs
                val k = step.episode.kind.label
                log(Ev.SUSPECT, anomaly = k, action = "${step.nextDelayMs / 60_000}分後に再確認（1/$total）", result = r.diagnosis)
                recent(Ev.SUSPECT, k)
            }
            is RecoveryMachine.Step.Recheck -> {
                upstreamDue = SystemClock.elapsedRealtime() + step.nextDelayMs
                var action = "${step.nextDelayMs / 60_000}分後に再確認（${step.episode.rechecks + 1}/$total）"
                if (step.revalidate && revalidate()) action = "ネットワークの再検証を依頼・$action"
                log(Ev.RECHECK_BAD, anomaly = step.episode.kind.label, action = action, result = r.diagnosis)
            }
            is RecoveryMachine.Step.Confirmed -> {
                upstreamDue = null
                val ep = step.episode
                log(Ev.CONFIRMED, anomaly = ep.kind.label, action = "通知", result = r.diagnosis)
                recent(Ev.CONFIRMED, ep.kind.label)
                if (prefs.bool(Defs.NOTIFY_ALERT)) Notifier.alert(ctx, Notifier.ID_UPSTREAM, "異常：${ep.kind.label}", upstreamText(ep, r))
            }
            is RecoveryMachine.Step.StillBad -> {
                upstreamDue = null
                if (step.remind) {
                    log(Ev.CONTINUING, anomaly = step.episode.kind.label, action = "再通知", result = r.diagnosis)
                    if (prefs.bool(Defs.NOTIFY_ALERT)) {
                        Notifier.alert(ctx, Notifier.ID_UPSTREAM, "まだ復旧していません：${step.episode.kind.label}", upstreamText(step.episode, r))
                    }
                }
            }
            is RecoveryMachine.Step.Recovered -> {
                upstreamDue = null
                val ep = step.episode
                val dur = Fmt.duration(step.durationMs)
                if (ep.confirmed) {
                    log(Ev.RECOVERED, anomaly = ep.kind.label, result = "所要 $dur")
                    recent(Ev.RECOVERED, "${ep.kind.label}（$dur）")
                    Notifier.cancel(ctx, Notifier.ID_UPSTREAM)
                    if (prefs.bool(Defs.NOTIFY_RECOVERY)) Notifier.info(ctx, Notifier.ID_UPSTREAM, "復旧しました", "${ep.kind.label}は${dur}で復旧しました")
                } else {
                    log(Ev.BLIP, anomaly = ep.kind.label, result = "再確認${ep.rechecks}回目で正常")
                    recent(Ev.BLIP, ep.kind.label)
                }
            }
        }
    }

    private fun upstreamText(ep: RecoveryMachine.Episode, r: CheckResult): String {
        val advice = when (ep.kind) {
            Anomaly.SIM -> "SIMの挿し直し・接点を確認してください。"
            Anomaly.NO_SERVICE -> "電波状況、回線の契約状態、APN設定を確認してください。"
            Anomaly.NO_DATA -> "モバイルデータがONか、APN設定を確認してください。"
            Anomaly.DNS, Anomaly.INTERNET -> "しばらく待っても直らなければ、機内モードのON/OFFを手動で試してください（このアプリは自動では切り替えません）。"
            else -> ""
        }
        return "${r.diagnosis}\n${Fmt.time(ep.startedAt)}から続いています。\n$advice".trim()
    }

    /** 低リスクの復旧処理：システムにモバイル回線の再検証を依頼する（接続を切ったりはしない） */
    private fun revalidate(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = ConnectivityProbe.upstreamNetwork(cm) ?: return false
        return runCatching {
            cm.reportNetworkConnectivity(n, false)
            true
        }.getOrDefault(false)
    }

    // ---- テザリング ----

    fun onTetherBroadcast(intent: Intent) {
        if (!started || closed) return
        val isAp = intent.action == TetherProbe.ACTION_AP
        val countClients = prefs.bool(Defs.CLIENTS)
        worker.execute {
            val t = runCatching {
                TetherProbe.read(ctx, countClients, ap = if (isAp) intent else null, tether = if (isAp) null else intent)
            }.getOrNull() ?: return@execute
            main.post { onTether(t) }
        }
    }

    private fun onTether(t: TetherInfo) {
        if (closed) return
        val prev = StateStore.state.tether?.wifiOn
        StateStore.update { it.copy(tether = t) }
        if (!prefs.bool(Defs.TETHER_EXPECTED) && prev != null && prev != t.wifiOn) {
            log(Ev.TETHER, result = "Wi-Fiテザリング ${Fmt.ap(t.apState)}")
        }
        // テザリングの ON/OFF で消費が大きく変わるので、区間を分ける
        if (prev != null && t.wifiOn != null && prev != t.wifiOn) {
            val s = StateStore.state
            recordSegment(s.power, s.line, t)
        } else {
            meter.noteClients(t.clients)
        }
        if (!prefs.maintenanceActive()) handleTether(System.currentTimeMillis(), t)
        publish()
        scheduleNext()
        refreshNotification()
    }

    private fun handleTether(now: Long, t: TetherInfo) {
        if (!prefs.bool(Defs.TETHER_EXPECTED)) {
            if (tether.episode != null) {
                tether.reset()
                tetherDue = null
                Notifier.cancel(ctx, Notifier.ID_TETHER)
            }
            return
        }
        val failure = when {
            t.apState == TetherInfo.AP_FAILED -> Anomaly.TETHER_FAILED
            t.wifiOn == false -> Anomaly.TETHER_OFF
            else -> null // ON、または取得不可（推測で異常にしない）
        }
        // 停止中 → OFF のように続けて届く通知や手動チェックで、猶予（1分）を待たずに確定しないようにする
        val ep = tether.episode
        if (ep != null && !ep.confirmed && failure != null && notYetDue(tetherDue)) return
        when (val step = tether.onResult(now, failure)) {
            is RecoveryMachine.Step.Ok -> tetherDue = null
            is RecoveryMachine.Step.Suspect -> {
                tetherDue = SystemClock.elapsedRealtime() + step.nextDelayMs
                log(Ev.TETHER, anomaly = step.episode.kind.label, action = "1分後に再確認")
                recent(Ev.TETHER, step.episode.kind.label + "（確認中）")
            }
            is RecoveryMachine.Step.Recheck -> tetherDue = SystemClock.elapsedRealtime() + step.nextDelayMs
            is RecoveryMachine.Step.Confirmed -> {
                tetherDue = null
                log(Ev.TETHER, anomaly = step.episode.kind.label, action = "通知")
                recent(Ev.TETHER, step.episode.kind.label)
                if (prefs.bool(Defs.NOTIFY_ALERT)) {
                    Notifier.alert(
                        ctx, Notifier.ID_TETHER, step.episode.kind.label,
                        "再起動や自動OFFで止まった可能性があります。テザリングをONにしてください（このアプリからは自動でONにできません）。",
                        openTether = true,
                    )
                }
            }
            is RecoveryMachine.Step.StillBad -> {
                tetherDue = null
                if (step.remind && prefs.bool(Defs.NOTIFY_ALERT)) {
                    Notifier.alert(ctx, Notifier.ID_TETHER, "まだ停止しています：${step.episode.kind.label}", "テザリングをONにしてください。", openTether = true)
                }
            }
            is RecoveryMachine.Step.Recovered -> {
                tetherDue = null
                val ep = step.episode
                val dur = Fmt.duration(step.durationMs)
                log(Ev.TETHER, anomaly = ep.kind.label, result = "再開（$dur）")
                recent(Ev.TETHER, "Wi-Fiテザリング再開")
                Notifier.cancel(ctx, Notifier.ID_TETHER)
                if (ep.confirmed && prefs.bool(Defs.NOTIFY_RECOVERY)) Notifier.info(ctx, Notifier.ID_TETHER, "Wi-Fiテザリングが再開しました", "停止していた時間: $dur")
            }
        }
    }

    // ---- 回線のイベント（ポーリングせずに変化を拾う） ----

    fun onServiceState(ss: ServiceState?) {
        serviceState = ss
        val inService = ss?.state == ServiceState.STATE_IN_SERVICE
        if (lastInService != null && lastInService != inService) eventCheck(recoveryHint = inService)
        lastInService = inService
    }

    fun onDataConnection(state: Int) {
        val connected = state == TelephonyManager.DATA_CONNECTED
        if (lastDataConnected != null && lastDataConnected != connected) eventCheck(recoveryHint = connected)
        lastDataConnected = connected
    }

    fun onCellNetwork(available: Boolean) = eventCheck(recoveryHint = available)

    fun onValidated(v: Boolean) {
        if (lastValidated != null && lastValidated != v) eventCheck(recoveryHint = v)
        lastValidated = v
    }

    fun onAirplane(on: Boolean) = eventCheck(recoveryHint = !on)

    private fun eventCheck(recoveryHint: Boolean) {
        if (!started || closed || prefs.maintenanceActive()) return
        val ep = upstream.episode
        // 確認中は予定どおりの再確認に任せる。正常時に「良くなった」通知が来ても確認しない
        if (ep != null && !recoveryHint) return
        if (ep == null && recoveryHint) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventCheck < 60_000L) return
        lastEventCheck = now
        WakeLocks.release(eventLock)
        eventLock = WakeLocks.acquire(ctx, "event", 30_000L)
        // 状態が落ち着くまで少し待ってから確認する
        main.postDelayed({ requestCheck(Trigger.EVENT) }, 15_000L)
    }

    // ---- 電源・温度（仕様 F-15 / F-16） ----

    fun onBattery(intent: Intent?) {
        if (!started || closed) return
        worker.execute {
            val p = runCatching { PowerProbe.read(ctx, intent, includeSoc = false) }.getOrNull() ?: return@execute
            main.post { onPower(p) }
        }
    }

    private fun onPower(p: PowerInfo) {
        if (closed) return
        val prev = StateStore.state.power
        val merged = p.copy(socTempC = prev?.socTempC, socZone = prev?.socZone)
        StateStore.update { it.copy(power = merged) }
        if (prev != null && prev.plug.powered != merged.plug.powered) {
            // 給電の有無が変わったら区間を分ける（区間はバッテリー駆動か給電中のどちらか一方になる）
            val s = StateStore.state
            recordSegment(merged, s.line, s.tether)
        } else {
            meter.addCurrent(merged)
        }
        noteProfileChange(merged.plug)
        handleAlerts(merged)
        publish()
        scheduleNext()
        refreshNotification()
    }

    fun onScreen(on: Boolean) {
        if (!started || closed) return
        meter.onScreen(on)
    }

    fun onThermal(status: Int) {
        if (!started || closed) return
        val p = StateStore.state.power ?: return
        val np = p.copy(thermalStatus = status)
        StateStore.update { it.copy(power = np) }
        handleAlerts(np)
        publish()
        refreshNotification()
    }

    private fun handleAlerts(p: PowerInfo) {
        val remainingMin = meter.view(p).remainingMin
        for (c in alerts.evaluate(p, prefs.limits, remainingMin)) {
            val a = c.alert
            val value = when (a) {
                DeviceAlert.TEMP_WARN, DeviceAlert.TEMP_CRIT -> Fmt.temp(p.tempC)
                DeviceAlert.THERMAL -> Fmt.thermal(p.thermalStatus)
                DeviceAlert.BATT_LOW -> Fmt.num(p.levelPct, "%")
                DeviceAlert.RUNTIME_LOW -> remainingMin?.let { "残り" + Fmt.duration(it * 60_000L) } ?: Fmt.NA
            }
            if (c.entered) {
                log(Ev.ALERT, anomaly = a.label, result = value)
                recent(Ev.ALERT, "${a.label}（$value）")
                if (a == DeviceAlert.TEMP_CRIT) Notifier.cancel(ctx, Notifier.deviceId(DeviceAlert.TEMP_WARN))
                if (prefs.bool(Defs.NOTIFY_ALERT)) Notifier.alert(ctx, Notifier.deviceId(a), "${a.label}（$value）", advice(a))
            } else {
                log(Ev.ALERT_CLEAR, anomaly = a.label, result = value)
                recent(Ev.ALERT_CLEAR, a.label)
                Notifier.cancel(ctx, Notifier.deviceId(a))
            }
        }
    }

    private fun advice(a: DeviceAlert) = when (a) {
        DeviceAlert.TEMP_WARN -> "直射日光・布の上・ケースでの熱こもりを避け、風通しのよい場所に置いてください。"
        DeviceAlert.TEMP_CRIT -> "高温が続くと電池の劣化や膨張、端末の停止につながります。給電を一時的に外す・涼しい場所へ移すなどしてください。"
        DeviceAlert.THERMAL -> "端末が熱による制限をかけています。速度低下やテザリング停止が起きることがあります。"
        DeviceAlert.BATT_LOW -> "給電してください。電池が切れるとテザリングが止まります。"
        DeviceAlert.RUNTIME_LOW -> "今の消費ペースが続くと、まもなく電池が切れてテザリングが止まります。給電してください。"
    }

    // ---- 起動後の状態確認（仕様 F-14） ----

    private fun bootSummary(pr: Probe) {
        bootPending = false
        bootDue = null
        prefs.lastBootCheckAt = System.currentTimeMillis()
        val actions = ArrayList<String>()
        if (pr.tether.wifiOn == false) {
            actions.add("Wi-Fiテザリングが OFF です（手動でONにしてください）")
            // この通知で知らせるので、テザリング停止の通知は重ねて出さない
            tether.markNotified(pr.check.at)
            tetherDue = null
        }
        if (pr.power.dataSaver == true) actions.add("データセーバーが ON です（テザリングが使えません）")
        if (pr.power.batteryOptExempt == false) actions.add("電池の最適化から除外されていません")
        if (pr.tether.autoOff == true) actions.add("アクセスポイントの自動OFFが有効です")
        if (pr.power.powerSave == true) actions.add("バッテリーセーバーが ON です")
        val pending = pr.check.failure?.let { "回線: ${it.label}（再確認中）" }
        val lines = actions + listOfNotNull(pending)
        log(
            Ev.BOOT_CHECK,
            result = if (lines.isEmpty()) "問題なし" else "要確認 ${lines.size}件",
            detail = lines.joinToString(" / ").ifEmpty { null },
        )
        recent(Ev.BOOT_CHECK, if (lines.isEmpty()) "問題なし" else "要確認 ${lines.size}件")
        if (!prefs.bool(Defs.NOTIFY_BOOT)) return
        if (actions.isEmpty()) {
            Notifier.info(ctx, Notifier.ID_BOOT, "起動後チェック：問題なし", pending ?: "テザリング・回線・設定の状態は正常です")
        } else {
            Notifier.alert(ctx, Notifier.ID_BOOT, "起動後チェック：要確認", lines.joinToString("\n"), openTether = pr.tether.wifiOn == false)
        }
    }

    // ---- 設定・メンテナンス（仕様 F-02 / F-22） ----

    fun onSettingsChanged() {
        if (!started || closed) return
        if (!prefs.bool(Defs.TETHER_EXPECTED) && tether.episode != null) {
            tether.reset()
            tetherDue = null
            Notifier.cancel(ctx, Notifier.ID_TETHER)
        }
        noteProfileChange(StateStore.state.power?.plug ?: Plug.UNKNOWN)
        publish()
        scheduleNext()
        refreshNotification()
    }

    fun setMaintenance(until: Long) {
        if (closed) return
        val now = System.currentTimeMillis()
        if (until <= now) {
            if (prefs.maintUntil != 0L) endMaintenance(auto = false)
            return
        }
        prefs.maintUntil = until
        // 作業中は自動の再確認・通知を止める（進行中の確認はいったん打ち切る）
        upstream.reset()
        tether.reset()
        upstreamDue = null
        tetherDue = null
        log(Ev.MAINT_ON, result = "〜${Fmt.hm(until)}")
        recent(Ev.MAINT_ON, "〜${Fmt.hm(until)}")
        publish()
        scheduleNext()
        refreshNotification()
    }

    private fun endMaintenance(auto: Boolean) {
        prefs.maintUntil = 0L
        val why = if (auto) "時間切れ" else "手動で終了"
        log(Ev.MAINT_OFF, result = why)
        recent(Ev.MAINT_OFF, why)
        publish()
        requestCheck(Trigger.SCHEDULED)
    }

    // ---- 予約・表示 ----

    private fun scheduleNext() {
        if (closed || !started) return
        val nowWall = System.currentTimeMillis()
        val nowEl = SystemClock.elapsedRealtime()
        val delay = if (prefs.maintenanceActive(nowWall)) {
            prefs.maintUntil - nowWall
        } else {
            val interval = StateStore.state.intervalMin * 60_000L
            val base = if (lastCheckElapsed == 0L) 5_000L else lastCheckElapsed + interval - nowEl
            // 比較テストの条件の終わり（バッテリー駆動中だけ時間を数えるので、そのときだけ予約する）
            val expDelay = exp?.takeIf { it.measuring && StateStore.state.power?.plug == Plug.NONE }?.let { e ->
                (e.remainingSec - meter.openBatterySec(e.condition)).coerceAtLeast(0) * 1000L + 5_000L
            }
            listOfNotNull(base, upstreamDue?.minus(nowEl), tetherDue?.minus(nowEl), bootDue?.minus(nowEl), expDelay).min()
        }
        val at = Scheduler.schedule(ctx, delay)
        StateStore.update { it.copy(nextCheckAt = at) }
    }

    /** 給電の変化・プロファイルの切り替えを記録する（仕様 F-15：切り替えはログに残す） */
    private fun noteProfileChange(plug: Plug) {
        val base = Profiles.base(prefs.mode, plug.powered)
        val prevPlug = lastPlug
        val prevBase = lastBase
        lastPlug = plug
        lastBase = base
        if (prevPlug != null && prevPlug.powered != plug.powered) {
            log(Ev.POWER, result = plug.label)
            recent(Ev.POWER, plug.label)
        }
        if (prevBase != null && prevBase != base) {
            val interval = Profiles.intervalMin(base, prefs.int(Defs.INTERVAL_NORMAL), prefs.int(Defs.INTERVAL_SAVER))
            log(Ev.PROFILE, result = "${prevBase.label} → ${base.label}", detail = "チェック間隔 ${interval}分")
            recent(Ev.PROFILE, "${prevBase.label} → ${base.label}")
            publish()
            scheduleNext()
        }
    }

    private fun publish() {
        val plug = StateStore.state.power?.plug ?: Plug.UNKNOWN
        val mode = prefs.mode
        val base = Profiles.base(mode, plug.powered)
        val maintUntil = prefs.maintUntil
        val eff = Profiles.effective(base, maintUntil > System.currentTimeMillis(), upstream.rechecking || tether.rechecking)
        val interval = Profiles.intervalMin(base, prefs.int(Defs.INTERVAL_NORMAL), prefs.int(Defs.INTERVAL_SAVER)) * intervalFactor
        val total = prefs.recheckDelaysMin.size
        val up = upstream.episode?.let { StateStore.EpisodeView(it.kind, it.startedAt, minOf(it.rechecks + 1, total), total, it.confirmed) }
        val te = tether.episode?.let { StateStore.EpisodeView(it.kind, it.startedAt, 1, 1, it.confirmed) }
        val meterView = meter.view(StateStore.state.power)
        val expView = exp?.let { e ->
            val cond = e.condition ?: 'A'
            StateStore.ExperimentView(
                title = e.candidate.title, labelA = e.candidate.labelA, labelB = e.candidate.labelB,
                index = e.index, total = e.phases.size, condition = cond,
                measuredSec = (e.current?.sec ?: 0) + meter.openBatterySec(e.condition), phaseSec = e.phaseSec,
                waitingSwitch = e.waitingSwitch, instruction = if (cond == 'B') e.candidate.toB else e.candidate.toA, auto = e.candidate.auto,
            )
        }
        StateStore.update {
            it.copy(
                mode = mode, base = base, effective = eff, intervalMin = interval, maintUntil = maintUntil,
                upstream = up, tetherIssue = te, alerts = alerts.active.toSet(), meter = meterView, experiment = expView,
            )
        }
    }

    // ---- 消費電力の区間と比較テスト（仕様 F-25 / F-27） ----

    /**
     * 今の区間を閉じて記録し、比較テストに反映してから、次の区間を始める。
     * [transition] は区間の境目で行う状態の変更（比較テストの開始・切り替え・中止）。閉じた区間は変更前の状態として数える。
     */
    private fun recordSegment(
        p: PowerInfo?,
        line: LineInfo?,
        t: TetherInfo?,
        check: CheckResult? = null,
        transition: (() -> Unit)? = null,
    ) {
        val closedSeg = meter.close(p, line, t?.clients)
        val e = exp
        if (closedSeg != null && e != null && e.measuring && closedSeg.onBattery && closedSeg.condition == e.condition &&
            !prefs.maintenanceActive()
        ) {
            closedSeg.consumption?.let { e.addSegment(it, closedSeg.bytes, closedSeg.clientsMax, closedSeg.rsrp) }
            if (check != null) e.addCheck(check.failure == null)
            if (e.phaseComplete) advanceExperiment()
            ExperimentStore.save(prefs, exp)
        }
        transition?.invoke()
        val next = exp
        meter.begin(p, t?.wifiOn, StateStore.state.effective.label, next?.candidate?.title, next?.takeIf { it.measuring }?.condition)
    }

    fun startExperiment(key: String, customA: String?, customB: String?) {
        if (!started || closed || exp != null) return
        val base = Candidates.of(key) ?: return
        val cand = if (key == Candidates.KEY_CUSTOM) {
            val a = customA?.trim()?.ifEmpty { null } ?: base.labelA
            val b = customB?.trim()?.ifEmpty { null } ?: base.labelB
            base.copy(title = "$a → $b", labelA = a, labelB = b, toB = "「$b」に切り替えてください。", toA = "「$a」に戻してください。")
        } else {
            base
        }
        val run = ExperimentRun(cand, prefs.int(Defs.EXP_PHASE) * 60L, prefs.int(Defs.EXP_REPEATS), prefs.int(Defs.EXP_MIN_GAIN), System.currentTimeMillis())
        val s = StateStore.state
        // ここまでの区間は比較テストの外。ここから条件 A（今の設定）として数える
        recordSegment(s.power, s.line, s.tether, transition = {
            exp = run
            intervalFactor = 1
            ExperimentStore.save(prefs, run)
        })
        log(Ev.EXP_START, result = cand.title, detail = "1条件 ${run.phaseSec / 60}分 × ${run.repeats}回（バッテリー駆動中だけ計測）")
        recent(Ev.EXP_START, cand.title)
        publish()
        scheduleNext()
        refreshNotification()
    }

    /** 手動の切り替えが済んだ（利用者が「切り替えた」を押した） */
    fun confirmExperimentSwitch() {
        val e = exp ?: return
        if (!e.waitingSwitch) return
        val s = StateStore.state
        recordSegment(s.power, s.line, s.tether, transition = {
            e.confirmSwitch()
            ExperimentStore.save(prefs, e)
        })
        val cond = e.condition ?: return
        log(Ev.EXP_SWITCH, result = "条件$cond（${label(e, cond)}）の計測を開始")
        Notifier.cancel(ctx, Notifier.ID_EXPERIMENT)
        publish()
        scheduleNext()
        refreshNotification()
    }

    fun abortExperiment() {
        if (exp == null) return
        val s = StateStore.state
        recordSegment(s.power, s.line, s.tether, transition = { finishExperiment(aborted = true) })
        publish()
        scheduleNext()
        refreshNotification()
    }

    private fun label(e: ExperimentRun, cond: Char) = if (cond == 'B') e.candidate.labelB else e.candidate.labelA

    /** 条件の計測が終わった。次の条件へ進むか、最後なら結果を出す（状態の変更だけ。区間の区切りは呼び出し側） */
    private fun advanceExperiment() {
        val e = exp ?: return
        val next = e.advance()
        if (next == null) {
            finishExperiment(aborted = false)
            return
        }
        if (e.candidate.auto) {
            intervalFactor = if (next == 'B') Candidates.INTERVAL_FACTOR else 1
            log(Ev.EXP_SWITCH, result = "条件$next（${label(e, next)}）を自動で開始")
        } else {
            log(Ev.EXP_SWITCH, result = "条件$next（${label(e, next)}）への切り替え待ち")
            Notifier.experiment(
                ctx, "比較テスト：条件$next に切り替えてください",
                "${if (next == 'B') e.candidate.toB else e.candidate.toA}\n切り替えたら「切り替えた」を押してください（押すまで計測しません）。",
                confirm = true,
            )
        }
    }

    private fun finishExperiment(aborted: Boolean) {
        val e = exp ?: return
        val settingIsB = e.settingIsB
        val o = e.outcome(aborted)
        exp = null
        intervalFactor = 1
        ExperimentStore.addHistory(prefs, e, o, System.currentTimeMillis())
        ExperimentStore.save(prefs, null)
        val summary = experimentSummary(o)
        log(Ev.EXP_END, anomaly = e.candidate.title, result = o.decision.label, detail = summary)
        recent(Ev.EXP_END, "${e.candidate.title}：${o.decision.label}")
        val text = when {
            e.candidate.auto -> "$summary\nチェック間隔は元に戻しました。"
            o.decision == Decision.ADOPT -> "$summary\nこの設定（${e.candidate.labelB}）のままにしてください。"
            settingIsB -> "$summary\n${e.candidate.toA}"
            else -> "$summary\n設定は元のままです。"
        }
        Notifier.experiment(ctx, "比較テストの結果：${o.decision.label}", text, confirm = false)
    }

    private fun experimentSummary(o: Outcome): String {
        fun v(x: Double?) = x?.let { "%.0f %s".format(it, o.unit) } ?: Fmt.NA
        val saving = o.savingPct?.let { "（%+.1f%%）".format(-it) } ?: ""
        return (listOf("A ${v(o.avgA)} → B ${v(o.avgB)}$saving") + o.notes).joinToString("\n")
    }

    /** 前回、比較テストの途中でアプリが止まった */
    private fun interrupted(run: ExperimentRun) {
        val o = run.outcome(aborted = true)
        ExperimentStore.addHistory(prefs, run, o, System.currentTimeMillis())
        ExperimentStore.save(prefs, null)
        log(Ev.EXP_END, anomaly = run.candidate.title, result = o.decision.label, detail = "アプリの再起動で中断")
        recent(Ev.EXP_END, "中断：${run.candidate.title}")
        // 本アプリのチェック間隔はメモリにだけ持っていたので、すでに元に戻っている
        if (!run.candidate.auto) {
            val text = if (run.settingIsB) run.candidate.toA else "設定は元のままです。"
            Notifier.experiment(ctx, "比較テストを中断しました", "アプリが再起動したため「${run.candidate.title}」を中止しました。\n$text", confirm = false)
        }
    }

    private fun refreshNotification() {
        if (closed) return
        val s = StateStore.state
        val text = Notifier.ongoingText(s)
        if (text == lastNotified) return
        lastNotified = text
        Notifier.update(ctx, s)
    }

    private fun log(
        event: String,
        includeCheck: Boolean = false,
        anomaly: String? = null,
        action: String? = null,
        result: String? = null,
        detail: String? = null,
    ) = LogStore.write(ctx, event, StateStore.state, includeCheck, anomaly, action, result, detail)

    private fun recent(kind: String, text: String) = StateStore.addRecent(RecentEvent(System.currentTimeMillis(), kind, text))
}
