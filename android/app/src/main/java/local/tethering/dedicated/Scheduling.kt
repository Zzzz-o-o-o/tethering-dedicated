package local.tethering.dedicated

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock

/** チェック中だけ CPU を起こしておく短いウェイクロック（必ずタイムアウト付き） */
object WakeLocks {
    /** アラーム受信からサービスが処理を始めるまでのつなぎ（参照を持っておかないと GC で解放される） */
    @Volatile
    private var bridge: PowerManager.WakeLock? = null

    fun acquire(ctx: Context, tag: String, timeoutMs: Long): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TetheringDedicated:$tag").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }.getOrNull()

    fun acquireBridge(ctx: Context) {
        release(bridge)
        bridge = acquire(ctx, "alarm", 30_000)
    }

    fun release(wl: PowerManager.WakeLock?) {
        runCatching { if (wl?.isHeld == true) wl.release() }
    }
}

/**
 * 低頻度スケジューラー（仕様 F-19）。次のチェックをアラーム 1 本で予約する（予約し直すと前の予約は置き換わる）。
 * 画面消灯中・Doze 中でも動くよう allowWhileIdle のアラームを使う。常時ポーリングはしない。
 */
object Scheduler {
    private const val REQ = 100

    fun schedule(ctx: Context, delayMs: Long): Long {
        val d = delayMs.coerceAtLeast(5_000L)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return 0L
        val at = SystemClock.elapsedRealtime() + d
        val pi = pending(ctx)
        if (ExactAlarms.usable(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
        val wall = System.currentTimeMillis() + d
        Prefs(ctx).nextCheckAt = wall
        return wall
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
        Prefs(ctx).nextCheckAt = 0L
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ,
        Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
