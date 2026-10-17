package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class PowerMathTest {
    private val h = 3_600_000L
    private fun r(atMs: Long, counter: Long?, level: Int?, mv: Int? = 3800, battery: Boolean = true) =
        Reading(atMs, counter, level, mv, battery)

    @Test
    fun normalizesCounter() {
        assertNull(PowerMath.normalizeCounter(Int.MIN_VALUE))
        assertNull(PowerMath.normalizeCounter(0))
        assertEquals(2_864_540L, PowerMath.normalizeCounter(2_864_540))
        assertEquals("mAh で返す機種", 2_864_000L, PowerMath.normalizeCounter(2_864))
    }

    @Test
    fun counterIsPreferred() {
        val c = PowerMath.consumption(r(0, 2_000_000, 80, 3800), r(h, 1_900_000, 77, 3700), emptyList(), 4000)!!
        assertEquals(Method.COUNTER, c.method)
        assertEquals(100.0, c.mah, 1e-9)
        assertEquals(100.0, c.avgMa, 1e-9)
        assertEquals(375.0, c.avgMw!!, 1e-9)
        assertEquals(3600L, c.durationSec)
    }

    @Test
    fun fallsBackToCurrentThenLevel() {
        // カウンタが動かない機種 → 電流の平均（符号は無視）
        val cur = listOf(CurrentSample(0, -300), CurrentSample(h / 2, -300), CurrentSample(h, -300))
        val c = PowerMath.consumption(r(0, 2_000_000, 80), r(h, 2_000_000, 79), cur, 4000)!!
        assertEquals(Method.CURRENT, c.method)
        assertEquals(300.0, c.avgMa, 1e-9)
        // カウンタも電流もない → 残量の変化と容量
        val l = PowerMath.consumption(r(0, null, 80), r(h, null, 75), emptyList(), 4000)!!
        assertEquals(Method.LEVEL, l.method)
        assertEquals(200.0, l.mah, 1e-9)
        // 容量が分からなければ推測しない
        assertNull(PowerMath.consumption(r(0, null, 80), r(h, null, 75), emptyList(), null))
    }

    @Test
    fun refusesWhatCannotBeMeasured() {
        assertNull("給電中は測らない", PowerMath.consumption(r(0, 2_000_000, 80, battery = false), r(h, 1_900_000, 79, battery = false), emptyList(), 4000))
        assertNull("短すぎる区間", PowerMath.consumption(r(0, 2_000_000, 80), r(30_000, 1_999_000, 80), emptyList(), 4000))
        assertNull("ありえない消費（1時間に 6000 mAh）", PowerMath.consumption(r(0, 7_000_000, 80), r(h, 1_000_000, 80), emptyList(), null))
    }

    @Test
    fun weightedAverageOfCurrent() {
        assertEquals(400.0, PowerMath.timeWeightedMa(listOf(CurrentSample(0, -300), CurrentSample(1000, -500)), 0, 1000)!!, 1e-9)
        assertNull(PowerMath.timeWeightedMa(listOf(CurrentSample(0, 300)), 0, 1000))
    }

    @Test
    fun recentRateNeedsEnoughDataAndFavoursRecent() {
        val now = 10 * h
        val c500 = Consumption(Method.COUNTER, 1800, 250.0, 500.0, null)
        val c300 = Consumption(Method.COUNTER, 1800, 150.0, 300.0, null)
        assertNull(PowerMath.recentRateMa(listOf(now to Consumption(Method.COUNTER, 300, 25.0, 300.0, null)), now))
        val rate = PowerMath.recentRateMa(listOf(now - 90 * 60_000L to c500, now to c300), now)!!
        assertEquals((500.0 * 225 + 300.0 * 1800) / 2025, rate, 1e-6)
        assertNull("2時間より古い区間は使わない", PowerMath.recentRateMa(listOf(now - 3 * h to c500), now))
    }

    @Test
    fun remainingAndCapacity() {
        assertEquals(200, PowerMath.remainingMinutes(1000.0, 300.0))
        assertNull(PowerMath.remainingMinutes(1000.0, 0.0))
        assertEquals(3871, PowerMath.capacityFrom(2_864_540, 74))
        assertNull("残量が少ないと推定しない", PowerMath.capacityFrom(500_000, 10))
        assertEquals(2864.54, PowerMath.remainingMah(2_864_540, 74, 4000)!!, 1e-9)
        assertEquals(2000.0, PowerMath.remainingMah(null, 50, 4000)!!, 1e-9)
        assertNull(PowerMath.remainingMah(null, 50, null))
    }
}

