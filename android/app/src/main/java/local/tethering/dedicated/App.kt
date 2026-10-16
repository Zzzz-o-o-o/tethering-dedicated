package local.tethering.dedicated

import android.app.Application

/** アプリ全体の初期化 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
