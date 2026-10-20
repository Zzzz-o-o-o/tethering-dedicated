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
            // アプリが読む OS の設定値を、1 つずつ読めるか確かめる（Android 12 で targetSdk 31 だと、非公開のキーの多くが読めない）
            "settings_read" -> {
                val cr = ctx.contentResolver
                val keys = listOf(
                    "global" to "soft_ap_timeout_enabled", "global" to "tether_offload_disabled", "global" to "ota_disable_automatic_update",
                    "global" to "mobile_data_always_on", "global" to "low_power", "global" to "low_power_sticky", "global" to "device_idle_constants",
                    "global" to "wifi_scan_always_enabled", "global" to "ble_scan_always_enabled", "global" to "stay_on_while_plugged_in",
                    "global" to "airplane_mode_on", "global" to "boot_count", "global" to "preferred_network_mode", "global" to "preferred_network_mode1",
                    "secure" to "doze_enabled", "secure" to "doze_always_on", "secure" to "doze_pulse_on_pick_up", "secure" to "doze_pulse_on_double_tap",
                    "secure" to "doze_tap_gesture", "secure" to "wake_gesture_enabled", "secure" to "double_tap_to_wake", "secure" to "location_mode",
                    "system" to "screen_off_timeout", "system" to "screen_brightness_mode", "system" to "screen_brightness",
                )
                for ((ns, k) in keys) {
                    val r = runCatching {
                        when (ns) {
                            "global" -> android.provider.Settings.Global.getString(cr, k)
                            "secure" -> android.provider.Settings.Secure.getString(cr, k)
                            else -> android.provider.Settings.System.getString(cr, k)
                        }
                    }
                    out("$ns $k = " + r.fold({ it ?: "(null)" }, { "DENIED ${it.javaClass.simpleName}" }))
                }
            }
            // 通知を出せるかの読み取り（Android 13 の通知の許可の確認用）
            "notify_state" -> {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                val perm = ctx.checkSelfPermission(NotifyPermission.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                out("enabled=${nm?.areNotificationsEnabled()} importance=${nm?.importance} permission=$perm helper=${NotifyPermission.enabled(ctx)}")
            }
            // 見えるアプリの数（Android 11 の「パッケージの公開範囲」の確認用。QUERY_ALL_PACKAGES がないと少なくなる）
            "pkgs" -> out("installed=${ctx.packageManager.getInstalledApplications(0).size} target=${ctx.applicationInfo.targetSdkVersion}")
            // 接続端末の読み取り方を確かめる（ip neigh の生の結果と、数え方・一覧。Android 11 の制限の確認用）
            "clients" -> {
                Thread {
                    val t = TetherProbe.read(ctx, countClients = false)
                    // --es iface <名前> で、数えるインターフェースを変えられる（エミュレーターでは接続端末を用意できないため）
                    val iface = intent.getStringExtra("iface") ?: t.apIface ?: t.tethered?.firstOrNull { Ifaces.isWifi(it) }
                    val raw = runCatching {
                        val p = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
                        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        "exit=${p.exitValue()} out=" + p.inputStream.bufferedReader().readText().replace("\n", " | ").take(600)
                    }.getOrElse { "error=${it.javaClass.simpleName}: ${it.message}" }
                    out("iface=$iface sdk=${android.os.Build.VERSION.SDK_INT} target=${ctx.applicationInfo.targetSdkVersion} ip neigh: $raw")
                    out("netlink=" + (Netlink.neighbors()?.replace("\n", " | ")?.take(600) ?: "null"))
                    // --ei prefix <長さ> で、範囲の長さを変えて小さな確認を試す（エミュレーターの eth0 は /8 で広すぎるため）
                    val prefix = intent.getIntExtra("prefix", 0)
                    if (prefix > 0 && iface != null) {
                        val a = ProbeScan.ipv4Of(iface)
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        val r = a?.let { ProbeScan.scan(iface, it.first, prefix) }
                        out("probe /$prefix: ${r?.size} present=${r?.take(20)} ms=${android.os.SystemClock.elapsedRealtime() - t0}")
                    }
                    val t1 = android.os.SystemClock.elapsedRealtime()
                    out("count=${Clients.count(iface)} list=${Clients.list(iface)?.map { it.ip + "/" + it.state }} ms=${android.os.SystemClock.elapsedRealtime() - t1}")
                    pr.finish()
                }.start()
                return
            }
            else -> out("unknown")
        }
        pr.finish()
    }
}
