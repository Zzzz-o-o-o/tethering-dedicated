package local.tethering.dedicated

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.net.InetAddress

/**
 * 近隣テーブルに残った、切断した端末のエントリを数えないための確かめ（v0.10.1。issue #1。近隣テーブルを読める Android 10〜12 用の版）。
 * Linux は、エントリの数が gc_thresh1（ふつう 128）未満のあいだ STALE のエントリを消さない。切断した端末あてに何も送らなければ
 * FAILED にもならず、テザリングを止めるまで何時間でも残る（つながっていても通信のない端末も、同じ STALE になる）。
 * そこで、最近の確認がない端末の IPv4 のエントリに 1 バイトの UDP を送る。カーネルはその端末に ARP で問い合わせ
 * （約 5 秒後から 1 秒おきに 3 回）、答えれば REACHABLE、答えなければ FAILED（MAC アドレスのない行）にする。
 * 終わってから読み直し、最近の確認がある端末だけを数える。確かめられなかった端末は、これまでどおり数える（多めに数えるほうが安全）。
 * 送る先の決め方・終わりの判定・数え方は Android に依存しない（単体テストで確かめる）
 */
object NeighborCheck {
    /** /proc/net/arp の解決済みのエントリ（STALE と REACHABLE の区別がない） */
    const val VALID = "VALID"

    /** 送ってから読み直す間隔 */
    const val POLL_MS = 1_000L

    /**
     * 送ってから待つ時間の既定（Linux の既定値では、5 秒待ってから 1 秒おきに 3 回問い合わせ、約 8 秒で終わる。余裕を足す）。
     * 待つのは終わるまで（/proc/net/arp は終わりが分からないので、いつもこの時間まで）
     */
    const val DEFAULT_WAIT_MS = 10_000L

    /** 待つ時間の上限（問い合わせの設定がとても大きい端末でも、チェックを長く止めない。問い合わせ中なら [waitMs] を過ぎてもここまで待つ） */
    const val LIMIT_WAIT_MS = 30_000L

    /** 送る先のポート（discard。受けた端末は何もしないか、閉じていると返すだけ） */
    private const val PORT = 9

    /** 最近の確認がある（そのまま数える） */
    private val CONFIRMED = setOf("REACHABLE", "PERMANENT", "NOARP")

    /** カーネルが問い合わせている途中 */
    private val PROBING = setOf("DELAY", "PROBE")

    /** 最近の確認がない（確かめる） */
    private val UNCONFIRMED = setOf("STALE", "DELAY", "PROBE", VALID)

    private val ONE = byteArrayOf(0)

    /**
     * 確かめる IPv4 アドレス。最近の確認がない端末のものだけ（同じ MAC アドレスのどれかのエントリに確認があれば、数えるので送らない）。
     * [exclude] の IP アドレス（数えないスマートプラグなど）には送らない
     */
    fun targets(entries: List<Parsers.Entry>, exclude: Set<String> = emptySet()): List<String> {
        val confirmed = entries.filter { it.state in CONFIRMED && it.ip !in exclude }.map { it.mac }.toSet()
        return entries.filter { it.state in UNCONFIRMED && it.mac !in confirmed && it.ip !in exclude && !it.ip.contains(':') }
            .map { it.ip }.distinct()
    }

    /** 確かめ終わったか（送ったアドレスがどれも、答えて REACHABLE になったか、答えずに MAC アドレスのない行になった・消えた） */
    fun settled(entries: List<Parsers.Entry>, sent: Collection<String>): Boolean {
        val pending = entries.filter { it.state in UNCONFIRMED }.map { it.ip }.toSet()
        return sent.none { it in pending }
    }

    /**
     * カーネルがまだ問い合わせている途中か（送ったアドレスのどれかが DELAY・PROBE）。設定を読めずに [waitMs] の既定で打ち切ると、
     * 問い合わせが長めの端末では終わる前になるので、その間は待ち続ける（STALE のまま・/proc/net/arp は待っても変わらないので待たない）
     */
    fun probing(entries: List<Parsers.Entry>, sent: Collection<String>): Boolean {
        val busy = entries.filter { it.state in PROBING }.map { it.ip }.toSet()
        return sent.any { it in busy }
    }

