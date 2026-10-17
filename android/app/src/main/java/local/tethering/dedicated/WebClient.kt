package local.tethering.dedicated

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.Base64
import javax.net.ssl.SSLException

/**
 * 外部への HTTP 送信（スマートプラグ F-45・外部への通知 F-44 で共通）。
 * URL に含めた「ユーザー:パスワード@」は Basic 認証のヘッダーにして、URL からは外す（ログ・画面にも出さない）。
 */
object WebClient {
    class Target(
        /** 認証情報を除いた URL */
        val url: String,
        /** Authorization ヘッダーの値（なければ null） */
        val auth: String?,
        val host: String,
        val path: String,
    )

    /** http / https の URL を読む。形式が違えば null */
    fun parse(raw: String): Target? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val auth = uri.rawUserInfo?.let { ui ->
            val (user, pass) = ui.split(':', limit = 2).let { decode(it[0]) to decode(it.getOrElse(1) { "" }) }
            "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
        }
        val clean = buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
        }
        return Target(clean, auth, host, uri.path.orEmpty())
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    class Result(val code: Int?, val error: String?) {
        val ok: Boolean get() = code != null && code in 200..299

        /** 送り直しても通りそうにない失敗（宛先の誤り・認証の失敗など） */
        val permanent: Boolean get() = code != null && code in 400..499 && code != 408 && code != 429

        val text: String get() = when {
            ok -> "OK（HTTP $code）"
            code != null -> "HTTP $code"
            else -> error ?: "失敗"
        }
    }

    /** UI スレッド以外で呼ぶ。応答の本文は少しだけ読んで捨てる */
    fun send(method: String, t: Target, headers: List<Pair<String, String>>, contentType: String?, body: ByteArray?, timeoutMs: Int): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(t.url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "TetheringDedicated/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Connection", "close")
            t.auth?.let { conn.setRequestProperty("Authorization", it) }
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (method == "POST" || method == "PUT") {
                val bytes = body ?: ByteArray(0)
                conn.doOutput = true
                contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            runCatching { (if (code >= 400) conn.errorStream else conn.inputStream)?.use { s -> s.read(ByteArray(2048)) } }
            Result(code, null)
        } catch (e: Exception) {
            Result(null, reason(e))
        } finally {
            conn?.disconnect()
        }
    }

    fun reason(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "タイムアウト"
        is UnknownHostException -> "名前解決できない"
        is ConnectException -> "接続できない"
        is SSLException -> "TLS エラー"
        is IOException -> "通信エラー（${e.javaClass.simpleName}）"
        else -> e.javaClass.simpleName
    }
}
