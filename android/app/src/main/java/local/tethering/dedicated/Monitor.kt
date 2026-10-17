package local.tethering.dedicated

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone
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

    /** 運用中の電波の悪化（仕様 F-29） */
    private val signal = SignalWatch()

    /** 待機中の通信の検知（仕様 F-33） */
    private val traffic = TrafficWatch().apply { lastIdleAlertAt = prefs.trafficIdleAlertAt }

    /** ローカル管理画面（仕様 F-43） */
    private val web = LocalServer(ctx) { path -> WebPages.route(path) { WebData.collect(ctx) } }
    private var webRetries = 0

    /** 自動切り替え（仕様 F-42） */
    private val auto = AutoSwitch(ctx, prefs)

    /** 接続 0 台（またはテザリング OFF）になった時刻（経過時間）と直近の区間の通信量。待機（仕様 F-32）の判断に使う */
    private var zeroSince: Long? = null
    private var lastSegKbPerHour: Double? = null
    private var idleReason: IdleMode.Reason? = null

    /** 前回のチェックのときのモバイル回線の受信量（通信が流れているかを見る。仕様 F-32） */
    private var lastRx: Long? = null

    /** 外部への通知（仕様 F-44） */
    private val ext = ExtSender(prefs).apply {
        onLog = { text -> log(Ev.EXT, result = text) }
        onChanged = { if (!closed) publish() }
    }

    /** スマートプラグ（仕様 F-45） */
    private val smartPlug = PlugSwitch(prefs).apply {
        onEvent = { e -> onPlugEvent(e) }
        onChanged = { if (!closed) publish() }
    }

    // 時間帯スケジュール（仕様 F-37）。再開のやり直しの状態（メモリだけ。止めた時間帯は Prefs に残す）
    private var resumePending = false
    private var resumeTries = 0
    private var resumeNextAt = 0L

    /** 再開をあきらめた理由（テザリング停止の通知の文面に使う） */
    private var resumeFailed: String? = null

    /** 止めるのを待っていることを記録した時間帯（同じ時間帯で何度も記録しない） */
    private var schedWaitLogged = 0L
    private var schedNote: String? = null

    /** テザリング ON・接続 0 台になった時刻（経過時間）。止めるのは 0 台が 10 分続いてから（仕様 F-37） */
    private var apIdleSince: Long? = null

    /** 接続 0 台が 10 分に達する時刻（経過時間）。その時刻に判断し直す */
    private var schedIdleDue: Long? = null

    // ---- 開始・停止 ----

    fun ensureStarted(boot: Boolean, initialCheck: Boolean = true) {
        if (!started) {
            started = true
            StateStore.update { it.copy(running = true) }
            publish()
            val s = StateStore.state
            meter.begin(s.power, s.tether?.wifiOn, s.effective.label, null, null, s.tether?.clients)
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
        // 監視をやめても、このアプリが変えたテザリング・給電の状態は残さない（止めていたら再開・ON に戻す）
        if (prefs.schedStoppedWindow != 0L) {
            TetherControl.start(ctx) { }
            prefs.schedStoppedWindow = 0L
            log(Ev.SCHEDULE, result = "監視の停止に合わせてテザリングを再開")
        }
        smartPlug.release("監視を停止")
        log(Ev.STOP)
        Scheduler.cancel(ctx)
        for (id in listOf(Notifier.ID_UPSTREAM, Notifier.ID_TETHER, Notifier.ID_BOOT, Notifier.ID_SCHEDULE, Notifier.ID_PLUG)) Notifier.cancel(ctx, id)
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
        web.stop()
        ext.close()
        smartPlug.close()
        WakeLocks.release(eventLock)
        StateStore.update { it.copy(running = false, checking = false, nextCheckAt = 0L, web = null) }
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
                // メンテナンス中は新たに止めないが、止めていたテザリングの再開は行う（仕様 F-37）
                schedEvaluate(StateStore.state.tether)
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
        // 異常の確認中は省かない（仕様 F-32）
        val troubled = upstream.episode != null || tether.episode != null
        val skipEnabled = prefs.bool(Defs.SKIP_PROBE)
        val prevRx = lastRx
        val exclude = plugExclude()
        worker.execute {
            val result = runCatching {
                val p = PowerProbe.read(ctx)
                val t = TetherProbe.read(ctx, countClients, exclude = exclude)
                val l = LineProbe.read(ctx, svc)
                val rx = mobileRx()
                val delta = if (prevRx != null && rx != null && rx >= prevRx) rx - prevRx else null
                val skip = ProbeSkip.should(skipEnabled, trigger, troubled, t.clients, delta)
                Probe(p, t, l, ConnectivityProbe.run(ctx, prefs, l, trigger, skipReach = skip), rx)
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

    private class Probe(val power: PowerInfo, val tether: TetherInfo, val line: LineInfo, val check: CheckResult, val rx: Long?)

    private fun mobileRx(): Long? = TrafficStats.getMobileRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() }

    /** このテザリングにつないだスマートプラグは、接続端末として数えない（仕様 F-45） */
    private fun plugExclude(): Set<String> = PlugUrls.exclude(prefs)

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
        lastRx = pr.rx
        StateStore.update { it.copy(checking = false, power = pr.power, tether = pr.tether, line = pr.line, check = r) }
        noteZero(pr.tether)
        autoEvaluate(pr.power)
        noteProfileChange(pr.power.plug)
        publish() // ログの「プロファイル」欄が、取得したばかりの給電状態を反映するように
        log(trigger.label, includeCheck = true, anomaly = r.failure?.label, result = r.diagnosis, detail = r.detail.ifEmpty { null })
        recordSegment(pr.power, pr.line, pr.tether, check = r)
        if (!prefs.maintenanceActive(r.at)) {
            handleUpstream(r)
            handleTether(r.at, pr.tether)
        }
        handleAlerts(pr.power)
        if (!prefs.maintenanceActive(r.at)) handleSignal(pr.line)
        if (trigger == Trigger.BOOT) bootSummary(pr)
        schedEvaluate(pr.tether)
        smartPlug.evaluate(pr.power, pr.tether.wifiOn)
        // 回線が正常なら、送れていなかった外部への通知を送る
        if (r.failure == null) ext.flush()
        updateWeb(pr.tether)
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
                    // 止まっている間は外へ送れないので、復旧してから止まっていた時間を送る（仕様 F-44）
                    ext.post(
                        ExtNotify.Category.RECOVERY, "回線が復旧しました",
                        "${ep.kind.label}が続いていました。止まっていた時間: $dur（${Fmt.hm(ep.startedAt)}〜${Fmt.hm(r.at)}）",
                    )
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
        val exclude = plugExclude()
        worker.execute {
            val t = runCatching {
                TetherProbe.read(ctx, countClients, ap = if (isAp) intent else null, tether = if (isAp) null else intent, exclude = exclude)
            }.getOrNull() ?: return@execute
            main.post { onTether(t) }
        }
    }

    private fun onTether(t: TetherInfo) {
        if (closed) return
        val prev = StateStore.state.tether?.wifiOn
        StateStore.update { it.copy(tether = t) }
        noteZero(t)
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
        // 再開できたか・利用者が手で ON にしたかを見る（仕様 F-37）
        schedEvaluate(t)
        updateWeb(t)
        publish()
        scheduleNext()
        refreshNotification()
    }

    private fun handleTether(now: Long, t: TetherInfo) {
        // 比較テスト「使わないときはテザリングを止める」の間は、テザリングの停止を異常としない（利用者が止めるため）
        val testingOff = exp?.candidate?.key == Candidates.KEY_TETHER_OFF
        // 時間帯スケジュールで止めている間（再開のやり直し中を含む）も異常としない（仕様 F-37）
        val scheduled = prefs.schedStoppedWindow != 0L
        if (!prefs.bool(Defs.TETHER_EXPECTED) || testingOff || scheduled) {
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
                val failed = resumeFailed
                resumeFailed = null
                val text = if (failed != null) {
                    "時間帯スケジュールで再開できませんでした（$failed）。テザリングを手動で ON にしてください。"
                } else {
                    "再起動や自動OFFで止まった可能性があります。テザリングをONにしてください（このアプリは自動では ON にしません）。"
                }
                if (prefs.bool(Defs.NOTIFY_ALERT)) Notifier.alert(ctx, Notifier.ID_TETHER, step.episode.kind.label, text, openTether = true)
                // テザリングが止まってもモバイル回線は使えることが多いので、外へも知らせる（仕様 F-44）
                ext.post(ExtNotify.Category.ALERT, step.episode.kind.label, text)
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
                if (ep.confirmed) {
                    ext.post(ExtNotify.Category.RECOVERY, "Wi-Fiテザリングが再開しました", "停止していた時間: $dur（${Fmt.hm(ep.startedAt)}〜${Fmt.hm(now)}）")
                }
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
        autoEvaluate(merged)
        noteProfileChange(merged.plug)
        handleAlerts(merged)
        // 電池の放送は残量・温度が変わるたびに届くので、スマートプラグの判断はここで行う（判断のために端末を起こさない）
        smartPlug.evaluate(merged, StateStore.state.tether?.wifiOn)
        publish()
        scheduleNext()
        refreshNotification()
    }

    // ---- 自動切り替え（仕様 F-42）。給電の有無・残量が変わったときに見直す ----

    private fun autoEvaluate(p: PowerInfo?) {
        // 比較テストの間は変えない（条件をそろえるため）
        for (c in auto.evaluate(p, hold = exp != null)) {
            val why = when {
                p?.plug == Plug.NONE -> "バッテリー駆動・残量 ${Fmt.num(p.levelPct, "%")}"
                else -> p?.plug?.label ?: Fmt.NA
            }
            log(Ev.AUTO, result = c, detail = why)
            recent(Ev.AUTO, c)
        }
    }

    /** 自動で変えた OS の設定をすべて元に戻し、自動切り替えを OFF にする（設定画面の「すべて元に戻す」） */
    fun autoRestoreAll() {
        prefs.put(Defs.AUTO_SAVER, false)
        prefs.put(Defs.AUTO_DOZE, false)
        val done = auto.restoreAll()
        log(Ev.AUTO, result = "すべて元に戻した", detail = done.joinToString(" / ").ifEmpty { "変えていた設定はなし" })
        recent(Ev.AUTO, "すべて元に戻した")
        publish()
    }

    // ---- 時間帯スケジュール（仕様 F-37）。チェック・テザリングの放送・設定の変更のたびに判断する ----

    private fun schedEvaluate(t: TetherInfo?) {
        if (closed) return
        val enabled = prefs.bool(Defs.TETHER_SCHEDULE)
        val stopped = prefs.schedStoppedWindow
        if (!enabled && stopped == 0L) return
        val now = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        val q = QuietHours.parse(prefs.text(Defs.QUIET_HOURS))
        val zeroFor = apIdleSince?.let { el - it }
        val d = TetherSchedule.decide(
            TetherSchedule.Input(
                enabled, q, now, t?.wifiOn, t?.clients, zeroFor, stopped, prefs.schedHandledWindow,
                hold = exp != null || prefs.maintenanceActive(now),
            ),
            TimeZone.getDefault(),
        )
        val w = d.window
        // 時間帯が終わったら「止めるのを待っています」の表示を消す
        if (w == null && schedWaitLogged != 0L) {
            schedWaitLogged = 0L
            if (stopped == 0L) schedNote = null
        }
        schedIdleDue = null
        when (d.act) {
            TetherSchedule.Act.NONE -> Unit
            TetherSchedule.Act.WAIT -> {
                // 接続 0 台が 10 分に達する時刻に判断し直す（それまでに端末がつながれば、またそのとき待つ）
                val idle = apIdleSince
                if (t?.clients == 0 && idle != null) schedIdleDue = idle + TetherSchedule.MIN_ZERO_MS + 1_000L
                val why = when (t?.clients) {
                    null -> "接続端末の数が分からない"
                    0 -> "接続 0 台になってから 10 分たっていない"
                    else -> "接続端末が ${t.clients} 台ある"
                }
                schedNote = "${why}ため、止めるのを待っています"
                // 記録は 1 つの時間帯につき 1 回だけ
                if (w != null && schedWaitLogged != w.first) {
                    schedWaitLogged = w.first
                    log(Ev.SCHEDULE, result = "止めるのを待つ（$why）")
                }
            }
            TetherSchedule.Act.ALREADY_OFF -> if (w != null) {
                prefs.schedHandledWindow = w.first
                log(Ev.SCHEDULE, result = "時間帯に入った（テザリングはすでに OFF のため何もしない）")
            }
            TetherSchedule.Act.STOP -> if (w != null && q != null) schedStop(w, q)
            TetherSchedule.Act.START -> schedStart(if (enabled) "時間帯の終わり" else "スケジュールを OFF にした")
            TetherSchedule.Act.CLEAR -> schedCleared()
        }
    }

    private fun schedStop(w: Pair<Long, Long>, q: QuietHours) {
        prefs.schedHandledWindow = w.first
        val r = TetherControl.stop(ctx)
        if (r.ok) {
            prefs.schedStoppedWindow = w.first
            prefs.schedStoppedAt = System.currentTimeMillis()
            schedNote = null
            val text = "テザリングを止めた（${q.endLabel} に再開）"
            log(Ev.SCHEDULE, result = text)
            recent(Ev.SCHEDULE, text)
            Notifier.cancel(ctx, Notifier.ID_SCHEDULE)
        } else {
            // この時間帯はもう試さない（許可がないときに何度も記録しない）
            schedNote = "止められませんでした（${r.text}）"
            log(Ev.SCHEDULE, anomaly = "テザリングを止められない", result = r.text)
            recent(Ev.SCHEDULE, "止められない（${r.reason?.label ?: r.text}）")
            // 夜中に鳴らさないよう、音のないお知らせにする
            if (prefs.bool(Defs.NOTIFY_ALERT)) {
                Notifier.silent(
                    ctx, Notifier.ID_SCHEDULE, "時間帯スケジュール：テザリングを止められませんでした",
                    "${r.text}\n設定の「使わない時間帯」で、許可と動作を確認してください。", Notifier.settingsAction(ctx),
                )
            }
            ext.post(ExtNotify.Category.ALERT, "時間帯スケジュール：テザリングを止められませんでした", r.text)
        }
    }

    private fun schedStart(why: String) {
        val now = SystemClock.elapsedRealtime()
        if (resumePending || now < resumeNextAt) return
        resumePending = true
        TetherControl.start(ctx) { r ->
            if (closed) return@start
            if (r.ok) {
                log(Ev.SCHEDULE, result = "テザリングの再開を依頼（$why）")
                // アクセスポイントが ON になったことは放送で確かめる（CLEAR）。90 秒たっても ON でなければ失敗として扱う
                main.postDelayed({ schedVerify() }, 90_000L)
            } else {
                resumePending = false
                schedResumeFailed(r.text)
            }
        }
    }

    private fun schedVerify() {
        if (closed || !resumePending) return
        resumePending = false
        if (StateStore.state.tether?.wifiOn == true) {
            schedCleared()
            publish()
            refreshNotification()
            return
        }
        schedResumeFailed("アクセスポイントが ON になりませんでした")
    }

    private fun schedResumeFailed(reason: String) {
        val delays = TetherSchedule.RETRY_MIN
        resumeTries++
        if (resumeTries <= delays.size) {
            val min = delays[resumeTries - 1]
            resumeNextAt = SystemClock.elapsedRealtime() + min * 60_000L
            log(Ev.SCHEDULE, anomaly = "再開に失敗", action = "${min}分後にやり直し（$resumeTries/${delays.size}）", result = reason)
            scheduleNext()
            return
        }
        // あきらめて、テザリング停止の異常として知らせる（通常の確認・通知の流れに戻す。無限にやり直さない）
        resumeTries = 0
        resumeNextAt = 0L
        prefs.schedStoppedWindow = 0L
        resumeFailed = reason
        schedNote = "再開できませんでした（$reason）"
        log(Ev.SCHEDULE, anomaly = "再開に失敗", action = "やり直しをやめた（手動で ON にしてください）", result = reason)
        recent(Ev.SCHEDULE, "再開に失敗（$reason）")
        StateStore.state.tether?.let { if (!prefs.maintenanceActive()) handleTether(System.currentTimeMillis(), it) }
        publish()
        scheduleNext()
        refreshNotification()
    }

    private fun schedCleared() {
        val since = prefs.schedStoppedAt
        prefs.schedStoppedWindow = 0L
        resumePending = false
        resumeTries = 0
        resumeNextAt = 0L
        schedNote = null
        val text = "テザリングが ON に戻った" + if (since > 0) "（止めていた時間 ${Fmt.duration(System.currentTimeMillis() - since)}）" else ""
        log(Ev.SCHEDULE, result = text)
        recent(Ev.SCHEDULE, text)
    }

    /** 次にスケジュールを判断し直すまでの時間（ミリ秒）。使っていなければ null */
    private fun schedDelay(nowWall: Long, nowEl: Long): Long? {
        val enabled = prefs.bool(Defs.TETHER_SCHEDULE)
        val stopped = prefs.schedStoppedWindow
        if (!enabled && stopped == 0L) return null
        if (resumeNextAt > nowEl) return resumeNextAt - nowEl + 1_000L
        val due = TetherSchedule.nextDue(enabled, QuietHours.parse(prefs.text(Defs.QUIET_HOURS)), nowWall, stopped, TimeZone.getDefault()) ?: return null
        // 境目のちょうど後に判断する。接続 0 台が 10 分に達するのを待っているなら、その時刻にも判断し直す
        val boundary = (due - nowWall).coerceAtLeast(0) + 2_000L
        return schedIdleDue?.let { minOf(boundary, (it - nowEl).coerceAtLeast(0)) } ?: boundary
    }

    // ---- スマートプラグ（仕様 F-45） ----

    private fun onPlugEvent(e: PlugSwitch.Event) {
        if (closed) return
        when (e.level) {
            PlugSwitch.Level.INFO -> log(Ev.PLUG, result = e.text, detail = e.detail)
            PlugSwitch.Level.ALERT -> {
                log(Ev.PLUG, anomaly = e.text, result = e.detail)
                if (prefs.bool(Defs.NOTIFY_ALERT)) Notifier.alert(ctx, Notifier.ID_PLUG, e.text, e.detail.orEmpty(), extra = Notifier.settingsAction(ctx))
                ext.post(ExtNotify.Category.ALERT, e.text, e.detail.orEmpty())
            }
            PlugSwitch.Level.CLEAR -> {
                log(Ev.PLUG, result = e.text)
                Notifier.cancel(ctx, Notifier.ID_PLUG)
            }
        }
        recent(Ev.PLUG, e.text)
        publish()
        refreshNotification()
    }

    // ---- 待機（仕様 F-32）。接続 0 台が続いたか ----

    private fun noteZero(t: TetherInfo?) {
        t ?: return
        val now = SystemClock.elapsedRealtime()
        zeroSince = when {
            t.wifiOn == false || t.clients == 0 -> zeroSince ?: now
            else -> null // 接続あり、または台数が分からない（待機にしない）
        }
        // 時間帯スケジュール用。テザリングが ON のまま 0 台のときだけ数える（OFF の間は数えない）
        apIdleSince = if (t.wifiOn == true && t.clients == 0) apIdleSince ?: now else null
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
                // 電波は handleSignal で扱う（ここには来ない）
                DeviceAlert.SIGNAL_WEAK -> Fmt.NA
            }
            if (c.entered) {
                log(Ev.ALERT, anomaly = a.label, result = value)
                recent(Ev.ALERT, "${a.label}（$value）")
                if (a == DeviceAlert.TEMP_CRIT) Notifier.cancel(ctx, Notifier.deviceId(DeviceAlert.TEMP_WARN))
                if (prefs.bool(Defs.NOTIFY_ALERT)) Notifier.alert(ctx, Notifier.deviceId(a), "${a.label}（$value）", advice(a))
                ext.post(ExtNotify.Category.ALERT, "${a.label}（$value）", advice(a))
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
        DeviceAlert.SIGNAL_WEAK -> "電波が弱いと端末の送信電力が上がり、消費と発熱が増え、速度も落ちます。置き場所を見直してください（設置場所アシストで比べられます）。"
    }

    // ---- 電波の悪化（仕様 F-29）。定期チェックのときだけ判定する ----

    private fun handleSignal(l: LineInfo) {
        // 設置場所アシストで端末を動かしている間は判定しない
        if (PlacementActivity.visible) return
        val base = PlaceStore.baseline(prefs)
        val lim = SignalWatch.Limits(prefs.bool(Defs.SIGNAL_ALERT), prefs.int(Defs.SIGNAL_WEAK_RSRP), prefs.int(Defs.SIGNAL_DROP_DB), base?.rsrp)
        val entered = signal.evaluate(l.rsrp, lim) ?: return
        val a = DeviceAlert.SIGNAL_WEAK
        val value = (l.rsrp?.let { "RSRP $it dBm" } ?: Fmt.NA) + (base?.rsrp?.let { "・設置時 $it dBm" } ?: "")
        if (entered) {
            log(Ev.ALERT, anomaly = a.label, result = value, detail = l.band)
            recent(Ev.ALERT, "${a.label}（$value）")
            if (prefs.bool(Defs.NOTIFY_ALERT)) {
                Notifier.alert(ctx, Notifier.deviceId(a), "${a.label}（$value）", advice(a), extra = Notifier.placementAction(ctx))
            }
            ext.post(ExtNotify.Category.ALERT, "${a.label}（$value）", advice(a))
        } else {
            log(Ev.ALERT_CLEAR, anomaly = a.label, result = value)
            recent(Ev.ALERT_CLEAR, a.label)
            Notifier.cancel(ctx, Notifier.deviceId(a))
        }
    }

    // ---- 待機中の通信（仕様 F-33）。閉じた区間の通信量で判定する ----

    private fun feedTraffic(seg: PowerMeter.Closed) {
        val bytes = seg.bytes ?: return
        val lim = TrafficWatch.Limits(prefs.int(Defs.IDLE_TRAFFIC_MB), prefs.int(Defs.QUIET_TRAFFIC_MB), QuietHours.parse(prefs.text(Defs.QUIET_HOURS)))
        val idle = seg.tether == false || seg.clientsMax == 0
        val found = traffic.add(TrafficWatch.Seg(seg.startWall, seg.endWall, bytes / 1024, idle), lim, TimeZone.getDefault())
        for (f in found) {
            when (f) {
                is TrafficWatch.Finding.Idle -> {
                    prefs.trafficIdleAlertAt = traffic.lastIdleAlertAt
                    val text = "${Fmt.hm(f.fromMs)}〜${Fmt.hm(f.toMs)} に約 ${mb(f.mb)}（1 時間あたり約 ${mb(f.mbPerHour)}）"
                    log(Ev.TRAFFIC, anomaly = "接続端末なしの通信", result = text)
                    recent(Ev.TRAFFIC, "接続端末なしで ${mb(f.mb)}")
                    // メンテナンス中（更新作業など）の通信は知らせない
                    if (!prefs.maintenanceActive() && prefs.bool(Defs.NOTIFY_ALERT)) {
                        Notifier.info(
                            ctx, Notifier.ID_TRAFFIC_IDLE, "接続端末がないのに通信しています（${mb(f.mb)}）",
                            "${text}の通信がありました。この端末自身のアプリ・OS の通信です（自動更新・同期など）。" +
                                "設定ガイドの「アプリのバックグラウンド制限」「自動同期」を確認してください。",
                            Notifier.trafficAction(ctx),
                        )
                    }
                }
                is TrafficWatch.Finding.Quiet -> {
                    val text = "使わない時間帯（${f.quiet.label}）に約 ${mb(f.mb)}" + if (f.idleMb >= 1) "（うち接続端末なし ${mb(f.idleMb)}）" else ""
                    log(Ev.TRAFFIC, anomaly = "使わない時間帯の通信", result = text)
                    recent(Ev.TRAFFIC, text)
                    if (prefs.bool(Defs.NOTIFY_ALERT)) {
                        // ほとんどが接続端末なしの通信なら、原因はこの端末自身（接続端末の設定では減らない）
                        val own = f.idleMb >= f.mb * 0.8
                        Notifier.info(
                            ctx, Notifier.ID_TRAFFIC_QUIET, "使わない時間帯の通信：${mb(f.mb)}",
                            "${text}の通信がありました。" + if (own) {
                                "ほとんどがこの端末自身のアプリ・OS の通信です。設定ガイドの「アプリのバックグラウンド制限」「自動同期」を確認してください。"
                            } else {
                                "接続端末の OS 更新・クラウド同期の可能性があります。接続端末で「従量制」にすると減らせます。"
                            },
                            if (own) Notifier.trafficAction(ctx) else Notifier.clientGuideAction(ctx),
                        )
                    }
                }
            }
        }
    }

    private fun mb(v: Double) = String.format(Locale.US, "%.1f MB", v)

    // ---- ローカル管理画面（仕様 F-43） ----

    private fun updateWeb(t: TetherInfo?) {
        if (closed) return
        val iface = if (t?.wifiOn == true) t.apIface ?: t.tethered?.firstOrNull { Ifaces.isWifi(it) } else null
        web.update(prefs.bool(Defs.WEB), iface, prefs.int(Defs.WEB_PORT)) { st -> main.post { onWebStatus(st) } }
    }

    private fun onWebStatus(st: WebStatus) {
        if (closed) return
        val prev = StateStore.state.web
        if (prev == st) return
        StateStore.update { it.copy(web = st) }
        // 記録には IP アドレスを残さない（ログの方針）
        if ((prev?.url != null) != (st.url != null)) {
            val text = if (st.url != null) "待ち受け開始（ポート ${prefs.int(Defs.WEB_PORT)}）" else "停止（${st.message}）"
            log(Ev.WEB, result = text)
            recent(Ev.WEB, text)
        }
        // アクセスポイントが ON になった直後はアドレスがまだないことがあるので、少し待ってやり直す（回数に上限あり）
        if (st.retry && webRetries < 4) {
            webRetries++
            main.postDelayed({ updateWeb(StateStore.state.tether) }, 15_000L)
        } else if (!st.retry) {
            webRetries = 0
        }
    }

    // ---- 起動後の状態確認（仕様 F-14） ----

    private fun bootSummary(pr: Probe) {
        bootPending = false
        bootDue = null
        prefs.lastBootCheckAt = System.currentTimeMillis()
        val actions = ArrayList<String>()
        val schedStopped = prefs.schedStoppedWindow != 0L
        if (pr.tether.wifiOn == false && !schedStopped) {
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
        // 時間帯スケジュールで止めている間に再起動した場合は、時間帯の終わりに再開する（異常ではない）
        val schedNoteText = if (schedStopped && pr.tether.wifiOn == false) {
            "時間帯スケジュールで停止中（${QuietHours.parse(prefs.text(Defs.QUIET_HOURS))?.endLabel ?: "時間帯の終わり"} に再開）"
        } else {
            null
        }
        val lines = actions + listOfNotNull(pending)
        log(
            Ev.BOOT_CHECK,
            result = if (lines.isEmpty()) "問題なし" else "要確認 ${lines.size}件",
            detail = (lines + listOfNotNull(schedNoteText)).joinToString(" / ").ifEmpty { null },
        )
        recent(Ev.BOOT_CHECK, if (lines.isEmpty()) "問題なし" else "要確認 ${lines.size}件")
        // 再起動したことは、離れた場所の管理者には大事な情報なので外へも送る（仕様 F-44）
        if (actions.isEmpty()) {
            ext.post(ExtNotify.Category.RECOVERY, "再起動しました（起動後チェック：問題なし）", listOfNotNull(pending, schedNoteText).joinToString("\n").ifEmpty { "テザリング・回線・設定の状態は正常です" })
        } else {
            ext.post(ExtNotify.Category.ALERT, "再起動しました（起動後チェック：要確認）", lines.joinToString("\n"))
        }
        if (!prefs.bool(Defs.NOTIFY_BOOT)) return
        if (actions.isEmpty()) {
            Notifier.info(ctx, Notifier.ID_BOOT, "起動後チェック：問題なし", listOfNotNull(pending, schedNoteText).joinToString("\n").ifEmpty { "テザリング・回線・設定の状態は正常です" })
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
        if (!prefs.bool(Defs.SIGNAL_ALERT) && signal.active) {
            signal.reset()
            Notifier.cancel(ctx, Notifier.deviceId(DeviceAlert.SIGNAL_WEAK))
        }
        // 自動切り替えを OFF にしたら、自動で変えていた設定を元に戻す
        autoEvaluate(StateStore.state.power)
        // スケジュールを OFF にしたら、止めていたテザリングを再開する（時間帯を変えたときも判断し直す）
        schedWaitLogged = 0L
        schedEvaluate(StateStore.state.tether)
        // スマートプラグを OFF にしたら、止めていた給電を ON に戻す
        if (prefs.bool(Defs.PLUG)) smartPlug.evaluate(StateStore.state.power, StateStore.state.tether?.wifiOn) else smartPlug.release("設定で OFF")
        if (!prefs.bool(Defs.EXT_NOTIFY)) ext.clear() else ext.flush()
        updateWeb(StateStore.state.tether)
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
            // メンテナンス中もスケジュールの再開だけは時刻どおりに行う
            minOf(prefs.maintUntil - nowWall, schedDelay(nowWall, nowEl) ?: Long.MAX_VALUE)
        } else {
            val interval = StateStore.state.intervalMin * 60_000L
            val base = if (lastCheckElapsed == 0L) 5_000L else lastCheckElapsed + interval - nowEl
            // 比較テストの条件の終わり（バッテリー駆動中だけ時間を数えるので、そのときだけ予約する）
            val expDelay = exp?.takeIf { it.measuring && StateStore.state.power?.plug == Plug.NONE }?.let { e ->
                (e.remainingSec - meter.openBatterySec(e.condition)).coerceAtLeast(0) * 1000L + 5_000L
            }
            listOfNotNull(base, upstreamDue?.minus(nowEl), tetherDue?.minus(nowEl), bootDue?.minus(nowEl), expDelay, schedDelay(nowWall, nowEl)).min()
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
        val power = StateStore.state.power
        val plug = power?.plug ?: Plug.UNKNOWN
        val mode = prefs.mode
        val base = Profiles.base(mode, plug.powered)
        val maintUntil = prefs.maintUntil
        val maintenance = maintUntil > System.currentTimeMillis()
        val eff = Profiles.effective(base, maintenance, upstream.rechecking || tether.rechecking)
        var interval = Profiles.intervalMin(base, prefs.int(Defs.INTERVAL_NORMAL), prefs.int(Defs.INTERVAL_SAVER)) * intervalFactor
        // 待機（仕様 F-32）。比較テスト中・メンテナンス中は使わない（条件と作業を乱さないため）
        val reason = if (exp == null && !maintenance) {
            IdleMode.reason(prefs.int(Defs.IDLE_INTERVAL), zeroSince, SystemClock.elapsedRealtime(), power, prefs.int(Defs.IDLE_BATT), lastSegKbPerHour)
        } else {
            null
        }
        if (reason != null) interval = maxOf(interval, prefs.int(Defs.IDLE_INTERVAL))
        if (reason != idleReason) {
            val prevReason = idleReason
            idleReason = reason
            val text = if (reason != null) "待機（${reason.label}）：チェック間隔 ${interval}分" else "待機を終了（${prevReason?.label ?: ""}）"
            log(Ev.PROFILE, result = text)
            recent(Ev.PROFILE, text)
        }
        val autoView = StateStore.AutoView(
            enabled = prefs.bool(Defs.AUTO_SAVER) || prefs.bool(Defs.AUTO_DOZE),
            permitted = AutoSwitch.permitted(ctx),
            saverOn = prefs.autoSaverApplied,
            dozeOn = prefs.autoDozeApplied,
        )
        val stoppedWindow = prefs.schedStoppedWindow
        val quiet = QuietHours.parse(prefs.text(Defs.QUIET_HOURS))
        val schedView = if (prefs.bool(Defs.TETHER_SCHEDULE) || stoppedWindow != 0L) {
            StateStore.SchedView(
                enabled = prefs.bool(Defs.TETHER_SCHEDULE), permitted = TetherControl.permitted(ctx), quiet = quiet?.label,
                stoppedUntil = if (stoppedWindow != 0L) quiet?.endLabel ?: "まもなく" else null, note = schedNote,
            )
        } else {
            null
        }
        val plugView = if (prefs.bool(Defs.PLUG)) {
            val low = prefs.int(Defs.PLUG_LOW)
            StateStore.PlugView(
                smartPlug.want?.on, smartPlug.want?.why?.label,
                "$low〜${PlugPolicy.high(PlugPolicy.Config(low, prefs.int(Defs.PLUG_HIGH), 0, 0, 0))}%", smartPlug.lastResult, smartPlug.stuck,
            )
        } else {
            null
        }
        val extView = if (prefs.bool(Defs.EXT_NOTIFY)) StateStore.ExtView(ExtNotify.parse(prefs.text(Defs.EXT_URL))?.label, ext.pending, ext.lastResult) else null
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
                upstream = up, tetherIssue = te, meter = meterView, experiment = expView,
                alerts = alerts.active.toSet() + listOfNotNull(DeviceAlert.SIGNAL_WEAK.takeIf { signal.active }),
                auto = autoView, idleReason = reason?.label, sched = schedView, plug = plugView, ext = extView,
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
        closedSeg?.let { seg ->
            feedTraffic(seg)
            // 待機（仕様 F-32）の判断用に、直近の区間の通信量（KB/時）を覚えておく（短すぎる区間は使わない）
            val b = seg.bytes
            if (b != null && seg.durationSec >= 300) lastSegKbPerHour = b / 1024.0 / (seg.durationSec / 3600.0)
        }
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
        meter.begin(p, t?.wifiOn, StateStore.state.effective.label, next?.candidate?.title, next?.takeIf { it.measuring }?.condition, t?.clients)
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
