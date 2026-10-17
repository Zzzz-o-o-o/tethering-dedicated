package local.tethering.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Calendar
import java.util.TimeZone

private val JST: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")

/** 2026-10-17（[day] 日）の [h]:[m]（日本時間）のミリ秒 */
private fun at(h: Int, m: Int = 0, day: Int = 17): Long = Calendar.getInstance(JST).apply {
    clear()
    set(2026, Calendar.OCTOBER, day, h, m, 0)
}.timeInMillis

class QuietHoursMinutesTest {
    @Test
    fun parsesMinutesAndKeepsTheOldForm() {
        assertEquals(QuietHours(1, 6, 30, 0), QuietHours.parse("1:30-6:00"))
        assertEquals("全角のコロンと波ダッシュ", QuietHours(23, 5, 15, 45), QuietHours.parse("23：15〜5:45"))
        assertEquals("分を書かない形はこれまでどおり", QuietHours(1, 6), QuietHours.parse("1-6"))
        assertEquals("1:30〜6:00", QuietHours(1, 6, 30, 0).label)
        assertEquals("6:00", QuietHours(1, 6, 30, 0).endLabel)
        assertNull(QuietHours.parse("1:60-6"))
        assertNull(QuietHours.parse("24:00-1"))
        assertNull(QuietHours.parse("24:30-1"))
        assertNull("開始と終わりが同じ", QuietHours.parse("1:00-1:00"))
        assertNull(QuietHours.parse("1:5-6"))
    }

    @Test
    fun hourlyBucketsUseTheMiddleOfTheHour() {
        val q = QuietHours(1, 6, 30, 0)
        assertTrue(q.contains(1))
        assertTrue(q.contains(5))
        assertFalse(q.contains(6))
        assertFalse(q.contains(0))
        assertTrue(q.containsMinute(90))
        assertFalse(q.containsMinute(89))
        assertFalse(q.containsMinute(360))
    }

    @Test
    fun findsTheWindowAndTheNextStart() {
        val q = QuietHours(1, 6, 30, 0)
        assertNull(q.windowAt(at(1, 0), JST))
        assertEquals(at(1, 30), q.nextStart(at(1, 0), JST))
        assertEquals(at(1, 30) to at(6, 0), q.windowAt(at(3, 0), JST))
        assertEquals("終わりの時刻は含まない", null, q.windowAt(at(6, 0), JST))
        assertEquals("時間帯の後は翌日の開始", at(1, 30, day = 18), q.nextStart(at(7, 0), JST))
        val night = QuietHours(23, 5, 30, 0)
        assertEquals("日をまたぐ時間帯", at(23, 30, day = 16) to at(5, 0), night.windowAt(at(0, 10), JST))
    }
}

class TetherScheduleTest {
    private val q = QuietHours(1, 6)

    private fun input(
        now: Long,
        tetherOn: Boolean? = true,
        clients: Int? = 0,
        stopped: Long = 0L,
        handled: Long = 0L,
        hold: Boolean = false,
        enabled: Boolean = true,
        zeroFor: Long? = 3 * 3_600_000L,
    ) = TetherSchedule.Input(enabled, q, now, tetherOn, clients, zeroFor, stopped, handled, hold)

    private fun act(i: TetherSchedule.Input) = TetherSchedule.decide(i, JST).act

