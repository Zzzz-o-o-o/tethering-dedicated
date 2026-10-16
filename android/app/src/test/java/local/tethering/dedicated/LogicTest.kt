package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryMachineTest {
    private val min = 60_000L

    @Test
    fun rechecksThenConfirmsOnceAndDoesNotLoop() {
        val m = RecoveryMachine({ listOf(1, 3, 5) })
        assertTrue(m.onResult(0, null) is RecoveryMachine.Step.Ok)

        val s1 = m.onResult(0, Anomaly.INTERNET) as RecoveryMachine.Step.Suspect
        assertEquals(1 * min, s1.nextDelayMs)
        assertTrue(m.rechecking)

        val s2 = m.onResult(1 * min, Anomaly.INTERNET) as RecoveryMachine.Step.Recheck
        assertEquals(3 * min, s2.nextDelayMs)
        assertTrue(s2.revalidate)

        val s3 = m.onResult(4 * min, Anomaly.INTERNET) as RecoveryMachine.Step.Recheck
        assertEquals(5 * min, s3.nextDelayMs)
        assertFalse(s3.revalidate)

        assertTrue(m.onResult(9 * min, Anomaly.INTERNET) is RecoveryMachine.Step.Confirmed)
        assertFalse("確定後は短い間隔の再確認をしない", m.rechecking)

        // 確定後は通常の間隔のチェックごとに StillBad。再通知は 6 時間ごとだけ
        val still = m.onResult(30 * min, Anomaly.INTERNET) as RecoveryMachine.Step.StillBad
        assertFalse(still.remind)
        val later = m.onResult(9 * min + 6 * 60 * min, Anomaly.INTERNET) as RecoveryMachine.Step.StillBad
        assertTrue(later.remind)

        val rec = m.onResult(9 * min + 6 * 60 * min + 15 * min, null) as RecoveryMachine.Step.Recovered
        assertTrue(rec.episode.confirmed)
        assertEquals(9 * min + 6 * 60 * min + 15 * min, rec.durationMs)
        assertNull(m.episode)
    }

    @Test
    fun shortBlipRecoversWithoutConfirming() {
        val m = RecoveryMachine({ listOf(1, 3, 5) })
        m.onResult(0, Anomaly.DNS)
        val rec = m.onResult(min, null) as RecoveryMachine.Step.Recovered
        assertFalse(rec.episode.confirmed)
    }

    @Test
    fun kindChangeKeepsTheCount() {
        val m = RecoveryMachine({ listOf(1, 3, 5) })
        m.onResult(0, Anomaly.NO_DATA)
        val r = m.onResult(min, Anomaly.DNS) as RecoveryMachine.Step.Recheck
        assertEquals(Anomaly.DNS, r.episode.kind)
        assertEquals(1, r.episode.rechecks)
    }

    @Test
    fun singleDelayConfirmsOnSecondObservation() {
        val m = RecoveryMachine({ listOf(1) })
        assertTrue(m.onResult(0, Anomaly.TETHER_OFF) is RecoveryMachine.Step.Suspect)
        assertTrue(m.onResult(min, Anomaly.TETHER_OFF) is RecoveryMachine.Step.Confirmed)
    }

    @Test
    fun markNotifiedSuppressesDuplicateNotification() {
        val m = RecoveryMachine({ listOf(1) })
        m.onResult(0, Anomaly.TETHER_OFF)
        m.markNotified(0)
        val s = m.onResult(min, Anomaly.TETHER_OFF) as RecoveryMachine.Step.StillBad
        assertFalse(s.remind)
    }

    @Test
    fun emptyDelaysFallBackToOneMinute() {
        val m = RecoveryMachine({ emptyList() })
        val s = m.onResult(0, Anomaly.INTERNET) as RecoveryMachine.Step.Suspect
        assertEquals(min, s.nextDelayMs)
    }
}

class AlertTrackerTest {
    private val limits = AlertTracker.Limits(tempWarnC = 45, tempCritC = 50, thermalWarn = 2, battLowPct = 20)

    private fun power(temp: Double? = 30.0, pct: Int? = 80, plug: Plug = Plug.USB, thermal: Int? = 0) =
        PowerInfo(pct, plug, plug.powered, temp, null, null, false, false, thermal, null, null, true, false)

