package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** テザリングの連続稼働時間と接続端末の一覧（v0.9.0） */
class ApSessionTest {
    private val boot = 1_000_000L
    private val none = ApSession.Stored(0L, false, 0L, 0L, 0L)

    @Test
    fun countsFromTheMomentItCameOn() {
        val off = ApSession.next(none, prev = true, on = false, now = boot + 100, bootWall = boot, bootOnAt = null)
        assertEquals(boot + 100, off.offAt)
        val on = ApSession.next(off, prev = false, on = true, now = boot + 400, bootWall = boot, bootOnAt = null)
        assertEquals(boot + 400, on.onSince)
        assertTrue(on.exact)
        assertEquals("前回の停止", boot + 100, on.lastOffStart)
        assertEquals(300L, on.lastOffMs)
        assertEquals(0L, on.offAt)
        assertEquals("同じ状態なら変えない", on, ApSession.next(on, prev = true, on = true, now = boot + 900, bootWall = boot, bootOnAt = null))
    }

    @Test
    fun keepsCountingAcrossAppRestarts() {
        val s = none.copy(onSince = boot + 50, exact = true)
        assertEquals("この起動の記録なら続ける", s, ApSession.next(s, prev = null, on = true, now = boot + 999, bootWall = boot, bootOnAt = null))
        val stale = none.copy(onSince = boot - 10, exact = true)
        val fromBoot = ApSession.next(stale, prev = null, on = true, now = boot + 999, bootWall = boot, bootOnAt = boot + 30)
        assertEquals("起動時にこのアプリが ON にした時刻", boot + 30, fromBoot.onSince)
        assertTrue(fromBoot.exact)
        val unknown = ApSession.next(stale, prev = null, on = true, now = boot + 999, bootWall = boot, bootOnAt = null)
        assertEquals("分からなければ、いまより前から", boot + 999, unknown.onSince)
        assertFalse(unknown.exact)
    }

    @Test
    fun offTimeIsKnownOnlyWhenSeenInThisBoot() {
        assertEquals(boot + 70, ApSession.next(none.copy(offAt = boot + 70), prev = null, on = false, now = boot + 999, bootWall = boot, bootOnAt = null).offAt)
        assertEquals("前の起動の記録は使わない", 0L, ApSession.next(none.copy(offAt = boot - 5), prev = null, on = false, now = boot + 999, bootWall = boot, bootOnAt = null).offAt)
        assertEquals(0L, ApSession.next(none.copy(onSince = boot + 1), prev = null, on = false, now = boot + 999, bootWall = boot, bootOnAt = null).onSince)
    }

    private fun row(start: String, end: String, tether: Boolean?) = PowerRow(
        start, end, 0, false, null, null, null, null, null, null, null, null, 0, tether, null, null, null, null, null, "", null, null,
    )

    @Test
    fun onTimeWithinTheWindow() {
        val t = mapOf("A" to 0L, "B" to 100L, "C" to 300L, "D" to 400L)
        val rows = listOf(row("A", "B", true), row("B", "C", false), row("C", "D", true))
        assertEquals("すべて窓の中", 200L to 400L, ApSession.onTime(rows, now = 400L, windowMs = 1_000L) { t[it] })
        assertEquals("窓の始まりで切る", 150L to 350L, ApSession.onTime(rows, now = 400L, windowMs = 350L) { t[it] })
        assertEquals("読めない時刻は飛ばす", 0L to 0L, ApSession.onTime(listOf(row("X", "Y", true)), now = 400L, windowMs = 1_000L) { t[it] })
    }

    @Test
    fun neighborsAreGroupedByMac() {
        val out = """
            192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE
            fe80::1 dev ap0 lladdr aa:bb:cc:dd:ee:01 REACHABLE
            192.168.43.60 dev ap0 lladdr aa:bb:cc:dd:ee:02 FAILED
            192.168.43.61 dev ap0  INCOMPLETE
            10.0.0.2 dev ccmni0 lladdr 11:22:33:44:55:66 REACHABLE
        """.trimIndent()
        val list = Parsers.neighbors(out, "ap0")
        assertEquals(2, list.size)
        assertEquals("表示は IPv4・状態は良いほう", Parsers.Neighbor("192.168.43.57", "aa:bb:cc:dd:ee:01", Parsers.Neighbor.State.REACHABLE), list[0])
        assertEquals(Parsers.Neighbor.State.GONE, list[1].state)
        val arp = listOf(
            "IP address       HW type     Flags       HW address            Mask     Device",
            "192.168.43.57    0x1         0x2         AA:BB:CC:DD:EE:01     *        ap0",
            "192.168.43.58    0x1         0x0         aa:bb:cc:dd:ee:03     *        ap0",
        )
        val a = Parsers.arpNeighbors(arp, "ap0")
        assertEquals(listOf(Parsers.Neighbor.State.REACHABLE, Parsers.Neighbor.State.GONE), a.map { it.state })
        assertEquals("aa:bb:cc:dd:ee:01", a[0].mac)
    }
}