    @Test
    fun stopsOnlyWithNoClientsInsideTheWindow() {
        assertEquals(TetherSchedule.Act.NONE, act(input(at(0, 30))))
        val d = TetherSchedule.decide(input(at(1, 0)), JST)
        assertEquals(TetherSchedule.Act.STOP, d.act)
        assertEquals(at(1) to at(6), d.window)
        assertEquals("接続端末がいれば待つ", TetherSchedule.Act.WAIT, act(input(at(1, 0), clients = 2)))
        assertEquals("台数が分からなければ止めない", TetherSchedule.Act.WAIT, act(input(at(1, 0), clients = null)))
        assertEquals("0 台になってから 10 分たっていなければ待つ（ON にした直後・一時的に離れただけ）", TetherSchedule.Act.WAIT, act(input(at(1, 0), zeroFor = 9 * 60_000L)))
        assertEquals("0 台が続いているかが分からなければ待つ", TetherSchedule.Act.WAIT, act(input(at(1, 0), zeroFor = null)))
        assertEquals("10 分続いたら止める", TetherSchedule.Act.STOP, act(input(at(1, 0), zeroFor = TetherSchedule.MIN_ZERO_MS)))
        assertEquals("すでに OFF なら何もしない", TetherSchedule.Act.ALREADY_OFF, act(input(at(1, 0), tetherOn = false)))
        assertEquals("状態が分からなければ何もしない", TetherSchedule.Act.NONE, act(input(at(1, 0), tetherOn = null)))
        assertEquals("同じ時間帯では 2 回止めない（手で ON に戻したとき）", TetherSchedule.Act.NONE, act(input(at(2, 0), handled = at(1))))
        assertEquals("メンテナンス・比較テスト中は止めない", TetherSchedule.Act.NONE, act(input(at(2, 0), hold = true)))
        assertEquals("翌日の時間帯ではまた止める", TetherSchedule.Act.STOP, act(input(at(1, 0, day = 18), handled = at(1))))
        assertEquals("設定が OFF なら何もしない", TetherSchedule.Act.NONE, act(input(at(2, 0), enabled = false)))
    }

    @Test
    fun resumesOnlyWhatItStopped() {
        val stopped = at(1)
        assertEquals("時間帯の中は止めたまま", TetherSchedule.Act.NONE, act(input(at(3, 0), tetherOn = false, stopped = stopped, handled = stopped)))
        assertEquals("時間帯の終わりに再開", TetherSchedule.Act.START, act(input(at(6, 0), tetherOn = false, stopped = stopped, handled = stopped)))
        assertEquals("利用者が ON にしたら記録だけ消す", TetherSchedule.Act.CLEAR, act(input(at(3, 0), tetherOn = true, stopped = stopped, handled = stopped)))
        assertEquals("スケジュールを OFF にしたらすぐ再開", TetherSchedule.Act.START, act(input(at(3, 0), tetherOn = false, stopped = stopped, enabled = false)))
        assertEquals("時間帯を変えて止めた時間帯がなくなったら再開", TetherSchedule.Act.START, act(input(at(3, 0), tetherOn = false, stopped = at(0, 30))))
        assertEquals("再開の依頼中に状態が分からなくても再開を続ける", TetherSchedule.Act.START, act(input(at(6, 5), tetherOn = null, stopped = stopped)))
    }

    @Test
    fun schedulesTheNextDecision() {
        assertEquals("時間帯の外は次の開始", at(1), TetherSchedule.nextDue(true, q, at(0, 30), 0L, JST))
        assertEquals("時間帯の中は終わり", at(6), TetherSchedule.nextDue(true, q, at(2, 0), 0L, JST))
        assertEquals("止めている間は終わり", at(6), TetherSchedule.nextDue(true, q, at(2, 0), at(1), JST))
        assertEquals("止めた時間帯が見つからなければすぐ", at(2, 0), TetherSchedule.nextDue(true, q, at(2, 0), at(0, 30), JST))
        assertNull(TetherSchedule.nextDue(false, q, at(2, 0), 0L, JST))
        assertNull(TetherSchedule.nextDue(true, null, at(2, 0), 0L, JST))
        assertEquals(3, TetherSchedule.RETRY_MIN.size)
    }
}

class PlugPolicyTest {
    private val cfg = PlugPolicy.Config(low = 40, high = 80, hotC = 40, critC = 50, emergencyPct = 20)
    private val on = PlugPolicy.Want(true, PlugPolicy.Why.LOW)
    private val off = PlugPolicy.Want(false, PlugPolicy.Why.HIGH)

    private fun want(level: Int?, temp: Double?, prev: PlugPolicy.Want?) = PlugPolicy.want(cfg, level, temp, prev)

