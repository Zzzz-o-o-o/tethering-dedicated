package local.tethering.dedicated

import java.util.Locale

/**
 * ローカル管理画面（仕様 F-43）のページ。読み取り専用で、スクリプトは使わない（表示だけ）。
 * Android に依存しない（単体テストできるように、必要なデータは [Data] で受け取る）。
 */
object WebPages {
    class Data(
        val now: Long,
        val version: String,
        val state: StateStore.State,
        val today: DailyReport?,
        val traffic: TrafficMath.Day?,
        /** 今日の操作ログ（古い順。LogStore の列） */
        val log: List<List<String>>,
        val quiet: QuietHours?,
        val baseline: Place?,
    )

    fun route(path: String, data: () -> Data): Http.Response = when (path) {
        "/", "/index.html" -> Http.html(200, statusPage(data()))
        "/log" -> Http.html(200, logPage(data()))
        "/traffic" -> Http.html(200, trafficPage(data()))
        "/guide" -> Http.html(200, guidePage())
        "/status.json" -> Http.json(statusJson(data()))
        else -> Http.html(404, page("見つかりません", "<div class=\"card\"><p>このページはありません。</p></div>", null))
    }

    fun esc(s: String?): String = s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    // ---- ページ ----

    private const val CSS =
        ":root{--bg:#f2f4f5;--card:#fff;--line:#dde2e5;--text:#1b1f22;--sub:#5c656b;--ok:#2e7d32;--warn:#b95c00;--err:#c62828;" +
            "--maint:#1565c0;--off:#6b7378;--brand:#0b6e69;--chip:#fff}" +
            "@media (prefers-color-scheme:dark){:root{--bg:#111416;--card:#1c2023;--line:#2c3236;--text:#e6e9eb;--sub:#a3acb2;" +
            "--ok:#81c784;--warn:#ffb74d;--err:#ef9a9a;--maint:#90caf9;--off:#9aa3a8;--brand:#4db6ac;--chip:#111416}}" +
            "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);" +
            "font:15px/1.55 system-ui,-apple-system,'Segoe UI','Hiragino Sans','Noto Sans JP',sans-serif}" +
            "header{background:var(--card);border-bottom:1px solid var(--line);padding:10px 16px}" +
            "header h1{margin:0;font-size:18px;color:var(--brand)}nav{margin-top:4px;font-size:14px}nav a{margin-right:14px;color:var(--brand)}" +
            "main{max-width:720px;margin:0 auto;padding:12px 16px 32px}" +
            ".card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px 14px;margin:0 0 10px}" +
            "h2{font-size:17px;margin:0 0 6px}p{margin:4px 0}.sub{color:var(--sub);font-size:13px}" +
            "table{width:100%;border-collapse:collapse}.kv th{color:var(--sub);font-weight:normal;text-align:left;padding:3px 8px 3px 0;" +
            "width:42%;vertical-align:top}.kv td{text-align:right;padding:3px 0;overflow-wrap:anywhere}" +
            ".chip{display:inline-block;padding:2px 12px;border-radius:14px;color:var(--chip);font-weight:bold;margin-bottom:6px}" +
            ".ok{background:var(--ok)}.warn{background:var(--warn)}.error{background:var(--err)}.maint{background:var(--maint)}.off{background:var(--off)}" +
            ".scroll{overflow-x:auto}.log{font-size:13px}.log th,.log td{border-bottom:1px solid var(--line);padding:4px 6px;text-align:left;vertical-align:top}" +
            ".log td:first-child{white-space:nowrap}ol{margin:4px 0;padding-left:22px}" +
            "svg .b{fill:var(--brand)}svg .i{fill:var(--warn)}svg .t{fill:var(--sub);font-size:10px}svg .a{stroke:var(--line)}" +
            "footer{max-width:720px;margin:0 auto;padding:0 16px 24px;color:var(--sub);font-size:12px}"

