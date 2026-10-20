package local.tethering.dedicated

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * テザリングの詳細（v0.9.0）。連続稼働時間・直近 24 時間の稼働・アクセスポイントの情報と、設定で選んだときは接続端末の一覧（名前を付けられる）。
 * メイン画面のテザリングの欄から開く。一覧は画面を開いている間だけ読み、名前はこの端末の中だけに保存する（ログ・CSV・外部への通知・管理画面には出さない）
 */
class TetherDetailsActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var resumed = false
    private val ticker = Runnable { tick() }

    private lateinit var vState: TextView
    private lateinit var vSession: TextView
    private lateinit var vLastOff: TextView
    private lateinit var vDay: TextView
    private lateinit var vIface: TextView
    private lateinit var vMode: TextView
    private lateinit var vShared: TextView
    private lateinit var vAutoOff: TextView
    private lateinit var vWeb: TextView
    private lateinit var vCount: TextView
    private lateinit var listBox: LinearLayout
    private var listKey = ""

    /** 画面を開いている間だけ読む値 */
    private class Live(val tether: TetherInfo, val devices: List<Parsers.Neighbor>?, val day: Pair<Long, Long>?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.heading(c, tr("稼働時間", "Uptime", "Durée de fonctionnement"))
            vState = Ui.kv(c, tr("Wi-Fiテザリング", "Wi-Fi hotspot", "Point d'accès Wi-Fi"))
            vSession = Ui.kv(c, tr("連続稼働時間", "On continuously", "Activé en continu"))
            vLastOff = Ui.kv(c, tr("前回の停止", "Last time off", "Dernier arrêt"))
            vDay = Ui.kv(c, tr("直近 24 時間", "Last 24 hours", "Dernières 24 heures"))
            Ui.small(
                c,
                tr(
                    "直近 24 時間は、電力の記録（監視していた時間）のうち、テザリングが ON だった時間です。",
                    "“Last 24 hours” is the time the hotspot was on, out of the time covered by the power records (while monitoring).",
                    "« Dernières 24 heures » : durée d'activation du point d'accès, sur la durée couverte par les relevés de consommation (pendant la surveillance).",
                ),
            )
        }
        Ui.card(page).also { c ->
            Ui.heading(c, tr("アクセスポイント", "Hotspot", "Point d'accès"))
            vIface = Ui.kv(c, tr("インターフェース", "Interface", "Interface"))
            vMode = Ui.kv(c, tr("方式", "Mode", "Mode"))
            vShared = Ui.kv(c, tr("共有中", "Sharing", "Partage"))
            vAutoOff = Ui.kv(c, tr("自動OFF設定", "Auto turn-off", "Arrêt automatique"))
            vWeb = Ui.kv(c, tr("管理画面（接続端末から）", "Local web page", "Page web locale"))
            Ui.button(c, tr("テザリング設定を開く", "Open hotspot settings", "Ouvrir les paramètres du point d'accès")) { SystemScreens.tether(this) }
        }
        Ui.card(page).also { c ->
            Ui.heading(c, tr("接続端末", "Connected devices", "Appareils connectés"))
            vCount = Ui.kv(c, tr("台数（推定）", "Count (estimate)", "Nombre (estimation)"))
            listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(listBox)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        listKey = ""
        tick()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 5 秒ごとに読み直す（画面を開いている間だけ） */
    private fun tick() {
        main.removeCallbacks(ticker)
        if (!resumed) return
        val listOn = prefs.bool(Defs.DEVICE_LIST)
        bg.execute {
            val live = runCatching {
                val t = TetherProbe.read(this, countClients = false)
                val iface = t.apIface ?: t.tethered?.firstOrNull { Ifaces.isWifi(it) }
                val devices = if (listOn && t.wifiOn == true) Clients.list(iface) else null
                Live(t, devices, dayOnTime())
            }.getOrNull()
            main.post { if (live != null && !isDestroyed) render(live, listOn) }
        }
        main.postDelayed(ticker, 5_000L)
    }

    /** 直近 24 時間のテザリング ON の時間と、記録のある時間（ミリ秒）。電力の記録の今日と昨日のファイルから */
    private fun dayOnTime(): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val rows = listOf(now - 86_400_000L, now).map { dayFmt.format(it) }.distinct().flatMap { PowerStore.rowsOfDay(this, it) }
        if (rows.isEmpty()) return null
        val p = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
        return ApSession.onTime(rows, now, 86_400_000L) { s -> runCatching { p.parse(s)?.time }.getOrNull() }
    }

    private fun render(l: Live, listOn: Boolean) {
        val t = l.tether
        val now = System.currentTimeMillis()
        vState.text = when {
            t.apState != null -> Fmt.ap(t.apState) + if (t.apMode == 2) Fmt.paren(tr("ローカル専用", "local only", "local uniquement")) else ""
            else -> Fmt.onOff(t.wifiOn)
        }
        val since = prefs.apOnSince
        vSession.text = when {
            t.wifiOn == true && since > 0 -> {
                val d = Fmt.duration(now - since)
                if (prefs.apOnExact) {
                    tr("ON（${Fmt.mdhm(since)} から・$d）", "On (since ${Fmt.mdhm(since)}, $d)", "Activé (depuis le ${Fmt.mdhm(since)}, $d)")
                } else {
                    // 始まりが分からない（このアプリの記録より前から ON）。長さは「それ以上」としてだけ出す
                    val more = if (now - since >= 60_000L) tr("・${d}以上", ", over $d", ", plus de $d") else ""
                    tr("ON（${Fmt.mdhm(since)} より前から$more）", "On (since before ${Fmt.mdhm(since)}$more)", "Activé (depuis avant le ${Fmt.mdhm(since)}$more)")
                }
            }
            t.wifiOn == true -> tr("ON（始まりは監視が記録してから分かります）", "On (the start is known once monitoring records it)", "Activé (le début sera connu après un relevé de la surveillance)")
            t.wifiOn == false && prefs.apOffAt > 0 -> Fmt.duration(now - prefs.apOffAt).let {
                tr("OFF（${Fmt.mdhm(prefs.apOffAt)} から・$it）", "Off (since ${Fmt.mdhm(prefs.apOffAt)}, $it)", "Désactivé (depuis le ${Fmt.mdhm(prefs.apOffAt)}, $it)")
            }
            t.wifiOn == false -> "OFF"
            else -> Fmt.NA
        }
        vLastOff.text = if (prefs.apLastOffStart > 0 && prefs.apLastOffMs > 0) {
            val d = Fmt.duration(prefs.apLastOffMs)
            tr("${Fmt.mdhm(prefs.apLastOffStart)} から $d", "${Fmt.mdhm(prefs.apLastOffStart)} for $d", "le ${Fmt.mdhm(prefs.apLastOffStart)} pendant $d")
        } else {
            tr("記録なし", "None recorded", "Aucun enregistré")
        }
        vDay.text = l.day?.let { (on, covered) ->
            if (covered <= 0) {
                tr("記録なし", "None recorded", "Aucun enregistré")
            } else {
                val pct = on * 100 / covered
                val c = Fmt.duration(covered)
                tr("ON ${Fmt.duration(on)}（記録のある ${c}のうち ${pct}%）", "On ${Fmt.duration(on)} ($pct% of $c recorded)", "Activé ${Fmt.duration(on)} ($pct % de $c enregistrées)")
            }
        } ?: tr("記録なし", "None recorded", "Aucun enregistré")
        vIface.text = t.apIface ?: t.tethered?.firstOrNull { Ifaces.isWifi(it) } ?: Fmt.NA
        vMode.text = when (t.apMode) {
            1 -> tr("テザリング", "Tethering", "Partage de connexion")
            2 -> tr("ローカル専用（インターネットの共有なし）", "Local only (no internet sharing)", "Local uniquement (sans partage d'Internet)")
            else -> Fmt.NA
        }
        vShared.text = Fmt.tethered(t.tethered)
        vAutoOff.text = when (t.autoOff) {
            false -> "OFF ✓"
            true -> tr("ON（接続がないと停止）⚠", "ON (stops when nothing is connected) ⚠", "ON (s'arrête sans appareil connecté) ⚠")
            null -> if (TetherProbe.autoOffReadable) tr("未設定（AOSPの既定はON）", "Not set (AOSP default: ON)", "Non défini (AOSP par défaut : ON)") else TetherProbe.autoOffUnreadable()
        }
        val s = StateStore.state
        vWeb.text = when {
            !prefs.bool(Defs.WEB) -> tr("OFF（設定）", "Off (Settings)", "Désactivée (paramètres)")
            s.web == null -> "－"
            else -> s.web.url ?: s.web.message
        }
        vCount.text = s.tether?.let { c ->
            c.clients?.let { n -> tr("${n}台（${c.clientsMethod}で推定）", "$n (estimated via ${c.clientsMethod})", "$n (estimé via ${c.clientsMethod})") } ?: c.clientsMethod
        } ?: Fmt.NA
        renderList(t, l.devices, listOn)
    }

    private fun renderList(t: TetherInfo, devices: List<Parsers.Neighbor>?, listOn: Boolean) {
        val names = DeviceNames.all(this)
        val key = "$listOn|${t.wifiOn}|" + devices?.joinToString { "${it.mac}${it.ip}${it.state}${names[DeviceNames.key(it)]}" }
        if (key == listKey) return
        listKey = key
        listBox.removeAllViews()
        when {
            !listOn -> {
                Ui.small(
                    listBox,
                    tr(
                        "いまつながっている端末を一覧にして名前を付けるには、設定の「テザリング」で「接続端末の一覧を表示する」を ON にしてください。",
                        "To list the connected devices and name them, turn on “Show the list of connected devices” under “Tethering” in Settings.",
                        "Pour lister les appareils connectés et les nommer, activez « Afficher la liste des appareils connectés » dans « Partage de connexion » des paramètres.",
                    ),
                )
                Ui.button(listBox, tr("設定を開く", "Open Settings", "Ouvrir les paramètres")) { startActivity(Intent(this, SettingsActivity::class.java)) }
            }
            t.wifiOn != true -> Ui.small(listBox, tr("テザリングが OFF のため、接続端末はありません。", "The hotspot is off, so no devices are connected.", "Le point d'accès est arrêté : aucun appareil connecté."))
            devices == null -> Ui.small(listBox, tr("この端末では一覧を読めません（OS の制限）。", "The list can't be read on this phone (system restriction).", "La liste est illisible sur ce téléphone (restriction du système)."))
            devices.isEmpty() -> Ui.small(listBox, tr("いまつながっている端末はありません。", "No devices are connected now.", "Aucun appareil n'est connecté."))
            else -> {
                val exclude = PlugUrls.exclude(prefs)
                for (d in devices.sortedWith(compareBy({ it.state.ordinal }, { it.ip }))) {
                    Ui.spacer(listBox, 6)
                    val name = names[DeviceNames.key(d)]
                    Ui.text(listBox, name ?: tr("名前なし", "No name", "Sans nom"), 15f, if (name == null) R.color.text_secondary else R.color.text_primary, bold = name != null)
                    val state = when (d.state) {
                        Parsers.Neighbor.State.REACHABLE -> tr("接続中", "Connected", "Connecté")
                        Parsers.Neighbor.State.IDLE -> tr("接続中（しばらく通信なし）", "Connected (idle)", "Connecté (inactif)")
                        Parsers.Neighbor.State.GONE -> tr("離れた（応答なし）", "Left (no response)", "Parti (pas de réponse)")
                    }
                    val plug = if (d.ip in exclude) Fmt.paren(tr("スマートプラグ・数えない", "smart plug, not counted", "prise connectée, non comptée")) else ""
                    Ui.small(listBox, "${d.ip} · $state" + macPart(d) + plug)
                    Ui.button(listBox, if (name == null) tr("名前を付ける", "Name it", "Le nommer") else tr("名前を変える", "Rename", "Renommer")) { rename(d, name) }
                }
                Ui.spacer(listBox, 6)
                Ui.small(
                    listBox,
                    if (devices.any { it.mac.isEmpty() }) {
                        // Android 13・14 用の版は、小さな確認で端末を見つけるので MAC アドレスが分からない
                        tr(
                            "Android 13 以降では MAC アドレスを読めないため、名前は IP アドレスで覚えます（端末の IP アドレスが変わると、名前は付け直しになります）。一覧は、テザリングの範囲へ小さな確認を送って作ります。名前はこの端末の中だけに保存し、ログ・CSV・外部への通知・管理画面には出しません。",
                            "Android 13 and later don't let apps read MAC addresses, so names are remembered by IP address (if a device's IP address changes, name it again). The list is built by sending a small probe across the hotspot's address range. Names stay on this phone and never appear in logs, CSV files, external notifications or the web page.",
                            "Android 13 et ultérieur ne permettent pas de lire les adresses MAC : les noms sont mémorisés par adresse IP (si l'adresse IP d'un appareil change, nommez-le à nouveau). La liste est établie en envoyant un petit sondage sur la plage d'adresses du point d'accès. Les noms restent sur ce téléphone et n'apparaissent jamais dans les journaux, les CSV, les notifications externes ou la page web.",
                        )
                    } else {
                        tr(
                            "名前は MAC アドレスで覚えます（同じ端末なら IP アドレスが変わっても同じ名前）。端末側の設定で MAC アドレスが変わると、名前は付け直しになります。名前はこの端末の中だけに保存し、ログ・CSV・外部への通知・管理画面には出しません。",
                            "Names are remembered by MAC address (the same device keeps its name even if its IP address changes). If the device changes its MAC address, name it again. Names stay on this phone and never appear in logs, CSV files, external notifications or the web page.",
                            "Les noms sont mémorisés par adresse MAC (un appareil garde son nom même si son adresse IP change). Si l'appareil change d'adresse MAC, nommez-le à nouveau. Les noms restent sur ce téléphone et n'apparaissent jamais dans les journaux, les CSV, les notifications externes ou la page web.",
                        )
                    },
                )
            }
        }
    }

    private fun macTail(mac: String): String = mac.split(':').takeLast(2).joinToString(":")

    /** 「 · MAC 末尾 xx:xx」（MAC アドレスが分からないときは何も出さない） */
    private fun macPart(d: Parsers.Neighbor): String =
        if (d.mac.isEmpty()) "" else " · " + tr("MAC 末尾 ${macTail(d.mac)}", "MAC …${macTail(d.mac)}", "MAC …${macTail(d.mac)}")

    private fun rename(d: Parsers.Neighbor, current: String?) {
        val edit = EditText(this).apply {
            setText(current.orEmpty())
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(DeviceNames.MAX_LENGTH))
            hint = tr("例: 自宅の PC", "e.g. Home PC", "ex. PC de la maison")
        }
        val b = AlertDialog.Builder(this)
            .setTitle(tr("端末の名前", "Device name", "Nom de l'appareil"))
            .setMessage(d.ip + macPart(d))
            .setView(edit)
            .setPositiveButton(tr("保存", "Save", "Enregistrer")) { _, _ ->
                DeviceNames.set(this, DeviceNames.key(d), edit.text.toString())
                listKey = ""
                tick()
            }
            .setNegativeButton(Ui.cancel(), null)
        if (current != null) {
            b.setNeutralButton(tr("名前を消す", "Remove name", "Supprimer le nom")) { _, _ ->
                DeviceNames.set(this, DeviceNames.key(d), "")
                listKey = ""
                tick()
            }
        }
        b.show()
    }
}

/** 接続端末に付けた名前（MAC アドレスごと。この端末の中だけに保存し、設定のファイル・ログ・外部への送信には含めない） */
object DeviceNames {
    const val MAX_LENGTH = 30
    private const val FILE = "device_names"

    /** 名前を覚える鍵。ふつうは MAC アドレス。MAC アドレスが分からない（Android 13・14 用の版）ときは IP アドレス */
    fun key(n: Parsers.Neighbor): String = n.mac.ifEmpty { "ip:" + n.ip }

    fun all(ctx: Context): Map<String, String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** 空なら名前を消す */
    fun set(ctx: Context, mac: String, name: String) {
        val n = name.trim().take(MAX_LENGTH)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply { if (n.isEmpty()) remove(mac) else putString(mac, n) }.apply()
    }
}
