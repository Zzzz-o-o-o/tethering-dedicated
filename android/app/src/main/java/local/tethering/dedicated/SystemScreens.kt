package local.tethering.dedicated

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * OS の設定画面を開く。機種によって画面の有無が違うので、候補を順に試す。
 * どれも開けなければトーストで知らせる（設定を自動で変えることはしない）。
 */
object SystemScreens {
    fun open(ctx: Context, vararg candidates: Intent): Boolean {
        for (i in candidates) {
            try {
                if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(ctx, "この端末では設定画面を開けませんでした。設定アプリから手動で開いてください", Toast.LENGTH_LONG).show()
        return false
    }

    private fun settings(cls: String) = Intent().setComponent(ComponentName("com.android.settings", cls))

    fun tether(ctx: Context) = open(
        ctx,
        settings("com.android.settings.TetherSettings"),
        settings("com.android.settings.Settings\$TetherSettingsActivity"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    /** アクセスポイントの詳細（「自動的にOFF」の設定がある画面） */
    fun hotspot(ctx: Context) = open(
        ctx,
        settings("com.android.settings.Settings\$WifiTetherSettingsActivity"),
        settings("com.android.settings.TetherSettings"),
        settings("com.android.settings.Settings\$TetherSettingsActivity"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    fun requestIgnoreBatteryOptimizations(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun batteryOptimizationList(ctx: Context) = open(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    fun batterySaver(ctx: Context) = open(ctx, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
    fun battery(ctx: Context) = open(ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    fun display(ctx: Context) = open(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS))
    fun sync(ctx: Context) = open(ctx, Intent(Settings.ACTION_SYNC_SETTINGS))
    fun dataUsage(ctx: Context) = open(ctx, Intent(Settings.ACTION_DATA_USAGE_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    fun apn(ctx: Context) = open(ctx, Intent(Settings.ACTION_APN_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    fun location(ctx: Context) = open(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    fun bluetooth(ctx: Context) = open(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun nfc(ctx: Context) = open(ctx, Intent(Settings.ACTION_NFC_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    fun sound(ctx: Context) = open(ctx, Intent(Settings.ACTION_SOUND_SETTINGS))
    fun security(ctx: Context) = open(ctx, Intent(Settings.ACTION_SECURITY_SETTINGS))
    fun developer(ctx: Context) = open(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    fun systemUpdate(ctx: Context) = open(ctx, Intent("android.settings.SYSTEM_UPDATE_SETTINGS"), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    fun appNotifications(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
        appDetails(ctx.packageName),
    )

    fun appDetails(pkg: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    fun usageAccess(ctx: Context) = open(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), Intent(Settings.ACTION_SECURITY_SETTINGS))

    /** モバイル ネットワークの設定（優先ネットワークの種類・VoLTE がある画面） */
    fun mobileNetwork(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    fun playStore(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage("com.android.vending") ?: return open(ctx)
        return open(ctx, i)
    }
}
