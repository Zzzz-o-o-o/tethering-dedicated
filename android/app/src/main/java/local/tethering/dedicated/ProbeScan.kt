package local.tethering.dedicated

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

/**
 * 接続端末を、テザリングの範囲の各アドレスへ小さな UDP を 1 回ずつ送って数える（v0.10.0 の Android 13 以降用の版。利用者の選択）。
 * Android 13 以降で targetSdk 33 以上のアプリは、近隣テーブル（/proc/net/arp・ip neigh・netlink の RTM_GETNEIGH）をどれも読めない。
 * 送った先に端末がいなければ、カーネルは近隣の解決（ARP）に失敗し（ふつう約 3 秒）、自分あてに「到達できない」（ICMP）を出す。
 * これはソケットに IP_RECVERR を付けたときだけ受け取れる（付けないと捨てられ、どのアドレスにも端末がいるように見える。エミュレーターで確認）。
 * 「到達できない」が返らなかったアドレスに端末がいるとみなす（ARP には通信を受け付けない設定の端末も答えるので、そうした端末も数えられる）。
 * 範囲（ふつう /24 の 253 個）へ 1 バイトずつ・約 5 秒（答えない端末があると、もう一度送って約 15 秒。v0.10.1）。
 * MAC アドレスは分からない。UI スレッド以外で呼ぶ
 */
object ProbeScan {
    /** discard（受けても何もしないポート） */
    private const val PORT = 9

    /** Linux の IP_RECVERR（公開の OsConstants にない） */
    private const val IP_RECVERR = 11

    /** 送る間隔。「到達できない」は OS 全体で 1 秒に 1000 個まで（一度には 50 個まで）に抑えられ、超えた分は捨てられるので、それより遅く送る */
    private const val GAP_MS = 3L

    /** 送り終えてから、最初の「到達できない」を待つ上限（ふつうは数秒で来る） */
    private const val FIRST_MS = 8_000L

    /** 「到達できない」が途切れてから、終わりとみなすまでの時間（送った順に、ほぼ送った間隔で返る） */
    private const val QUIET_MS = 1_000L

    /** もう一度送ってから、「到達できない」を待つ上限（近隣の解決の失敗は約 3 秒） */
    private const val SECOND_MS = 5_000L

    /** これより広い範囲は数えない（/24 まで。テザリングはふつう /24 で、広すぎると近隣テーブルがあふれる） */
    private const val MAX_HOSTS = 254

    /** この数以上の範囲で「到達できない」が 1 つも返らなければ、この方法が使えないとみなす（全部のアドレスに端末がいるとは考えにくい） */
    private const val SANE_MIN = 8

    /** 続けて呼ばれたとき（チェックと詳細の画面など）は、この間の結果を使い回す */
    private const val CACHE_MS = 30_000L

    private const val UNKNOWN = 0
    private const val PRESENT = 1
    private const val GONE = 2

    private val ONE = byteArrayOf(0)
    private val lock = Any()

    /** 使い回す結果（インターフェースと自分のアドレス・調べた時刻・結果）。Android 11 からは、テザリングを入れ直すと範囲が変わる */
    private var cached: Triple<String, Long, List<String>>? = null

    /** [iface] のテザリングの範囲を調べ、端末がいたアドレス（IPv4）を返す。調べられなければ null */
    fun scan(iface: String?): List<String>? {
        val name = iface ?: return null
        val (self, prefix) = ipv4Of(name) ?: return null
        return scan(name, self, prefix)
    }

    /** [self]/[prefix] の範囲を調べる（開発用の版は、範囲の長さを変えて試す。エミュレーターの範囲は広すぎるため） */
    fun scan(name: String, self: ByteArray, prefix: Int): List<String>? {
        synchronized(lock) {
            val key = name + "/" + InetAddress.getByAddress(self).hostAddress + "/" + prefix
            cached?.let { (k, at, r) -> if (k == key && SystemClock.elapsedRealtime() - at < CACHE_MS) return r }
            val hosts = Subnet.hosts(self, prefix, MAX_HOSTS) ?: return null
            val present = probe(hosts.map { InetAddress.getByAddress(it) }, NeighborCheck.waitMs(name)) ?: return null
            cached = Triple(key, SystemClock.elapsedRealtime(), present)
            return present
        }
    }

    /** インターフェースの IPv4 アドレスと、範囲の長さ（プレフィックス長） */
    fun ipv4Of(iface: String): Pair<ByteArray, Int>? = runCatching {
        NetworkInterface.getByName(iface)?.interfaceAddresses?.firstOrNull { it.address is Inet4Address }
            ?.let { it.address.address to it.networkPrefixLength.toInt() }
    }.getOrNull()

