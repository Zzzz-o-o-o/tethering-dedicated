package local.tethering.dedicated

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** 電源・温度（仕様 F-01 / F-04 / F-15 / F-16） */
object PowerProbe {
    /** [battery] には受信済みの ACTION_BATTERY_CHANGED を渡せる（無ければスティッキーから読む） */
    fun read(ctx: Context, battery: Intent? = null, includeSoc: Boolean = true): PowerInfo {
        val bat = battery ?: runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pluggedRaw = bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plug = when {
            bat == null || pluggedRaw < 0 -> Plug.UNKNOWN
            pluggedRaw == 0 -> Plug.NONE
            pluggedRaw and BatteryManager.BATTERY_PLUGGED_AC != 0 -> Plug.AC
            pluggedRaw and BatteryManager.BATTERY_PLUGGED_USB != 0 -> Plug.USB
            pluggedRaw and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> Plug.WIRELESS
            else -> Plug.AC
        }
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempRaw = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val soc = if (includeSoc) readSocTemp() else null
        return PowerInfo(
            levelPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            plug = plug,
            charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            tempC = if (tempRaw == Int.MIN_VALUE) null else (tempRaw / 10.0).takeIf { it in -30.0..120.0 },
            voltageMv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            currentMa = bm?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull() }
                ?.let { Parsers.currentMa(it) },
            powerSave = pm?.isPowerSaveMode,
            deviceIdle = pm?.isDeviceIdleMode,
            thermalStatus = pm?.let { runCatching { it.currentThermalStatus }.getOrNull() },
            socTempC = soc?.first,
            socZone = soc?.second,
            batteryOptExempt = pm?.isIgnoringBatteryOptimizations(ctx.packageName),
            dataSaver = cm?.let { runCatching { it.restrictBackgroundStatus }.getOrNull() }
                ?.let { it != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED },
            counterUah = bm?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull() }
                ?.let { PowerMath.normalizeCounter(it) },
        )
    }

    /** SoC の温度センサーのうち最も高い値（読めない機種が多いので参考値。読めなければ null） */
    private fun readSocTemp(): Pair<Double, String>? {
        val zones = File("/sys/class/thermal").listFiles(FileFilter { it.name.startsWith("thermal_zone") }) ?: return null
        var best: Pair<Double, String>? = null
        for (z in zones) {
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: continue
            if (!Parsers.isSocZone(type)) continue
            val raw = runCatching { File(z, "temp").readText().trim().toLong() }.getOrNull() ?: continue
            val c = Parsers.thermalC(raw) ?: continue
            if (best == null || c > best.first) best = c to type
        }
        return best
    }
}

/**
 * Wi-Fi テザリング（仕様 F-10）。
 * 状態は OS が送るスティッキーブロードキャストから読む（非公開 API の呼び出しやリフレクションは使わない）。
 */
object TetherProbe {
    const val ACTION_AP = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_AP_STATE = "wifi_state"
    private const val EXTRA_AP_IFACE = "wifi_ap_interface_name"
    private const val EXTRA_AP_MODE = "wifi_ap_mode"
    const val ACTION_TETHER = "android.net.conn.TETHER_STATE_CHANGED"
    private const val EXTRA_ACTIVE = "tetherArray"

    /** Android 10 の「接続がないときにアクセスポイントを自動でOFF」（Settings.Global、非公開キー） */
    const val KEY_SOFT_AP_TIMEOUT = "soft_ap_timeout_enabled"

    /** [exclude] は接続端末として数えない IP アドレス（このテザリングにつないだスマートプラグなど。仕様 F-45） */
    fun read(ctx: Context, countClients: Boolean, ap: Intent? = null, tether: Intent? = null, exclude: Set<String> = emptySet()): TetherInfo {
        val apIntent = ap ?: sticky(ctx, ACTION_AP)
        val apState = apIntent?.getIntExtra(EXTRA_AP_STATE, -1)?.takeIf { it >= 0 }
        val apIface = apIntent?.getStringExtra(EXTRA_AP_IFACE)?.takeIf { it.isNotEmpty() }
        val apMode = apIntent?.getIntExtra(EXTRA_AP_MODE, -1)?.takeIf { it >= 0 }
        val active = (tether ?: sticky(ctx, ACTION_TETHER))?.getStringArrayListExtra(EXTRA_ACTIVE)?.toList()
        val autoOff = Os.global(ctx, KEY_SOFT_AP_TIMEOUT)?.toIntOrNull()?.let { it != 0 }
        val wifiIface = apIface ?: active?.firstOrNull { Ifaces.isWifi(it) }
        val running = apState == TetherInfo.AP_ENABLED || active?.any { Ifaces.isWifi(it) } == true
        val (clients, method) = when {
            !countClients -> null to "数えない設定"
            !running -> null to "テザリングOFF"
            else -> Clients.count(wifiIface, exclude)
        }
        return TetherInfo(apState, apIface, apMode, active, clients, method, autoOff)
    }