class ExperimentTest {
    private fun cand(auto: Boolean = false) = Candidate("k", "テスト", "A", "B", "Bにする", "Aに戻す", auto)

    /** [minutes] 分・[mw] mW（4.0 V）の区間 */
    private fun seg(minutes: Int, mw: Double, withMw: Boolean = true): Consumption {
        val ma = mw / 4.0
        return Consumption(Method.COUNTER, minutes * 60L, ma * minutes / 60.0, ma, if (withMw) mw else null)
    }

    /** A・B を交互に [mws] の消費で 1 条件 30 分ずつ計測し終える */
    private fun run(vararg mws: Double, failuresB: Int = 0, auto: Boolean = false, minutes: Int = 30): ExperimentRun {
        val r = ExperimentRun(cand(auto), 1800, mws.size / 2, 3, 0)
        for ((i, mw) in mws.withIndex()) {
            r.addSegment(seg(minutes, mw), null, null, null)
            r.addCheck(!(r.condition == 'B' && i == 1 && failuresB > 0))
            repeat(if (r.condition == 'B' && i == 1) failuresB - 1 else 0) { r.addCheck(false) }
            r.advance()
            if (r.waitingSwitch) r.confirmSwitch()
        }
        return r
    }

    @Test
    fun manualFlowWaitsForTheSwitch() {
        val r = ExperimentRun(cand(), 1800, 2, 3, 0)
        assertEquals('A', r.condition)
        assertTrue(r.measuring)
        assertFalse(r.settingIsB)
        r.addSegment(seg(30, 400.0), null, null, null)
        assertTrue(r.phaseComplete)
        assertEquals('B', r.advance())
        assertTrue("手動は切り替え待ちになる", r.waitingSwitch)
        assertFalse("切り替え前はまだ A", r.settingIsB)
        r.addSegment(seg(30, 999.0), null, null, null)
        assertEquals("切り替え待ちの間は数えない", 0L, r.current!!.sec)
        r.confirmSwitch()
        assertTrue(r.settingIsB)
        r.addSegment(seg(30, 300.0), null, null, null)
        assertEquals('A', r.advance())
        assertTrue("A に戻す前はまだ B", r.settingIsB)
        r.confirmSwitch()
        assertFalse(r.settingIsB)
        r.addSegment(seg(30, 400.0), null, null, null)
        r.advance()
        r.confirmSwitch()
        r.addSegment(seg(30, 300.0), null, null, null)
        assertNull("4 条件で終わり", r.advance())
        assertTrue(r.finished)
        assertTrue("最後は B", r.settingIsB)
    }

    @Test
    fun autoCandidateDoesNotWait() {
        val r = ExperimentRun(cand(auto = true), 1800, 1, 3, 0)
        r.addSegment(seg(30, 400.0), null, null, null)
        assertEquals('B', r.advance())
        assertFalse(r.waitingSwitch)
    }

    @Test
    fun adoptsOnlyAConsistentSaving() {
        val o = run(400.0, 300.0, 410.0, 310.0).outcome()
        assertEquals(Decision.ADOPT, o.decision)
        assertEquals("mW", o.unit)
        assertEquals(25.0, o.savingPct!!, 0.5)
        assertTrue(o.consistent)
    }

