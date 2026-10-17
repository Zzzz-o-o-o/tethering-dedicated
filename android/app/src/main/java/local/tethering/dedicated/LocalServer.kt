package local.tethering.dedicated

import android.content.Context
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * ローカル管理画面（仕様 F-43）の小さな HTTP サーバー。読み取り専用（GET / HEAD だけ）。
 *
 * テザリングのアクセスポイント（例: ap0）のアドレスだけで待ち受け、同じサブネットからの接続だけに応える。
 * モバイル回線側や他のインターフェースでは待ち受けない。待ち受け自体はスレッドが accept() で眠っているだけなので、
 * 接続がなければ電力を使わない（ポーリングしない）。
 */
class LocalServer(private val ctx: Context, private val render: (String) -> Http.Response) {
    private data class Binding(val iface: String, val addr: Inet4Address, val prefix: Int, val port: Int) {
        val url: String get() = "http://${addr.hostAddress}:$port/"
    }

    private val control = Executors.newSingleThreadExecutor()
    private val handlers = Executors.newFixedThreadPool(2)
    private val slots = Semaphore(4)

    @Volatile
    private var server: ServerSocket? = null
    private var bound: Binding? = null

    /** 設定・テザリングの状態が変わったら呼ぶ（何度呼んでもよい）。結果は [onStatus] に別スレッドから返す */
    fun update(enabled: Boolean, apIface: String?, port: Int, onStatus: (WebStatus) -> Unit) {
        runCatching {
            control.execute {
                val st = runCatching { apply(enabled, apIface, port) }.getOrElse { WebStatus(null, "開始できません（${it.javaClass.simpleName}）") }
                onStatus(st)
            }
        }
    }

    fun stop() {
        runCatching {
            control.execute { closeServer() }
            control.shutdown()
        }
        handlers.shutdownNow()
    }

    private fun apply(enabled: Boolean, apIface: String?, port: Int): WebStatus {
        if (!enabled) {
            closeServer()
            return WebStatus(null, "OFF（設定）")
        }
        if (apIface == null) {
            closeServer()
            return WebStatus(null, "テザリング ON のときに待ち受けます")
        }
        val (addr, prefix) = ipv4(apIface) ?: run {
            closeServer()
            return WebStatus(null, "アクセスポイントのアドレスを待っています", retry = true)
        }
        val want = Binding(apIface, addr, prefix, port)
        if (want == bound && server?.isClosed == false) return WebStatus(want.url, "待ち受け中")
        closeServer()
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(addr, port), 8)
        } catch (e: IOException) {
            runCatching { ss.close() }
            return WebStatus(null, "開始できません（ポート $port が使用中など）")
        }
        server = ss
        bound = want
        Thread({ acceptLoop(ss, want) }, "local-web").apply {
            isDaemon = true
            start()
        }
        return WebStatus(want.url, "待ち受け中")
    }

    private fun closeServer() {
        runCatching { server?.close() }
        server = null
        bound = null
    }

    private fun acceptLoop(ss: ServerSocket, b: Binding) {
        while (!ss.isClosed) {
            val s = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            if (!inSubnet(s.inetAddress, b) || !slots.tryAcquire()) {
                runCatching { s.close() }
                continue
            }
            try {
                handlers.execute {
                    try {
                        serve(s)
                    } finally {
                        slots.release()
                    }
                }
            } catch (_: Exception) {
                slots.release()
                runCatching { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        // 応答を返し終わるまで CPU が眠らないように（必ずタイムアウト付き）
        val wl = WakeLocks.acquire(ctx, "web", 10_000L)
        try {
            s.use { sock ->
                sock.soTimeout = 5_000
                val head = Http.readHead(sock.getInputStream())
                val req = Http.parseRequestLine(head?.lineSequence()?.firstOrNull())
                val resp = when {
                    req == null -> Http.text(400, "Bad Request")
                    req.method != "GET" && req.method != "HEAD" -> Http.text(405, "読み取り専用です（GET だけ受け付けます）")
                    else -> runCatching { render(req.path) }.getOrElse { Http.text(500, "内部エラー") }
                }
                val out = BufferedOutputStream(sock.getOutputStream())
                out.write(Http.headerBytes(resp))
                if (req?.method != "HEAD") out.write(resp.body)
                out.flush()
            }
        } catch (_: Exception) {
            // 途中で切断されたなど。管理画面の不具合で監視を止めない
        } finally {
            WakeLocks.release(wl)
        }
    }

    companion object {
        /** インターフェースの IPv4 アドレスとプレフィックス長（無い・停止中なら null） */
        fun ipv4(iface: String): Pair<Inet4Address, Int>? {
            val ni = runCatching { NetworkInterface.getByName(iface) }.getOrNull() ?: return null
            if (runCatching { ni.isUp }.getOrDefault(false) != true) return null
            return ni.interfaceAddresses.firstNotNullOfOrNull { ia ->
                (ia.address as? Inet4Address)?.let { it to ia.networkPrefixLength.toInt() }
            }
        }

        private fun inSubnet(remote: InetAddress?, b: Binding): Boolean =
            remote is Inet4Address && Http.sameSubnet(remote.address, b.addr.address, b.prefix)
    }
}

/** 管理画面に出すデータを集める（リクエストのたびに、記録から読む）。サーバーのスレッドから呼ぶ */
object WebData {
    fun collect(ctx: Context): WebPages.Data {
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))
        val power = PowerStore.rowsOfDay(ctx, day)
        val logRows = LogStore.rowsOfDay(ctx, day)
        val prefs = Prefs(ctx)
        return WebPages.Data(
            now = now,
            version = BuildConfig.VERSION_NAME,
            state = StateStore.state,
            today = DailyReports.of(ctx, day, power, logRows),
            traffic = TrafficMath.day(power, day, java.util.TimeZone.getDefault()),
            log = logRows,
            quiet = QuietHours.parse(prefs.text(Defs.QUIET_HOURS)),
            baseline = PlaceStore.baseline(prefs),
        )
    }
}