    private fun sticky(ctx: Context, action: String): Intent? =
        runCatching { ctx.registerReceiver(null, IntentFilter(action)) }.getOrNull()
}

/** 接続端末数の推定。台数だけを数え、MAC アドレス・IP アドレスは保存しない */
object Clients {
    fun count(iface: String?, exclude: Set<String> = emptySet()): Pair<Int?, String> {
        val note = if (exclude.isEmpty()) "" else "・プラグを除く"
        try {
            return Parsers.countArp(File("/proc/net/arp").readLines(), iface, exclude) to "ARP$note"
        } catch (_: Exception) {
            // Android 10 では読めない機種がある → 次の方法へ
        }
        try {
            val p = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroy()
                return null to "取得不可（応答なし）"
            }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (p.exitValue() == 0) return Parsers.countNeigh(out, iface, exclude) to "ip neigh$note"
        } catch (_: Exception) {
        }
        return null to "取得不可（OSの制限）"
    }
}

/** OS の設定値を読む（読むだけ。書き込みは ADB 事前設定スクリプトで行う） */
object Os {
    fun global(ctx: Context, key: String): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun system(ctx: Context, key: String): String? =
        runCatching { Settings.System.getString(ctx.contentResolver, key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun secure(ctx: Context, key: String): String? =
        runCatching { Settings.Secure.getString(ctx.contentResolver, key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** 優先ネットワークの種類（仕様 F-31）。データ通信の SIM の値（preferred_network_mode<subId>）を優先し、なければ全体の値 */
    fun preferredNetworkMode(ctx: Context): Int? {
        val sub = runCatching { android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        val keys = listOfNotNull(sub.takeIf { it >= 0 }?.let { "preferred_network_mode$it" }, "preferred_network_mode")
        for (k in keys) NetworkModes.parse(global(ctx, k))?.let { return it }
        return null
    }
}

/** モバイル回線（仕様 F-11） */
object LineProbe {
    @SuppressLint("MissingPermission")
    fun read(ctx: Context, service: ServiceState?): LineInfo {
        val perm = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val airplane = Os.global(ctx, Settings.Global.AIRPLANE_MODE_ON)?.let { it != "0" }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
            ?: return LineInfo(perm, null, null, null, null, null, null, null, airplane, null, null, null, null, null, null, null, null, null, null)

        val ss = service ?: if (perm) runCatching { tm.serviceState }.getOrNull() else null
        val dataRat = if (perm) {
            runCatching { tm.dataNetworkType }.getOrNull()
        } else {
            // 権限がないと 0（不明）が返ることがあり「未登録」と区別できないので、0 は取得不可として扱う
            @Suppress("DEPRECATION")
            runCatching { tm.networkType }.getOrNull()?.takeIf { it != 0 }
        }
        val sig = runCatching { tm.signalStrength }.getOrNull()
        val cells = sig?.cellSignalStrengths.orEmpty()
        val lte = cells.filterIsInstance<CellSignalStrengthLte>().firstOrNull()
        val nr = cells.filterIsInstance<CellSignalStrengthNr>().firstOrNull()
        val wcdma = cells.filterIsInstance<CellSignalStrengthWcdma>().firstOrNull()
        val gsm = cells.filterIsInstance<CellSignalStrengthGsm>().firstOrNull()

        var type: String? = null
        var rsrp: Int? = null
        var rsrq: Int? = null
        var sinr: Double? = null
        var dbm: Int? = null
        when {
            // NSA の 5G は LTE が制御を担うので LTE の値を表示する
            lte != null -> {
                type = if (nr != null) "LTE＋5G" else "LTE"
                rsrp = Parsers.inRange(lte.rsrp, -140..-43)
                rsrq = Parsers.inRange(lte.rsrq, -34..3)
                sinr = Parsers.lteSinrDb(lte.rssnr, Build.VERSION.SDK_INT)
                dbm = Parsers.inRange(lte.dbm, -140..-43)
            }
            nr != null -> {
                type = "5G"
                rsrp = Parsers.inRange(nr.ssRsrp, -156..-31)
                rsrq = Parsers.inRange(nr.ssRsrq, -43..20)
                sinr = Parsers.inRange(nr.ssSinr, -23..40)?.toDouble()
                dbm = Parsers.inRange(nr.dbm, -156..-31)
            }
            wcdma != null -> {
                type = "WCDMA"
                dbm = Parsers.inRange(wcdma.dbm, -120..-24)
            }
            gsm != null -> {
                type = "GSM"
                dbm = Parsers.inRange(gsm.dbm, -113..-51)
            }
        }

        val isLte = dataRat == TelephonyManager.NETWORK_TYPE_LTE || dataRat == NETWORK_TYPE_LTE_CA || lte != null
        val channel = ss?.channelNumber?.takeIf { it > 0 && it != Int.MAX_VALUE }
        val band = if (isLte && channel != null) Bands.lteBand(channel) else null
        val bws = ss?.cellBandwidths?.filter { it > 0 }?.map { it / 1000.0 }?.takeIf { it.isNotEmpty() }

        return LineInfo(
            phonePermission = perm,
            simState = runCatching { tm.simState }.getOrNull(),
            simOperator = runCatching { tm.simOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            networkOperator = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            voiceState = ss?.state,
            dataRat = dataRat,
            dataState = runCatching { tm.dataState }.getOrNull(),
            dataEnabled = runCatching { tm.isDataEnabled }.getOrNull(),
            airplane = airplane,
            roaming = ss?.roaming ?: runCatching { tm.isNetworkRoaming }.getOrNull(),
            signalType = type,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            dbm = dbm,
            level = sig?.level?.takeIf { it in 0..4 },
            earfcn = if (isLte) channel else null,
            band = band?.label,
            bandwidthsMhz = bws,
        )
    }

    /** TelephonyManager.NETWORK_TYPE_LTE_CA（非公開の定数） */
    private const val NETWORK_TYPE_LTE_CA = 19
}

/**
 * 接続性チェック（仕様 F-12）。回線登録 → IP → DNS → HTTP を段階ごとに判定し、
 * 最初につまずいた段階を原因とする。端末自身のモバイル回線（Network）に紐づけて通信する。
 */
object ConnectivityProbe {
    private val dnsPool = Executors.newCachedThreadPool()

    @Suppress("DEPRECATION")
    fun cellularNetwork(cm: ConnectivityManager): Network? {
        var fallback: Network? = null
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) return n
            if (fallback == null) fallback = n
        }
        return fallback
    }

    /**
     * チェックに使うネットワーク＝端末が実際に使っている既定のネットワーク（テザリング中はふつうモバイル回線）。
     * Wi-Fi に接続しているあいだモバイル回線は「バックグラウンド」になり、通常のアプリからは通信できない
     * （Android 8 以降の仕様）ため、モバイル回線に固定せず既定のネットワークを使い、経路を結果に残す。
     */
    fun upstreamNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            return active
        }
        val cell = cellularNetwork(cm) ?: return null
        return cell.takeIf { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND) == true }
    }

    private fun routeOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "モバイル回線"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有線"
        else -> "その他"
    }

