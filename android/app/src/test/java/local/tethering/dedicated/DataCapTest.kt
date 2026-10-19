package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 月の通信量の上限（v0.8.0）の判断 */
class DataCapTest {
    private val jst: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")

    private fun at(y: Int, mon: Int, d: Int, h: Int = 0, m: Int = 0): Long = Calendar.getInstance(jst).apply {
        clear()
        set(y, mon - 1, d, h, m, 0)
    }.timeInMillis

    private val limit = 50 * DataCap.GB
    private val cycle = at(2026, 10, 1)

    private fun d(
        enabled: Boolean = true,
        stopEnabled: Boolean = true,
        used: Long? = 10 * DataCap.GB,
        notified: Int = DataCap.LEVEL_NONE,
        stopped: Long = 0L,
        handled: Long = 0L,
        tetherOn: Boolean? = true,
        hold: Boolean = false,
    ) = DataCap.decide(DataCap.Input(enabled, stopEnabled, limit, used, cycle, notified, stopped, handled, tetherOn, hold))

    @Test
    fun periodStartsOnTheChosenDayAtMidnight() {
        assertEquals(at(2026, 10, 1) to at(2026, 11, 1), DataCap.cycle(at(2026, 10, 20, 15), 1, jst))
        assertEquals("数え始める日の前なら前の月から", at(2026, 9, 25) to at(2026, 10, 25), DataCap.cycle(at(2026, 10, 20), 25, jst))
        assertEquals("その日の 0:00 ちょうどから新しい期間", at(2026, 10, 25) to at(2026, 11, 25), DataCap.cycle(at(2026, 10, 25), 25, jst))
        assertEquals("年をまたぐ", at(2025, 12, 15) to at(2026, 1, 15), DataCap.cycle(at(2026, 1, 10), 15, jst))
        assertEquals("2 月も 28 日まで", at(2026, 2, 28) to at(2026, 3, 28), DataCap.cycle(at(2026, 3, 5), 28, jst))
        assertEquals("29 日以降は 28 日として扱う", DataCap.cycle(at(2026, 10, 20), 28, jst), DataCap.cycle(at(2026, 10, 20), 31, jst))
    }

    @Test
    fun levels() {
        assertEquals(DataCap.LEVEL_NONE, DataCap.level(limit * 79 / 100, limit))
        assertEquals(DataCap.LEVEL_WARN, DataCap.level(limit * 80 / 100, limit))
        assertEquals(DataCap.LEVEL_REACHED, DataCap.level(limit, limit))
        assertEquals("上限 0 は使わない", DataCap.LEVEL_NONE, DataCap.level(1L, 0L))
    }

    @Test
    fun notifiesOncePerLevel() {
        assertNull(d().notify)
        assertEquals(DataCap.LEVEL_WARN, d(used = 41 * DataCap.GB).notify)
        assertNull("同じ段階は 2 回知らせない", d(used = 45 * DataCap.GB, notified = DataCap.LEVEL_WARN).notify)
        assertEquals(DataCap.LEVEL_REACHED, d(used = 50 * DataCap.GB, notified = DataCap.LEVEL_WARN).notify)
        assertEquals("一度に超えたら 100% だけ", DataCap.LEVEL_REACHED, d(used = 60 * DataCap.GB).notify)
        assertNull("読めなければ知らせない", d(used = null).notify)
        assertNull("使わない設定", d(enabled = false, used = 60 * DataCap.GB).notify)
    }

    @Test
    fun stopsOnlyWhenAskedAndOnlyOnce() {
        val over = 51 * DataCap.GB
        assertEquals(DataCap.Tether.STOP, d(used = over).tether)
        assertEquals("止める設定でなければ知らせるだけ", DataCap.Tether.NONE, d(used = over, stopEnabled = false).tether)
        assertEquals(DataCap.Tether.NONE, d(used = 45 * DataCap.GB).tether)
        assertEquals("すでに OFF なら止めない（この期間はもう止めない）", DataCap.Tether.ALREADY_OFF, d(used = over, tetherOn = false).tether)
        assertEquals("状態が分からないときは待つ", DataCap.Tether.NONE, d(used = over, tetherOn = null).tether)
        assertEquals("メンテナンス中・比較テスト中は新たに止めない", DataCap.Tether.NONE, d(used = over, hold = true).tether)
        assertEquals("この期間はもう判断した", DataCap.Tether.NONE, d(used = over, handled = cycle).tether)
        assertEquals("前の期間の判断は関係ない", DataCap.Tether.STOP, d(used = over, handled = at(2026, 9, 1)).tether)
    }

    @Test
    fun resumesOnlyWhatThisAppStopped() {
        val over = 51 * DataCap.GB
        assertEquals("止めている間は何もしない", DataCap.Tether.NONE, d(used = over, stopped = cycle, tetherOn = false).tether)
        assertEquals("止めている間に手で ON にした", DataCap.Tether.CLEAR, d(used = over, stopped = cycle, tetherOn = true).tether)
        assertEquals("次の期間に入った", DataCap.Tether.START, d(used = 0L, stopped = at(2026, 9, 1), tetherOn = false).tether)
        assertEquals("上限を OFF にした", DataCap.Tether.START, d(enabled = false, stopped = cycle, tetherOn = false).tether)
        assertEquals("止める設定を OFF にした", DataCap.Tether.START, d(stopEnabled = false, used = over, stopped = cycle, tetherOn = false).tether)
        assertEquals("上限を上げて下回った", DataCap.Tether.START, d(used = 45 * DataCap.GB, stopped = cycle, tetherOn = false).tether)
        assertEquals("通信量が読めないうちは再開しない", DataCap.Tether.NONE, d(used = null, stopped = cycle, tetherOn = false).tether)
    }

    @Test
    fun bootTetherKeepsItOffWhileBlocked() {
        val off = TetherInfo.AP_DISABLED
        assertEquals(BootTether.Act.BLOCKED, BootTether.decide(true, true, off, null, 0, false, 1, blocked = true))
        assertEquals("すでに ON なら何もしない", BootTether.Act.ALREADY_ON, BootTether.decide(true, true, TetherInfo.AP_ENABLED, null, 0, false, 1, blocked = true))
        assertEquals(BootTether.Act.DISABLED, BootTether.decide(false, true, off, null, 0, false, 1, blocked = true))
        assertEquals(BootTether.Act.START, BootTether.decide(true, true, off, null, 0, false, 1, blocked = false))
    }
}