    /**
     * [recheckMs] は、1 回目に何も返らなかったアドレスへもう一度送るまでの時間（1 回目を送り終えてから。v0.10.1。issue #1）。
     * 近隣テーブルに切断した端末の古い（STALE）エントリがあると、1 回目は古い MAC アドレスあてにそのまま送られ、「到達できない」が返らない。
     * ただし 1 回目でカーネルがその端末に問い合わせを始め、答えがなければ失敗（FAILED）にする（既定の設定で約 8 秒）ので、
     * そのあとに送ると「到達できない」が返る
     */
    private fun probe(hosts: List<InetAddress>, recheckMs: Long): List<String>? {
        val fds = ArrayList<FileDescriptor>(hosts.size)
        val state = IntArray(hosts.size)
        try {
            for ((i, h) in hosts.withIndex()) {
                // 作れない・送れないソケットがあると数え違えるので、そのときは「調べられない」にする（外の catch）
                val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC, 0)
                fds.add(fd)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, IP_RECVERR, 1)
                try {
                    Os.connect(fd, h, PORT)
                    Os.write(fd, ONE, 0, 1)
                } catch (e: ErrnoException) {
                    if (!unreachable(e.errno)) throw e
                    state[i] = GONE
                }
                Thread.sleep(GAP_MS)
            }
            val sent = SystemClock.elapsedRealtime()
            collect(fds, state, hosts.indices.toList(), sent, FIRST_MS)
            if (hosts.size >= SANE_MIN && state.none { it == GONE }) return null
            // 何も届かなかったアドレス（相手がいて答えない端末か、切断した端末の古いエントリ）へ、確かめが終わってからもう一度送る
            val silent = hosts.indices.filter { state[it] == UNKNOWN }
            if (silent.isNotEmpty()) {
                val wait = sent + recheckMs - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                for (i in silent) {
                    try {
                        Os.write(fds[i], ONE, 0, 1)
                    } catch (e: ErrnoException) {
                        state[i] = if (unreachable(e.errno)) GONE else PRESENT
                    }
                    Thread.sleep(GAP_MS)
                }
                collect(fds, state, silent, SystemClock.elapsedRealtime(), SECOND_MS)
            }
            // 何も届かなかったアドレス（相手がいて、答えない）も、相手がいる
            return hosts.indices.filter { state[it] != GONE }.mapNotNull { hosts[it].hostAddress }
        } catch (_: ErrnoException) {
            return null
        } catch (_: InterruptedIOException) {
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            for (fd in fds) runCatching { Os.close(fd) }
        }
    }

    /**
     * [idx] のソケットに届くものを読み、[state] を決める。[start] から [firstMs] まで待ち、「到達できない」が届き始めたら、
     * 途切れてから [QUIET_MS] で終える（送った順に、ほぼ送った間隔で返る）
     */
    private fun collect(fds: List<FileDescriptor>, state: IntArray, idx: List<Int>, start: Long, firstMs: Long) {
        var lastGone = 0L
        val buf = ByteArray(16)
        while (true) {
            val open = idx.filter { state[it] == UNKNOWN }
            val now = SystemClock.elapsedRealtime()
            val until = if (lastGone == 0L) start + firstMs else lastGone + QUIET_MS
            if (open.isEmpty() || now >= until) break
            val polls = Array(open.size) { k -> StructPollfd().apply { fd = fds[open[k]]; events = OsConstants.POLLIN.toShort() } }
            if (Os.poll(polls, (until - now).toInt()) <= 0) continue
            for ((k, p) in polls.withIndex()) {
                if (p.revents.toInt() == 0) continue
                // 届いたもの：受信（相手が答えた）・ECONNREFUSED（相手がいてポートが閉じている）は、相手がいる。
                // EHOSTUNREACH は近隣の解決に失敗した＝相手がいない
                val s = try {
                    Os.read(p.fd, buf, 0, buf.size)
                    PRESENT
                } catch (e: ErrnoException) {
                    if (unreachable(e.errno)) GONE else PRESENT
                }
                state[open[k]] = s
                if (s == GONE) lastGone = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun unreachable(errno: Int) = errno == OsConstants.EHOSTUNREACH || errno == OsConstants.ENETUNREACH
}

/** IPv4 の範囲の計算（Android に依存しない） */
object Subnet {
    /**
     * [self]（4 バイト）/[prefix] の範囲の、ネットワークアドレス・ブロードキャスト・自分を除いたアドレス。
     * 範囲が [max] 個より広い・/30 より狭い・IPv4 でないときは null
     */
    fun hosts(self: ByteArray, prefix: Int, max: Int): List<ByteArray>? {
        if (self.size != 4 || prefix !in 1..30) return null
        val size = 1L shl (32 - prefix)
        if (size - 2 > max) return null
        val ip = ByteBuffer.wrap(self).int
        val net = ip and (-1 shl (32 - prefix))
        return (1 until size).map { net + it.toInt() }.filter { it != ip && it != net + (size - 1).toInt() }
            .map { ByteBuffer.allocate(4).putInt(it).array() }
    }
}
