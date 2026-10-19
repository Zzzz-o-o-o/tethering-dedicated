package local.tethering.dedicated

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 起動時にテザリングを ON にする（v0.7.0）。テザリング専用機として、端末の起動直後から Wi-Fi テザリングを使えるようにする。
 *
 * 画面ロック（PIN など）がある端末でも動くよう、ロック解除前（Direct Boot）に届く LOCKED_BOOT_COMPLETED で ON にする。
 * ロック解除前は、設定・ログの保存領域（credential encrypted storage）を読めないので、設定の写しと結果は
 * 端末暗号化の領域（device protected storage）に置き、ロック解除後に監視サービスがログへ移す（[takeResults]）。
 * ON にならなければ少し待ってやり直し、1 回の起動につき [MAX_ATTEMPTS] 回まで依頼する。
 * ON になったあとは、利用者が OFF にしても次の起動まではそのままにする。
 * 切り替えの仕組みと権限（「システム設定の変更」）は時間帯スケジュール（F-37）と同じ（[TetherControl]）。
 * 判断（[decide]・[waitMs]）は Android に依存しない。
 */
object BootTether {
    enum class Act {
        /** ON を依頼する */
        START,

        /** ON になっている（この起動ではもう何もしない） */
        ALREADY_ON,

        /** この起動ではもう ON にした（そのあと OFF にされても、そのままにする） */
        DONE,

        /** ON になる途中・依頼の直後（あとで確かめる） */
        WAIT,

        /** 設定で使わない */
        DISABLED,

        /** 「システム設定の変更」が許可されていない */
        NO_PERMISSION,

        /** この起動で試す回数を使い切った */
        GIVE_UP,

        /** 月の通信量の上限でこのアプリが止めている期間（ON にしない） */
        BLOCKED,
    }

    /** 依頼のあいだの時間（秒）。n 回目の依頼で ON にならなければ、RETRY_SEC[n-1] 秒後に依頼し直す */
    val RETRY_SEC = listOf(30, 60, 120, 300)

    /** 依頼が受け付けられてから、ON になったかを確かめるまでの時間（秒）。最後の依頼のあとは、これを待ってあきらめる */
    const val VERIFY_SEC = 30

    /** ON になる途中のとき、次に確かめるまでの時間（秒） */
    const val ENABLING_SEC = 15

    /** 1 回の起動で依頼する回数の上限 */
    val MAX_ATTEMPTS: Int get() = RETRY_SEC.size + 1

    /** 1 回の起動で動く回数の上限（ON になる途中のまま変わらないときなどに、いつまでも続けないため） */
    const val MAX_RUNS = 20

    /** [attempts] 回目の依頼のあと、次に依頼するまでの時間（ミリ秒） */
    fun gapMs(attempts: Int): Long = (RETRY_SEC.getOrNull(attempts - 1) ?: VERIFY_SEC) * 1000L

    /**
     * [apState] はアクセスポイントの状態（TetherInfo.AP_*。分からなければ null）、[sinceRequestMs] はこの起動で最後に依頼してからの時間、
     * [attempts] はこの起動で依頼した回数、[onceOn] はこの起動で ON になったことがあるか、[runs] はこの起動で動いた回数（今回を含む）、
     * [blocked] は月の通信量の上限でこのアプリが止めている期間か
     */
    fun decide(
        enabled: Boolean,
        permitted: Boolean,
        apState: Int?,
        sinceRequestMs: Long?,
        attempts: Int,
        onceOn: Boolean,
        runs: Int,
        blocked: Boolean = false,
    ): Act = when {
        !enabled -> Act.DISABLED
        onceOn -> Act.DONE
        apState == TetherInfo.AP_ENABLED -> Act.ALREADY_ON
        blocked -> Act.BLOCKED
        runs > MAX_RUNS -> Act.GIVE_UP
        apState == TetherInfo.AP_ENABLING -> Act.WAIT
        // アラームは予約した時刻ちょうどか少しあとに届く。誤差で早く届いても重ねて依頼しないよう 1 秒の幅をとる
        sinceRequestMs != null && sinceRequestMs < gapMs(attempts) - 1_000L -> Act.WAIT
        !permitted -> Act.NO_PERMISSION
        attempts >= MAX_ATTEMPTS -> Act.GIVE_UP
        else -> Act.START
    }

    /** [Act.WAIT] のとき、次に確かめるまでの時間（ミリ秒） */
    fun waitMs(apState: Int?, sinceRequestMs: Long?, attempts: Int): Long =
        if (apState == TetherInfo.AP_ENABLING || sinceRequestMs == null) {
            ENABLING_SEC * 1000L
        } else {
            (gapMs(attempts) - sinceRequestMs).coerceIn(5_000L, gapMs(attempts))
        }

