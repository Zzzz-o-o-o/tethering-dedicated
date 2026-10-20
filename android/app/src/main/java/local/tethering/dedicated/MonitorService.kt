package local.tethering.dedicated

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 画面消灯中も監視を続けるフォアグラウンドサービス（仕様 設計目標 2）。
 * 状態変化はイベント（ブロードキャスト・コールバック）で受け取り、定期チェックはアラームで低頻度に行う。
 * OS 標準のテザリング機能は読むだけ。例外は利用者が時間帯スケジュール（仕様 F-37）を ON にしたときで、
 * そのときだけテザリングを止め、決めた時刻に再開する（監視を止めるときは、止めていたテザリングを再開する）。
 * 起動時のテザリング ON（v0.7.0）は、ロック解除前にも動くよう、このサービスとは別の受信先（BootTether）で行う。
 */
class MonitorService : Service() {
    companion object {
        const val ACTION_START = "local.tethering.dedicated.START"
        const val ACTION_BOOT = "local.tethering.dedicated.BOOT"
        const val ACTION_ALARM = "local.tethering.dedicated.ALARM"
        const val ACTION_CHECK_NOW = "local.tethering.dedicated.CHECK_NOW"
        const val ACTION_SETTINGS = "local.tethering.dedicated.SETTINGS"
        const val ACTION_MAINT = "local.tethering.dedicated.MAINT"
        const val ACTION_STOP = "local.tethering.dedicated.STOP"
        const val ACTION_EXP_START = "local.tethering.dedicated.EXP_START"
        const val ACTION_EXP_CONFIRM = "local.tethering.dedicated.EXP_CONFIRM"
        const val ACTION_EXP_ABORT = "local.tethering.dedicated.EXP_ABORT"
        const val ACTION_AUTO_RESTORE = "local.tethering.dedicated.AUTO_RESTORE"
        const val EXTRA_UNTIL = "until"
        const val EXTRA_TRUSTED_BOOT = "trusted_boot"
        const val EXTRA_CANDIDATE = "candidate"
        const val EXTRA_LABEL_A = "label_a"
        const val EXTRA_LABEL_B = "label_b"

        /** 動いているか（常駐通知を出してから止まるまで。同じプロセスの中だけで使う） */
        @Volatile
        var running = false
            private set

        /** [trustedBoot] は OS だけが送れる起動の通知（BOOT_COMPLETED）で起こされたとき */
        fun send(ctx: Context, action: String, until: Long? = null, trustedBoot: Boolean = false) {
            val i = Intent(ctx, MonitorService::class.java).setAction(action)
            if (until != null) i.putExtra(EXTRA_UNTIL, until)
            if (trustedBoot) i.putExtra(EXTRA_TRUSTED_BOOT, true)
            start(ctx, i)
        }

        fun startExperiment(ctx: Context, candidate: String, labelA: String?, labelB: String?) {
            start(
                ctx,
                Intent(ctx, MonitorService::class.java).setAction(ACTION_EXP_START)
                    .putExtra(EXTRA_CANDIDATE, candidate).putExtra(EXTRA_LABEL_A, labelA).putExtra(EXTRA_LABEL_B, labelB),
            )
        }

        /**
         * 動いているあいだは startService で送る（常駐通知があるので、アプリは前面と同じ扱いになる）。止まっているときは
         * startForegroundService で開始する。Android 12 用の版（targetSdk 31）では、裏からの開始を OS が断ることがある
         * （電池の最適化の対象外・起動の直後・正確なアラームのときは開始できる）ので、断られても落ちないようにする
         * （次の正確なアラームや、画面を開いたときに始まる）
         */
        private fun start(ctx: Context, i: Intent) {
            if (running) {
                try {
                    ctx.startService(i)
                    return
                } catch (_: IllegalStateException) {
                    // 止まる途中など。開始からやり直す
                }
            }
            try {
                ctx.startForegroundService(i)
            } catch (e: IllegalStateException) {
                // Android 12 以降の ForegroundServiceStartNotAllowedException
                Log.w("TDservice", "start not allowed: ${i.action}: ${e.javaClass.simpleName}")
            }
        }
    }

