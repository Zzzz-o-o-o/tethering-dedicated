package local.tethering.dedicated

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * スマートプラグ連携（仕様 F-45）の判断。Android に依存しない。
 * 電池残量が [Config.low]〜[Config.high] に収まるよう給電を ON／OFF し、高温のときは給電を止める（充電上限のない端末の代わり）。
 */
object PlugPolicy {
    /** 目標の理由。保存は [name] で行う（表示は [label]。かっこ書きで使う） */
    enum class Why(private val tx: Tx) {
        LOW(Tx("残量が下限以下", "battery at or below the lower limit", "batterie au seuil bas ou en dessous")),
        HIGH(Tx("残量が上限に達した", "battery reached the upper limit", "batterie au seuil haut")),
        HOT(Tx("電池温度が高い", "battery hot", "batterie chaude")),
        CRIT(Tx("電池温度が非常に高い", "battery very hot", "batterie très chaude")),
        EMERGENCY(Tx("高温だが残量が少ない", "hot but battery low", "chaude mais batterie faible"));

        val label: String get() = tx.get()
    }

    data class Config(
        val low: Int,
        val high: Int,
        /** これ以上で給電を止める（3℃ 下がるまで再開しない） */
        val hotC: Int,
        /** これ以上なら残量に関係なく止める（温度の警告の値） */
        val critC: Int,
        /** 高温でも、この残量以下なら給電する（電池切れでテザリングが止まらないように） */
        val emergencyPct: Int,
    )

    data class Want(val on: Boolean, val why: Why)

    private val HEAT = setOf(Why.HOT, Why.CRIT, Why.EMERGENCY)

    /** 上限は下限より 10% 以上上にする（近すぎると ON／OFF を繰り返すため） */
    fun high(cfg: Config): Int = maxOf(cfg.high, cfg.low + 10).coerceAtMost(100)

    /**
     * 給電の目標。[prev] は今の目標（なければ null）。範囲の中では直前の向きのまま（充電中は上限まで、放電中は下限まで）。
     * 残量が分からなければ今の目標のまま
     */
    fun want(cfg: Config, level: Int?, tempC: Double?, prev: Want?): Want? {
        if (level == null) return prev
        val crit = maxOf(cfg.critC, cfg.hotC)
        val wasHot = prev != null && prev.why in HEAT
        if (tempC != null && tempC >= crit) return Want(false, Why.CRIT)
        val hot = tempC != null && tempC >= if (wasHot) cfg.hotC - 3.0 else cfg.hotC.toDouble()
        if (hot) {
            val charging = prev?.on == true && prev.why == Why.EMERGENCY
            return if (level <= cfg.emergencyPct || (charging && level < cfg.emergencyPct + 5)) Want(true, Why.EMERGENCY) else Want(false, Why.HOT)
        }
        if (level <= cfg.low) return Want(true, Why.LOW)
        if (level >= high(cfg)) return Want(false, Why.HIGH)
        if (prev == null) return null
        // 冷えたあとは、そのときの向きのまま通常の範囲の制御に戻る
        return if (prev.why in HEAT) Want(prev.on, if (prev.on) Why.LOW else Why.HIGH) else prev
    }

    fun encode(w: Want?): String? = w?.let { "${if (it.on) 1 else 0}:${it.why.name}" }

    fun decode(s: String?): Want? {
        val p = s?.split(':') ?: return null
        if (p.size != 2) return null
        val why = Why.entries.firstOrNull { it.name == p[1] } ?: return null
        return when (p[0]) {
            "1" -> Want(true, why)
            "0" -> Want(false, why)
            else -> null
        }
    }
}

/** プラグの種類ごとの操作 URL（仕様 F-45） */
object PlugUrls {
    const val SHELLY2 = "shelly2"
    const val SHELLY1 = "shelly1"
    const val TASMOTA = "tasmota"
    const val CUSTOM = "url"

    /** 種類と表示名（表示の言語で作る） */
    val kinds: List<Pair<String, String>> get() = listOf(
        SHELLY2 to tr("Shelly（第 2 世代以降: Plus・Pro・Gen3 など）", "Shelly (Gen 2 or later: Plus, Pro, Gen3…)", "Shelly (2e génération ou plus : Plus, Pro, Gen3…)"),
        SHELLY1 to tr("Shelly（第 1 世代: Plug S など）", "Shelly (Gen 1: Plug S…)", "Shelly (1re génération : Plug S…)"),
        TASMOTA to "Tasmota",
        CUSTOM to tr(
            "URL を指定（そのほかのプラグ・Home Assistant など）", "Custom URL (other plugs, Home Assistant…)",
            "URL personnalisée (autres prises, Home Assistant…)",
        ),
    )

