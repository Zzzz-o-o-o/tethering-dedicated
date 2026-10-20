package local.tethering.dedicated

import android.app.Application
import android.content.res.Configuration
import android.os.Build

/** アプリ全体の初期化 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 表示の言語は端末の言語に合わせる（最初に行う。ほかの処理が文字列を作る前に）
        I18n.sync(resources)
        Notifier.createChannels(this)
        // 接続端末の読み方。Android 11・12 用の版は netlink で直接読み（ip neigh が使えないため）、Android 13・14 用の版は近隣テーブルを
        // どれも読めないので、小さな確認で数える
        val target = applicationInfo.targetSdkVersion
        Clients.way = when {
            target >= Build.VERSION_CODES.TIRAMISU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Clients.Way.PROBE
            target >= Build.VERSION_CODES.R -> Clients.Way.NETLINK_FIRST
            else -> Clients.Way.DEFAULT
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 端末の言語が変わった。通知のチャンネル名も新しい言語にする（常駐通知は監視サービスが作り直す）
        val before = I18n.lang
        I18n.sync(resources)
        if (I18n.lang != before) Notifier.createChannels(this)
    }
}
