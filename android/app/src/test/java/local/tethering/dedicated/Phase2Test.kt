package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.TimeZone

private val TOKYO: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")
private const val MIN = 60_000L
private const val HOUR = 3_600_000L

/** 東京時間の 2026-10-17 [h]:[m] */
private fun at(h: Int, m: Int = 0, day: Int = 17): Long = Calendar.getInstance(TOKYO).apply {
    clear()
    set(2026, Calendar.OCTOBER, day, h, m, 0)
}.timeInMillis

class SignalQualityTest {
    @Test
    fun scoresFromRsrpAndSinr() {
        // 実機（UMIDIGI・B41）の値: RSRP −98 dBm・SINR 22 dB
        assertEquals(69, SignalQuality.score(-98, 22.0))
        assertEquals(SignalQuality.Grade.GOOD, SignalQuality.grade(69))
        assertEquals(100, SignalQuality.score(-70, 30.0))
        assertEquals(0, SignalQuality.score(-130, -10.0))
        assertEquals("SINR が取れなければ RSRP だけで", 50, SignalQuality.score(-100, null))
        assertNull(SignalQuality.score(null, null))
        assertEquals(SignalQuality.Grade.BAD, SignalQuality.grade(19))
        assertEquals(300, SignalQuality.toneHz(0))
        assertEquals(1500, SignalQuality.toneHz(100))
    }
}

class SignalWatchTest {
    private val abs = SignalWatch.Limits(enabled = true, weakRsrp = -110, dropDb = 10, baselineRsrp = null)

    @Test
    fun needsTwoBadChecksAndClearsWithMargin() {
        val w = SignalWatch()
        assertNull(w.evaluate(-112, abs))
        assertEquals("2 回続けて弱いと通知", true, w.evaluate(-113, abs))
        assertNull("通知は 1 回だけ", w.evaluate(-115, abs))
        assertNull("しきい値をわずかに上回っただけでは解除しない", w.evaluate(-108, abs))
        assertEquals(false, w.evaluate(-104, abs))
        assertFalse(w.active)
    }

    @Test
    fun aSingleDipIsIgnoredAndUnknownIsNotJudged() {
        val w = SignalWatch()
        assertNull(w.evaluate(-115, abs))
        assertNull(w.evaluate(-100, abs))
        assertNull(w.evaluate(-115, abs))
        assertNull("取得不可は判定しない（連続にも数えない）", w.evaluate(null, abs))
        assertEquals(true, w.evaluate(-116, abs))
    }

    @Test
    fun comparesWithTheBaselinePlace() {
        val l = abs.copy(baselineRsrp = -90)
        val w = SignalWatch()
        assertNull(w.evaluate(-101, l))
        assertEquals("設置時より 10 dB 以上悪い状態が続いた", true, w.evaluate(-100, l))
        assertNull("設置時 −10 dB からの戻りは 3 dB の余裕が要る", w.evaluate(-98, l))
        assertEquals(false, w.evaluate(-96, l))
    }

    @Test
    fun disablingClearsTheAlert() {
        val w = SignalWatch()
        w.evaluate(-120, abs)
        w.evaluate(-120, abs)
        assertTrue(w.active)
        assertEquals(false, w.evaluate(-120, abs.copy(enabled = false)))
        assertNull(w.evaluate(-120, abs.copy(enabled = false)))
    }
}

class PlacesTest {
    private fun s(rsrp: Int?, sinr: Double? = 10.0, band: String? = "B41（2.5GHz帯）") = SignalSample(rsrp, sinr, -10, band)

    @Test
    fun summarizesWithTheMedian() {
        val samples = listOf(s(-100), s(-98), s(-60), s(-99), s(-97), s(null), s(-98, band = "B8（900MHz帯）"))
        val p = Places.summarize("窓際", 1L, samples)!!
        assertEquals("一瞬の大きな値（−60）に引きずられない", -98, p.rsrp)
        assertEquals(6, p.samples)
        assertEquals("B41（2.5GHz帯）", p.band)
        assertEquals(SignalQuality.score(-98, 10.0), p.score)
        assertNull("5 回分に満たなければ記録しない", Places.summarize("x", 1L, listOf(s(-90), s(-90), s(null))))
    }