    fun label(kind: String): String = kinds.firstOrNull { it.first == kind }?.second ?: kind

    /** OFF のあと、プラグ自身のタイマーで ON に戻せる種類 */
    fun supportsFlipback(kind: String) = kind == SHELLY1 || kind == SHELLY2

    class Req(val method: String, val target: WebClient.Target)

    /**
     * ON／OFF の要求。[host] は種類が Shelly・Tasmota のときのアドレス（「ユーザー:パスワード@」付きも可）、
     * [onUrl] / [offUrl] は「URL を指定」のとき（先頭に「POST 」を付けると POST で送る）。
     * [flipbackSec] > 0 なら、OFF にするときにプラグのタイマーで ON に戻す（Shelly のみ）
     */
    fun request(kind: String, host: String, onUrl: String, offUrl: String, on: Boolean, flipbackSec: Int): Req? {
        val h = host.trim().removePrefix("http://").removeSuffix("/")
        val url = when (kind) {
            SHELLY1 -> "http://$h/relay/0?turn=${if (on) "on" else "off"}" + if (!on && flipbackSec > 0) "&timer=$flipbackSec" else ""
            SHELLY2 -> "http://$h/rpc/Switch.Set?id=0&on=$on" + if (!on && flipbackSec > 0) "&toggle_after=$flipbackSec" else ""
            TASMOTA -> "http://$h/cm?cmnd=Power%20${if (on) "On" else "Off"}"
            CUSTOM -> return spec(if (on) onUrl else offUrl)
            else -> return null
        }
        if (h.isEmpty()) return null
        return WebClient.parse(url)?.let { Req("GET", it) }
    }

    /** 「URL」または「POST URL」 */
    fun spec(s: String): Req? {
        val t = s.trim()
        val (method, url) = when {
            t.startsWith("POST ", ignoreCase = true) -> "POST" to t.substring(5)
            t.startsWith("GET ", ignoreCase = true) -> "GET" to t.substring(4)
            else -> "GET" to t
        }
        return WebClient.parse(url)?.let { Req(method, it) }
    }

    fun checkSpec(s: String): String? =
        if (s.isBlank() || spec(s) != null) {
            null
        } else {
            tr(
                "http:// または https:// で始まる URL を入れてください（POST で送るときは先頭に「POST 」）",
                "Enter a URL starting with http:// or https:// (start with “POST ” to send it as POST)",
                "Saisissez une URL commençant par http:// ou https:// (commencez par « POST » pour un envoi en POST)",
            )
        }

    fun checkHost(s: String): String? {
        val h = s.trim().removePrefix("http://").removeSuffix("/")
        return if (h.isEmpty() || (!h.contains('/') && !h.contains(' ') && WebClient.parse("http://$h/") != null)) {
            null
        } else {
            tr(
                "IP アドレスかホスト名だけを入れてください（例: 192.168.43.50）", "Enter only an IP address or host name (e.g. 192.168.43.50)",
                "Saisissez seulement une adresse IP ou un nom d'hôte (ex. 192.168.43.50)",
            )
        }
    }

    fun checkKind(s: String): String? = if (kinds.any { it.first == s }) null else tr("種類を選んでください", "Choose a type", "Choisissez un type")

    /** 接続端末の数から除くプラグの IP アドレス（IPv4 で書かれているときだけ） */
    fun ipv4(kind: String, host: String, onUrl: String): String? {
        val h = (if (kind == CUSTOM) spec(onUrl)?.target?.host else WebClient.parse("http://" + host.trim().removePrefix("http://").removeSuffix("/") + "/")?.host)
            ?: return null
        return h.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) }
    }

    /** このテザリングにつないだスマートプラグは、接続端末として数えない（連携を使うときだけ） */
    fun exclude(prefs: Prefs): Set<String> =
        if (!prefs.bool(Defs.PLUG)) emptySet()
        else setOfNotNull(ipv4(prefs.text(Defs.PLUG_KIND), prefs.text(Defs.PLUG_HOST), prefs.text(Defs.PLUG_ON_URL)))
}