    // ---- 端末暗号化の領域（ロック解除前でも読み書きできる） ----

    const val ACTION_RETRY = "local.tethering.dedicated.BOOT_TETHER_RETRY"
    private const val FILE = "boot_tether"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BOOT = "boot"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_RUNS = "runs"
    private const val KEY_REQUESTED = "requested_elapsed"
    private const val KEY_ACCEPTED = "accepted"
    private const val KEY_ERROR = "error"
    private const val KEY_DONE = "done"
    private const val KEY_REPORTED = "reported"
    private const val KEY_RESULTS = "results"
    private const val KEY_LAST = "last"
    private const val KEY_BLOCK_UNTIL = "block_until"
    private const val KEY_REQUESTED_WALL = "requested_wall"
    private const val KEY_ON_AT = "on_at"
    private const val MAX_RESULTS = 8

    private fun store(ctx: Context): SharedPreferences =
        ctx.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 設定の写しを更新する（設定を保存したとき・監視を始めたとき）。設定してすぐ再起動しても読めるよう、すぐにファイルへ書く
     */
    @SuppressLint("ApplySharedPref")
    fun sync(ctx: Context, enabled: Boolean) {
        val sp = store(ctx)
        if (!sp.contains(KEY_ENABLED) || sp.getBoolean(KEY_ENABLED, false) != enabled) sp.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    /** 設定の写し（起動時に読むもの） */
    fun enabledCopy(ctx: Context): Boolean = store(ctx).getBoolean(KEY_ENABLED, false)

    /**
     * 月の通信量の上限でテザリングを止めている間は、起動しても ON にしない（[untilWall] は期間の終わり。壁時計のミリ秒）。
     * 止めたとき・再開したときにすぐ書く（再起動しても読めるように）
     */
    @SuppressLint("ApplySharedPref")
    fun block(ctx: Context, untilWall: Long) {
        val sp = store(ctx)
        if (sp.getLong(KEY_BLOCK_UNTIL, 0L) != untilWall) sp.edit().putLong(KEY_BLOCK_UNTIL, untilWall).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun unblock(ctx: Context) {
        val sp = store(ctx)
        if (sp.contains(KEY_BLOCK_UNTIL)) sp.edit().remove(KEY_BLOCK_UNTIL).commit()
    }

    /** この起動で、このアプリがテザリングを ON にした時刻（ON にしていなければ null） */
    fun turnedOnAt(ctx: Context): Long? {
        if (status(ctx) != Status.TURNED_ON) return null
        return store(ctx).getLong(KEY_ON_AT, 0L).takeIf { it > 0 }
    }

    /** 起動しても ON にしない期間の終わり（なければ null） */
    fun blockedUntil(ctx: Context): Long? = store(ctx).getLong(KEY_BLOCK_UNTIL, 0L).takeIf { it > System.currentTimeMillis() }

    /** 直近の結果（日付・時刻つき。設定画面・診断情報に出す。ログへ移しても消さない） */
    fun lastResult(ctx: Context): String? = store(ctx).getString(KEY_LAST, null)

    /** この起動の進み具合（起動後チェックの文面に使う） */
    enum class Status {
        /** この起動では動いていない（使わない設定・起動後に ON にした設定など） */
        NONE,

        /** ON にしようとしている（やり直しの途中） */
        TRYING,

        /** このアプリが ON にした */
        TURNED_ON,

        /** 起動したときにはもう ON だった */
        WAS_ON,

        /** ON にできなかった（許可がない・やり直しを使い切った） */
        FAILED,
    }

    fun status(ctx: Context): Status {
        val sp = store(ctx)
        if (!sp.getBoolean(KEY_ENABLED, false) || sp.getInt(KEY_BOOT, Int.MIN_VALUE) != bootId(ctx)) return Status.NONE
        val attempts = sp.getInt(KEY_ATTEMPTS, 0)
        return when {
            sp.getBoolean(KEY_DONE, false) -> if (attempts > 0) Status.TURNED_ON else Status.WAS_ON
            sp.getBoolean(KEY_REPORTED, false) -> Status.FAILED
            attempts > 0 -> Status.TRYING
            else -> Status.NONE
        }
    }

    /** ロック解除後に監視サービスがログへ移す結果（読んだら消す。時刻つきの文） */
    @SuppressLint("ApplySharedPref")
    fun takeResults(ctx: Context): List<String> {
        val sp = store(ctx)
        val s = sp.getString(KEY_RESULTS, null) ?: return emptyList()
        sp.edit().remove(KEY_RESULTS).commit()
        return s.split('\n').filter { it.isNotBlank() }
    }

    @SuppressLint("ApplySharedPref")
    private fun addResult(ctx: Context, text: String) {
        val sp = store(ctx)
        val now = System.currentTimeMillis()
        val notes = listOfNotNull(sinceBoot(), tr("ロック解除前", "before unlock", "avant déverrouillage").takeIf { isLocked(ctx) })
        val full = text + Fmt.paren(Fmt.join(notes))
        val lines = (sp.getString(KEY_RESULTS, null)?.split('\n').orEmpty() + (Fmt.time(now) + " " + full)).filter { it.isNotBlank() }.takeLast(MAX_RESULTS)
        sp.edit().putString(KEY_RESULTS, lines.joinToString("\n")).putString(KEY_LAST, Fmt.dateTime(now).take(16) + " " + full).commit()
    }

    private fun sinceBoot(): String {
        val ms = SystemClock.elapsedRealtime()
        return if (ms < 600_000L) {
            (ms / 1000).let { tr("起動${it}秒後", "$it s after startup", "$it s après le démarrage") }
        } else {
            Fmt.duration(ms).let { tr("起動の${it}後", "$it after startup", "$it après le démarrage") }
        }
    }

    private fun isLocked(ctx: Context): Boolean = ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == false

    /**
     * 起動ごとに変わる番号（同じ起動の中の試行を数えるため）。OS の起動回数を使い、読めなければ起動した時刻（10 分単位）で代える
     */
    private fun bootId(ctx: Context): Int {
        val count = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
        if (count >= 0) return count
        val bootWallMin = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L
        return -((bootWallMin / 10) % Int.MAX_VALUE).toInt() - 1
    }

    /** 起動時・やり直しの時刻に呼ぶ。[done] は処理が終わったとき（受信の goAsync を終える）に 1 回だけ呼ぶ。メインスレッドから呼ぶ */
    @SuppressLint("ApplySharedPref")
    fun run(context: Context, done: () -> Unit) {
        val ctx = context.applicationContext
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) done() }
        val sp = store(ctx)
        // 使わない設定なら何も書かない
        if (!sp.getBoolean(KEY_ENABLED, false)) return finish()
        val boot = bootId(ctx)
        if (sp.getInt(KEY_BOOT, Int.MIN_VALUE) != boot) {
            // 新しい起動。前の起動の記録を消す（結果の一覧は、ログへ移すまで残す）
            val e = sp.edit().putInt(KEY_BOOT, boot)
            listOf(KEY_ATTEMPTS, KEY_RUNS, KEY_REQUESTED, KEY_REQUESTED_WALL, KEY_ACCEPTED, KEY_ERROR, KEY_DONE, KEY_REPORTED, KEY_ON_AT).forEach { e.remove(it) }
            e.commit()
        }
        val attempts = sp.getInt(KEY_ATTEMPTS, 0)
        val runs = sp.getInt(KEY_RUNS, 0) + 1
        val since = sp.getLong(KEY_REQUESTED, 0L).takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() - it }
        val reported = sp.getBoolean(KEY_REPORTED, false)
        val ap = TetherProbe.apState(ctx)
        val blockUntil = sp.getLong(KEY_BLOCK_UNTIL, 0L).takeIf { it > System.currentTimeMillis() }
        val act = decide(true, TetherControl.permitted(ctx), ap, since, attempts, sp.getBoolean(KEY_DONE, false), runs, blocked = blockUntil != null)
        if (act != Act.DONE) sp.edit().putInt(KEY_RUNS, runs).commit()
        fun markReported() = sp.edit().putBoolean(KEY_REPORTED, true).commit()
        when (act) {
            Act.DISABLED, Act.DONE -> Unit
            Act.ALREADY_ON -> {
                // このアプリが依頼して ON になったなら、依頼した時刻を ON になった時刻とする（連続稼働時間の始まり）
                val e = sp.edit().putBoolean(KEY_DONE, true)
                if (attempts > 0) e.putLong(KEY_ON_AT, sp.getLong(KEY_REQUESTED_WALL, 0L))
                e.commit()
                cancel(ctx)
                addResult(
                    ctx,
                    when (attempts) {
                        0 -> tr("テザリングはすでに ON（何もしない）", "Hotspot already on (nothing to do)", "Point d'accès déjà activé (rien à faire)")
                        1 -> tr("テザリングを ON にした", "Hotspot turned on", "Point d'accès activé")
                        else -> tr("テザリングを ON にした（${attempts}回目）", "Hotspot turned on (attempt $attempts)", "Point d'accès activé (essai $attempts)")
                    },
                )
            }
            Act.WAIT -> schedule(ctx, waitMs(ap, since, attempts))
            Act.BLOCKED -> if (!reported) {
                markReported()
                cancel(ctx)
                val until = blockUntil?.let { Fmt.dateTime(it).take(10) } ?: Fmt.NA
                addResult(
                    ctx,
                    tr(
                        "月の通信量の上限で止めているため、ON にしない（$until まで）", "Not turned on: off because of the monthly data cap (until $until)",
                        "Pas activé : arrêté à cause du plafond de données mensuel (jusqu'au $until)",
                    ),
                )
            }
            Act.NO_PERMISSION -> if (!reported) {
                markReported()
                addResult(
                    ctx,
                    tr(
                        "テザリングを ON にできません（「システム設定の変更」が許可されていません）",
                        "Can't turn on the hotspot (“Modify system settings” isn't allowed)",
                        "Impossible d'activer le point d'accès (« Modifier les paramètres système » n'est pas autorisé)",
                    ),
                )
            }
            Act.GIVE_UP -> if (!reported) {
                markReported()
                cancel(ctx)
                // 最後の依頼が受け付けられていたなら、ON にならなかったことが理由
                val why = sp.getString(KEY_ERROR, null)?.takeUnless { sp.getBoolean(KEY_ACCEPTED, false) }?.let { inner(it) } ?: notOn(ap)
                addResult(
                    ctx,
                    tr(
                        "テザリングを ON にできませんでした（${attempts}回試した・$why）", "Couldn't turn on the hotspot ($attempts attempts, $why)",
                        "Impossible d'activer le point d'accès ($attempts essais, $why)",
                    ),
                )
            }
            Act.START -> {
                // 依頼は受け付けられたのに ON にならなかった（次に依頼する前に理由を残す）
                if (attempts > 0 && sp.getBoolean(KEY_ACCEPTED, false)) sp.edit().putString(KEY_ERROR, notOn(ap)).commit()
                val n = attempts + 1
                sp.edit().putInt(KEY_ATTEMPTS, n).putLong(KEY_REQUESTED, SystemClock.elapsedRealtime())
                    .putLong(KEY_REQUESTED_WALL, System.currentTimeMillis()).putBoolean(KEY_ACCEPTED, false).commit()
                // ON になったかの確認（と、ならなかったときのやり直し）を先に予約しておく（OS の返事が来ないまま終わっても続けられるように）
                schedule(ctx, gapMs(n))
                // OS の返事が来なくても受信を長く止めないよう、一定時間で終える（依頼そのものは OS 側で続く）
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 15_000L)
                TetherControl.start(ctx) { r ->
                    if (r.ok) {
                        sp.edit().putBoolean(KEY_ACCEPTED, true).commit()
                        // ON になったかは早めに確かめる（ならなければ、依頼から gapMs(n) たったときに依頼し直す）
                        schedule(ctx, VERIFY_SEC * 1000L)
                    } else {
                        sp.edit().putString(KEY_ERROR, r.text).commit()
                        // 許可の問題は、やり直しても直らない
                        if (r.reason == TetherControl.Reason.NO_PERMISSION) {
                            cancel(ctx)
                            if (!sp.getBoolean(KEY_REPORTED, false)) {
                                markReported()
                                addResult(ctx, tr("テザリングを ON にできません（${r.text}）", "Can't turn on the hotspot (${r.text})", "Impossible d'activer le point d'accès (${r.text})"))
                            }
                        }
                    }
                    finish()
                }
                return
            }
        }
        finish()
    }

    /** 文の途中に入れる語句（英語・フランス語は小文字で始める。「OFF」のような略語はそのまま） */
    private fun inner(s: String): String =
        if (I18n.lang == Lang.JA || s.length < 2 || !s[1].isLowerCase()) s else s.replaceFirstChar { it.lowercase(I18n.locale) }

    private fun notOn(ap: Int?): String = inner(Fmt.ap(ap)).let {
        tr("アクセスポイントが ON にならない・状態 $it", "the hotspot didn't come on, state $it", "le point d'accès ne s'est pas activé, état $it")
    }

    private fun retryIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, BootTetherRetryReceiver::class.java).setAction(ACTION_RETRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** [ms] ミリ秒後にもう一度 [run] する（ロック解除前でも届く受信先へ。予約は 1 つだけで、新しい予約が前のものを置き換える） */
    private fun schedule(ctx: Context, ms: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, retryIntent(ctx)) }
    }

    private fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(retryIntent(ctx)) }
    }
}

/**
 * 端末の起動（ロック解除前の LOCKED_BOOT_COMPLETED と、解除後の BOOT_COMPLETED）でテザリングを ON にする。
 * Direct Boot で動くので、端末暗号化の領域（BootTether の写し）だけを使う（Prefs・ログは使わない）
 */
class BootTetherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> {
                val pending = goAsync()
                BootTether.run(context) { pending.finish() }
            }
        }
    }
}

/** 起動時のテザリング ON のやり直し・確認（このアプリのアラームだけが送る） */
class BootTetherRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BootTether.ACTION_RETRY) return
        val pending = goAsync()
        BootTether.run(context) { pending.finish() }
    }
}