    @Test
    fun keepsTheLevelInsideTheRange() {
        assertEquals(on, want(40, 30.0, null))
        assertEquals(off, want(80, 30.0, null))
        assertNull("範囲の中で目標がなければ何もしない", want(60, 30.0, null))
        assertEquals("充電中は上限まで続ける", on, want(79, 30.0, on))
        assertEquals("放電中は下限まで続ける", off, want(41, 30.0, off))
        assertEquals("残量が分からなければ今のまま", on, want(null, 30.0, on))
        assertEquals("温度が分からなくても範囲の制御はする", off, want(85, null, on))
    }

    @Test
    fun stopsChargingWhenHotWithHysteresis() {
        val hot = want(60, 40.0, on)
        assertEquals(PlugPolicy.Want(false, PlugPolicy.Why.HOT), hot)
        assertEquals("3℃ 下がるまでは止めたまま", PlugPolicy.Why.HOT, want(60, 37.5, hot)?.why)
        val cooled = want(60, 36.9, hot)
        assertEquals("冷えたら通常の制御に戻る（放電中として下限まで）", off, cooled)
        assertEquals("冷えたあとはふつうのしきい値に戻る", off, want(60, 39.5, cooled))
        assertEquals("冷えて下限なら給電", on, want(40, 36.0, hot))
    }

    @Test
    fun crititalHeatAlwaysStopsButLowBatteryChargesWhenOnlyWarm() {
        assertEquals(PlugPolicy.Want(false, PlugPolicy.Why.CRIT), want(10, 50.0, on))
        val em = want(20, 45.0, PlugPolicy.Want(false, PlugPolicy.Why.HOT))
        assertEquals(PlugPolicy.Want(true, PlugPolicy.Why.EMERGENCY), em)
        assertEquals("少し充電するまで続ける", em, want(24, 45.0, em))
        assertEquals("5% 充電したら止める", PlugPolicy.Want(false, PlugPolicy.Why.HOT), want(25, 45.0, em))
    }

    @Test
    fun highIsAtLeastTenAboveLowAndStateRoundTrips() {
        assertEquals(50, PlugPolicy.high(cfg.copy(high = 45)))
        assertEquals(100, PlugPolicy.high(cfg.copy(low = 95, high = 96)))
        for (w in listOf(on, off, PlugPolicy.Want(true, PlugPolicy.Why.EMERGENCY))) assertEquals(w, PlugPolicy.decode(PlugPolicy.encode(w)))
        assertNull(PlugPolicy.encode(null))
        assertNull(PlugPolicy.decode("x"))
        assertNull(PlugPolicy.decode("2:LOW"))
    }
}

class PlugUrlsTest {
    private fun url(kind: String, host: String, on: Boolean, flip: Int = 0) = PlugUrls.request(kind, host, "", "", on, flip)?.target?.url

    @Test
    fun buildsPresetUrls() {
        assertEquals("http://192.168.43.50/rpc/Switch.Set?id=0&on=true", url(PlugUrls.SHELLY2, "192.168.43.50", true))
        assertEquals("http://192.168.43.50/rpc/Switch.Set?id=0&on=false&toggle_after=14400", url(PlugUrls.SHELLY2, "192.168.43.50", false, 14400))
        assertEquals("ON にはタイマーを付けない", "http://192.168.43.50/rpc/Switch.Set?id=0&on=true", url(PlugUrls.SHELLY2, "192.168.43.50", true, 14400))
        assertEquals("http://192.168.43.50/relay/0?turn=off&timer=600", url(PlugUrls.SHELLY1, "192.168.43.50", false, 600))
        assertEquals("http://plug.lan/cm?cmnd=Power%20On", url(PlugUrls.TASMOTA, "plug.lan", true))
        assertEquals("先頭の http:// と末尾の / は除く", "http://192.168.43.50:8080/relay/0?turn=on", url(PlugUrls.SHELLY1, "http://192.168.43.50:8080/", true))
        assertNull(url(PlugUrls.SHELLY2, "", true))
        assertNull(url("unknown", "192.168.43.50", true))
        assertTrue(PlugUrls.supportsFlipback(PlugUrls.SHELLY1))
        assertFalse(PlugUrls.supportsFlipback(PlugUrls.TASMOTA))
    }