    @Test
    fun revertsSmallOrInconsistentSavings() {
        assertEquals("削減が小さい", Decision.REVERT, run(400.0, 395.0, 400.0, 395.0).outcome().decision)
        val inconsistent = run(400.0, 300.0, 310.0, 320.0).outcome()
        assertTrue(inconsistent.savingPct!! > 3)
        assertFalse(inconsistent.consistent)
        assertEquals("平均は下がっても、毎回下がっていなければ採用しない", Decision.REVERT, inconsistent.decision)
    }

    @Test
    fun worseConnectivityWins() {
        val o = run(400.0, 300.0, failuresB = 2).outcome()
        assertEquals(Decision.WORSE, o.decision)
        assertTrue(o.notes.first().contains("失敗"))
    }

    @Test
    fun inconclusiveAndAborted() {
        val short = ExperimentRun(cand(auto = true), 1800, 1, 3, 0)
        short.addSegment(seg(30, 400.0), null, null, null)
        short.advance()
        short.addSegment(seg(10, 300.0), null, null, null)
        assertEquals("B の計測が半分未満", Decision.INCONCLUSIVE, short.outcome().decision)
        assertEquals(Decision.ABORTED, short.outcome(aborted = true).decision)
    }

    @Test
    fun fallsBackToCurrentWhenVoltageIsMissing() {
        val r = ExperimentRun(cand(auto = true), 1800, 1, 3, 0)
        r.addSegment(seg(30, 400.0, withMw = false), null, null, null)
        r.advance()
        r.addSegment(seg(30, 300.0, withMw = false), null, null, null)
        r.advance()
        val o = r.outcome()
        assertEquals("mA", o.unit)
        assertEquals(100.0, o.avgA!!, 1e-9)
        assertEquals(Decision.ADOPT, o.decision)
    }

    @Test
    fun candidatesAreWellFormed() {
        val keys = Candidates.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(Candidates.of(Candidates.KEY_INTERVAL)!!.auto)
        assertTrue("自動で切り替えるのは本アプリの設定だけ", Candidates.all.filter { it.auto }.all { it.key == Candidates.KEY_INTERVAL })
        assertFalse(Candidates.of(Candidates.KEY_CUSTOM)!!.auto)
    }
}

class ReportsTest {
    private fun row(sec: Long, battery: Boolean, mah: Double?, mw: Double?, kb: Long?, rsrp: Int?, clients: Int?, screen: Long = 0, tether: Boolean? = true) =
        PowerRow(
            "2026-10-16 10:00:00", "2026-10-16 10:30:00", sec, battery, if (mah != null) Method.COUNTER else null, 80, 78,
            mah, mah?.let { it / (sec / 3600.0) }, 3800, mw, 30.5, screen, tether, clients, rsrp, 20.0, "B41（2.5GHz帯）", kb, "省電力", null, null,
        )

    @Test
    fun aggregatesADay() {
        val power = listOf(
            row(1800, true, 100.0, 760.0, 1024, -100, 2, screen = 60),
            row(1800, true, 50.0, 380.0, 2048, -90, 3, screen = 120),
            row(3600, false, null, null, null, -95, null, tether = false),
        )
        val checks = listOf(CheckRow(true, 31.0, -100), CheckRow(true, 32.5, -95), CheckRow(false, 30.0, -99))
        val r = Reports.daily("20261016", power, checks, anomalies = 1, capacityMah = 4000)
        assertEquals(3600L, r.batterySec)
        assertEquals(3600L, r.poweredSec)
        assertEquals(150.0, r.avgMa!!, 1e-9)
        assertEquals(570.0, r.avgMw!!, 1e-9)
        assertEquals(4000 / 150.0, r.fullRuntimeH!!, 1e-9)
        assertEquals(3.0, r.mobileMb!!, 1e-9)
        assertEquals(3600L, r.tetherSec)
        assertEquals(3, r.clientsMax)
        assertEquals(180L, r.screenSec)
        assertEquals(2, r.checksOk)
        assertEquals(3, r.checks)
        assertEquals(32.5, r.tempMaxC!!, 1e-9)
        assertEquals(-95.0, r.rsrpAvg!!, 1e-9)
        assertEquals(Reports.CSV_HEADER.size, Reports.csvFields(r).size)
    }

