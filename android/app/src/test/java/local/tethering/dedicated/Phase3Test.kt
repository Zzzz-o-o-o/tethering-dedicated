package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

private val TZ: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")

private fun power(plug: Plug, level: Int?) = PowerInfo(level, plug, plug.powered, 30.0, 3800, 300, false, false, 0, null, null, true, false)

/** 2026-10-17 の [h]:[m] からの区間 */
private fun seg(
    h: Int,
    m: Int,
    minutes: Int,
    battery: Boolean,
    mah: Double?,
    tether: Boolean?,
    clients: Int?,
    rsrp: Int? = null,
    band: String? = null,
    kb: Long? = null,
): PowerRow {
    fun t(totalMin: Int) = String.format("2026-10-17 %02d:%02d:00", totalMin / 60, totalMin % 60)
    val start = h * 60 + m
    return PowerRow(
        t(start), t(start + minutes), minutes * 60L, battery, if (mah != null) Method.COUNTER else null, null, null,
        mah, mah?.let { it / (minutes / 60.0) }, null, null, null, 0, tether, clients, rsrp, null, band, kb, "省電力", null, null,
    )
}

class AutoPolicyTest {
    private val none = AutoPolicy.Want(saver = false, doze = false)

    @Test
    fun turnsOnOnlyOnBatteryAndBackWhenPlugged() {
        val cfg = AutoPolicy.Config(saver = true, saverPct = 100, doze = true)
        assertEquals(AutoPolicy.Want(saver = true, doze = true), AutoPolicy.want(cfg, power(Plug.NONE, 80), none))
        assertEquals("給電で元に戻す", none, AutoPolicy.want(cfg, power(Plug.USB, 80), AutoPolicy.Want(saver = true, doze = true)))
        assertEquals("給電状態が分からなければ変えない側（OFF）", none, AutoPolicy.want(cfg, power(Plug.UNKNOWN, 80), none))
        assertEquals("設定が OFF なら何もしない", none, AutoPolicy.want(AutoPolicy.Config(false, 100, false), power(Plug.NONE, 10), none))
    }

    @Test
    fun usesTheLevelWithHysteresis() {
        val cfg = AutoPolicy.Config(saver = true, saverPct = 40, doze = false)
        assertFalse(AutoPolicy.want(cfg, power(Plug.NONE, 41), none).saver)
        val on = AutoPolicy.want(cfg, power(Plug.NONE, 40), none)
        assertTrue(on.saver)
        assertTrue("しきい値の少し上ではまだ戻さない", AutoPolicy.want(cfg, power(Plug.NONE, 44), on).saver)
        assertFalse("5% 上で戻す", AutoPolicy.want(cfg, power(Plug.NONE, 46), on).saver)
        assertTrue("残量が分からなければ今のまま", AutoPolicy.want(cfg, power(Plug.NONE, null), on).saver)
        assertTrue(AutoPolicy.DOZE_EARLY.contains("inactive_to=300000"))
    }
}

class ProbeSkipAndIdleTest {
    @Test
    fun skipsOnlyWhenClientsAreBusy() {
        val busy = ProbeSkip.MIN_RX_BYTES
        assertTrue(ProbeSkip.should(true, Trigger.SCHEDULED, false, 2, busy))
        assertFalse("設定 OFF", ProbeSkip.should(false, Trigger.SCHEDULED, false, 2, busy))
        assertFalse("手動チェックは省かない", ProbeSkip.should(true, Trigger.MANUAL, false, 2, busy))
        assertFalse("異常の確認中は省かない", ProbeSkip.should(true, Trigger.SCHEDULED, true, 2, busy))
        assertFalse("接続 0 台", ProbeSkip.should(true, Trigger.SCHEDULED, false, 0, busy))
        assertFalse("台数不明", ProbeSkip.should(true, Trigger.SCHEDULED, false, null, busy))
        assertFalse("通信が少ない", ProbeSkip.should(true, Trigger.SCHEDULED, false, 2, busy - 1))
        assertFalse("受信量が分からない", ProbeSkip.should(true, Trigger.SCHEDULED, false, 2, null))
    }