    @Test
    fun movesCredentialsToTheHeader() {
        val r = PlugUrls.request(PlugUrls.SHELLY1, "admin:secret@192.168.43.50", "", "", true, 0)
        assertNotNull(r)
        assertEquals("http://192.168.43.50/relay/0?turn=on", r!!.target.url)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("admin:secret".toByteArray()), r.target.auth)
    }

    @Test
    fun customUrlsAndChecks() {
        val r = PlugUrls.request(PlugUrls.CUSTOM, "", "POST https://ha.example.com/api/webhook/abc", "http://x/off", true, 0)
        assertEquals("POST", r?.method)
        assertEquals("https://ha.example.com/api/webhook/abc", r?.target?.url)
        assertEquals("GET", PlugUrls.request(PlugUrls.CUSTOM, "", "", "http://x/off", false, 0)?.method)
        assertNull("URL が空なら送らない", PlugUrls.request(PlugUrls.CUSTOM, "", "", "", true, 0))
        assertNull(PlugUrls.checkSpec(""))
        assertNull(PlugUrls.checkSpec("post http://x/on"))
        assertNotNull(PlugUrls.checkSpec("ftp://x/on"))
        assertNull(PlugUrls.checkHost("192.168.43.50"))
        assertNull(PlugUrls.checkHost(""))
        assertNotNull(PlugUrls.checkHost("http://x/relay"))
        assertNotNull(PlugUrls.checkHost("a b"))
        assertNull(PlugUrls.checkKind(PlugUrls.TASMOTA))
        assertNotNull(PlugUrls.checkKind("x"))
    }

    @Test
    fun findsTheAddressToExcludeFromTheClientCount() {
        assertEquals("192.168.43.50", PlugUrls.ipv4(PlugUrls.SHELLY2, "192.168.43.50", ""))
        assertEquals("192.168.43.50", PlugUrls.ipv4(PlugUrls.SHELLY2, "admin:pw@192.168.43.50:80", ""))
        assertNull("ホスト名は除けない", PlugUrls.ipv4(PlugUrls.SHELLY2, "shelly.lan", ""))
        assertEquals("192.168.43.60", PlugUrls.ipv4(PlugUrls.CUSTOM, "", "http://192.168.43.60/cm?cmnd=Power%20On"))
    }
}

class ClientExcludeTest {
    @Test
    fun skipsExcludedAddresses() {
        val neigh = "192.168.43.50 dev ap0 lladdr aa:aa:aa:aa:aa:01 REACHABLE\n192.168.43.57 dev ap0 lladdr aa:aa:aa:aa:aa:02 STALE\n"
        assertEquals(2, Parsers.countNeigh(neigh, "ap0"))
        assertEquals(1, Parsers.countNeigh(neigh, "ap0", setOf("192.168.43.50")))
        val arp = listOf(
            "IP address       HW type     Flags       HW address            Mask     Device",
            "192.168.43.50    0x1         0x2         aa:aa:aa:aa:aa:01     *        ap0",
            "192.168.43.57    0x1         0x2         aa:aa:aa:aa:aa:02     *        ap0",
        )
        assertEquals(1, Parsers.countArp(arp, "ap0", setOf("192.168.43.50")))
    }
}

class LanTrafficTest {
    private val mb = 1024 * 1024L

    @Test
    fun countsNonMobileTraffic() {
        assertEquals("全体からモバイル回線の分を引いた残り", 300 * mb, LanTraffic.bytes(1000 * mb, 200 * mb, 800 * mb, 100 * mb))
        assertNull("取得できない値（-1）があれば null", LanTraffic.bytes(-1, 0, 0, 0))
        assertEquals("負にはしない", 0L, LanTraffic.bytes(10, 10, 20, 20))
    }

    @Test
    fun treatsTetheringTrafficAsAClient() {
        // 2026-10-17 実機: 待機中につながった PC の 444 MB を「接続端末なし」と数えていた
        assertEquals(1, LanTraffic.clients(0, true, 447 * mb))
        assertEquals("少しなら 0 台のまま", 0, LanTraffic.clients(0, true, 100 * 1024L))
        assertEquals("テザリング OFF の区間は変えない", 0, LanTraffic.clients(0, false, 447 * mb))
        assertEquals("数えた台数はそのまま", 2, LanTraffic.clients(2, true, 447 * mb))
        assertNull("数えられない機種（null）はそのまま", LanTraffic.clients(null, true, 447 * mb))
        assertEquals("読めなければそのまま", 0, LanTraffic.clients(0, true, null))
    }

