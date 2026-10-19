package local.tethering.dedicated

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** 通知（仕様 F-07 の「重要な警告は残す」に沿って、常駐・警告・お知らせのチャンネルを分ける） */
object Notifier {
    const val CH_MONITOR = "monitor"
    const val CH_ALERT = "alert"
    const val CH_INFO = "info"

    const val ID_ONGOING = 1
    const val ID_UPSTREAM = 10
    const val ID_TETHER = 11
    const val ID_BOOT = 12
    const val ID_EXPERIMENT = 13
    const val ID_TRAFFIC_IDLE = 14
    const val ID_TRAFFIC_QUIET = 15
    const val ID_SCHEDULE = 16
    const val ID_PLUG = 17
    private const val ID_DEVICE_BASE = 20

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MONITOR, ctx.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.channel_monitor_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERT, ctx.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.channel_alert_desc)
                },
                NotificationChannel(CH_INFO, ctx.getString(R.string.channel_info), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = ctx.getString(R.string.channel_info_desc)
                },
            ),
        )
    }

    /** 常駐通知の本文（状態が変わったときだけ更新するため、比較用に文字列で返す） */
    fun ongoingText(s: StateStore.State): Pair<String, String> {
        val o = s.overall()
        val ol = o.label
        val title = when {
            o == Overall.MAINT -> Fmt.hm(s.maintUntil).let { tr("メンテナンス中（〜$it）", "Maintenance (until $it)", "Maintenance (jusqu'à $it)") }
            s.upstream != null && !s.upstream.confirmed -> {
                val n = "${s.upstream.rechecks}/${s.upstream.total}"
                tr("$ol：再確認中（$n）", "$ol: rechecking ($n)", "$ol : revérification ($n)")
            }
            s.upstream != null -> withColon(ol, s.upstream.kind.label)
            s.tetherIssue != null -> withColon(ol, s.tetherIssue.kind.label)
            else -> ol
        }
        val parts = ArrayList<String>()
        val l = s.line
        if (l != null) parts.add(Fmt.signalSummary(l))
        s.check?.let { parts.add(tr("ネット", "Internet ", "Internet ") + if (it.failure == null) "✓" else "✗") }
        s.tether?.clients?.let { parts.add(tr("端末${it}台", "$it device(s)", "$it appareil(s)")) }
        s.sched?.stoppedUntil?.let { parts.add(tr("テザリング停止中（$it に再開）", "Hotspot off (back on at $it)", "Point d'accès arrêté (réactivation à $it)")) }
        s.meter?.remainingMin?.let { m -> Fmt.duration(m * 60_000L).let { parts.add(tr("残り$it", "$it left", "reste $it")) } }
        s.experiment?.let { e ->
            parts.add(
                if (e.waitingSwitch) {
                    tr("比較テスト：切り替え待ち", "Comparison test: waiting for switch", "Test comparatif : en attente du changement")
                } else {
                    val p = "${e.condition} ${e.measuredSec / 60}/${e.phaseSec / 60}"
                    tr("比較テスト ${p}分", "Comparison test $p min", "Test comparatif $p min")
                },
            )
        }
        if (s.nextCheckAt > 0 && o != Overall.MAINT) Fmt.hm(s.nextCheckAt).let { parts.add(tr("次回 $it", "Next $it", "Prochaine $it")) }
        return title to parts.joinToString(" · ").ifEmpty { tr("状態を確認しています", "Checking status", "Vérification de l'état") }
    }

    /** 「状態：内容」（フランス語はコロンの前に空白） */
    private fun withColon(a: String, b: String) = tr("$a：$b", "$a: $b", "$a : $b")

    /** 比較テストの案内。[confirm] なら「切り替えた」「中止」のボタンを付ける */
    fun experiment(ctx: Context, title: String, text: String, confirm: Boolean) {
        val b = Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_hotspot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(ctx.getColor(R.color.brand))
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 3, Intent(ctx, ExperimentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS,
                ),
            )
            .setAutoCancel(!confirm)
            .setOngoing(confirm)
        if (confirm) {
            b.addAction(action(ctx, tr("切り替えた", "Switched", "C'est fait"), service(ctx, 4, MonitorService.ACTION_EXP_CONFIRM)))
            b.addAction(action(ctx, tr("中止して元に戻す", "Stop and revert", "Arrêter et rétablir"), service(ctx, 5, MonitorService.ACTION_EXP_ABORT)))
        }
        ctx.getSystemService(NotificationManager::class.java)?.notify(ID_EXPERIMENT, b.build())
    }

    private fun service(ctx: Context, req: Int, action: String): PendingIntent =
        PendingIntent.getForegroundService(ctx, req, Intent(ctx, MonitorService::class.java).setAction(action), FLAGS)

    fun ongoing(ctx: Context, s: StateStore.State): Notification {
        val (title, text) = ongoingText(s)
        return Notification.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_hotspot)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(ctx.getColor(R.color.brand))
            .setContentIntent(openApp(ctx))
            .addAction(action(ctx, tr("今すぐチェック", "Check now", "Vérifier maintenant"), checkNow(ctx)))
            .build()
    }

    fun update(ctx: Context, s: StateStore.State) {
        ctx.getSystemService(NotificationManager::class.java)?.notify(ID_ONGOING, ongoing(ctx, s))
    }

    /** [extra] は通知に付けるボタン（例: 設置場所アシストを開く） */
    fun alert(ctx: Context, id: Int, title: String, text: String, openTether: Boolean = false, extra: Notification.Action? = null) =
        post(ctx, CH_ALERT, id, title, text, openTether, extra)

    fun info(ctx: Context, id: Int, title: String, text: String, extra: Notification.Action? = null) = post(ctx, CH_INFO, id, title, text, false, extra)

    /** 音を出さないお知らせ（夜中に起きる時間帯スケジュールの失敗など。常駐通知と同じチャンネル） */
    fun silent(ctx: Context, id: Int, title: String, text: String, extra: Notification.Action? = null) = post(ctx, CH_MONITOR, id, title, text, false, extra)

    fun deviceId(a: DeviceAlert) = ID_DEVICE_BASE + a.ordinal

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /** このアプリの画面を開くボタン */
    fun openScreen(ctx: Context, label: String, cls: Class<*>, req: Int): Notification.Action = action(
        ctx, label,
        PendingIntent.getActivity(ctx, req, Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS),
    )

    fun placementAction(ctx: Context) =
        openScreen(ctx, tr("設置場所アシストを開く", "Open Placement assistant", "Ouvrir l'assistant d'emplacement"), PlacementActivity::class.java, 6)

    fun trafficAction(ctx: Context) = openScreen(ctx, tr("通信量を見る", "View traffic", "Voir le trafic"), TrafficActivity::class.java, 7)

    fun clientGuideAction(ctx: Context) =
        openScreen(ctx, tr("接続端末の設定ガイド", "Client device guide", "Guide des appareils connectés"), ClientGuideActivity::class.java, 8)

    fun settingsAction(ctx: Context) = openScreen(ctx, tr("設定を開く", "Open Settings", "Ouvrir les paramètres"), SettingsActivity::class.java, 9)

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, openTether: Boolean, extra: Notification.Action?) {
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_hotspot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(ctx.getColor(R.color.brand))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        if (openTether) {
            b.addAction(action(ctx, tr("テザリング設定を開く", "Open hotspot settings", "Ouvrir les paramètres du point d'accès"), openTetherSettings(ctx)))
        }
        if (extra != null) b.addAction(extra)
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, b.build())
    }

    private fun action(ctx: Context, label: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_hotspot), label, pi).build()

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        FLAGS,
    )

    private fun openTetherSettings(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TETHER, true),
        FLAGS,
    )

    private fun checkNow(ctx: Context): PendingIntent = PendingIntent.getForegroundService(
        ctx, 2,
        Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_CHECK_NOW),
        FLAGS,
    )
}