    @Test
    fun roundTripsAndPicksTheBest() {
        val a = Place(10, "棚の上, \"北側\"", 20, -95, 18.5, -9, "B41（2.5GHz帯）", 77)
        val b = Place(20, "改行\nあり", 18, -105, null, null, null, 45)
        val back = Places.decode(Places.encode(listOf(a, b)))
        assertEquals(a, back[0])
        assertEquals("名前の改行は取り除く", "改行 あり", back[1].name)
        assertEquals(a, Places.best(back))
        assertTrue(Places.decode(null).isEmpty())
    }
}

class QuietHoursTest {
    @Test
    fun parsesAndContains() {
        assertEquals(QuietHours(1, 6), QuietHours.parse("1-6"))
        assertEquals(QuietHours(23, 5), QuietHours.parse(" 23 〜 5 "))
        assertEquals("日本語入力の長音記号でも", QuietHours(1, 6), QuietHours.parse("1ー6"))
        assertEquals(QuietHours(22, 0), QuietHours.parse("22-24"))
        assertEquals("全角は設定画面で半角にしてから渡す", "1-6", Fmt.halfWidth("１－６"))
        assertNull(QuietHours.parse(""))
        assertNull(QuietHours.parse("6-6"))
        assertNull(QuietHours.parse("25-3"))
        assertNull(QuietHours.parse("1時-6時"))
        val q = QuietHours(23, 5)
        assertTrue(q.contains(23) && q.contains(0) && q.contains(4))
        assertFalse(q.contains(5) || q.contains(12))
        assertNull("空欄は「設定しない」", Defs.checkQuietHours(""))
        assertNotNull(Defs.checkQuietHours("1"))
    }

    @Test
    fun windowsCrossMidnight() {
        val q = QuietHours(23, 5)
        val w = q.windowsOverlapping(at(22), at(23, 30), TOKYO)
        assertEquals(listOf(at(23) to at(5, day = 18)), w)
        val morning = q.windowsOverlapping(at(4), at(6), TOKYO)
        assertEquals(listOf(at(23, day = 16) to at(5)), morning)
        assertTrue(q.windowsOverlapping(at(6), at(22), TOKYO).isEmpty())
    }
}

class TrafficWatchTest {
    private val lim = TrafficWatch.Limits(idleMbPerHour = 5, quietMb = 50, quiet = null)

    @Test
    fun idleTrafficNeedsEnoughTimeAndRate() {
        val w = TrafficWatch()
        // 接続端末なしの 15 分で 3 MB（12 MB/時）だが、まだ 15 分しか見ていない
        assertTrue(w.add(TrafficWatch.Seg(at(10), at(10, 15), 3 * 1024, idle = true), lim, TOKYO).isEmpty())
        val f = w.add(TrafficWatch.Seg(at(10, 15), at(10, 30), 3 * 1024, idle = true), lim, TOKYO).single() as TrafficWatch.Finding.Idle
        assertEquals(6.0, f.mb, 1e-6)
        assertEquals(12.0, f.mbPerHour, 1e-6)
        assertEquals(at(10), f.fromMs)
        assertTrue("6 時間は重ねて知らせない", w.add(TrafficWatch.Seg(at(10, 30), at(10, 45), 9 * 1024, idle = true), lim, TOKYO).isEmpty())
        assertEquals(1, w.add(TrafficWatch.Seg(at(16, 30), at(17, 0), 9 * 1024, idle = true), lim, TOKYO).size)
    }

    @Test
    fun trafficWithClientsOrSlowTrafficIsNotIdleAlert() {
        val w = TrafficWatch()
        assertTrue(w.add(TrafficWatch.Seg(at(10), at(11), 500 * 1024, idle = false), lim, TOKYO).isEmpty())
        assertTrue("1 時間 2 MB はしきい値（5 MB/時）未満", w.add(TrafficWatch.Seg(at(11), at(12), 2 * 1024, idle = true), lim, TOKYO).isEmpty())
        assertTrue("0 は通知しない設定", w.add(TrafficWatch.Seg(at(12), at(13), 900 * 1024, idle = true), lim.copy(idleMbPerHour = 0), TOKYO).isEmpty())
    }