/**
 * スマートプラグの制御（仕様 F-45）。メインスレッドから呼ぶ（通信は専用のスレッド）。
 * 目標（ON／OFF）と、端末から見た給電の有無が違えば指示を送り、2 分たっても変わらなければ送り直す（3 回まで。その後は 30 分ごと）。
 * 送り直しても直らないときは [onEvent] で知らせる。
 */
class PlugSwitch(private val prefs: Prefs) {
    companion object {
        private const val RESEND_MS = 2 * 60_000L
        private const val GIVE_UP_RETRY_MS = 30 * 60_000L
        private const val MAX_ATTEMPTS = 3
    }

    enum class Level {
        /** 記録だけ */
        INFO,

        /** 利用者に知らせる（給電の状態が目標と合わない） */
        ALERT,

        /** 知らせた問題が直った */
        CLEAR,
    }

    class Event(val text: String, val detail: String?, val level: Level)

    var onEvent: ((Event) -> Unit)? = null

    /** 指示の結果が出た（画面の「直近」を更新するため） */
    var onChanged: (() -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var inFlight = false
    private var lastSentEl = 0L

    /** 最後に送った指示（ON = true）。目標が変わったら、送り直しの間隔を待たずにすぐ送る */
    private var lastSentOn: Boolean? = null
    private var attempts = 0
    private var alerted = false

    var want: PlugPolicy.Want? = PlugPolicy.decode(prefs.plugWant)
        private set

    /** 直近の指示と結果（画面用） */
    var lastResult: String? = null
        private set

    /** 送り直しても給電の状態が目標と合わない */
    val stuck: Boolean get() = alerted

    fun enabled(): Boolean = prefs.bool(Defs.PLUG)

    private fun config() = PlugPolicy.Config(
        prefs.int(Defs.PLUG_LOW), prefs.int(Defs.PLUG_HIGH), prefs.int(Defs.PLUG_HOT), prefs.int(Defs.TEMP_CRIT), prefs.int(Defs.BATT_LOW),
    )

    fun evaluate(p: PowerInfo?, tetherOn: Boolean?) {
        if (closed || !enabled() || p == null) return
        val w = PlugPolicy.want(config(), p.levelPct, p.tempC, want)
        if (w != want) {
            val prev = want
            want = w
            prefs.plugWant = PlugPolicy.encode(w)
            attempts = 0
            if (w != null && (prev == null || prev.on != w.on)) {
                val onOff = if (w.on) "ON" else "OFF"
                val why = w.why.label
                val lvl = Fmt.pct(p.levelPct)
                val temp = Fmt.temp(p.tempC)
                onEvent?.invoke(
                    Event(
                        tr("給電を${onOff}にする（$why）", "Charging $onOff ($why)", "Charge $onOff ($why)"),
                        tr("残量 $lvl・電池 $temp", "Battery $lvl, $temp", "Batterie $lvl, $temp"), Level.INFO,
                    ),
                )
            }
        }
        val target = w?.on ?: return
        val powered = p.plug.powered ?: return
        val now = SystemClock.elapsedRealtime()
        if (powered == target) {
            attempts = 0
            if (alerted) {
                alerted = false
                val onOff = if (target) "ON" else "OFF"
                onEvent?.invoke(
                    Event(tr("給電の状態が目標に戻りました（$onOff）", "Charging is as intended again ($onOff)", "La charge est de nouveau conforme ($onOff)"), null, Level.CLEAR),
                )
            }
            // 動いている間は、OFF のあと自動で ON に戻るタイマーを延長し続ける
            val flip = prefs.int(Defs.PLUG_FLIPBACK)
            if (!target && flip > 0 && PlugUrls.supportsFlipback(prefs.text(Defs.PLUG_KIND)) && !inFlight && now - lastSentEl >= flip * 60_000L / 2) {
                send(false, tetherOn, rearm = true)
            }
            return
        }
        // 送っている途中か、同じ指示を送ったばかり（給電の変化を待つ）なら待つ
        if (inFlight || (lastSentOn == target && now - lastSentEl < RESEND_MS)) return
        if (attempts >= MAX_ATTEMPTS) {
            if (!alerted) {
                alerted = true
                val last = lastResult ?: "－"
                onEvent?.invoke(
                    Event(
                        if (target) {
                            tr("スマートプラグで給電を始められません", "Can't start charging with the smart plug", "Impossible de démarrer la charge avec la prise connectée")
                        } else {
                            tr("スマートプラグで給電を止められません", "Can't stop charging with the smart plug", "Impossible d'arrêter la charge avec la prise connectée")
                        },
                        tr(
                            "${MAX_ATTEMPTS}回送っても給電の状態が変わりません（$last）。プラグの電源・ネットワーク・ケーブルを確認してください",
                            "Charging didn't change after $MAX_ATTEMPTS commands ($last). Check the plug's power, its network and the cable",
                            "La charge n'a pas changé après $MAX_ATTEMPTS commandes ($last). Vérifiez l'alimentation de la prise, son réseau et le câble",
                        ),
                        Level.ALERT,
                    ),
                )
            }
            if (now - lastSentEl < GIVE_UP_RETRY_MS) return
        }
        send(target, tetherOn, rearm = false)
    }

    private fun send(on: Boolean, tetherOn: Boolean?, rearm: Boolean) {
        val kind = prefs.text(Defs.PLUG_KIND)
        val req = PlugUrls.request(kind, prefs.text(Defs.PLUG_HOST), prefs.text(Defs.PLUG_ON_URL), prefs.text(Defs.PLUG_OFF_URL), on, flipSec(kind))
        if (req == null) {
            lastResult = tr("プラグのアドレス・URL が未設定", "Plug address or URL not set", "Adresse ou URL de la prise non définie")
            return
        }
        inFlight = true
        lastSentEl = SystemClock.elapsedRealtime()
        lastSentOn = on
        io.execute {
            val r = WebClient.send(req.method, req.target, emptyList(), null, null, 10_000)
            main.post {
                inFlight = false
                if (closed) return@post
                val onOff = if (on) "ON" else "OFF"
                val timer = if (rearm) Fmt.paren(tr("タイマー延長", "timer extended", "minuteur prolongé")) else ""
                lastResult = "${Fmt.hm(System.currentTimeMillis())} $onOff$timer: ${r.text}"
                // テザリング OFF の間は、このテザリングにつないだプラグには届かないので数えない
                if (!rearm && !(tetherOn == false && !r.ok)) attempts++
                if (!r.ok && !rearm) {
                    onEvent?.invoke(
                        Event(
                            tr("スマートプラグに送れませんでした（$onOff）", "Couldn't reach the smart plug ($onOff)", "Impossible de joindre la prise connectée ($onOff)"),
                            r.text, Level.INFO,
                        ),
                    )
                }
                onChanged?.invoke()
                // 結果を確かめる（給電の変化は電池の放送で届く。届かなければこの時点で見直す）
                main.postDelayed({ if (!closed) evaluate(StateStore.state.power, StateStore.state.tether?.wifiOn) }, RESEND_MS + 5_000L)
            }
        }
    }

    private fun flipSec(kind: String): Int = if (PlugUrls.supportsFlipback(kind)) prefs.int(Defs.PLUG_FLIPBACK) * 60 else 0

    /**
     * 連携をやめるとき（設定で OFF・監視の停止）。このアプリが給電を止めていたら ON に戻す（電池切れを防ぐ）。
     * 結果は待たない
     */
    fun release(reason: String) {
        val w = want
        want = null
        prefs.plugWant = null
        attempts = 0
        alerted = false
        if (w?.on != false) return
        val kind = prefs.text(Defs.PLUG_KIND)
        val req = PlugUrls.request(kind, prefs.text(Defs.PLUG_HOST), prefs.text(Defs.PLUG_ON_URL), prefs.text(Defs.PLUG_OFF_URL), true, 0) ?: return
        onEvent?.invoke(Event(tr("給電を ON に戻す（$reason）", "Charging back ON ($reason)", "Charge rétablie ($reason)"), null, Level.INFO))
        // 送った指示として覚えておく（連携をまた ON にしたとき、次の指示を待たせないように）
        lastSentOn = true
        lastSentEl = SystemClock.elapsedRealtime()
        io.execute { WebClient.send(req.method, req.target, emptyList(), null, null, 10_000) }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        io.shutdown()
    }
}
