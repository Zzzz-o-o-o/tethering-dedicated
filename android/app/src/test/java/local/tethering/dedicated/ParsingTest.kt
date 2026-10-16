package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandsTest {
    @Test
    fun mapsJapaneseLteBands() {
        assertEquals(Bands.LteBand(1, 2140.0), Bands.lteBand(300))
        val b3 = Bands.lteBand(1850)!!
        assertEquals(3, b3.band)
        assertEquals(1870.0, b3.dlMhz, 1e-9)
        assertEquals("B3（1.7GHz帯）", b3.label)
        assertEquals("B8（900MHz帯）", Bands.lteBand(3600)!!.label)
        assertEquals(41, Bands.lteBand(40000)!!.band)
        assertEquals(783.0, Bands.lteBand(9460)!!.dlMhz, 1e-9)
        assertEquals(2, Bands.lteBand(600)!!.band)
    }

    @Test
    fun unknownChannelIsNull() {
        assertNull(Bands.lteBand(4990)) // B11 と B12 の間
        assertNull(Bands.lteBand(-1))
        assertNull(Bands.lteBand(99999))
    }
}

class CsvTest {
    @Test
    fun escapesOnlyWhenNeeded() {
        assertEquals("abc", Csv.escape("abc"))
        assertEquals("\"a,b\"", Csv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
        assertEquals("a,,c", Csv.line(listOf("a", null, "c")))
    }

    @Test
    fun parseRoundTrip() {
        val fields = listOf("2026-10-16 12:00:00", "異常の疑い", "", "DNS: タイムアウト / HTTP: 応答コード 302", "x,y", "q\"uote", "日本語")
        assertEquals(fields, Csv.parseLine(Csv.line(fields)))
    }
}

class ParsersTest {
    private val arp = listOf(
        "IP address       HW type     Flags       HW address            Mask     Device",
        "192.168.43.57    0x1         0x2         aa:bb:cc:dd:ee:01     *        ap0",
        "192.168.43.58    0x1         0x2         AA:BB:CC:DD:EE:02     *        ap0",
        "192.168.43.59    0x1         0x0         00:00:00:00:00:00     *        ap0",
        "192.168.43.60    0x1         0x2         aa:bb:cc:dd:ee:01     *        ap0",
        "10.0.0.1         0x1         0x2         11:22:33:44:55:66     *        ccmni0",
    )

    @Test
    fun countsArpClientsOnHotspotOnly() {
        assertEquals(2, Parsers.countArp(arp, "ap0"))
        assertEquals(0, Parsers.countArp(arp, "wlan1"))
        // インターフェース名が分からないときは Wi-Fi らしい名前だけ数える
        assertEquals(2, Parsers.countArp(arp, null))
        assertEquals(0, Parsers.countArp(arp.take(1), "ap0"))
    }

    @Test
    fun countsNeighboursAndSkipsFailed() {
        val out = """
            192.168.43.57 dev ap0 lladdr aa:bb:cc:dd:ee:01 REACHABLE
            fe80::1 dev ap0 lladdr aa:bb:cc:dd:ee:01 STALE
            192.168.43.58 dev ap0 lladdr aa:bb:cc:dd:ee:02 DELAY
            192.168.43.61 dev ap0  FAILED
            192.168.43.62 dev ap0 lladdr aa:bb:cc:dd:ee:03 FAILED
            10.0.0.1 dev ccmni0 lladdr 11:22:33:44:55:66 REACHABLE
        """.trimIndent()
        assertEquals(2, Parsers.countNeigh(out, "ap0"))
        assertEquals(0, Parsers.countNeigh("", "ap0"))
    }

    @Test
    fun normalizesThermalUnits() {
        assertEquals(45.0, Parsers.thermalC(45000)!!, 1e-9)
        assertEquals(45.0, Parsers.thermalC(450)!!, 1e-9)
        assertEquals(45.0, Parsers.thermalC(45)!!, 1e-9)
        assertEquals(-5.0, Parsers.thermalC(-5)!!, 1e-9)
        assertNull(Parsers.thermalC(999_999))
    }

    @Test
    fun recognisesSocZones() {
        assertTrue(Parsers.isSocZone("mtktscpu"))
        assertTrue(Parsers.isSocZone("tsens_tz_sensor0"))
        assertTrue(Parsers.isSocZone("cpu-0-0-usr"))
        assertFalse(Parsers.isSocZone("battery"))
        assertFalse(Parsers.isSocZone("pa_therm0"))
        assertFalse(Parsers.isSocZone("mtktsbattery"))
    }

    @Test
    fun normalizesCurrent() {
        assertNull(Parsers.currentMa(Int.MIN_VALUE))
        assertNull(Parsers.currentMa(0))
        assertEquals(-523, Parsers.currentMa(-523_000))
        assertEquals(1200, Parsers.currentMa(1200))
    }

    @Test
    fun lteSinrUnitsDependOnSdk() {
        assertEquals(20.2, Parsers.lteSinrDb(202, 29)!!, 1e-9)
        assertNull(Parsers.lteSinrDb(Int.MAX_VALUE, 29))
        assertEquals(25.0, Parsers.lteSinrDb(25, 30)!!, 1e-9)
        assertNull(Parsers.lteSinrDb(250, 30))
    }

    @Test
    fun classifiesInterfaces() {
        assertTrue(Ifaces.isWifi("ap0"))
        assertTrue(Ifaces.isWifi("wlan1"))
        assertTrue(Ifaces.isWifi("swlan0"))
        assertFalse(Ifaces.isWifi("rndis0"))
        assertEquals("USB", Ifaces.label("rndis0"))
        assertEquals("Bluetooth", Ifaces.label("bt-pan"))
    }
}

class ModelTest {
    private fun tether(ap: Int?, tethered: List<String>?) = TetherInfo(ap, null, null, tethered, null, "", null)

    @Test
    fun wifiOnPrefersApStateThenInterfaces() {
        assertEquals(true, tether(TetherInfo.AP_ENABLED, null).wifiOn)
        assertEquals(false, tether(TetherInfo.AP_DISABLED, listOf("ap0")).wifiOn)
        assertEquals(true, tether(null, listOf("ap0")).wifiOn)
        assertEquals(false, tether(null, listOf("rndis0")).wifiOn)
        assertNull(tether(null, null).wifiOn)
    }

    @Test
    fun dataRegistrationFromRat() {
        assertEquals(true, TestLines.line(dataRat = 13).dataRegistered)
        assertEquals(false, TestLines.line(dataRat = 0).dataRegistered)
        assertNull(TestLines.line(dataRat = null).dataRegistered)
    }
}

object TestLines {
    fun line(
        simState: Int? = LineInfo.SIM_READY,
        voiceState: Int? = LineInfo.STATE_OUT_OF_SERVICE,
        dataRat: Int? = 13,
        dataEnabled: Boolean? = true,
        airplane: Boolean? = false,
    ) = LineInfo(
        phonePermission = true, simState = simState, simOperator = null, networkOperator = null,
        voiceState = voiceState, dataRat = dataRat, dataState = 2, dataEnabled = dataEnabled, airplane = airplane,
        roaming = false, signalType = "LTE", rsrp = -95, rsrq = -9, sinr = 12.0, dbm = -95, level = 3,
        earfcn = 1850, band = "B3（1.7GHz帯）", bandwidthsMhz = listOf(15.0),
    )
}