    @Test
    fun quietHoursAreSummedAfterTheyEnd() {
        val l = lim.copy(idleMbPerHour = 0, quiet = QuietHours(1, 6))
        val w = TrafficWatch()
        // 0:30〜1:30 の 60 MB のうち、1:00 以降の半分（30 MB）だけが時間帯に入る
        assertTrue(w.add(TrafficWatch.Seg(at(0, 30), at(1, 30), 60 * 1024, idle = false), l, TOKYO).isEmpty())
        assertTrue("時間帯の途中では知らせない（夜中に通知で起こさない）", w.add(TrafficWatch.Seg(at(1, 30), at(5, 30), 20 * 1024, idle = true), l, TOKYO).isEmpty())
        val f = w.add(TrafficWatch.Seg(at(5, 30), at(6, 30), 20 * 1024, idle = false), l, TOKYO).single() as TrafficWatch.Finding.Quiet
        assertEquals(30.0 + 20.0 + 10.0, f.mb, 1e-6)
        assertEquals(20.0, f.idleMb, 1e-6)
        assertEquals(at(1), f.fromMs)
        assertEquals(at(6), f.toMs)
        assertTrue(w.add(TrafficWatch.Seg(at(6, 30), at(7), 99 * 1024, idle = false), l, TOKYO).isEmpty())
    }

    @Test
    fun smallQuietTrafficIsNotReported() {
        val l = lim.copy(idleMbPerHour = 0, quiet = QuietHours(1, 6))
        val w = TrafficWatch()
        w.add(TrafficWatch.Seg(at(1), at(6), 40 * 1024, idle = false), l, TOKYO)
        assertTrue(w.add(TrafficWatch.Seg(at(6), at(7), 1, idle = false), l, TOKYO).isEmpty())
    }
}

class TrafficMathTest {
    private fun row(start: String, end: String, kb: Long?, tether: Boolean? = true, clients: Int? = 1) = PowerRow(
        start, end, 0, true, null, null, null, null, null, null, null, null, 0, tether, clients, null, null, null, kb, "省電力", null, null,
    )

    @Test
    fun splitsSegmentsByHourAndDay() {
        val rows = listOf(
            row("2026-10-16 23:30:00", "2026-10-17 00:30:00", 600),
            row("2026-10-17 10:15:00", "2026-10-17 10:45:00", 300, clients = 0),
            row("2026-10-17 11:00:00", "2026-10-17 12:00:00", 100, tether = false, clients = null),
            row("2026-10-17 13:00:00", "2026-10-17 14:00:00", null),
        )
        val d = TrafficMath.day(rows, "20261017", TOKYO)
        assertEquals("前日 23:30 からの区間は 0 時台に半分だけ", 300.0, d.hours[0].kb, 1e-6)
        assertEquals(300.0, d.hours[10].kb, 1e-6)
        assertEquals("接続 0 台の区間", 300.0, d.hours[10].idleKb, 1e-6)
        assertEquals("テザリング OFF の区間も接続端末なし", 100.0, d.hours[11].idleKb, 1e-6)
        assertEquals(700.0, d.kb, 1e-6)
        assertEquals(400.0, d.idleKb, 1e-6)
        assertEquals(300.0, d.kbIn(QuietHours(0, 6)), 1e-6)
        val prev = TrafficMath.day(rows, "20261016", TOKYO)
        assertEquals(300.0, prev.hours[23].kb, 1e-6)
        assertEquals(24, TrafficMath.csvRows(d).size)
        assertEquals(listOf("2026-10-17", "10", "300", "300"), TrafficMath.csvRows(d)[10])
    }
}

class HttpTest {
    @Test
    fun parsesRequestLines() {
        assertEquals(Http.Request("GET", "/status.json"), Http.parseRequestLine("GET /status.json?x=1 HTTP/1.1"))
        assertEquals(Http.Request("HEAD", "/"), Http.parseRequestLine("HEAD / HTTP/1.0\r"))
        assertNull(Http.parseRequestLine("GET http://example.com/ HTTP/1.1"))
        assertNull(Http.parseRequestLine("GET /"))
        assertNull(Http.parseRequestLine("get / HTTP/1.1"))
        assertNull(Http.parseRequestLine(null))
    }

    @Test
    fun readsOnlyTheHead() {
        val head = Http.readHead(ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n\r\nBODY".toByteArray()))!!
        assertTrue(head.endsWith("\r\n\r\n"))
        assertFalse(head.contains("BODY"))
        assertNull("途中で切れた", Http.readHead(ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray())))
        assertNull("長すぎる", Http.readHead(ByteArrayInputStream(ByteArray(20_000) { 'a'.code.toByte() })))
    }

