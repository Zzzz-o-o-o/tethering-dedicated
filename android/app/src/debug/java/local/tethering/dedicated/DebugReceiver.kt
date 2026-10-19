package local.tethering.dedicated

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 開発用（debug ビルドだけに入る）。PC から機能を直接呼んで確かめる。結果は logcat（タグ TDdebug）に出す。
 * 例:
 *   adb shell am broadcast -n local.tethering.dedicated/.DebugReceiver -a tether_probe
 *   adb shell am broadcast -n local.tethering.dedicated/.DebugReceiver -a set_pref --es key quiet_hours --es str 1-6
 *   adb shell am broadcast -n local.tethering.dedicated/.DebugReceiver -a set_pref --es key plug --ez bool true
 *   adb shell am broadcast -n local.tethering.dedicated/.DebugReceiver -a state
 */
class DebugReceiver : BroadcastReceiver() {
    // 書いた設定をサービスがすぐ読めるよう、ここでは commit で書く
    @SuppressLint("ApplySharedPref")
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pr = goAsync()
        fun out(s: String) {
            Log.i("TDdebug", "${intent.action}: $s")
        }
        when (intent.action) {
            "tether_probe" -> out("permitted=${TetherControl.permitted(ctx)} probe=${TetherControl.probe(ctx).text}")
            "tether_stop" -> out(TetherControl.stop(ctx).text)
            "tether_start" -> {
                TetherControl.start(ctx) { r ->
                    out(r.text)
                    pr.finish()
                }
                return
            }
            // 設定を書き換えて、監視サービスに反映させる（設定画面の「保存」と同じ流れ）
            "set_pref" -> {
                val key = intent.getStringExtra("key")
                val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                when {
                    key == null -> out("key がありません")
                    intent.hasExtra("int") -> sp.putInt(key, intent.getIntExtra("int", 0))
                    intent.hasExtra("bool") -> sp.putBoolean(key, intent.getBooleanExtra("bool", false))
                    intent.hasExtra("str") -> sp.putString(key, intent.getStringExtra("str"))
                    else -> sp.remove(key)
                }
                sp.commit()
                // 起動時のテザリング ON は、ロック解除前でも読める写しを使う（設定画面の「保存」と同じく写す）
                BootTether.sync(ctx, Prefs(ctx).bool(Defs.TETHER_ON_BOOT))
                if (Prefs(ctx).monitoring) MonitorService.send(ctx, MonitorService.ACTION_SETTINGS)
                out("$key を設定")
            }
            "check_now" -> {
                MonitorService.send(ctx, MonitorService.ACTION_CHECK_NOW)
                out("チェックを依頼")
            }
            // 月の通信量を差し替えてチェックする（--el bytes N。bytes を付けなければ差し替えをやめる）
            "cap_fake" -> {
                MonthUsage.debugOverride = if (intent.hasExtra("bytes")) intent.getLongExtra("bytes", 0L) else null
                MonitorService.send(ctx, MonitorService.ACTION_CHECK_NOW)
                out("月の通信量=${MonthUsage.debugOverride}")
            }
            "state" -> {
                val s = StateStore.state
                val p = Prefs(ctx)
                out(
                    "overall=${s.overall().label} tether=${s.tether?.let { Fmt.ap(it.apState) }} clients=${s.tether?.clients}(${s.tether?.clientsMethod}) " +
                        "plug=${s.power?.plug} level=${s.power?.levelPct} temp=${s.power?.tempC} interval=${s.intervalMin} next=${Fmt.time(s.nextCheckAt)}",
                )
                out("sched=${s.sched} stoppedWindow=${p.schedStoppedWindow} handled=${p.schedHandledWindow}")
                out("plugView=${s.plug} want=${p.plugWant}")
                out("ext=${s.ext} queue=${p.extQueueJson}")
                out("cap=${s.cap} cycle=${p.capCycle} notified=${p.capNotified} stopped=${p.capStoppedCycle} handled=${p.capHandledCycle} block=${BootTether.blockedUntil(ctx)}")
                out("tetherIssue=${s.tetherIssue} upstream=${s.upstream} alerts=${s.alerts}")
            }
            // ファイルに書き出す設定（通知先の URL などが含まれないことの確認）
            "export_json" -> out(Prefs(ctx).toJson().toString())
            else -> out("unknown")
        }
        pr.finish()
    }
}
