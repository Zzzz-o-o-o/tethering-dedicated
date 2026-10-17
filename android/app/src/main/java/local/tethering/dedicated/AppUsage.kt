package local.tethering.dedicated

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process

/**
 * アプリ別のモバイル通信（仕様 F-35）を OS の記録から読む。読むだけで、ほかのアプリには何もしない。
 * 「使用状況へのアクセス」が必要（ADB の `appops set … GET_USAGE_STATS allow`、または設定画面で許可）。
 * テザリング（接続端末）の通信も UID_TETHERING として分けて取れる。UI スレッド以外で呼ぶ。
 */
object AppUsageProbe {
    fun hasAccess(ctx: Context): Boolean {
        val aom = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching { aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) }.getOrNull()
            ?: return false
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** [start, end) のモバイル回線の通信量を UID ごとに（前面・裏に分けて）。Android 10 以降は subscriberId に null を渡すと全モバイル回線の合計になる */
    fun mobileByUid(ctx: Context, start: Long, end: Long): List<AppTraffic.UidBytes> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end)
        val map = HashMap<Int, LongArray>()
        val b = NetworkStats.Bucket()
        try {
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                val a = map.getOrPut(b.uid) { LongArray(2) }
                val bytes = b.rxBytes + b.txBytes
                if (b.state == NetworkStats.Bucket.STATE_FOREGROUND) a[0] += bytes else a[1] += bytes
            }
        } finally {
            stats.close()
        }
        return map.map { (uid, v) -> AppTraffic.UidBytes(uid, v[0], v[1]) }
    }

    /** パッケージごとの [前面に出た回数, 常駐サービスを始めた回数]（使用状況の記録から） */
    fun eventCounts(ctx: Context, start: Long, end: Long): Map<String, IntArray> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val ev = usm.queryEvents(start, end) ?: return emptyMap()
        val out = HashMap<String, IntArray>()
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val i = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> 0
                UsageEvents.Event.FOREGROUND_SERVICE_START -> 1
                else -> -1
            }
            if (i >= 0) out.getOrPut(e.packageName) { IntArray(2) }[i]++
        }
        return out
    }

    fun appInfo(ctx: Context, uid: Int): AppTraffic.AppInfo? {
        val pm = ctx.packageManager
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val main = pkgs.sorted().first()
        val ai = runCatching { pm.getApplicationInfo(main, 0) }.getOrNull()
        return AppTraffic.AppInfo(
            label = ai?.loadLabel(pm)?.toString() ?: main,
            pkg = main,
            system = ai?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } ?: true,
            others = pkgs.size - 1,
        )
    }

    fun summary(ctx: Context, start: Long, end: Long): AppTraffic.Summary {
        val list = mobileByUid(ctx, start, end)
        val events = runCatching { eventCounts(ctx, start, end) }.getOrDefault(emptyMap())
        val cache = HashMap<Int, AppTraffic.AppInfo?>()
        return AppTraffic.summarize(list, Process.myUid(), { uid -> cache.getOrPut(uid) { appInfo(ctx, uid) } }, events)
    }
}
