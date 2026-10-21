package local.tethering.dedicated

import android.app.admin.DeviceAdminReceiver

/**
 * 端末の管理アプリ（デバイスオーナー）の受け口（v0.10.0 の Android 16 用の版だけ）。Android 16 では、管理アプリでないと
 * テザリングを切り替えられない（[DeviceOwner]）。管理アプリとして使う機能はテザリングの切り替えだけで、端末の制限などはしない。
 * PC から adb shell dpm set-device-owner local.tethering.dedicated/.AdminReceiver で管理アプリにする
 */
class AdminReceiver : DeviceAdminReceiver()
