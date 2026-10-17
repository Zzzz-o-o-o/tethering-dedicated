package local.tethering.dedicated

import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors

/**
 * 外部への通知（仕様 F-44）の組み立て。Android に依存しない。
 * 送り先は URL から判断する: Discord・Slack の Webhook、それ以外は ntfy（本文はテキスト、題名などはヘッダー）。
 * 送るのは状態だけで、IP アドレス・SSID・接続端末の情報・電話番号は含めない（ログの方針と同じ）。
 */
object ExtNotify {
    enum class Kind(val label: String) { NTFY("ntfy"), DISCORD("Discord"), SLACK("Slack") }

    enum class Category { RECOVERY, ALERT }

    data class Msg(val at: Long, val title: String, val body: String, val high: Boolean)

    class Dest(val kind: Kind, val target: WebClient.Target) {
        /** 画面・診断に出す名前（トークンを含む URL は出さない） */
        val label: String get() = "${kind.label}（${target.host}）"
    }

    class Request(val url: WebClient.Target, val headers: List<Pair<String, String>>, val contentType: String, val body: String)

    /** 送り先の URL を読む。ntfy はトピック（パス）が必要 */
    fun parse(raw: String): Dest? {
        val t = WebClient.parse(raw) ?: return null
        val host = t.host.lowercase()
        val kind = when {
            (host == "discord.com" || host == "discordapp.com" || host.endsWith(".discord.com")) && t.path.startsWith("/api/webhooks/") -> Kind.DISCORD
            host == "hooks.slack.com" -> Kind.SLACK
            else -> Kind.NTFY
        }
        if (kind == Kind.NTFY && t.path.trim('/').isEmpty()) return null
        return Dest(kind, t)
    }

    fun check(s: String): String? = when {
        s.isBlank() -> null
        WebClient.parse(s) == null -> "http:// または https:// で始まる URL を入れてください"
        parse(s) == null -> "ntfy はトピック名まで入れてください（例: https://ntfy.sh/トピック名）"
        else -> null
    }

    fun build(d: Dest, m: Msg, device: String): Request {
        val title = "[$device] ${m.title}"
        val time = Fmt.dateTime(m.at)
        return when (d.kind) {
            Kind.NTFY -> Request(
                d.target,
                listOf(
                    "Title" to header(title),
                    "Priority" to if (m.high) "4" else "3",
                    "Tags" to if (m.high) "warning" else "information_source",
                ),
                "text/plain; charset=utf-8",
                "${m.body}\n$time",
            )
            // メンションは無効にする（本文はこのアプリが作るが念のため）。Discord の上限は 2000 文字
            Kind.DISCORD -> Request(
                d.target, emptyList(), "application/json",
                Json.obj("content" to "**$title**\n${m.body}\n$time".take(1900), "allowed_mentions" to Json.Raw("{\"parse\":[]}")),
            )
            Kind.SLACK -> Request(d.target, emptyList(), "application/json", Json.obj("text" to "*$title*\n${m.body}\n$time"))
        }
    }

    /** テスト送信（設定画面から。待ち行列を通さない）。UI スレッド以外で呼ぶ */
    fun sendTest(d: Dest, device: String, now: Long): WebClient.Result {
        val req = build(d, Msg(now, "テスト通知", "テザリング専用化からのテスト送信です。", false), device)
        return WebClient.send("POST", req.url, req.headers, req.contentType, req.body.toByteArray(Charsets.UTF_8), 10_000)
    }

    /** HTTP ヘッダーに入れる文字列。ASCII 以外を含むときは RFC 2047 の形にする（ntfy が対応） */
    fun header(s: String): String =
        if (s.all { it.code in 0x20..0x7e }) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

    /**
     * 送れなかった通知の待ち行列（回線が戻ったら古い順に送る）。上限と期限を超えたものは捨てる。
     * 送りすぎを防ぐため、1 時間に [maxPerHour] 件までにする
     */
    class Queue(private val maxItems: Int = 20, private val maxAgeMs: Long = 24 * 3_600_000L, private val maxPerHour: Int = 20) {
        val items = ArrayDeque<Msg>()
        private val sent = ArrayDeque<Long>()

        fun add(m: Msg) {
            items.addLast(m)
            while (items.size > maxItems) items.removeFirst()
        }