    @Test
    fun idlesOnlyOnBattery() {
        val min = 60_000L
        val now = 100 * min
        assertNull("給電中は待機しない", IdleMode.reason(180, 0, now, power(Plug.USB, 10), 30, null))
        assertEquals(IdleMode.Reason.LOW_BATTERY, IdleMode.reason(180, null, now, power(Plug.NONE, 30), 30, null))
        assertEquals(IdleMode.Reason.NO_CLIENTS, IdleMode.reason(180, now - 30 * min, now, power(Plug.NONE, 80), 30, null))
        assertNull("接続 0 台になってまだ 29 分", IdleMode.reason(180, now - 29 * min, now, power(Plug.NONE, 80), 30, null))
        assertNull("直近の区間で通信が多ければ待機しない", IdleMode.reason(180, now - 60 * min, now, power(Plug.NONE, 80), 30, 2048.0))
        assertNull("0 は使わない設定", IdleMode.reason(0, now - 60 * min, now, power(Plug.NONE, 10), 30, null))
    }
}

class SignalPowerTest {
    @Test
    fun groupsMeasuredSegmentsByRsrpAndBand() {
        val rows = listOf(
            seg(1, 0, 60, true, 300.0, true, 1, rsrp = -85, band = "B41", kb = 2048),
            seg(2, 0, 60, true, 500.0, true, 1, rsrp = -105, band = "B41", kb = 2048),
            seg(3, 0, 30, true, 300.0, true, 1, rsrp = -115, band = "B8"),
            seg(4, 0, 60, false, null, true, 1, rsrp = -85),
        )
        val g = SignalPower.byRsrp(rows)
        assertEquals("給電中の区間は数えない", 3600L, g[0].sec)
        assertEquals(300.0, g[0].avgMa!!, 1e-9)
        assertEquals(2.0, g[0].mbPerHour!!, 1e-9)
        assertEquals(0L, g[1].sec)
        assertEquals(500.0, g[2].avgMa!!, 1e-9)
        assertEquals(600.0, g[3].avgMa!!, 1e-9)
        val bands = SignalPower.byBand(rows)
        assertEquals(listOf("B41", "B8"), bands.map { it.label })
        assertEquals(400.0, bands[0].avgMa!!, 1e-9)
        val slope = SignalPower.slopeMaPerDb(rows)!!
        assertTrue("電波が良いほど消費が少ない（傾きが負）", slope < 0)
        assertNull("計測が 2 時間未満なら出さない", SignalPower.slopeMaPerDb(rows.take(1)))
    }
}

class StandbyTest {
    @Test
    fun computesTheStandbyCostAndAdvice() {
        val rows = listOf(
            seg(0, 0, 60, true, 150.0, true, 0),
            seg(1, 0, 60, true, 150.0, true, 0),
            seg(2, 0, 60, true, 50.0, false, null),
            seg(3, 0, 120, true, 900.0, true, 2),
            seg(5, 0, 120, false, null, true, 0),
        )
        val r = Standby.analyze(rows, TZ, 4000)
        assertEquals(7.0, r.recordedHours, 1e-9)
        assertEquals(4.0, r.idleHours, 1e-9)
        assertEquals(150.0, r.idleMa!!, 1e-9)
        assertEquals(50.0, r.offMa!!, 1e-9)
        assertEquals(450.0, r.usedMa!!, 1e-9)
        assertEquals(100.0, r.costMa!!, 1e-9)
        assertEquals("接続 0 台は 1 日の 4/7", 24 * 4 / 7.0, r.idleHoursPerDay, 1e-9)
        assertEquals(100 * 24 * 4 / 7.0, r.dailyCostMah!!, 1e-6)
        assertEquals("0:00〜2:00 と 5:00〜7:00 の 2 回", 2, r.stretches)
        assertEquals(2.0, r.longestIdleH, 1e-9)
        assertEquals("1 日に電池の約 34%、長い待機 → 止める運用も検討", Standby.Advice.CONSIDER_OFF, r.advice)
    }