    /**
     * [skipReach] なら、OS の接続検証が OK で IP 通信もできているときに限り、DNS・HTTP の確認を省く（仕様 F-32。
     * 接続端末の通信が流れていて、インターネットに届いているのが明らかなとき）
     */
    fun run(ctx: Context, prefs: Prefs, line: LineInfo?, trigger: Trigger, skipReach: Boolean = false): CheckResult {
        val now = System.currentTimeMillis()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val timeoutMs = prefs.int(Defs.TIMEOUT) * 1000
        val cell = cm?.let { cellularNetwork(it) }
        val net = cm?.let { upstreamNetwork(it) }
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val route = caps?.let { routeOf(it) }

        val registered = when {
            line == null -> Tri.UNKNOWN
            line.simReady == false -> Tri.NG
            // データ専用 SIM は音声が「圏外」のままなので、データ網の登録とモバイル回線の Network も見る
            line.voiceState == LineInfo.STATE_IN_SERVICE || line.dataRegistered == true || cell != null -> Tri.OK
            line.airplane == true -> Tri.NG
            line.voiceState != null || line.dataRegistered == false -> Tri.NG
            else -> Tri.UNKNOWN
        }
        val hasAddr = lp?.linkAddresses?.any { la ->
            val a = la.address
            !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress
        } == true
        val hasRoute = lp?.routes?.any { it.isDefaultRoute } == true
        val ip = if (net != null && hasAddr && hasRoute) Tri.OK else Tri.NG
        val validated = when {
            caps == null -> Tri.UNKNOWN
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Tri.OK
            else -> Tri.NG
        }
        val viaCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        if (skipReach && ip == Tri.OK && validated == Tri.OK && Diagnosis.failure(line?.simReady, registered, ip, Tri.SKIP, Tri.SKIP) == null) {
            return CheckResult(
                now, trigger, registered, ip, Tri.SKIP, Tri.SKIP, validated, null, null, viaCellular, route, null,
                "インターネットに正常に到達できます（接続端末の通信が流れているため、到達確認は省略）", "", probeSkipped = true,
            )
        }

        val url = runCatching { URL(prefs.text(Defs.URL)) }.getOrNull()
        val details = ArrayList<String>()
        var dns = Tri.SKIP
        var http = Tri.SKIP
        var code: Int? = null
        var httpMs: Long? = null
        var httpReason: String? = null

        if (ip == Tri.OK && net != null && url != null) {
            dns = try {
                val f = dnsPool.submit<Array<InetAddress>> { net.getAllByName(url.host) }
                val addrs = try {
                    f.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                } finally {
                    f.cancel(true)
                }
                if (addrs.isNotEmpty()) Tri.OK else Tri.NG
            } catch (e: Exception) {
                details.add("DNS: " + reason(e))
                Tri.NG
            }
        }

        if (dns == Tri.OK && net != null && url != null && prefs.bool(Defs.HTTP)) {
            val t0 = SystemClock.elapsedRealtime()
            var conn: HttpURLConnection? = null
            try {
                conn = net.openConnection(url) as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("Connection", "close")
                code = conn.responseCode
                drain(conn, code)
                httpMs = SystemClock.elapsedRealtime() - t0
                http = if (code == prefs.int(Defs.EXPECT_CODE)) Tri.OK else Tri.NG
                if (http == Tri.NG) details.add("HTTP: 応答コード $code")
            } catch (e: Exception) {
                http = Tri.NG
                httpReason = reason(e)
                details.add("HTTP: $httpReason")
            } finally {
                conn?.disconnect()
            }
        }

        val failure = Diagnosis.failure(line?.simReady, registered, ip, dns, http)
        var diagnosis = Diagnosis.text(failure, line, if (http == Tri.NG) code else null, httpReason)
        if (failure == null && route != null && route != "モバイル回線") diagnosis += "（経路: $route）"
        return CheckResult(now, trigger, registered, ip, dns, http, validated, code, httpMs, viaCellular, route, failure, diagnosis, details.joinToString(" / "))
    }

    /** 応答本文を少しだけ読んで捨てる（接続をきれいに閉じるため。generate_204 は本文なし） */
    private fun drain(conn: HttpURLConnection, code: Int) {
        runCatching {
            val s = if (code >= 400) conn.errorStream else conn.inputStream
            s?.use { input ->
                val buf = ByteArray(1024)
                var total = 0
                while (total < 4096) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
        }
    }

    private fun reason(e: Throwable): String {
        val c = if (e is ExecutionException) e.cause ?: e else e
        return when (c) {
            is TimeoutException, is SocketTimeoutException -> "タイムアウト"
            is UnknownHostException -> "名前解決できない"
            is ConnectException -> "接続できない"
            is SSLException -> "TLSエラー"
            is InterruptedIOException -> "中断"
            is IOException -> "通信エラー（${c.javaClass.simpleName}）"
            else -> c.javaClass.simpleName
        }
    }
}