        /** 期限切れを捨て、今送ってよい先頭の 1 件を返す（送りすぎのときは null） */
        fun next(now: Long): Msg? {
            while (items.isNotEmpty() && now - items.first().at > maxAgeMs) items.removeFirst()
            while (sent.isNotEmpty() && now - sent.first() >= 3_600_000L) sent.removeFirst()
            if (sent.size >= maxPerHour) return null
            return items.firstOrNull()
        }

        fun done(m: Msg, now: Long) {
            items.remove(m)
            sent.addLast(now)
        }

        fun drop(m: Msg) {
            items.remove(m)
        }

        fun toJson(): String = JSONArray().also { a ->
            items.forEach { a.put(JSONObject().put("at", it.at).put("title", it.title).put("body", it.body).put("high", it.high)) }
        }.toString()

        fun load(json: String?) {
            items.clear()
            val a = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return
            for (i in 0 until a.length()) {
                runCatching {
                    val j = a.getJSONObject(i)
                    items.addLast(Msg(j.getLong("at"), j.getString("title"), j.getString("body"), j.optBoolean("high")))
                }
            }
        }
    }
}

/**
 * 外部への通知の送信（仕様 F-44）。メインスレッドから呼ぶ（通信は専用のスレッドで 1 件ずつ）。
 * 回線が止まっている間は送れないので待ち行列に残し、次のチェックで回線が正常なら送る。
 */
class ExtSender(private val prefs: Prefs) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ExtNotify.Queue().apply { load(prefs.extQueueJson) }
    private var sending = false
    private var closed = false

    /** 直近の送信結果（画面用） */
    var lastResult: String? = null
        private set

    /** 送れなかった件数 */
    val pending: Int get() = queue.items.size

    /** 結果の記録（ログ）。送り先の URL は渡さない */
    var onLog: ((String) -> Unit)? = null

    /** 送信の結果が出た（画面の「直近」「未送信」を更新するため） */
    var onChanged: (() -> Unit)? = null

    private val device: String get() = Build.MODEL ?: "Android"

    fun enabled(cat: ExtNotify.Category): Boolean =
        prefs.bool(Defs.EXT_NOTIFY) && prefs.bool(if (cat == ExtNotify.Category.RECOVERY) Defs.EXT_RECOVERY else Defs.EXT_ALERT) &&
            ExtNotify.parse(prefs.text(Defs.EXT_URL)) != null

    fun post(cat: ExtNotify.Category, title: String, body: String, high: Boolean = cat == ExtNotify.Category.ALERT) {
        if (closed || !enabled(cat)) return
        queue.add(ExtNotify.Msg(System.currentTimeMillis(), title, body, high))
        save()
        flush()
    }

    /** 待っている通知を送る（回線が戻ったときなどに呼ぶ） */
    fun flush() {
        if (closed || sending) return
        val dest = ExtNotify.parse(prefs.text(Defs.EXT_URL))
        if (!prefs.bool(Defs.EXT_NOTIFY) || dest == null) return
        val m = queue.next(System.currentTimeMillis()) ?: return
        sending = true
        val req = ExtNotify.build(dest, m, device)
        io.execute {
            val r = WebClient.send("POST", req.url, req.headers, req.contentType, req.body.toByteArray(Charsets.UTF_8), 10_000)
            main.post {
                sending = false
                if (closed) return@post
                lastResult = "${Fmt.hm(System.currentTimeMillis())} ${r.text}"
                when {
                    r.ok -> {
                        queue.done(m, System.currentTimeMillis())
                        save()
                        flush()
                    }
                    r.permanent -> {
                        // 宛先の誤りなどは送り直しても通らないので捨てる（設定の見直しを促す）
                        queue.drop(m)
                        save()
                        onLog?.invoke("送信先に断られました（${r.text}・${dest.kind.label}）。通知先の URL を確認してください")
                    }
                    // 回線の不調などは残して、次の機会に送り直す
                    else -> onLog?.invoke("送れませんでした（${r.text}）。回線が戻ったら送り直します（${queue.items.size}件）")
                }
                onChanged?.invoke()
            }
        }
    }

    /** 外部への通知を OFF にしたときなど。送れていない通知を捨てる */
    fun clear() {
        if (queue.items.isEmpty()) return
        queue.items.clear()
        save()
    }

    fun close() {
        closed = true
        io.shutdownNow()
    }

    private fun save() {
        prefs.extQueueJson = queue.toJson()
    }
}