    @Test
    fun recountsOnlyWhenTrafficFlowsWithZeroClients() {
        assertTrue(LanTraffic.shouldRecount(true, 0, 3 * mb, LanTraffic.RECOUNT_GAP_MS))
        assertFalse("直前に数え直した", LanTraffic.shouldRecount(true, 0, 3 * mb, 5 * 60_000L))
        assertFalse("1 台以上と数えている", LanTraffic.shouldRecount(true, 1, 3 * mb, LanTraffic.RECOUNT_GAP_MS))
        assertFalse("通信が少ない", LanTraffic.shouldRecount(true, 0, mb, LanTraffic.RECOUNT_GAP_MS))
        assertFalse("テザリング OFF", LanTraffic.shouldRecount(false, 0, 3 * mb, LanTraffic.RECOUNT_GAP_MS))
        assertFalse("台数を数えられない", LanTraffic.shouldRecount(true, null, 3 * mb, LanTraffic.RECOUNT_GAP_MS))
    }
}

class WebClientTest {
    @Test
    fun parsesUrls() {
        val t = WebClient.parse("https://user:p%40ss@example.com:8443/a/b?x=1&y=2")
        assertNotNull(t)
        assertEquals("https://example.com:8443/a/b?x=1&y=2", t!!.url)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:p@ss".toByteArray()), t.auth)
        assertEquals("example.com", t.host)
        assertNull(WebClient.parse("ftp://example.com/"))
        assertNull(WebClient.parse("example.com/a"))
        assertNull(WebClient.parse(""))
        assertNull(WebClient.parse("http://"))
    }

    @Test
    fun classifiesResults() {
        assertTrue(WebClient.Result(204, null).ok)
        assertTrue(WebClient.Result(404, null).permanent)
        assertFalse("混雑は送り直す", WebClient.Result(429, null).permanent)
        assertFalse(WebClient.Result(503, null).permanent)
        assertFalse("通信エラーは送り直す", WebClient.Result(null, "タイムアウト").permanent)
        assertEquals("タイムアウト", WebClient.Result(null, "タイムアウト").text)
    }
}

class ExtNotifyTest {
    @Test
    fun detectsTheService() {
        assertEquals(ExtNotify.Kind.NTFY, ExtNotify.parse("https://ntfy.sh/my-topic-123")?.kind)
        assertEquals("ntfy（ntfy.sh）", ExtNotify.parse("https://ntfy.sh/my-topic-123")?.label)
        assertNull("ntfy はトピックが必要", ExtNotify.parse("https://ntfy.sh/"))
        assertNotNull(ExtNotify.check("https://ntfy.sh/"))
        assertNull(ExtNotify.check(""))
        assertNull(ExtNotify.check("https://ntfy.sh/t"))
        assertEquals(ExtNotify.Kind.DISCORD, ExtNotify.parse("https://discord.com/api/webhooks/1/abc")?.kind)
        assertEquals("Webhook 以外の discord.com は ntfy 扱い", ExtNotify.Kind.NTFY, ExtNotify.parse("https://discord.com/channels/1")?.kind)
        assertEquals(ExtNotify.Kind.SLACK, ExtNotify.parse("https://hooks.slack.com/services/T0/B0/x")?.kind)
        val tok = ExtNotify.parse("https://:tk_abc@ntfy.example.com/t")
        assertEquals("https://ntfy.example.com/t", tok?.target?.url)
        assertEquals("空のユーザー名＋トークン（ntfy のアクセストークン）", "Basic " + Base64.getEncoder().encodeToString(":tk_abc".toByteArray()), tok?.target?.auth)
    }

