package local.tethering.dedicated

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** クイック設定のタイルの表示（v0.10.0）。Android に依存しない判断 */
object HotspotTile {
    /** [state] は Tile.STATE_* */
    data class View(val state: Int, val subtitle: String)

    /**
     * [apState] はアクセスポイントの状態（TetherInfo.AP_*）、[clients] は接続端末の数（分からなければ null）。
     * タイルの 2 行目は約 20 文字で切れるので短くする（ON・OFF はメイン画面と同じく、どの言語でもこの表記）
     */
    fun view(apState: Int?, clients: Int?, permitted: Boolean): View = when (apState) {
        TetherInfo.AP_ENABLED -> View(
            Tile.STATE_ACTIVE,
            clients?.let { n -> tr("ON・端末${n}台", "ON · $n device(s)", "ON · $n appareil(s)") } ?: "ON",
        )
        TetherInfo.AP_ENABLING -> View(Tile.STATE_UNAVAILABLE, tr("起動中…", "Turning on…", "Activation…"))
        TetherInfo.AP_DISABLING -> View(Tile.STATE_UNAVAILABLE, tr("停止中…", "Turning off…", "Arrêt…"))
        TetherInfo.AP_FAILED -> View(Tile.STATE_INACTIVE, tr("起動に失敗", "Failed to start", "Échec du démarrage"))
        else -> View(
            Tile.STATE_INACTIVE,
            if (permitted) "OFF" else tr("OFF・許可が必要", "Permission needed", "Autorisation requise"),
        )
    }
}

/**
 * クイック設定のタイル（v0.10.0）。テザリングの状態と接続台数を出し、タップで ON・OFF する（「システム設定の変更」の許可が必要。
 * なければ、はじめの設定を開く）。画面ロック中は、ロックを解除してから切り替える（他の人がロック画面から切り替えられないように）。
 * タイルから OFF にしたときは、意図した停止なので、テザリング停止の異常として知らせない（ON になったら解除）
 */
class HotspotTileService : TileService() {
    private var registered = false
    private var shown: HotspotTile.View? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    // ON にした直後は接続台数がまだ分からないので、監視サービスが数え直したときにも直す
    private val listener: (StateStore.State) -> Unit = { refresh() }

    override fun onStartListening() {
        super.onStartListening()
        // パネルを開いている間は、アクセスポイントの状態が変わるたびに表示を直す
        if (!registered) registered = runCatching { SystemBroadcasts.register(this, receiver, IntentFilter(TetherProbe.ACTION_AP)) }.isSuccess
        StateStore.unlisten(listener) // 続けて呼ばれても二重に登録しない
        StateStore.listen(listener)
        shown = null
        refresh()
    }

    override fun onStopListening() {
        StateStore.unlisten(listener)
        if (registered) runCatching { unregisterReceiver(receiver) }
        registered = false
        super.onStopListening()
    }

    override fun onDestroy() {
        StateStore.unlisten(listener)
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        if (!TetherControl.permitted(this)) {
            openSetup()
            return
        }
        val prefs = Prefs(this)
        if (TetherProbe.apState(this) == TetherInfo.AP_ENABLED) {
            val r = TetherControl.stop(this)
            if (r.ok) {
                prefs.tileOffAt = System.currentTimeMillis()
                note(tr("クイック設定のタイルで OFF にした", "Turned off from the quick settings tile", "Arrêté depuis la vignette des réglages rapides"))
            } else {
                toast(tr("OFF にできませんでした（${r.text}）", "Couldn't turn it off (${r.text})", "Arrêt impossible (${r.text})"))
            }
        } else {
            TetherControl.start(this) { r ->
                if (r.ok) {
                    note(tr("クイック設定のタイルで ON にした", "Turned on from the quick settings tile", "Activé depuis la vignette des réglages rapides"))
                } else {
                    toast(tr("ON にできませんでした（${r.text}）", "Couldn't turn it on (${r.text})", "Activation impossible (${r.text})"))
                }
                refresh()
            }
        }
        refresh()
    }

    /**
     * パネルを閉じて「はじめの設定」を開く。PendingIntent を渡す形は Android 14 から（Android 14 用の版（targetSdk 34）で Intent を
     * 渡す形を使うと例外になる）。Android 13 までは Intent を渡す形しかない
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSetup() {
        val i = Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val v = HotspotTile.view(TetherProbe.apState(this), StateStore.state.tether?.clients, TetherControl.permitted(this))
        if (v == shown) return // 監視の状態は頻繁に変わるので、表示が変わるときだけ送る
        shown = v
        t.label = getString(R.string.tile_label)
        t.state = v.state
        t.subtitle = v.subtitle
        t.updateTile()
    }

    private fun note(text: String) {
        LogStore.write(this, Ev.TETHER, StateStore.state, action = text)
        StateStore.addRecent(RecentEvent(System.currentTimeMillis(), Ev.TETHER, text))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
