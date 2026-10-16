package local.tethering.dedicated

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 不要なアプリの整理（仕様 F-05）。利用者が入れたアプリだけを一覧にし、判断は利用者に任せる。
 * システムアプリは一覧にも出さない（仕様 7 章「システムアプリの一括無効化」をしないため）。
 */
class AppsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private class UserApp(val label: String, val pkg: String, val installedAt: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(c, "利用者がインストールしたアプリの一覧です。使わないものはアプリ情報からアンインストール（または無効化）してください。", 13.5f)
            Ui.small(c, "システムアプリは表示しません。無効化はPCからも行えます（元に戻せます）:")
            Ui.mono(c, "adb shell pm disable-user --user 0 <パッケージ名>\nadb shell pm enable <パッケージ名>")
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        val loading = Ui.small(list, "読み込み中…")
        bg.execute {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != packageName }
                .map { ai ->
                    UserApp(
                        ai.loadLabel(pm).toString(),
                        ai.packageName,
                        runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L),
                    )
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
            main.post {
                list.removeView(loading)
                if (apps.isEmpty()) Ui.small(list, "ユーザーがインストールしたアプリはありません")
                val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN)
                for (a in apps) {
                    val c = Ui.card(list)
                    Ui.text(c, a.label, 15f, bold = true)
                    Ui.small(c, a.pkg + if (a.installedAt > 0) "　導入: ${fmt.format(Date(a.installedAt))}" else "")
                    Ui.button(c, "アプリ情報を開く") { SystemScreens.open(this, SystemScreens.appDetails(a.pkg)) }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }
}
