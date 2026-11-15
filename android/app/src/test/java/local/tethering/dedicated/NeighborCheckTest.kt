package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 切断した端末の古いエントリを数えないための確かめ（v0.10.1。issue #1） */
class NeighborCheckTest {
    private fun neigh(vararg lines: String) = Parsers.entries(lines.joinToString("\n"), "ap0")

    private val arpHeader = "IP address       HW type     Flags       HW address            Mask     Device"

    @Test
    fun readsEntriesPerAddress() {
        val e = neigh(
            "192.168.43.57 dev ap0 lladdr AA:BB:CC:DD:EE:01 stale",
            "fe80::1 dev ap0 lladdr aa:bb:cc:dd:ee:01 REACHABLE",
            "192.168.43.61 dev ap0  FAILED",
            "10.0.0.1 dev ccmni0 lladdr 11:22:33:44:55:66 REACHABLE",
        )
        assertEquals(
            "MAC のない行（FAILED）とほかのインターフェースは読まない。MAC は小文字・状態は大文字",
            listOf(Parsers.Entry("192.168.43.57", "aa:bb:cc:dd:ee:01", "STALE"), Parsers.Entry("fe80::1", "aa:bb:cc:dd:ee:01", "REACHABLE")),
            e,
        )
        val a = Parsers.arpEntries(
            listOf(
                arpHeader,
                "192.168.43.57    0x1         0x2         aa:bb:cc:dd:ee:01     *        ap0",
                "192.168.43.58    0x1         0x0         aa:bb:cc:dd:ee:02     *        ap0",
                "192.168.43.59    0x1         0x6         aa:bb:cc:dd:ee:03     *        ap0",
                "192.168.43.60    0x1         0x0         00:00:00:00:00:00     *        ap0",
            ),
            "ap0",
        )
        assertEquals(listOf(NeighborCheck.VALID, "FAILED", "PERMANENT"), a.map { it.state })
    }

    @Test
    fun departedDevicesAreNotCountedAfterTheCheck() {
        // issue #1：6 台と出ていたとき、つながっている端末は 0 台で、REACHABLE のエントリは 1 つもなかった
        val text = listOf(
            "192.168.43.11 dev ap0 lladdr 02:00:00:00:00:11 STALE",
            "192.168.43.12 dev ap0 lladdr 02:00:00:00:00:12 STALE",
            "192.168.43.13 dev ap0 lladdr 02:00:00:00:00:13 STALE",
            "192.168.43.14 dev ap0 lladdr 02:00:00:00:00:14 STALE",
            "192.168.43.15 dev ap0 lladdr 02:00:00:00:00:15 DELAY",
            "192.168.43.16 dev ap0 lladdr 02:00:00:00:00:16 PROBE",
            "fe80::11 dev ap0 lladdr 02:00:00:00:00:11 STALE",
        ).joinToString("\n")
        val before = Parsers.entries(text, "ap0")
        assertEquals("確かめずに数えると 6 台", 6, Parsers.countNeigh(text, "ap0"))
        val targets = NeighborCheck.targets(before)
        assertEquals("IPv4 のエントリにだけ送る", (11..16).map { "192.168.43.$it" }, targets)
        // 答えなかったエントリは FAILED（MAC のない行）になる。IPv6 の STALE は残る
        val after = Parsers.entries(
            (11..16).joinToString("\n") { "192.168.43.$it dev ap0  FAILED" } + "\nfe80::11 dev ap0 lladdr 02:00:00:00:00:11 STALE",
            "ap0",
        )
        assertTrue(NeighborCheck.settled(after, targets))
        assertEquals(0, NeighborCheck.count(before, after, targets.toSet(), emptySet()))
    }

    @Test
    fun idleConnectedDevicesAreCountedOnceTheyAnswer() {
        val before = neigh("192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE")
        val sent = NeighborCheck.targets(before).toSet()
        val during = neigh("192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 DELAY")
        assertFalse("問い合わせの途中は終わっていない", NeighborCheck.settled(during, sent))
        assertEquals("待つ上限を過ぎても問い合わせ中なら数える", 1, NeighborCheck.count(before, during, sent, emptySet()))
        val after = neigh("192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 REACHABLE")
        assertTrue(NeighborCheck.settled(after, sent))
        assertEquals(1, NeighborCheck.count(before, after, sent, emptySet()))
    }

    @Test
    fun recentlyConfirmedDevicesAreNotChecked() {
        // IPv4 は STALE でも、同じ端末の IPv6 に最近の確認があれば、送らずに数える
        val before = neigh(
            "192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE",
            "fe80::1 dev ap0 lladdr aa:bb:cc:dd:ee:01 REACHABLE",
        )
        assertEquals(emptyList<String>(), NeighborCheck.targets(before))
        // 待つあいだに REACHABLE が STALE に戻っても、送る前に確認があったので数える
        val after = neigh(
            "192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE",
            "fe80::1 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE",
        )
        assertEquals(1, NeighborCheck.count(before, after, emptySet(), emptySet()))
    }

    @Test
    fun uncheckedDevicesAreStillCounted() {
        val before = neigh(
            "192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE",
            "192.168.43.58 dev ap0 lladdr aa:bb:cc:dd:ee:02 STALE",
        )
        // .57 は送れなかった。.58 は送ったのに STALE のまま（送りが効かなかった）
        assertEquals("確かめられなかった端末は数える", 2, NeighborCheck.count(before, before, setOf("192.168.43.58"), setOf("192.168.43.57")))
        assertFalse("送ったのに STALE のままなら、まだ終わっていない", NeighborCheck.settled(before, setOf("192.168.43.58")))
    }