    @Test
    fun temperatureWithHysteresis() {
        val a = AlertTracker()
        assertTrue(a.evaluate(power(44.0), limits).isEmpty())
        assertEquals(listOf(AlertTracker.Change(DeviceAlert.TEMP_WARN, true)), a.evaluate(power(45.0), limits))
        assertTrue("しきい値をまたいでも解除側の余裕の間は出入りしない", a.evaluate(power(44.0), limits).isEmpty())

        val up = a.evaluate(power(50.0), limits)
        assertTrue(AlertTracker.Change(DeviceAlert.TEMP_CRIT, true) in up)
        assertTrue(AlertTracker.Change(DeviceAlert.TEMP_WARN, false) in up)

        assertTrue(a.evaluate(power(49.0), limits).isEmpty())
        val down = a.evaluate(power(47.5), limits)
        assertTrue(AlertTracker.Change(DeviceAlert.TEMP_CRIT, false) in down)
        assertTrue(AlertTracker.Change(DeviceAlert.TEMP_WARN, true) in down)

        assertEquals(listOf(AlertTracker.Change(DeviceAlert.TEMP_WARN, false)), a.evaluate(power(42.9), limits))
    }

    @Test
    fun batteryLowOnlyWhenDischarging() {
        val a = AlertTracker()
        assertTrue(a.evaluate(power(pct = 15, plug = Plug.USB), limits).isEmpty())
        assertEquals(listOf(AlertTracker.Change(DeviceAlert.BATT_LOW, true)), a.evaluate(power(pct = 20, plug = Plug.NONE), limits))
        assertTrue(a.evaluate(power(pct = 22, plug = Plug.NONE), limits).isEmpty())
        assertEquals(listOf(AlertTracker.Change(DeviceAlert.BATT_LOW, false)), a.evaluate(power(pct = 22, plug = Plug.AC), limits))
    }

    @Test
    fun unknownValuesNeverRaiseAlerts() {
        val a = AlertTracker()
        assertTrue(a.evaluate(power(temp = null, pct = null, plug = Plug.UNKNOWN, thermal = null), limits).isEmpty())
    }

    @Test
    fun thermalStatus() {
        val a = AlertTracker()
        assertEquals(listOf(AlertTracker.Change(DeviceAlert.THERMAL, true)), a.evaluate(power(thermal = 2), limits))
        assertEquals(listOf(AlertTracker.Change(DeviceAlert.THERMAL, false)), a.evaluate(power(thermal = 1), limits))
    }
}

class ProfilesTest {
    @Test
    fun autoFollowsPower() {
        assertEquals(Effective.NORMAL, Profiles.base(BaseMode.AUTO, true))
        assertEquals(Effective.SAVER, Profiles.base(BaseMode.AUTO, false))
        assertEquals(Effective.SAVER, Profiles.base(BaseMode.AUTO, null))
        assertEquals(Effective.NORMAL, Profiles.base(BaseMode.NORMAL, false))
        assertEquals(Effective.SAVER, Profiles.base(BaseMode.SAVER, true))
    }

    @Test
    fun maintenanceAndRecoveryOverride() {
        assertEquals(Effective.MAINTENANCE, Profiles.effective(Effective.NORMAL, maintenance = true, recovering = true))
        assertEquals(Effective.RECOVERY, Profiles.effective(Effective.SAVER, maintenance = false, recovering = true))
        assertEquals(Effective.SAVER, Profiles.effective(Effective.SAVER, maintenance = false, recovering = false))
        assertEquals(60, Profiles.intervalMin(Effective.SAVER, 15, 60))
        assertEquals(15, Profiles.intervalMin(Effective.NORMAL, 15, 60))
    }

    @Test
    fun modeKeys() {
        assertEquals(BaseMode.SAVER, BaseMode.of("saver"))
        assertEquals(BaseMode.AUTO, BaseMode.of("bogus"))
        assertEquals(BaseMode.AUTO, BaseMode.of(null))
    }
}

class DiagnosisTest {
    @Test
    fun firstFailingStageIsTheCause() {
        assertEquals(Anomaly.SIM, Diagnosis.failure(false, Tri.NG, Tri.NG, Tri.SKIP, Tri.SKIP))
        assertEquals(Anomaly.NO_SERVICE, Diagnosis.failure(true, Tri.NG, Tri.NG, Tri.SKIP, Tri.SKIP))
        assertEquals(Anomaly.NO_DATA, Diagnosis.failure(true, Tri.OK, Tri.NG, Tri.SKIP, Tri.SKIP))
        assertEquals(Anomaly.DNS, Diagnosis.failure(true, Tri.OK, Tri.OK, Tri.NG, Tri.SKIP))
        assertEquals(Anomaly.INTERNET, Diagnosis.failure(true, Tri.OK, Tri.OK, Tri.OK, Tri.NG))
        assertNull(Diagnosis.failure(true, Tri.OK, Tri.OK, Tri.OK, Tri.OK))
        assertNull("HTTP を確認しない設定", Diagnosis.failure(true, Tri.OK, Tri.OK, Tri.OK, Tri.SKIP))
        assertNull("権限がなく登録状態が取得不可でも、IP 以降が通れば正常", Diagnosis.failure(null, Tri.UNKNOWN, Tri.OK, Tri.OK, Tri.OK))
    }

