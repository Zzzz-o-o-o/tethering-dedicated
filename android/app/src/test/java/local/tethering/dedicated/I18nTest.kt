package local.tethering.dedicated

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 表示の言語（英語・フランス語）。単体テストの既定は日本語なので、テストの中で切り替えて、終わったら戻す。
 * 英語・フランス語の表示に日本語が混ざらないこと、保存する値が言語で変わらないことを確かめる。
 */
class I18nTest {
    /** ひらがな・カタカナ・漢字・全角英数記号（空欄の記号「－」は言語によらず使うので除く） */
    private val japanese = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uff01-\\uff0c\\uff0e-\\uffef]")

    private val tokyo: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")

    private fun at(h: Int, m: Int = 0): Long = Calendar.getInstance(tokyo).apply {
        clear()
        set(2026, Calendar.OCTOBER, 17, h, m, 0)
    }.timeInMillis

    @After
    fun backToJapanese() {
        I18n.lang = Lang.JA
    }

    private fun noJapanese(what: String, s: String?) {
        assertFalse("${I18n.lang} $what: $s", s != null && japanese.containsMatchIn(s))
    }

    /** 英語とフランス語で [block] を実行する */
    private fun inEnglishAndFrench(block: () -> Unit) {
        for (l in listOf(Lang.EN, Lang.FR)) {
            I18n.lang = l
            block()
        }
    }

    @Test
    fun enumLabelsAreTranslated() {
        inEnglishAndFrench {
            val groups: List<Pair<String, List<String>>> = listOf(
                "Trigger" to Trigger.entries.map { it.label },
                "Anomaly" to Anomaly.entries.map { it.label },
                "DeviceAlert" to DeviceAlert.entries.map { it.label },
                "Overall" to Overall.entries.map { it.label },
                "Plug" to Plug.entries.map { it.label },
                "BaseMode" to BaseMode.entries.map { it.label },
                "Effective" to Effective.entries.map { it.label },
                "Decision" to Decision.entries.map { it.label },
                "Method" to Method.entries.map { it.label },
                "Why" to PlugPolicy.Why.entries.map { it.label },
                "AppKind" to AppTraffic.Kind.entries.map { it.label },
                "TetherReason" to TetherControl.Reason.entries.map { it.label },
                "IdleReason" to IdleMode.Reason.entries.map { it.label },
                "Grade" to SignalQuality.Grade.entries.map { it.label },
            )
            for ((name, labels) in groups) {
                labels.forEach { assertTrue("$name: 空欄", it.isNotBlank()) }
                labels.forEach { noJapanese(name, it) }
                assertEquals("$name: 同じ表示が重なっている $labels", labels.size, labels.toSet().size)
            }
        }
    }

    @Test
    fun settingsAndMessagesAreTranslated() {
        inEnglishAndFrench {
            for (d in Defs.ints) {
                noJapanese(d.key, d.label)
                noJapanese(d.key, d.help)
                noJapanese(d.key, d.range)
            }
            for (d in Defs.bools) {
                noJapanese(d.key, d.label)
                noJapanese(d.key, d.help)
            }
            for (d in Defs.texts) {
                noJapanese(d.key, d.label)
                noJapanese(d.key, d.help)
            }
            val errors = listOf(
                Defs.checkUrl("ftp://x"), Defs.checkUrl("http://"), Defs.checkRecheck("0"), Defs.checkQuietHours("x"),
                PlugUrls.checkSpec("ftp://x"), PlugUrls.checkHost("a b"), PlugUrls.checkKind("x"),
                ExtNotify.check("ftp://x"), ExtNotify.check("https://ntfy.sh/"),
            )
            errors.forEach { assertTrue("誤りの説明がない", it != null) }
            errors.forEach { noJapanese("check", it) }
            PlugUrls.kinds.forEach { noJapanese("plug kind", it.second) }
            val parsed = SettingsCodec.parse(mapOf(Defs.MODE_KEY to "x", "interval_normal_min" to 1, "http_check" to "yes", "check_url" to 5))
            assertEquals(4, parsed.errors.size)
            parsed.errors.forEach { noJapanese("restore", it) }
            val changes = SettingsCodec.describeChanges(
                mapOf(Defs.MODE_KEY to "auto", "http_check" to true, "ext_url" to "https://ntfy.sh/a"),
                mapOf(Defs.MODE_KEY to "saver", "http_check" to false, "ext_url" to ""),
            )
            assertEquals(3, changes.size)
            changes.forEach { noJapanese("changes", it) }
        }
    }

    @Test
    fun storedValuesStayTheSameInEveryLanguage() {
        I18n.lang = Lang.EN
        assertEquals("定期チェック", Trigger.SCHEDULED.key)
        assertEquals("Scheduled check", Trigger.SCHEDULED.label)
        assertEquals("日時", LogStore.HEADER.first())
        val row = LogStore.fields(at(12), Trigger.SCHEDULED.key, StateStore.State(), false, null, null, null, null)
        assertEquals("ログのイベント名は保存用の値", "定期チェック", row[LogStore.IDX_EVENT])
        val p = PowerRow(
            "2026-10-17 10:00:00", "2026-10-17 11:00:00", 3600, true, Method.COUNTER, 80, 70, 400.0, 400.0, 3800, 1520.0, 30.0, 0,
            true, 0, -100, 5.0, "B3 (1.7 GHz)", 2048, "Normal", null, null,
        )
        val fields = p.fields()
        assertEquals(PowerRow.BATTERY, fields[3])
        assertEquals(Method.COUNTER.key, fields[4])
        assertEquals("読み戻せる", p, PowerRow.parse(fields.map { it.orEmpty() }))
        // 書き出しのときだけ今の言語にする
        val exported = PowerRow.exportRow(fields.map { it.orEmpty() })
        assertEquals("On battery", exported[3])
        assertEquals(Method.COUNTER.label, exported[4])
        assertEquals(PowerRow.HEADER.size, PowerRow.exportHeader.size)
        assertEquals(LogStore.HEADER.size, LogStore.exportHeader.size)
        assertEquals("Scheduled check", LogStore.exportRow(row.map { it.orEmpty() })[LogStore.IDX_EVENT])
        assertTrue(Ev.NOTIFIED.all.containsAll(listOf("通知", "Notified", "Notifié")))
        assertEquals("Notified", Ev.NOTIFIED.get())
    }

    @Test
    fun eventNamesAreShownInTheCurrentLanguage() {
        inEnglishAndFrench {
            for (k in Ev.recentKinds + Ev.EXPORT + Ev.checkKinds) noJapanese(k, Ev.display(k))
            assertEquals("知らない名前はそのまま", "x", Ev.display("x"))
        }
        I18n.lang = Lang.FR
        assertEquals("Problème confirmé", Ev.display(Ev.CONFIRMED))
        assertEquals(Trigger.BOOT.label, Ev.display(Trigger.BOOT.key))
        I18n.lang = Lang.JA
        assertEquals("日本語は保存した名前のまま", Ev.CONFIRMED, Ev.display(Ev.CONFIRMED))
    }

    @Test
    fun numbersAndUnitsFollowTheLanguage() {
        assertEquals("1.5 MB", Fmt.mb(1.5))
        assertEquals("45.2℃", Fmt.temp(45.2))
        assertEquals("20%", Fmt.pct(20))
        assertEquals("約5分", Fmt.duration(5 * 60_000L))
        assertEquals("約2時間5分", Fmt.approx(125 * 60_000L))
        assertEquals("（x）", Fmt.paren("x"))
        I18n.lang = Lang.EN
        assertEquals("1.5 MB", Fmt.mb(1.5))
        assertEquals("45.2°C", Fmt.temp(45.2))
        assertEquals("about 5 min", Fmt.duration(5 * 60_000L))
        assertEquals("about 2 h 5 min", Fmt.approx(125 * 60_000L))
        assertEquals(" (x)", Fmt.paren("x"))
        assertEquals("B3 (1.7 GHz)", Bands.lteBand(1300)!!.label)
        assertEquals("1:00–6:00", QuietHours(1, 6).label)
        assertEquals("4G/3G/2G (auto)", NetworkModes.label(20))
        assertEquals("4G only", NetworkModes.label(11))
        assertEquals("5–240 min", Defs.INTERVAL_NORMAL.range)
        I18n.lang = Lang.FR
        assertEquals("1,5 Mo", Fmt.mb(1.5))
        assertEquals("45,2\u00A0°C", Fmt.temp(45.2))
        assertEquals("20\u00A0%", Fmt.pct(20))
        assertEquals("environ 5 min", Fmt.duration(5 * 60_000L))
        assertEquals("B3 (1,7 GHz)", Bands.lteBand(1300)!!.label)
        assertEquals("4G uniquement", NetworkModes.label(11))
        assertEquals("Label\u00A0: texte", SettingsCodec.issue("Label", "texte"))
        assertEquals("約物の前後は改行しない空白", "«\u00A0x\u00A0» a\u00A0: b\u00A0; c\u00A0?", I18n.frenchSpacing("« x » a : b ; c ?"))
        assertEquals("バンドの番号は言語によらず取り出せる", "B3", Bands.key("B3 (1,7 GHz)"))
        assertEquals("B3", Bands.key("B3（1.7GHz帯）"))
    }

    @Test
    fun bandsRecordedInDifferentLanguagesAreGroupedTogether() {
        fun row(band: String, sec: Long) = PowerRow(
            "2026-10-17 10:00:00", "2026-10-17 11:00:00", sec, true, Method.COUNTER, 80, 70, 100.0, 100.0, 3800, 380.0, 30.0, 0,
            true, 0, -100, 5.0, band, 0, "Normal", null, null,
        )
        val groups = SignalPower.byBand(listOf(row("B3（1.7GHz帯）", 3600), row("B3 (1.7 GHz)", 1800)))
        assertEquals(1, groups.size)
        assertEquals("新しい記録の表示名", "B3 (1.7 GHz)", groups[0].label)
        assertEquals(5400L, groups[0].sec)
    }

    @Test
    fun textsBuiltByTheAppHaveNoJapanese() {
        inEnglishAndFrench {
            for (a in Anomaly.entries + listOf(null)) noJapanese("diagnosis $a", Diagnosis.text(a, null, 503, "x"))
            noJapanese("tether", TetherControl.Result(TetherControl.Reason.NO_PERMISSION, "x").text)
            noJapanese("web", WebClient.Result(null, null).text)
            noJapanese("web", WebClient.Result(204, null).text)
            noJapanese("guide", ClientGuide.INTRO)
            noJapanese("guide", ClientGuide.CHECK)
            for (s in ClientGuide.sections) {
                noJapanese("guide", s.title)
                s.steps.forEach { noJapanese("guide", it) }
                noJapanese("guide", s.note)
            }
            val keys = Candidates.all.map { it.key }
            for (c in Candidates.all) {
                listOf(c.title, c.labelA, c.labelB, c.toA, c.toB).forEach { noJapanese("candidate ${c.key}", it) }
            }
            I18n.lang.let { l ->
                I18n.lang = Lang.JA
                assertEquals("候補のキーは言語によらず同じ", keys, Candidates.all.map { it.key })
                I18n.lang = l
            }
            val running = StateStore.State(running = true, upstream = StateStore.EpisodeView(Anomaly.DNS, at(11), 1, 3, false))
            val (title, text) = Notifier.ongoingText(running)
            noJapanese("ongoing", title)
            noJapanese("ongoing", text)
        }
    }

    @Test
    fun webPagesFollowTheLanguage() {
        inEnglishAndFrench {
            val check = CheckResult(
                at(11, 50), Trigger.SCHEDULED, Tri.OK, Tri.OK, Tri.OK, Tri.OK, Tri.OK, 204, 80, true, ConnectivityProbe.ROUTE_CELLULAR, null,
                Diagnosis.text(null, null, null, null), "",
            )
            val state = StateStore.State(
                running = true, check = check, nextCheckAt = at(12, 15),
                recent = listOf(RecentEvent(at(11), Ev.CONFIRMED, ""), RecentEvent(at(10), Trigger.BOOT.key, "")),
            )
            val rows = listOf(
                PowerRow(
                    "2026-10-17 10:00:00", "2026-10-17 11:00:00", 3600, true, null, null, null, null, null, null, null, null, 0, true, 0, null, null, null,
                    2048, "Normal", null, null,
                ),
            )
            val day = TrafficMath.day(rows, "20261017", tokyo)
            val log = listOf(LogStore.fields(at(11), Trigger.SCHEDULED.key, StateStore.State(), false, null, null, null, null).map { it.orEmpty() })
            val data = WebPages.Data(at(12), "0.6.0", state, null, day, log, QuietHours(1, 6), null)
            val pages = listOf(
                WebPages.statusPage(data), WebPages.logPage(data), WebPages.trafficPage(data), WebPages.guidePage(),
                String(WebPages.route("/nothing") { data }.body, Charsets.UTF_8), WebPages.statusJson(data),
            )
            pages.forEach { noJapanese("page", it) }
            assertTrue(pages[0].contains("<html lang=\"${I18n.lang.code}\""))
            assertTrue(pages[5].contains("\"overallKey\":\"OK\""))
        }
        I18n.lang = Lang.JA
        assertTrue("検出のしかたが正しいこと（日本語のページでは見つかる）", japanese.containsMatchIn(WebPages.guidePage()))
    }
}