    @Test
    fun buildsNtfyWithEncodedTitle() {
        val d = ExtNotify.parse("https://ntfy.sh/t")!!
        val r = ExtNotify.build(d, ExtNotify.Msg(at(6, 1), "回線が復旧しました", "止まっていた時間: 約17分", true), "A9 Pro")
        val title = r.headers.first { it.first == "Title" }.second
        assertTrue(title.startsWith("=?UTF-8?B?") && title.endsWith("?="))
        assertEquals("[A9 Pro] 回線が復旧しました", String(Base64.getDecoder().decode(title.removePrefix("=?UTF-8?B?").removeSuffix("?=")), Charsets.UTF_8))
        assertEquals("4", r.headers.first { it.first == "Priority" }.second)
        assertTrue(r.body.startsWith("止まっていた時間: 約17分\n"))
        assertTrue(r.contentType.startsWith("text/plain"))
        assertEquals("ASCII だけならそのまま", "Test", ExtNotify.header("Test"))
    }

    @Test
    fun buildsDiscordAndSlackJson() {
        val m = ExtNotify.Msg(at(6, 1), "テスト \"引用\"", "1 行目\n2 行目", false)
        val discord = ExtNotify.build(ExtNotify.parse("https://discord.com/api/webhooks/1/abc")!!, m, "A9 Pro")
        assertEquals("application/json", discord.contentType)
        assertTrue(discord.body.startsWith("{\"content\":\"**[A9 Pro] テスト \\\"引用\\\"**\\n1 行目\\n2 行目\\n"))
        assertTrue("メンションは無効", discord.body.contains("\"allowed_mentions\":{\"parse\":[]}"))
        val slack = ExtNotify.build(ExtNotify.parse("https://hooks.slack.com/services/T/B/x")!!, m, "A9 Pro")
        assertTrue(slack.body.startsWith("{\"text\":\"*[A9 Pro] テスト"))
    }

    @Test
    fun queueKeepsTheNewestWithinLimits() {
        val q = ExtNotify.Queue(maxItems = 3, maxAgeMs = 3_600_000L, maxPerHour = 2)
        val t0 = at(1)
        for (i in 1..4) q.add(ExtNotify.Msg(t0 + i, "m$i", "", false))
        assertEquals("古いものから捨てる", listOf("m2", "m3", "m4"), q.items.map { it.title })
        val first = q.next(t0 + 10)!!
        assertEquals("m2", first.title)
        q.done(first, t0 + 10)
        q.done(q.next(t0 + 11)!!, t0 + 11)
        assertNull("1 時間の上限に達したら送らない", q.next(t0 + 12))
        assertNull("1 時間たつと期限切れで捨てる", q.next(t0 + 3_600_000L + 20))
        assertTrue(q.items.isEmpty())
    }
}

class Phase4SettingsTest {
    @Test
    fun secretsAreNotShownOrExported() {
        assertEquals(setOf("ext_url", "plug_host", "plug_on_url", "plug_off_url"), Defs.secretKeys)
        val changes = SettingsCodec.describeChanges(
            mapOf(Defs.EXT_URL.key to "https://ntfy.sh/old-secret", Defs.PLUG_KIND.key to PlugUrls.SHELLY2),
            mapOf(Defs.EXT_URL.key to "https://ntfy.sh/new-secret", Defs.PLUG_KIND.key to PlugUrls.TASMOTA),
        )
        assertEquals(2, changes.size)
        assertTrue(changes.none { it.contains("secret") })
        assertTrue(changes.any { it.contains("Tasmota") })
        val parsed = SettingsCodec.parse(mapOf(Defs.PLUG_KIND.key to "bogus", Defs.PLUG_LOW.key to 5, Defs.TETHER_SCHEDULE.key to true))
        assertEquals("範囲外・不正な値は採用しない", 2, parsed.errors.size)
        assertEquals(true, parsed.values[Defs.TETHER_SCHEDULE.key])
    }

    @Test
    fun newExperimentCandidatesExist() {
        assertNotNull(Candidates.of("ap_5g"))
        assertNotNull(Candidates.of("ap_24g"))
        assertNotNull(Candidates.of("tether_offload"))
        assertFalse(Candidates.of("tether_offload")!!.auto)
    }
}