    @Test
    fun explainsCommonCauses() {
        assertEquals("機内モードがONです", Diagnosis.text(Anomaly.NO_SERVICE, TestLines.line(airplane = true), null, null))
        assertEquals("モバイルデータ通信がOFFです", Diagnosis.text(Anomaly.NO_DATA, TestLines.line(dataEnabled = false), null, null))
        assertTrue(Diagnosis.text(Anomaly.INTERNET, null, 302, null).contains("HTTP 302"))
        assertTrue(Diagnosis.text(Anomaly.INTERNET, null, null, "タイムアウト").contains("タイムアウト"))
    }
}

class DefsTest {
    @Test
    fun recheckList() {
        assertEquals(listOf(1, 3, 5), Defs.parseRecheck("1,3,5"))
        assertEquals(listOf(2, 4), Defs.parseRecheck(" 2 , 4 "))
        assertNull(Defs.parseRecheck(""))
        assertNull(Defs.parseRecheck("0,3"))
        assertNull(Defs.parseRecheck("61"))
        assertNull(Defs.parseRecheck("1,2,3,4,5,6"))
        assertNull(Defs.parseRecheck("a"))
    }

    @Test
    fun checkUrl() {
        assertNull(Defs.checkUrl("http://connectivitycheck.gstatic.com/generate_204"))
        assertNull(Defs.checkUrl("https://example.jp/a"))
        assertTrue(Defs.checkUrl("ftp://example.jp") != null)
        assertTrue(Defs.checkUrl("notaurl") != null)
        assertTrue(Defs.checkUrl("http://") != null)
    }

    @Test
    fun defaultsAreWithinTheirOwnRanges() {
        for (d in Defs.ints) assertTrue(d.key, d.def in d.min..d.max)
        for (d in Defs.texts) assertNull(d.key, d.check(d.def))
        val keys = Defs.ints.map { it.key } + Defs.bools.map { it.key } + Defs.texts.map { it.key } + Defs.MODE_KEY
        assertEquals("キーの重複なし", keys.size, keys.toSet().size)
    }
}

class SettingsCodecTest {
    @Test
    fun acceptsValidAndReportsInvalid() {
        val p = SettingsCodec.parse(
            mapOf(
                "mode" to "saver",
                "interval_normal_min" to 20,
                "interval_saver_min" to 10_000,
                "timeout_sec" to 15L,
                "batt_low_pct" to 25.0,
                "temp_warn_c" to 44.5,
                "log_days" to "14",
                "http_check" to false,
                "notify_alert" to "yes",
                "check_url" to "https://example.jp/204",
                "recheck_min" to "0",
                "unknown_key" to 1,
            ),
        )
        assertEquals("saver", p.values["mode"])
        assertEquals(20, p.values["interval_normal_min"])
        assertEquals(15, p.values["timeout_sec"])
        assertEquals(25, p.values["batt_low_pct"])
        assertEquals(14, p.values["log_days"])
        assertEquals(false, p.values["http_check"])
        assertEquals("https://example.jp/204", p.values["check_url"])
        assertFalse(p.values.containsKey("interval_saver_min"))
        assertFalse(p.values.containsKey("temp_warn_c"))
        assertFalse(p.values.containsKey("notify_alert"))
        assertFalse(p.values.containsKey("recheck_min"))
        assertEquals(4, p.errors.size)
        assertEquals(listOf("unknown_key"), p.unknown)
    }

    @Test
    fun describesOnlyRealChanges() {
        val cur = mapOf<String, Any>("mode" to "auto", "interval_normal_min" to 15, "http_check" to true)
        val inc = mapOf<String, Any>("mode" to "saver", "interval_normal_min" to 15, "http_check" to false)
        val d = SettingsCodec.describeChanges(cur, inc)
        assertEquals(2, d.size)
        assertTrue(d.any { it.startsWith("運用モード: 自動 → 省電力") })
        assertTrue(d.any { it.contains("ON → OFF") })
    }
}

class LogRowTest {
    @Test
    fun everyRowHasOneValuePerColumn() {
        val s = StateStore.State(running = true, line = TestLines.line())
        val row = LogStore.fields(0L, Ev.SUSPECT, s, includeCheck = true, anomaly = "x", action = "y", result = "z", detail = null)
        assertEquals(LogStore.HEADER.size, row.size)
        assertEquals("x", row[LogStore.HEADER.indexOf("異常")])
        assertEquals("B3（1.7GHz帯）", row[LogStore.HEADER.indexOf("バンド")])
        assertEquals("15.0", row[LogStore.HEADER.indexOf("帯域(MHz)")])
        val parsed = Csv.parseLine(Csv.line(row))
        assertEquals(LogStore.HEADER.size, parsed.size)
    }
}