    @Test
    fun checksSubnetsAndHeaders() {
        val ap = byteArrayOf(192.toByte(), 168.toByte(), 43, 167.toByte())
        assertTrue(Http.sameSubnet(byteArrayOf(192.toByte(), 168.toByte(), 43, 20), ap, 24))
        assertFalse(Http.sameSubnet(byteArrayOf(192.toByte(), 168.toByte(), 44, 20), ap, 24))
        assertFalse(Http.sameSubnet(byteArrayOf(10, 0, 0, 1), ap, 24))
        assertTrue(Http.sameSubnet(byteArrayOf(192.toByte(), 168.toByte(), 43, 200.toByte()), ap, 25))
        assertFalse(Http.sameSubnet(byteArrayOf(192.toByte(), 168.toByte(), 43, 20), ap, 25))
        val h = String(Http.headerBytes(Http.html(200, "あ")), Charsets.ISO_8859_1)
        assertTrue(h.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("UTF-8 のバイト数", h.contains("Content-Length: 3\r\n"))
        assertTrue(h.contains("Content-Security-Policy: default-src 'none'"))
        assertTrue(h.endsWith("\r\n\r\n"))
    }
}

class WebPagesTest {
    private fun data(state: StateStore.State = StateStore.State()) = WebPages.Data(
        now = at(12), version = "0.3.0", state = state, today = null, traffic = null, log = emptyList(), quiet = null, baseline = null,
    )

    private fun check(diagnosis: String) = CheckResult(
        at(11, 50), Trigger.SCHEDULED, Tri.OK, Tri.OK, Tri.OK, Tri.OK, Tri.OK, 204, 80, true, ConnectivityProbe.ROUTE_CELLULAR, null, diagnosis, "",
    )

    @Test
    fun escapesEverythingAndHasNoScripts() {
        val s = StateStore.State(running = true, check = check("<script>alert(1)</script>"))
        val html = WebPages.statusPage(data(s))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("http-equiv=\"refresh\""))
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", WebPages.esc("<a href=\"x\">&'"))
    }

    @Test
    fun routesPages() {
        assertEquals(200, WebPages.route("/") { data() }.status)
        assertEquals(200, WebPages.route("/log") { data() }.status)
        assertEquals(200, WebPages.route("/traffic") { data() }.status)
        val guide = WebPages.route("/guide") { throw AssertionError("ガイドはデータを使わない") }
        assertEquals(200, guide.status)
        assertTrue(String(guide.body, Charsets.UTF_8).contains("Windows 11"))
        assertEquals(404, WebPages.route("/settings") { data() }.status)
        assertTrue(WebPages.route("/status.json") { data() }.contentType.startsWith("application/json"))
    }

    @Test
    fun buildsJsonSafely() {
        assertEquals("{\"a\":\"x\\\"y\\n\\u003c\",\"b\":null,\"c\":[1,true],\"d\":{\"e\":1.5}}", Json.obj("a" to "x\"y\n<", "b" to null, "c" to listOf(1, true), "d" to Json.Raw(Json.obj("e" to 1.5))))
        assertEquals("null", Json.value(Double.NaN))
        val j = WebPages.statusJson(data(StateStore.State(running = true, check = check("正常"))))
        assertTrue(j.startsWith("{\"app\":\"local.tethering.dedicated\""))
        assertTrue(j.contains("\"ok\":true"))
    }

    @Test
    fun drawsTrafficBars() {
        val rows = listOf(
            PowerRow("2026-10-17 10:00:00", "2026-10-17 11:00:00", 3600, true, null, null, null, null, null, null, null, null, 0, true, 0, null, null, null, 2048, "省電力", null, null),
        )
        val day = TrafficMath.day(rows, "20261017", TOKYO)
        val svg = WebPages.bars(day)
        assertEquals("接続端末なしの棒が 1 本", 1, Regex("class=\"i\"").findAll(svg).count())
        val page = WebPages.trafficPage(data().let { WebPages.Data(it.now, it.version, it.state, null, day, emptyList(), QuietHours(1, 6), null) })
        assertTrue(page.contains("2.0 MB"))
    }
}
