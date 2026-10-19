package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Test

/** 起動時のテザリング ON（v0.7.0）の判断 */
class BootTetherTest {
    private val off = TetherInfo.AP_DISABLED
    private val on = TetherInfo.AP_ENABLED
    private val starting = TetherInfo.AP_ENABLING
    private val failed = TetherInfo.AP_FAILED

    private fun d(
        enabled: Boolean = true,
        permitted: Boolean = true,
        ap: Int? = off,
        since: Long? = null,
        attempts: Int = 0,
        onceOn: Boolean = false,
        runs: Int = 1,
    ) = BootTether.decide(enabled, permitted, ap, since, attempts, onceOn, runs)

    @Test
    fun startsAtBootOnlyWhenEnabledAndAllowed() {
        assertEquals(BootTether.Act.START, d())
        assertEquals("状態が分からなくても依頼する", BootTether.Act.START, d(ap = null))
        assertEquals(BootTether.Act.DISABLED, d(enabled = false))
        assertEquals(BootTether.Act.NO_PERMISSION, d(permitted = false))
        assertEquals("すでに ON なら何もしない", BootTether.Act.ALREADY_ON, d(ap = on))
        assertEquals("使わない設定なら、ON でも何もしない", BootTether.Act.DISABLED, d(enabled = false, ap = on))
    }

    @Test
    fun waitsAfterARequestAndRetriesWithLongerGaps() {
        assertEquals("依頼の直後は重ねて依頼しない", BootTether.Act.WAIT, d(since = 5_000L, attempts = 1))
        assertEquals("ON になる途中", BootTether.Act.WAIT, d(ap = starting, since = 40_000L, attempts = 1))
        assertEquals("ON になる途中（ほかの何かが依頼した）", BootTether.Act.WAIT, d(ap = starting))
        assertEquals("30 秒たっても ON でなければ依頼し直す", BootTether.Act.START, d(since = 30_000L, attempts = 1))
        assertEquals("アクセスポイントの起動に失敗したときも", BootTether.Act.START, d(ap = failed, since = 30_000L, attempts = 1))
        assertEquals("2 回目のあとは 60 秒待つ", BootTether.Act.WAIT, d(since = 30_000L, attempts = 2))
        assertEquals(BootTether.Act.START, d(since = 60_000L, attempts = 2))
        assertEquals("アラームが少し早く届いても依頼する", BootTether.Act.START, d(since = 59_500L, attempts = 2))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L, 30_000L), (1..5).map { BootTether.gapMs(it) })
    }

    @Test
    fun givesUpAfterTheLastAttempt() {
        val last = BootTether.MAX_ATTEMPTS
        assertEquals(5, last)
        assertEquals("最後の依頼のあとは確かめるだけ", BootTether.Act.WAIT, d(since = 10_000L, attempts = last))
        assertEquals(BootTether.Act.GIVE_UP, d(since = 30_000L, attempts = last))
        assertEquals("あきらめる直前に ON になっていれば成功", BootTether.Act.ALREADY_ON, d(ap = on, since = 30_000L, attempts = last))
        assertEquals("許可がないときは、回数より先にそれを知らせる", BootTether.Act.NO_PERMISSION, d(permitted = false, since = 30_000L, attempts = last))
        assertEquals(BootTether.Act.WAIT, d(ap = starting, runs = BootTether.MAX_RUNS))
        assertEquals("ON になる途中のまま変わらなくても、いつまでも続けない", BootTether.Act.GIVE_UP, d(ap = starting, runs = BootTether.MAX_RUNS + 1))
    }

    @Test
    fun leavesTheHotspotAloneOnceItCameOn() {
        assertEquals("ON になったあと利用者が OFF にしたら、次の起動までそのまま", BootTether.Act.DONE, d(onceOn = true, attempts = 1, since = 600_000L))
        assertEquals(BootTether.Act.DONE, d(ap = on, onceOn = true))
    }

    @Test
    fun recheckTimes() {
        assertEquals("ON になる途中は 15 秒後", 15_000L, BootTether.waitMs(starting, 40_000L, 1))
        assertEquals("依頼から 30 秒たったとき", 25_000L, BootTether.waitMs(off, 5_000L, 1))
        assertEquals("少なくとも 5 秒あける", 5_000L, BootTether.waitMs(off, 29_500L, 1))
        assertEquals(15_000L, BootTether.waitMs(off, null, 0))
    }
}