    @Test
    fun noBatteryDataMeansNoConsumption() {
        val r = Reports.daily("20261016", listOf(row(3600, false, null, null, null, null, null)), emptyList(), 0, 4000)
        assertNull(r.avgMa)
        assertNull(r.fullRuntimeH)
    }

    @Test
    fun powerRowRoundTrip() {
        val original = row(1800, true, 100.0, 760.0, 1024, -100, 2, screen = 60)
        val parsed = PowerRow.parse(Csv.parseLine(Csv.line(original.fields())))!!
        assertEquals(original.copy(avgMa = 200.0), parsed)
        assertEquals(PowerRow.HEADER.size, original.fields().size)
        assertEquals("20261016", parsed.day)
    }
}

class DailyCsvTest {
    private val day = 24 * 3_600_000L
    private val header = listOf("日時", "値")

    private fun store(): Pair<DailyCsv, File> {
        val dir = Files.createTempDirectory("dailycsv").toFile()
        return DailyCsv(dir, "t-", header) to dir
    }

    @Test
    fun appendsExportsAndPrunes() {
        val (csv, _) = store()
        val t0 = 1_760_000_000_000L
        csv.append("a,1", t0, 30, 10_000_000)
        csv.append("b,2", t0 + 1000, 30, 10_000_000)
        csv.append("c,3", t0 + day, 30, 10_000_000)
        assertEquals(2, csv.files().size)
        val out = ByteArrayOutputStream()
        assertEquals(3, csv.export(out))
        val bytes = out.toByteArray()
        assertTrue("BOM 付き", bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        assertEquals("見出しは 1 回だけ", 1, text.lines().count { it.startsWith("日時,") })
        assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), csv.rowsOf(csv.files().first()))

        csv.prune(t0 + 3 * day, 2, 10_000_000)
        assertEquals("保存期間 2 日 → 3 日前のファイルは消える", 0, csv.files().size)
    }

    @Test
    fun prunesBySizeButKeepsNewest() {
        val (csv, _) = store()
        val t0 = 1_760_000_000_000L
        for (i in 0 until 5) csv.append("x,${"y".repeat(100)}", t0 + i * day, 30, 10_000_000)
        csv.prune(t0 + 4 * day, 30, 250)
        assertTrue(csv.files().size in 1..2)
        assertEquals("最新のファイルは残す", csv.dayName(t0 + 4 * day), csv.files().last().name)
    }
}

class RuntimeAlertTest {
    private val limits = AlertTracker.Limits(45, 50, 2, 20, runtimeAlertMin = 60)
    private fun power(plug: Plug) = PowerInfo(60, plug, plug.powered, 30.0, 3800, 300, false, false, 0, null, null, true, false)

    @Test
    fun alertsWithHysteresisAndOnlyOnBattery() {
        val a = AlertTracker()
        assertTrue("推定できないうちは通知しない", a.evaluate(power(Plug.NONE), limits, null).isEmpty())
        assertEquals(listOf(AlertTracker.Change(DeviceAlert.RUNTIME_LOW, true)), a.evaluate(power(Plug.NONE), limits, 50))
        assertTrue(a.evaluate(power(Plug.NONE), limits, 80).isEmpty())
        assertEquals(listOf(AlertTracker.Change(DeviceAlert.RUNTIME_LOW, false)), a.evaluate(power(Plug.NONE), limits, 95))
        a.evaluate(power(Plug.NONE), limits, 50)
        assertEquals("給電で解除", listOf(AlertTracker.Change(DeviceAlert.RUNTIME_LOW, false)), a.evaluate(power(Plug.USB), limits, 50))
        assertTrue("給電中は出さない", a.evaluate(power(Plug.USB), limits, 10).isEmpty())
    }
}
