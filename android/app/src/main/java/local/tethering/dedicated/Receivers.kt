package local.tethering.dedicated

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 予約したチェックの時刻になった */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "local.tethering.dedicated.CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // サービスが処理を始めるまで CPU が眠らないよう、短いウェイクロックでつなぐ
        WakeLocks.acquireBridge(context)
        MonitorService.send(context, MonitorService.ACTION_ALARM)
    }
}

/** 端末の起動・本アプリの更新のあとで監視を再開する（仕様 F-14 / F-09 の「本アプリ自身の更新」） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).monitoring) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" ->
                MonitorService.send(context, MonitorService.ACTION_BOOT)
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                MonitorService.send(context, MonitorService.ACTION_START)
        }
    }
}