    @Test
    fun saysWhatIsMissing() {
        assertEquals(Standby.Advice.NEED_DATA, Standby.analyze(listOf(seg(0, 0, 120, false, null, true, 0)), TZ, 4000).advice)
        val noOff = listOf(seg(0, 0, 60, true, 150.0, true, 0))
        assertEquals(Standby.Advice.NEED_OFF, Standby.analyze(noOff, TZ, 4000).advice)
        val small = listOf(seg(0, 0, 60, true, 60.0, true, 0), seg(1, 0, 60, true, 50.0, false, null), seg(2, 0, 600, false, null, true, 3))
        assertEquals("差が小さければ常時待機でよい", Standby.Advice.KEEP_ON, Standby.analyze(small, TZ, 4000).advice)
        assertNull(Standby.kindOf(seg(0, 0, 10, true, 1.0, null, null)))
    }
}

class AppTrafficTest {
    @Test
    fun separatesTetheringAndRanksApps() {
        val mb = 1024L * 1024
        val list = listOf(
            AppTraffic.UidBytes(AppTraffic.UID_TETHERING, 0, 300 * mb),
            AppTraffic.UidBytes(1000, 0, 2 * mb),
            AppTraffic.UidBytes(10050, mb, 40 * mb),
            AppTraffic.UidBytes(10060, 0, 3 * mb),
            AppTraffic.UidBytes(10070, 0, 0),
            AppTraffic.UidBytes(10146, 0, mb / 10),
        )
        val info = mapOf(
            10050 to AppTraffic.AppInfo("Android System WebView", "com.google.android.webview", system = true),
            10060 to AppTraffic.AppInfo("SafetyCore", "com.google.android.safetycore", system = false),
            1000 to AppTraffic.AppInfo("設定", "android", system = true, others = 20),
        )
        val s = AppTraffic.summarize(list, selfUid = 10146, info = { info[it] }, events = mapOf("com.google.android.webview" to intArrayOf(0, 3)))
        assertEquals(300 * mb, s.tetheringBytes)
        assertEquals(2 * mb + 41 * mb + 3 * mb + mb / 10, s.ownBytes)
        assertEquals(listOf("テザリング（接続端末）", "Android System WebView", "SafetyCore", "Android システム", "UID 10146"), s.rows.map { it.label })
        val web = s.rows.first { it.uid == 10050 }
        assertEquals(AppTraffic.Kind.SYSTEM_APP, web.kind)
        assertEquals(3, web.services)
        assertTrue("システムアプリは手動で対策", AppTraffic.needsManual(web))
        val sc = s.rows.first { it.uid == 10060 }
        assertTrue("裏で 1 MB 以上のユーザーアプリは制限の候補", AppTraffic.isCandidate(sc))
        assertEquals(AppTraffic.Kind.OS, s.rows.first { it.uid == 1000 }.kind)
        assertEquals(AppTraffic.Kind.SELF, s.rows.first { it.uid == 10146 }.kind)
        assertFalse("通信 0 のアプリは出さない", s.rows.any { it.uid == 10070 })
        // 常駐サービスを持つシステムアプリの通信は「前面」に数えられるので、合計で判定する
        val gms = AppTraffic.Row(10011, "Google Play 開発者サービス", "com.google.android.gms", AppTraffic.Kind.SYSTEM_APP, 26 * mb, 0, 6, 0)
        assertTrue(AppTraffic.needsManual(gms))
        assertFalse("ユーザーアプリの制限の候補は裏の通信で判定（制限は裏の動作にしか効かない）", AppTraffic.isCandidate(gms.copy(kind = AppTraffic.Kind.USER_APP)))
    }
}

class NetworkModesTest {
    @Test
    fun labelsModes() {
        assertEquals("4G/3G/2G（自動）", NetworkModes.label(20))
        assertEquals("4G/3G/2G（自動）", NetworkModes.label(9))
        assertEquals("4Gのみ", NetworkModes.label(11))
        assertTrue(NetworkModes.hasNr(26))
        assertFalse(NetworkModes.hasNr(20))
        assertTrue(NetworkModes.hasLte(9))
        assertEquals("種類 99", NetworkModes.label(99))
        assertEquals(9, NetworkModes.parse("9,9"))
        assertEquals(20, NetworkModes.parse(" 20 "))
        assertNull(NetworkModes.parse(null))
        assertNotNull(Candidates.of(Candidates.KEY_TETHER_OFF))
        assertFalse(Candidates.of(Candidates.KEY_TETHER_OFF)!!.auto)
    }
}