    private var monitor: Monitor? = null
    private var receiverRegistered = false
    private var phoneListener: PhoneStateListener? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val m = monitor ?: return
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> m.onBattery(intent)
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> m.onBattery(null)
                TetherProbe.ACTION_AP, TetherProbe.ACTION_TETHER -> m.onTetherBroadcast(intent)
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> m.onAirplane(intent.getBooleanExtra("state", false))
                Intent.ACTION_SCREEN_ON -> m.onScreen(true)
                Intent.ACTION_SCREEN_OFF -> m.onScreen(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // startForegroundService() から 5 秒以内に呼ぶ必要があるので最初に行う。Android 12 用の版では、裏から始まったときに
        // OS が断ることがある（そのときは止める。次の正確なアラームや、画面を開いたときに始まる）
        try {
            startForeground(Notifier.ID_ONGOING, Notifier.ongoing(this, StateStore.state.copy(running = true)))
        } catch (e: IllegalStateException) {
            Log.w("TDservice", "startForeground not allowed: ${e.javaClass.simpleName}")
            stopSelf()
            return
        }
        running = true
        val m = Monitor(this)
        monitor = m
        registerEvents(m)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val m = monitor ?: return START_NOT_STICKY
        val prefs = Prefs(this)
        if (intent?.action == ACTION_STOP) {
            prefs.monitoring = false
            m.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.monitoring) {
            // 利用者が停止中（アラームの取り残しや再起動の通知で起こされた場合）
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Notifier.update(this, StateStore.state)
        when (intent?.action) {
            ACTION_BOOT -> m.ensureStarted(boot = true, trustedBoot = intent.getBooleanExtra(EXTRA_TRUSTED_BOOT, false))
            ACTION_ALARM -> {
                m.ensureStarted(boot = false, initialCheck = false)
                m.onAlarm()
            }
            ACTION_CHECK_NOW -> {
                m.ensureStarted(boot = false, initialCheck = false)
                m.requestCheck(Trigger.MANUAL)
            }
            ACTION_SETTINGS -> {
                m.ensureStarted(boot = false)
                m.onSettingsChanged()
            }
            ACTION_MAINT -> {
                m.ensureStarted(boot = false, initialCheck = false)
                m.setMaintenance(intent.getLongExtra(EXTRA_UNTIL, 0L))
            }
            ACTION_EXP_START -> {
                m.ensureStarted(boot = false, initialCheck = false)
                intent.getStringExtra(EXTRA_CANDIDATE)?.let {
                    m.startExperiment(it, intent.getStringExtra(EXTRA_LABEL_A), intent.getStringExtra(EXTRA_LABEL_B))
                }
            }
            ACTION_EXP_CONFIRM -> {
                m.ensureStarted(boot = false, initialCheck = false)
                m.confirmExperimentSwitch()
            }
            ACTION_EXP_ABORT -> {
                m.ensureStarted(boot = false, initialCheck = false)
                m.abortExperiment()
            }
            ACTION_AUTO_RESTORE -> {
                m.ensureStarted(boot = false, initialCheck = false)
                m.autoRestoreAll()
            }
            // ACTION_START、またはシステムによる再起動（START_STICKY で intent が null）
            else -> m.ensureStarted(boot = false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterEvents()
        monitor?.close()
        monitor = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 端末の言語が変わったら、状態の表示と常駐通知を新しい言語で作り直す
        monitor?.onLanguageChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun registerEvents(m: Monitor) {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(TetherProbe.ACTION_AP)
            addAction(TetherProbe.ACTION_TETHER)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            // 画面の点灯時間を電力の記録に残すため（消費の大きな要因）
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            registerReceiver(receiver, f)
            receiverRegistered = true
        }

        getSystemService(TelephonyManager::class.java)?.let { tm ->
            val l = object : PhoneStateListener() {
                override fun onServiceStateChanged(serviceState: ServiceState?) = m.onServiceState(serviceState)
                override fun onDataConnectionStateChanged(state: Int, networkType: Int) = m.onDataConnection(state)
            }
            runCatching {
                tm.listen(l, PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
                phoneListener = l
            }
        }

        getSystemService(ConnectivityManager::class.java)?.let { cm ->
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = m.onCellNetwork(true)
                override fun onLost(network: Network) = m.onCellNetwork(false)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    m.onValidated(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            runCatching {
                cm.registerNetworkCallback(req, cb, Handler(Looper.getMainLooper()))
                netCallback = cb
            }
        }

        getSystemService(PowerManager::class.java)?.let { pm ->
            val tl = PowerManager.OnThermalStatusChangedListener { status -> m.onThermal(status) }
            runCatching {
                pm.addThermalStatusListener(mainExecutor, tl)
                thermalListener = tl
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterEvents() {
        if (receiverRegistered) runCatching { unregisterReceiver(receiver) }
        receiverRegistered = false
        phoneListener?.let { l -> runCatching { getSystemService(TelephonyManager::class.java)?.listen(l, PhoneStateListener.LISTEN_NONE) } }
        phoneListener = null
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        thermalListener?.let { tl -> runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(tl) } }
        thermalListener = null
    }
}
