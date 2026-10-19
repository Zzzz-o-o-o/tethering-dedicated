package local.tethering.dedicated

import android.app.Application
import android.content.res.Configuration

/** アプリ全体の初期化 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 表示の言語は端末の言語に合わせる（最初に行う。ほかの処理が文字列を作る前に）
        I18n.sync(resources)
        Notifier.createChannels(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 端末の言語が変わった。通知のチャンネル名も新しい言語にする（常駐通知は監視サービスが作り直す）
        val before = I18n.lang
        I18n.sync(resources)
        if (I18n.lang != before) Notifier.createChannels(this)
    }
}