    @Test
    fun ipv6OnlyStaleEntriesAreNotCounted() {
        // テザリングの端末は IPv4 を使う。IPv6 だけの古いエントリは、確かめられないので数えない
        val before = neigh("fe80::2 dev ap0 lladdr aa:bb:cc:dd:ee:02 STALE")
        assertEquals(emptyList<String>(), NeighborCheck.targets(before))
        assertEquals(0, NeighborCheck.count(before, before, emptySet(), emptySet()))
    }

    @Test
    fun newDevicesDuringTheCheck() {
        val before = neigh("192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE")
        val sent = setOf("192.168.43.57")
        val after = neigh(
            "192.168.43.57 dev ap0  FAILED",
            "192.168.43.70 dev ap0 lladdr aa:bb:cc:dd:ee:70 REACHABLE",
            "192.168.43.71 dev ap0 lladdr aa:bb:cc:dd:ee:71 STALE",
        )
        // 新しく REACHABLE になった端末は数える。確かめていない STALE は、次のチェックで確かめる
        assertEquals(1, NeighborCheck.count(before, after, sent, emptySet()))
    }

    @Test
    fun excludedAddressesAreNeitherCheckedNorCounted() {
        val before = neigh(
            "192.168.43.50 dev ap0 lladdr aa:aa:aa:aa:aa:01 STALE",
            "192.168.43.57 dev ap0 lladdr aa:aa:aa:aa:aa:02 STALE",
        )
        val exclude = setOf("192.168.43.50")
        assertEquals(listOf("192.168.43.57"), NeighborCheck.targets(before, exclude))
        val after = neigh(
            "192.168.43.50 dev ap0 lladdr aa:aa:aa:aa:aa:01 STALE",
            "192.168.43.57 dev ap0 lladdr aa:aa:aa:aa:aa:02 REACHABLE",
        )
        assertEquals(1, NeighborCheck.count(before, after, setOf("192.168.43.57"), emptySet(), exclude))
    }

    @Test
    fun arpEntriesAreCheckedUntilTheEnd() {
        // /proc/net/arp は STALE と REACHABLE の区別がないので、解決済みのエントリすべてに送り、上限まで待つ
        val before = Parsers.arpEntries(
            listOf(
                arpHeader,
                "192.168.43.57    0x1         0x2         aa:bb:cc:dd:ee:01     *        ap0",
                "192.168.43.58    0x1         0x2         aa:bb:cc:dd:ee:02     *        ap0",
            ),
            "ap0",
        )
        val sent = NeighborCheck.targets(before).toSet()
        assertEquals(setOf("192.168.43.57", "192.168.43.58"), sent)
        assertFalse(NeighborCheck.settled(before, sent))
        val after = Parsers.arpEntries(
            listOf(
                arpHeader,
                "192.168.43.57    0x1         0x2         aa:bb:cc:dd:ee:01     *        ap0",
                "192.168.43.58    0x1         0x0         aa:bb:cc:dd:ee:02     *        ap0",
            ),
            "ap0",
        )
        assertEquals("答えなかった端末（Flags 0x0）は数えない", 1, NeighborCheck.count(before, after, sent, emptySet()))
    }

    @Test
    fun waitsUntilTheProbesEnd() {
        assertEquals("Linux の既定（5 秒待ってから 1 秒おきに 3 回）に 1 秒の余裕", 9_000L, NeighborCheck.waitMs(5, 3, 1_000))
        assertEquals("Android が Wi-Fi の接続先側で長めにした設定（10 回・752 ミリ秒おき）", 13_520L, NeighborCheck.waitMs(5, 10, 752))
        assertEquals("大きすぎる設定でも 30 秒まで", 30_000L, NeighborCheck.waitMs(60, 100, 1_000))
        assertEquals("読めなければ既定", NeighborCheck.DEFAULT_WAIT_MS, NeighborCheck.waitMs(null))
        assertEquals("名前に使えない文字があれば読まない", NeighborCheck.DEFAULT_WAIT_MS, NeighborCheck.waitMs("../x"))
    }

    @Test
    fun keepsWaitingOnlyWhileTheKernelIsProbing() {
        val sent = setOf("192.168.43.57", "192.168.43.58")
        assertTrue("問い合わせ中が残っていれば待ち続ける", NeighborCheck.probing(neigh("192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 PROBE"), sent))
        assertFalse("STALE のまま（送りが効かなかった）は待っても変わらない", NeighborCheck.probing(neigh("192.168.43.58 dev ap0 lladdr aa:bb:cc:dd:ee:02 STALE"), sent))
        assertFalse("送っていないアドレスの問い合わせは待たない", NeighborCheck.probing(neigh("192.168.43.99 dev ap0 lladdr aa:bb:cc:dd:ee:09 DELAY"), sent))
    }

    @Test
    fun listShowsEntriesBeingChecked() {
        val list = Parsers.neighborsOf(
            neigh(
                "192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 DELAY",
                "192.168.43.58 dev ap0 lladdr aa:bb:cc:dd:ee:02 REACHABLE",
            ),
        )
        assertEquals(listOf(Parsers.Neighbor.State.IDLE, Parsers.Neighbor.State.REACHABLE), list.map { it.state })
        // IPv4 が FAILED（MAC のない行）になった端末の IPv6 の古いエントリは、一覧に残さない
        val gone = Parsers.neighborsOf(
            neigh("192.168.43.57 dev ap0  FAILED", "fe80::57 dev ap0 lladdr aa:bb:cc:dd:ee:57 STALE", "fe80::58 dev ap0 lladdr aa:bb:cc:dd:ee:58 REACHABLE"),
        )
        assertEquals("IPv6 でも最近の確認があれば出す", listOf("fe80::58"), gone.map { it.ip })
    }
}
