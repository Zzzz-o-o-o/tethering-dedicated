package local.tethering.dedicated

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.InterruptedIOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * カーネルの近隣テーブル（ARP・NDP）を netlink の RTM_GETNEIGH で読む（v0.10.0 の Android 11 用の版から）。
 * Android 11 で targetSdk 30 のアプリは、netlink ソケットの bind が禁止され（b/155595000）、`ip neigh` が使えない。
 * 読むこと（nlmsg_read）は禁止されていないので、bind せずに問い合わせる（送ったときにカーネルが自動で番号を割り当てる）。
 * 結果は `ip neigh show` と同じ形の行にして、Parsers の数え方・一覧をそのまま使う。解析の部分は Android に依存しない
 */
object Netlink {
    private const val NLMSG_HDRLEN = 16
    private const val NDMSG_LEN = 12
    private const val RTA_HDRLEN = 4
    private const val NLMSG_ERROR = 2
    private const val NLMSG_DONE = 3
    private const val RTM_NEWNEIGH = 28
    private const val RTM_GETNEIGH = 30
    private const val NLM_F_REQUEST = 0x01
    private const val NLM_F_DUMP = 0x300
    private const val NDA_DST = 1
    private const val NDA_LLADDR = 2
    private const val SEQ = 1

    /** ndm_state のビットと、ip neigh の表記（複数立つことはまずないが、先に並べたものを使う） */
    private val STATES = listOf(
        0x02 to "REACHABLE", 0x04 to "STALE", 0x08 to "DELAY", 0x10 to "PROBE", 0x20 to "FAILED", 0x01 to "INCOMPLETE",
    )

    /**
     * `ip neigh show` が既定で出す状態（上の 6 つ）。NOARP・PERMANENT（マルチキャストや自分自身など。接続端末ではない）と、
     * 状態なしは出さない（ip neigh と同じ数え方にするため）
     */
    private const val SHOWN_STATES = 0x3f

    /** 近隣テーブルを `ip neigh show` の形の行で返す。読めなければ null。UI スレッド以外で呼ぶ */
    fun neighbors(timeoutMs: Long = 2_000L): String? {
        val fd = try {
            Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_DGRAM or OsConstants.SOCK_CLOEXEC, OsConstants.NETLINK_ROUTE)
        } catch (_: ErrnoException) {
            return null
        }
        try {
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(timeoutMs))
            val req = neighRequest(SEQ, ByteOrder.nativeOrder())
            Os.write(fd, req, 0, req.size)
            val out = StringBuilder()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) return null
                val chunk = parse(buf, n, ByteOrder.nativeOrder()) { i -> runCatching { Os.if_indextoname(i) }.getOrNull() }
                for (line in chunk.lines) out.append(line).append('\n')
                if (chunk.error) return null
                if (chunk.done) return out.toString()
            }
        } catch (_: ErrnoException) {
            // OS に拒否された・時間切れ
            return null
        } catch (_: InterruptedIOException) {
            return null
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    /** RTM_GETNEIGH の一覧の依頼（nlmsghdr + ndmsg。すべてのアドレスファミリ・すべてのインターフェース） */
    fun neighRequest(seq: Int, order: ByteOrder): ByteArray {
        val len = NLMSG_HDRLEN + NDMSG_LEN
        return ByteBuffer.allocate(len).order(order)
            .putInt(len).putShort(RTM_GETNEIGH.toShort()).putShort((NLM_F_REQUEST or NLM_F_DUMP).toShort()).putInt(seq).putInt(0)
            .put(0).put(0).putShort(0).putInt(0).putShort(0).put(0).put(0)
            .array()
    }

    /** 1 回の受信の結果。[done] は一覧の終わり（NLMSG_DONE）まで読んだ、[error] は OS がエラーを返した */
    class Chunk(val lines: List<String>, val done: Boolean, val error: Boolean)

    /** 受け取った 1 回分（いくつかのメッセージ）を読む。[ifName] はインターフェースの番号から名前を返す */
    fun parse(buf: ByteArray, len: Int, order: ByteOrder, ifName: (Int) -> String?): Chunk {
        val bb = ByteBuffer.wrap(buf, 0, len).order(order)
        val lines = ArrayList<String>()
        var off = 0
        while (off + NLMSG_HDRLEN <= len) {
            val msgLen = bb.getInt(off)
            val type = bb.getShort(off + 4).toInt() and 0xffff
            if (msgLen < NLMSG_HDRLEN || off + msgLen > len) break
            when (type) {
                NLMSG_DONE -> return Chunk(lines, done = true, error = false)
                NLMSG_ERROR -> {
                    val err = if (msgLen >= NLMSG_HDRLEN + 4) bb.getInt(off + NLMSG_HDRLEN) else -1
                    if (err != 0) return Chunk(lines, done = true, error = true)
                }
                RTM_NEWNEIGH -> neighLine(bb, off + NLMSG_HDRLEN, off + msgLen, ifName)?.let(lines::add)
            }
            off += align(msgLen)
        }
        return Chunk(lines, done = false, error = false)
    }

    /** 1 件（ndmsg と属性）を `<IP> dev <IF> lladdr <MAC> <状態>` にする（MAC がなければ lladdr を省く。ip neigh と同じ） */
    private fun neighLine(bb: ByteBuffer, start: Int, end: Int, ifName: (Int) -> String?): String? {
        if (start + NDMSG_LEN > end) return null
        val ifindex = bb.getInt(start + 4)
        val state = bb.getShort(start + 8).toInt() and 0xffff
        if (state and SHOWN_STATES == 0) return null
        var dst: String? = null
        var mac: String? = null
        var a = start + NDMSG_LEN
        while (a + RTA_HDRLEN <= end) {
            val rtaLen = bb.getShort(a).toInt() and 0xffff
            val rtaType = bb.getShort(a + 2).toInt() and 0x3fff
            if (rtaLen < RTA_HDRLEN || a + rtaLen > end) break
            val data = ByteArray(rtaLen - RTA_HDRLEN) { i -> bb.get(a + RTA_HDRLEN + i) }
            when (rtaType) {
                NDA_DST -> if (data.size == 4 || data.size == 16) dst = runCatching { InetAddress.getByAddress(data).hostAddress }.getOrNull()
                NDA_LLADDR -> if (data.size == 6) mac = data.joinToString(":") { b -> hex(b.toInt() and 0xff) }
            }
            a += align(rtaLen)
        }
        val ip = dst ?: return null
        val dev = ifName(ifindex) ?: return null
        val st = STATES.first { (bit, _) -> state and bit != 0 }.second
        return if (mac != null) "$ip dev $dev lladdr $mac $st" else "$ip dev $dev $st"
    }

    private fun hex(v: Int): String = "0123456789abcdef".let { d -> "${d[v shr 4]}${d[v and 0xf]}" }

    private fun align(n: Int): Int = (n + 3) and 3.inv()
}