/** HTTP の最小限の処理（Android に依存しない） */
object Http {
    data class Request(val method: String, val path: String)

    class Response(val status: Int, val contentType: String, val body: ByteArray)

    private const val MAX_HEAD = 8 * 1024

    fun html(status: Int, body: String) = Response(status, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    fun text(status: Int, body: String) = Response(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    fun json(body: String) = Response(200, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    /** リクエストの先頭（空行まで）を読む。長すぎる・途中で切れたら null */
    fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || (last4 and 0xFFFF) == 0x0A0A) return buf.toString("ISO-8859-1")
        }
        return null
    }

    /** 「GET /path?x HTTP/1.1」を分解する。形式が違えば null */
    fun parseRequestLine(line: String?): Request? {
        val parts = line?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val method = parts[0]
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) return null
        val target = parts[1]
        if (!target.startsWith("/")) return null
        return Request(method, target.substringBefore('?').substringBefore('#'))
    }

    fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Internal Server Error"
    }

    fun headerBytes(r: Response): ByteArray = buildString {
        append("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
        append("Content-Type: ${r.contentType}\r\n")
        append("Content-Length: ${r.body.size}\r\n")
        append("Cache-Control: no-store\r\n")
        append("X-Content-Type-Options: nosniff\r\n")
        append("X-Frame-Options: DENY\r\n")
        append("Referrer-Policy: no-referrer\r\n")
        // スクリプトは使わない（表示だけ）。外部への読み込みもしない
        append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; frame-ancestors 'none'\r\n")
        if (r.status == 405) append("Allow: GET, HEAD\r\n")
        append("Connection: close\r\n\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    /** [a] と [b] が同じサブネット（先頭 [prefix] ビットが同じ）か */
    fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        if (a.size != b.size || prefix !in 0..a.size * 8) return false
        var bits = prefix
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