    private fun page(title: String, body: String, footer: String?, refreshSec: Int? = null): String = buildString {
        append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light dark\">")
        if (refreshSec != null) append("<meta http-equiv=\"refresh\" content=\"$refreshSec\">")
        append("<title>").append(esc(title)).append(" - テザリング専用化</title><style>").append(CSS).append("</style></head><body>")
        append("<header><h1>テザリング専用化</h1><nav><a href=\"/\">状態</a><a href=\"/traffic\">通信量</a><a href=\"/log\">記録</a>")
        append("<a href=\"/guide\">通信を減らす設定</a></nav></header><main>").append(body).append("</main>")
        append("<footer>読み取り専用の管理画面です（この画面から設定は変えられません）。")
        if (footer != null) append(esc(footer))
        append("</footer></body></html>")
    }

    private fun footer(d: Data) = " v${d.version} · ${Fmt.time(d.now)} 時点"

    private fun card(title: String?, inner: String) = buildString {
        append("<div class=\"card\">")
        if (title != null) append("<h2>").append(esc(title)).append("</h2>")
        append(inner).append("</div>")
    }

    private fun kv(rows: List<Pair<String, String?>>) = buildString {
        append("<table class=\"kv\">")
        for ((k, v) in rows) append("<tr><th>").append(esc(k)).append("</th><td>").append(esc(v ?: Fmt.NA)).append("</td></tr>")
        append("</table>")
    }

    private fun chipClass(o: Overall) = when (o) {
        Overall.OK -> "ok"
        Overall.WARN -> "warn"
        Overall.ERROR -> "error"
        Overall.MAINT -> "maint"
        Overall.STOPPED, Overall.UNKNOWN -> "off"
    }

    /** 画面の見出し（ダッシュボードと同じ考え方） */
    fun headline(s: StateStore.State, now: Long): String {
        val up = s.upstream
        return when {
            !s.running -> "監視は停止しています"
            s.maintUntil > now -> "メンテナンス中（〜${Fmt.hm(s.maintUntil)}）"
            up != null && up.confirmed -> "異常：${up.kind.label}（${Fmt.time(up.startedAt)}から）"
            up != null -> "確認中：${up.kind.label}（再確認 ${up.rechecks}/${up.total}）"
            s.tetherIssue != null -> s.tetherIssue.kind.label
            s.check != null -> s.check.diagnosis
            else -> "最初のチェックを実行しています"
        }
    }

    private fun mb(kb: Double?) = kb?.let { String.format(Locale.US, "%.1f MB", it / 1024) }

    fun statusPage(d: Data): String {
        val s = d.state
        val o = s.overall(d.now)
        val body = StringBuilder()
        body.append(
            card(
                null,
                "<span class=\"chip ${chipClass(o)}\">${esc(o.label)}</span><p><b>${esc(headline(s, d.now))}</b></p>" +
                    "<p class=\"sub\">" + esc(
                        listOfNotNull(
                            s.check?.let { "最終チェック ${Fmt.time(it.at)}（${it.trigger.label}）" },
                            if (s.running && s.nextCheckAt > 0) "次回 ${Fmt.hm(s.nextCheckAt)}" else null,
                        ).joinToString(" · "),
                    ) + "</p>" +
                    if (s.alerts.isNotEmpty()) "<p>⚠ " + esc(s.alerts.joinToString("・") { it.label }) + "</p>" else "",
            ),
        )
        val t = s.tether
        body.append(
            card(
                "テザリング",
                kv(
                    listOf(
                        "Wi-Fiテザリング" to t?.let { Fmt.ap(it.apState) },
                        "接続端末" to t?.clients?.let { "${it}台" },
                    ),
                ),
            ),
        )
        val l = s.line
        val score = l?.let { SignalQuality.score(it.rsrp, it.sinr) }
        body.append(
            card(
                "モバイル回線",
                kv(
                    listOf(
                        "電波" to l?.let { Fmt.signalSummary(it) },
                        "品質" to l?.let { "RSRQ ${Fmt.num(it.rsrq, " dB")} · SINR ${Fmt.db(it.sinr)}" },
                        "電波の点数" to score?.let { "$it 点（${SignalQuality.grade(it).label}）" },
                        "データ" to l?.let { Fmt.rat(it.dataRat) },
                        "設置時の記録" to d.baseline?.let { b -> "${b.name}：RSRP ${Fmt.num(b.rsrp, " dBm")}" },
                    ),
                ),
            ),
        )
        val c = s.check
        body.append(
            card(
                "接続性",
                if (c == null) "<p class=\"sub\">まだチェックしていません</p>" else kv(
                    listOf(
                        "① 回線登録" to c.registered.mark,
                        "② IP通信" to c.ip.mark,
                        "③ DNS" to c.dns.mark,
                        "④ インターネット" to c.http.mark + (c.httpMs?.let { "（$it ms）" } ?: ""),
                    ),
                ) + "<p class=\"sub\">${esc(c.diagnosis)}</p>",
            ),
        )
        val p = s.power
        val m = s.meter
        body.append(
            card(
                "電源",
                kv(
                    listOf(
                        "電池" to p?.let { Fmt.num(it.levelPct, "%") + if (it.charging == true) "・充電中" else "" },
                        "給電" to p?.plug?.label,
                        "電池温度" to Fmt.temp(p?.tempC),
                        "消費（直近）" to when {
                            p == null -> "－"
                            p.plug != Plug.NONE -> "給電中は計測しません"
                            m?.rateMa == null -> "計測中"
                            else -> (m.rateMw?.let { "${it.toInt()} mW（${m.rateMa.toInt()} mA）" } ?: "${m.rateMa.toInt()} mA")
                        },
                        "推定残り時間" to when {
                            p?.plug != Plug.NONE || m?.rateMa == null -> "－"
                            m.remainingMin == null -> "計算できません"
                            else -> "約" + Fmt.duration(m.remainingMin * 60_000L).removePrefix("約")
                        },
                    ),
                ),
            ),
        )
        val r = d.today
        if (r != null) {
            body.append(
                card(
                    "今日",
                    kv(
                        listOf(
                            "モバイル通信量" to (r.mobileMb?.let { String.format(Locale.US, "%.1f MB", it) } ?: Fmt.NA) +
                                (d.traffic?.let { "（うち接続端末なし ${mb(it.idleKb)}）" } ?: ""),
                            "チェック" to if (r.checks == 0) "－" else "${r.checksOk}/${r.checks} 成功・異常 ${r.anomalies}回",
                            "平均消費" to (r.avgMw?.let { "${it.toInt()} mW" } ?: r.avgMa?.let { "${it.toInt()} mA" } ?: "計測なし（バッテリー駆動の記録なし）"),
                        ),
                    ),
                ),
            )
        }
        val recent = s.recent.take(10)
        body.append(
            card(
                "直近の記録",
                if (recent.isEmpty()) "<p class=\"sub\">まだありません</p>" else recent.joinToString("") {
                    "<p>${esc(Fmt.time(it.at))}　${esc(it.kind)}${if (it.text.isNotEmpty()) "：" + esc(it.text) else ""}</p>"
                } + "<p class=\"sub\"><a href=\"/log\">今日の記録をすべて見る</a></p>",
            ),
        )
        body.append("<p class=\"sub\">この画面は 60 秒ごとに更新されます。状態は <a href=\"/status.json\">JSON</a> でも取得できます。</p>")
        return page("状態", body.toString(), footer(d), refreshSec = 60)
    }

    fun logPage(d: Data): String {
        val rows = d.log.takeLast(100).asReversed()
        val body = if (rows.isEmpty()) {
            card("今日の記録", "<p class=\"sub\">まだありません</p>")
        } else {
            card(
                "今日の記録（新しい順・最大 100 件）",
                "<div class=\"scroll\"><table class=\"log\"><tr><th>時刻</th><th>イベント</th><th>内容</th></tr>" +
                    rows.joinToString("") { c ->
                        val text = listOf(c.getOrNull(LogStore.IDX_ANOMALY), c.getOrNull(LogStore.IDX_RESULT), c.getOrNull(LogStore.IDX_ACTION))
                            .filter { !it.isNullOrBlank() }.joinToString(" · ")
                        "<tr><td>${esc(c.getOrNull(0)?.drop(11))}</td><td>${esc(c.getOrNull(LogStore.IDX_EVENT))}</td><td>${esc(text)}</td></tr>"
                    } + "</table></div>",
            )
        }
        return page("記録", body, footer(d))
    }

    /** 時間帯別の通信量の棒グラフ（SVG。橙色が接続端末なしの通信） */
    fun bars(day: TrafficMath.Day): String {
        val maxKb = day.hours.maxOf { it.kb }.takeIf { it > 0 } ?: 1.0
        val h = 120.0
        val sb = StringBuilder("<svg viewBox=\"0 0 336 136\" width=\"100%\" role=\"img\" aria-label=\"時間帯別の通信量\">")
        for (hr in day.hours) {
            val x = hr.hour * 14 + 2
            val total = hr.kb / maxKb * h
            val idle = hr.idleKb / maxKb * h
            if (total - idle > 0.05) sb.append(String.format(Locale.US, "<rect class=\"b\" x=\"%d\" y=\"%.1f\" width=\"10\" height=\"%.1f\"/>", x, h - total, total - idle))
            if (idle > 0.05) sb.append(String.format(Locale.US, "<rect class=\"i\" x=\"%d\" y=\"%.1f\" width=\"10\" height=\"%.1f\"/>", x, h - idle, idle))
            if (hr.hour % 6 == 0) sb.append("<text class=\"t\" x=\"$x\" y=\"132\">${hr.hour}時</text>")
        }
        sb.append("<line class=\"a\" x1=\"0\" y1=\"120\" x2=\"336\" y2=\"120\"/></svg>")
        return sb.toString()
    }

    fun trafficPage(d: Data): String {
        val day = d.traffic
        val body = StringBuilder()
        if (day == null || day.kb <= 0) {
            body.append(card("今日の通信量（時間帯別）", "<p class=\"sub\">まだ記録がありません（チェックの区切りごとに記録します）。</p>"))
        } else {
            val maxKb = day.hours.maxOf { it.kb }
            body.append(
                card(
                    "今日の通信量（時間帯別）",
                    "<p>合計 ${esc(mb(day.kb))}（うち接続端末なし ${esc(mb(day.idleKb))}）</p>" + bars(day) +
                        "<p class=\"sub\">いちばん多い時間帯 ${esc(mb(maxKb))}／時。緑 = 接続端末あり・不明、橙 = 接続端末なし（この端末自身の通信）。</p>",
                ),
            )
            val q = d.quiet
            if (q != null) body.append(card("使わない時間帯（${q.label}）", "<p>今日 ${esc(mb(day.kbIn(q)))}</p>"))
            body.append(
                card(
                    "時間帯ごとの値",
                    "<table class=\"log\"><tr><th>時</th><th>通信量</th><th>うち接続端末なし</th></tr>" +
                        day.hours.filter { it.kb > 0 }.joinToString("") { "<tr><td>${it.hour}時台</td><td>${esc(mb(it.kb))}</td><td>${esc(mb(it.idleKb))}</td></tr>" } +
                        "</table>",
                ),
            )
        }
        body.append(
            card(
                null,
                "<p class=\"sub\">通信量は、チェックなどで区切った区間ごとの値を時間で按分した目安です。使っていない時間帯の通信が多いときは、" +
                    "<a href=\"/guide\">接続端末の通信を減らす設定</a>を確認してください。</p>",
            ),
        )
        return page("通信量", body.toString(), footer(d))
    }

    fun guidePage(): String {
        val body = StringBuilder(card("接続端末の通信を減らす設定", "<p>${esc(ClientGuide.INTRO)}</p>"))
        for (s in ClientGuide.sections) {
            body.append(
                card(
                    s.title,
                    "<ol>" + s.steps.joinToString("") { "<li>${esc(it)}</li>" } + "</ol>" + (s.note?.let { "<p class=\"sub\">${esc(it)}</p>" } ?: ""),
                ),
            )
        }
        body.append(card(null, "<p class=\"sub\">${esc(ClientGuide.CHECK)}</p>"))
        return page("通信を減らす設定", body.toString(), null)
    }

    // ---- JSON ----

    fun statusJson(d: Data): String {
        val s = d.state
        val l = s.line
        val p = s.power
        val c = s.check
        val m = s.meter
        return Json.obj(
            "app" to "local.tethering.dedicated",
            "version" to d.version,
            "time" to Fmt.dateTime(d.now),
            "overall" to s.overall(d.now).label,
            "headline" to headline(s, d.now),
            "alerts" to s.alerts.map { it.label },
            "nextCheck" to s.nextCheckAt.takeIf { it > 0 }?.let { Fmt.dateTime(it) },
            "tether" to Json.Raw(Json.obj("state" to s.tether?.let { Fmt.ap(it.apState) }, "clients" to s.tether?.clients)),
            "mobile" to Json.Raw(
                Json.obj(
                    "type" to l?.signalType, "band" to l?.band, "rsrp" to l?.rsrp, "rsrq" to l?.rsrq, "sinr" to l?.sinr,
                    "level" to l?.level, "score" to l?.let { SignalQuality.score(it.rsrp, it.sinr) },
                ),
            ),
            "check" to c?.let {
                Json.Raw(
                    Json.obj(
                        "time" to Fmt.dateTime(it.at), "trigger" to it.trigger.label, "ok" to (it.failure == null),
                        "diagnosis" to it.diagnosis, "httpMs" to it.httpMs,
                    ),
                )
            },
            "power" to Json.Raw(
                Json.obj(
                    "level" to p?.levelPct, "plug" to p?.plug?.label, "tempC" to p?.tempC,
                    "rateMa" to m?.rateMa?.let { Math.round(it) }, "rateMw" to m?.rateMw?.let { Math.round(it) }, "remainingMin" to m?.remainingMin,
                ),
            ),
            "today" to d.today?.let { r ->
                Json.Raw(
                    Json.obj(
                        "mobileMb" to r.mobileMb?.let { Math.round(it * 10) / 10.0 },
                        "idleMb" to d.traffic?.let { Math.round(it.idleKb / 1024 * 10) / 10.0 },
                        "checks" to r.checks, "checksOk" to r.checksOk, "anomalies" to r.anomalies,
                    ),
                )
            },
        )
    }
}

/** JSON の組み立て（外部ライブラリを使わないための最小限のもの） */
object Json {
    /** そのまま埋め込む JSON の断片 */
    class Raw(val json: String)

    fun obj(vararg pairs: Pair<String, Any?>): String = pairs.joinToString(",", "{", "}") { (k, v) -> str(k) + ":" + value(v) }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.json
        is Boolean -> v.toString()
        is Double -> if (v.isNaN() || v.isInfinite()) "null" else v.toString()
        is Float -> if (v.isNaN() || v.isInfinite()) "null" else v.toString()
        is Number -> v.toString()
        is Collection<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }

    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch == '\u2028' || ch == '\u2029' || ch == '<' -> sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
