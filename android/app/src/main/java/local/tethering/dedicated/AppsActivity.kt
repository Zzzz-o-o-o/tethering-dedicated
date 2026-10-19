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
            Ui.text(
                c,
                tr(
                    "利用者がインストールしたアプリの一覧です。使わないものはアプリ情報からアンインストール（または無効化）してください。",
                    "Apps you installed. Uninstall (or disable) the ones you don't use from their app info.",
                    "Applications que vous avez installées. Désinstallez (ou désactivez) celles que vous n'utilisez pas depuis leurs informations.",
                ),
                13.5f,
            )
            Ui.small(
                c,
                tr(
                    "システムアプリは表示しません。無効化はPCからも行えます（元に戻せます）:", "System apps aren't listed. You can also disable apps from a PC (reversible):",
                    "Les applications système ne sont pas listées. Vous pouvez aussi désactiver des applications depuis un PC (réversible) :",
                ),
            )
            val pkg = tr("<パッケージ名>", "<package>", "<paquet>")
            Ui.mono(c, "adb shell pm disable-user --user 0 $pkg\nadb shell pm enable $pkg")
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        val loading = Ui.small(list, tr("読み込み中…", "Loading…", "Chargement…"))
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
                if (apps.isEmpty()) Ui.small(list, tr("ユーザーがインストールしたアプリはありません", "No user-installed apps", "Aucune application installée par l'utilisateur"))
                val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN)
                for (a in apps) {
                    val c = Ui.card(list)
                    Ui.text(c, a.label, 15f, bold = true)
                    val installed = if (a.installedAt > 0) {
                        fmt.format(Date(a.installedAt)).let { tr("　導入: $it", " · installed $it", " · installée le $it") }
                    } else {
                        ""
                    }
                    Ui.small(c, a.pkg + installed)
                    Ui.button(c, tr("アプリ情報を開く", "Open app info", "Ouvrir les infos de l'application")) {
                        SystemScreens.open(this, SystemScreens.appDetails(a.pkg))
                    }
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