    /**
     * 数える。[before] は送る前、[after] は確かめたあとに読んだもの（送らなかったときは同じもの）。[sent] は送ったアドレス、
     * [failed] は送れなかったアドレス。MAC アドレスごとに、次のどれかなら数える（[exclude] の IP アドレスのエントリは見ない）。
     * 送る前か確かめたあとに最近の確認がある・確かめたあとも問い合わせの途中（待つ上限を過ぎた）か /proc/net/arp に残っている・
     * 送ったのに STALE のまま（送りが効かなかった）・送れなかった
     */
    fun count(
        before: List<Parsers.Entry>,
        after: List<Parsers.Entry>,
        sent: Set<String>,
        failed: Set<String>,
        exclude: Set<String> = emptySet(),
    ): Int {
        val macs = HashSet<String>()
        for (e in before) {
            if (e.ip in exclude) continue
            if (e.state in CONFIRMED || e.ip in failed) macs.add(e.mac)
        }
        for (e in after) {
            if (e.ip in exclude) continue
            if (e.state in CONFIRMED || e.state in PROBING || e.state == VALID || (e.state == "STALE" && e.ip in sent)) macs.add(e.mac)
        }
        return macs.size
    }

    /**
     * 問い合わせが終わるまで待つ時間。[delaySec] 秒待ってから、[retransMs] おきに [probes] 回問い合わせる（Linux の
     * delay_first_probe_time・retrans_time_ms と、ucast_solicit・app_solicit・mcast_resolicit の合計）。1 秒の余裕を足す
     */
    fun waitMs(delaySec: Int, probes: Int, retransMs: Int): Long =
        (delaySec * 1_000L + probes * retransMs.toLong() + 1_000L).coerceIn(2_000L, LIMIT_WAIT_MS)

    /**
     * [iface] の問い合わせの設定（/proc/sys/net/ipv4/neigh/<名前>/）から待つ時間を決める。読めなければ [DEFAULT_WAIT_MS]。
     * Android は Wi-Fi の接続先（クライアント）側のインターフェースの問い合わせを長めにする（10 回・約 0.75 秒おき）ことがある
     */
    fun waitMs(iface: String?): Long {
        val name = iface?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: return DEFAULT_WAIT_MS
        fun read(key: String): Int? = runCatching { File("/proc/sys/net/ipv4/neigh/$name/$key").readText().trim().toInt() }.getOrNull()
        val delay = read("delay_first_probe_time") ?: return DEFAULT_WAIT_MS
        val ucast = read("ucast_solicit") ?: return DEFAULT_WAIT_MS
        val retrans = read("retrans_time_ms") ?: return DEFAULT_WAIT_MS
        return waitMs(delay, ucast + (read("app_solicit") ?: 0) + (read("mcast_resolicit") ?: 0), retrans)
    }

    /** [ips] へ 1 バイトの UDP を 1 つずつ送る（カーネルに確かめさせるため。返事は読まない）。送れなかったアドレスを返す。UI スレッド以外で呼ぶ */
    fun poke(ips: List<String>): Set<String> {
        if (ips.isEmpty()) return emptySet()
        val fd = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC, 0)
        } catch (_: ErrnoException) {
            return ips.toSet()
        }
        val failed = HashSet<String>()
        try {
            for (ip in ips) {
                try {
                    Os.sendto(fd, ONE, 0, ONE.size, 0, InetAddress.getByName(ip), PORT)
                } catch (_: Exception) {
                    // OS の拒否・送る場所がいっぱい など。確かめられないので、数えるほうに残す
                    failed.add(ip)
                }
            }
        } finally {
            runCatching { Os.close(fd) }
        }
        return failed
    }
}
