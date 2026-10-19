package local.tethering.dedicated

import java.util.Locale

/**
 * ローカル管理画面（仕様 F-43）のページ。読み取り専用で、スクリプトは使わない（表示だけ）。
 * Android に依存しない（単体テストできるように、必要なデータは [Data] で受け取る）。
 * 表示の言語は端末（アプリ）の言語に合わせる（状態の文言は端末で作ったものなので、ページだけ別の言語にはしない）。
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
        else -> Http.html(
            404,
            page(
                tr("見つかりません", "Not found", "Introuvable"),
                "<div class=\"card\"><p>" + esc(tr("このページはありません。", "This page doesn't exist.", "Cette page n'existe pas.")) + "</p></div>", null,
            ),
        )
    }

    fun esc(s: String?): String = s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** 訳文を escape したうえで、{link} をリンクに置き換える（語順が言語で違うため） */
    private fun withLink(text: String, href: String, label: String): String =
        esc(text).replace("{link}", "<a href=\"$href\">${esc(label)}</a>")

    // ---- 見出し・言い回し ----

    private fun appName() = tr("テザリング専用化", "Tethering Dedicated", "Tethering Dedicated")

    private fun navStatus() = tr("状態", "Status", "État")

    private fun navTraffic() = tr("通信量", "Traffic", "Trafic")

    private fun navLog() = tr("記録", "Log", "Journal")

    private fun navGuide() = tr("通信を減らす設定", "Reduce data use", "Réduire les données")

    private fun noneYet() = tr("まだありません", "None yet", "Aucun pour l'instant")

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
        append("<!doctype html><html lang=\"").append(I18n.lang.code).append("\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light dark\">")
        if (refreshSec != null) append("<meta http-equiv=\"refresh\" content=\"$refreshSec\">")
        append("<title>").append(esc(title)).append(" - ").append(esc(appName())).append("</title><style>").append(CSS).append("</style></head><body>")
        append("<header><h1>").append(esc(appName())).append("</h1><nav>")
        append("<a href=\"/\">").append(esc(navStatus())).append("</a><a href=\"/traffic\">").append(esc(navTraffic())).append("</a>")
        append("<a href=\"/log\">").append(esc(navLog())).append("</a><a href=\"/guide\">").append(esc(navGuide())).append("</a>")
        append("</nav></header><main>").append(body).append("</main><footer>")
        append(
            esc(
                tr(
                    "読み取り専用の管理画面です（この画面から設定は変えられません）。", "Read-only page (settings can't be changed from here).",
                    "Page en lecture seule (les paramètres ne peuvent pas être modifiés ici).",
                ),
            ),
        )
        if (footer != null) append(esc(footer))
        append("</footer></body></html>")
    }

    private fun footer(d: Data): String {
        val v = d.version
        val t = Fmt.time(d.now)
        return tr(" v$v · $t 時点", " v$v · as of $t", " v$v · à $t")
    }

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
            !s.running -> tr("監視は停止しています", "Monitoring is stopped", "La surveillance est arrêtée")
            s.maintUntil > now -> Fmt.hm(s.maintUntil).let { tr("メンテナンス中（〜$it）", "Maintenance (until $it)", "Maintenance (jusqu'à $it)") }
            up != null && up.confirmed -> {
                val k = up.kind.label
                val since = Fmt.time(up.startedAt)
                tr("異常：$k（${since}から）", "Error: $k (since $since)", "Erreur : $k (depuis $since)")
            }
            up != null -> {
                val k = up.kind.label
                val n = "${up.rechecks}/${up.total}"
                tr("確認中：$k（再確認 $n）", "Checking: $k (recheck $n)", "Vérification : $k (revérification $n)")
            }
            s.tetherIssue != null -> s.tetherIssue.kind.label
            s.check != null -> s.check.diagnosis
            else -> tr("最初のチェックを実行しています", "Running the first check", "Première vérification en cours")
        }
    }

    private fun mb(kb: Double?) = kb?.let { Fmt.mb(it / 1024) }

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
                            s.check?.let {
                                val at = Fmt.time(it.at)
                                val why = it.trigger.label
                                tr("最終チェック $at（$why）", "Last check $at ($why)", "Dernière vérification $at ($why)")
                            },
                            if (s.running && s.nextCheckAt > 0) Fmt.hm(s.nextCheckAt).let { tr("次回 $it", "Next $it", "Prochaine $it") } else null,
                            s.idleReason?.let { tr("待機中（$it）", "Idle ($it)", "En veille ($it)") },
                            s.auto?.takeIf { it.saverOn || it.dozeOn }?.let {
                                tr(
                                    "自動でバッテリーセーバー・Doze を調整中", "Battery Saver/Doze adjusted automatically",
                                    "Économiseur de batterie/Doze ajustés automatiquement",
                                )
                            },
                        ).joinToString(" · "),
                    ) + "</p>" +
                    if (s.alerts.isNotEmpty()) "<p>⚠ " + esc(Fmt.join(s.alerts.map { it.label })) + "</p>" else "",
            ),
        )
        val t = s.tether
        body.append(
            card(
                tr("テザリング", "Tethering", "Partage de connexion"),
                kv(
                    listOfNotNull(
                        tr("Wi-Fiテザリング", "Wi-Fi hotspot", "Point d'accès Wi-Fi") to t?.let { Fmt.ap(it.apState) },
                        tr("接続端末", "Connected devices", "Appareils connectés") to t?.clients?.let { tr("${it}台", "$it", "$it") },
                        s.sched?.let { sc ->
                            tr("時間帯スケジュール", "Quiet-hours schedule", "Planification") to (
                                sc.quiet?.let {
                                    tr("$it は止める（接続 0 台が 10 分続いたら）", "Off during $it (after 10 min with no devices)", "Arrêt pendant $it (après 10 min sans appareil)")
                                } ?: tr("時間帯が未設定", "Quiet hours not set", "Heures d'inactivité non définies")
                                )
                        },
                    ),
                ),
            ),
        )
        val l = s.line
        val score = l?.let { SignalQuality.score(it.rsrp, it.sinr) }
        body.append(
            card(
                tr("モバイル回線", "Mobile line", "Ligne mobile"),
                kv(
                    listOf(
                        tr("電波", "Signal", "Signal") to l?.let { Fmt.signalSummary(it) },
                        tr("品質", "Quality", "Qualité") to l?.let { "RSRQ ${Fmt.num(it.rsrq, " dB")} · SINR ${Fmt.db(it.sinr)}" },
                        tr("電波の点数", "Signal score", "Score du signal") to score?.let { Fmt.points(it) + Fmt.paren(SignalQuality.grade(it).label) },
                        tr("データ", "Data", "Données") to l?.let { Fmt.rat(it.dataRat) },
                        tr("設置時の記録", "Placement record", "Emplacement enregistré") to d.baseline?.let { b ->
                            val r = Fmt.num(b.rsrp, " dBm")
                            tr("${b.name}：RSRP $r", "${b.name}: RSRP $r", "${b.name} : RSRP $r")
                        },
                    ),
                ),
            ),
        )
        val c = s.check
        body.append(
            card(
                tr("接続性", "Connectivity", "Connectivité"),
                if (c == null) {
                    "<p class=\"sub\">" + esc(tr("まだチェックしていません", "Not checked yet", "Pas encore vérifié")) + "</p>"
                } else {
                    kv(
                        listOf(
                            tr("① 回線登録", "① Registration", "① Enregistrement") to c.registered.mark,
                            tr("② IP通信", "② IP connection", "② Connexion IP") to c.ip.mark,
                            "③ DNS" to c.dns.mark,
                            tr("④ インターネット", "④ Internet", "④ Internet") to c.http.mark + (c.httpMs?.let { Fmt.paren("$it ms") } ?: ""),
                        ),
                    ) + "<p class=\"sub\">${esc(c.diagnosis)}</p>"
                },
            ),
        )
        val p = s.power
        val m = s.meter
        body.append(
            card(
                tr("電源", "Power", "Alimentation"),
                kv(
                    listOfNotNull(
                        tr("電池", "Battery", "Batterie") to p?.let { Fmt.pct(it.levelPct) + if (it.charging == true) tr("・充電中", " · charging", " · en charge") else "" },
                        tr("給電", "Charger", "Alimentation") to p?.plug?.label,
                        s.plug?.let { sp ->
                            val w = sp.why.orEmpty()
                            val target = sp.want?.let { on ->
                                val onOff = if (on) "ON" else "OFF"
                                tr("給電 $onOff（$w）", "Charging $onOff ($w)", "Charge $onOff ($w)")
                            } ?: tr("まだ切り替えていません", "Not switched yet", "Pas encore commutée")
                            tr("スマートプラグ", "Smart plug", "Prise connectée") to target +
                                sp.range.let { tr("・範囲 $it", " · range $it", " · plage $it") } +
                                if (sp.stuck) tr("・⚠ 給電の状態が合いません", " · ⚠ charging doesn't match", " · ⚠ la charge ne correspond pas") else ""
                        },
                        tr("電池温度", "Battery temperature", "Température batterie") to Fmt.temp(p?.tempC),
                        tr("消費（直近）", "Consumption (recent)", "Consommation (récente)") to when {
                            p == null -> "－"
                            p.plug != Plug.NONE -> tr("給電中は計測しません", "Not measured while charging", "Non mesurée en charge")
                            m?.rateMa == null -> tr("計測中", "Measuring", "Mesure en cours")
                            else -> (m.rateMw?.let { "${it.toInt()} mW" + Fmt.paren("${m.rateMa.toInt()} mA") } ?: "${m.rateMa.toInt()} mA")
                        },
                        tr("推定残り時間", "Estimated time left", "Autonomie estimée") to when {
                            p?.plug != Plug.NONE || m?.rateMa == null -> "－"
                            m.remainingMin == null -> tr("計算できません", "Can't calculate", "Calcul impossible")
                            else -> Fmt.approx(m.remainingMin * 60_000L)
                        },
                    ),
                ),
            ),
        )
        val r = d.today
        if (r != null) {
            body.append(
                card(
                    tr("今日", "Today", "Aujourd'hui"),
                    kv(
                        listOf(
                            tr("モバイル通信量", "Mobile data", "Données mobiles") to (r.mobileMb?.let { Fmt.mb(it) } ?: Fmt.NA) +
                                (d.traffic?.let { mb(it.idleKb) }?.let { Fmt.paren(tr("うち接続端末なし $it", "$it with no devices", "dont $it sans appareil")) } ?: ""),
                            tr("チェック", "Checks", "Vérifications") to if (r.checks == 0) {
                                "－"
                            } else {
                                val ok = "${r.checksOk}/${r.checks}"
                                val a = r.anomalies
                                tr("$ok 成功・異常 ${a}回", "$ok OK, $a problem(s)", "$ok OK, $a problème(s)")
                            },
                            tr("平均消費", "Average consumption", "Consommation moyenne") to (
                                r.avgMw?.let { "${it.toInt()} mW" } ?: r.avgMa?.let { "${it.toInt()} mA" }
                                    ?: tr("計測なし（バッテリー駆動の記録なし）", "Not measured (no time on battery)", "Non mesurée (aucun temps sur batterie)")
                                ),
                        ),
                    ),
                ),
            )
        }
        val recent = s.recent.take(10)
        body.append(
            card(
                tr("直近の記録", "Recent events", "Événements récents"),
                if (recent.isEmpty()) {
                    "<p class=\"sub\">" + esc(noneYet()) + "</p>"
                } else {
                    recent.joinToString("") {
                        val sep = tr("：", ": ", " : ")
                        val gap = tr("　", " ", " ")
                        "<p>${esc(Fmt.time(it.at))}$gap${esc(Ev.display(it.kind))}${if (it.text.isNotEmpty()) esc(sep) + esc(it.text) else ""}</p>"
                    } + "<p class=\"sub\"><a href=\"/log\">" + esc(tr("今日の記録をすべて見る", "See all of today's log", "Voir tout le journal du jour")) + "</a></p>"
                },
            ),
        )
        body.append(
            "<p class=\"sub\">" + withLink(
                tr(
                    "この画面は 60 秒ごとに更新されます。状態は {link} でも取得できます。", "This page refreshes every 60 seconds. The status is also available as {link}.",
                    "Cette page se met à jour toutes les 60 secondes. L'état est aussi disponible en {link}.",
                ),
                "/status.json", "JSON",
            ) + "</p>",
        )
        return page(navStatus(), body.toString(), footer(d), refreshSec = 60)
    }

    fun logPage(d: Data): String {
        val rows = d.log.takeLast(100).asReversed()
        val body = if (rows.isEmpty()) {
            card(tr("今日の記録", "Today's log", "Journal du jour"), "<p class=\"sub\">" + esc(noneYet()) + "</p>")
        } else {
            card(
                tr("今日の記録（新しい順・最大 100 件）", "Today's log (newest first, up to 100)", "Journal du jour (plus récents d'abord, 100 max.)"),
                "<div class=\"scroll\"><table class=\"log\"><tr><th>" + esc(tr("時刻", "Time", "Heure")) + "</th><th>" + esc(tr("イベント", "Event", "Événement")) +
                    "</th><th>" + esc(tr("内容", "Details", "Détails")) + "</th></tr>" +
                    rows.joinToString("") { c ->
                        val text = listOf(c.getOrNull(LogStore.IDX_ANOMALY), c.getOrNull(LogStore.IDX_RESULT), c.getOrNull(LogStore.IDX_ACTION))
                            .filter { !it.isNullOrBlank() }.joinToString(" · ")
                        val event = c.getOrNull(LogStore.IDX_EVENT)?.let { Ev.display(it) }
                        "<tr><td>${esc(c.getOrNull(0)?.drop(11))}</td><td>${esc(event)}</td><td>${esc(text)}</td></tr>"
                    } + "</table></div>",
            )
        }
        return page(navLog(), body, footer(d))
    }

    private fun hourLabel(h: Int) = tr("${h}時", "$h:00", "$h h")

    /** 時間帯別の通信量の棒グラフ（SVG。橙色が接続端末なしの通信） */
    fun bars(day: TrafficMath.Day): String {
        val maxKb = day.hours.maxOf { it.kb }.takeIf { it > 0 } ?: 1.0
        val h = 120.0
        val sb = StringBuilder("<svg viewBox=\"0 0 336 136\" width=\"100%\" role=\"img\" aria-label=\"")
            .append(esc(tr("時間帯別の通信量", "Traffic by hour", "Trafic par heure"))).append("\">")
        for (hr in day.hours) {
            val x = hr.hour * 14 + 2
            val total = hr.kb / maxKb * h
            val idle = hr.idleKb / maxKb * h
            if (total - idle > 0.05) sb.append(String.format(Locale.US, "<rect class=\"b\" x=\"%d\" y=\"%.1f\" width=\"10\" height=\"%.1f\"/>", x, h - total, total - idle))
            if (idle > 0.05) sb.append(String.format(Locale.US, "<rect class=\"i\" x=\"%d\" y=\"%.1f\" width=\"10\" height=\"%.1f\"/>", x, h - idle, idle))
            if (hr.hour % 6 == 0) sb.append("<text class=\"t\" x=\"$x\" y=\"132\">${esc(hourLabel(hr.hour))}</text>")
        }
        sb.append("<line class=\"a\" x1=\"0\" y1=\"120\" x2=\"336\" y2=\"120\"/></svg>")
        return sb.toString()
    }

    fun trafficPage(d: Data): String {
        val day = d.traffic
        val body = StringBuilder()
        val title = tr("今日の通信量（時間帯別）", "Today's traffic by hour", "Trafic du jour par heure")
        if (day == null || day.kb <= 0) {
            body.append(
                card(
                    title,
                    "<p class=\"sub\">" + esc(
                        tr(
                            "まだ記録がありません（チェックの区切りごとに記録します）。", "No records yet (recorded at each check).",
                            "Aucun enregistrement pour l'instant (enregistré à chaque vérification).",
                        ),
                    ) + "</p>",
                ),
            )
        } else {
            val maxKb = day.hours.maxOf { it.kb }
            val total = mb(day.kb)
            val idle = mb(day.idleKb)
            val peak = mb(maxKb)
            body.append(
                card(
                    title,
                    "<p>" + esc(tr("合計 $total（うち接続端末なし $idle）", "Total $total ($idle with no devices)", "Total $total (dont $idle sans appareil)")) + "</p>" +
                        bars(day) +
                        "<p class=\"sub\">" + esc(
                            tr(
                                "いちばん多い時間帯 $peak／時。緑 = 接続端末あり・不明、橙 = 接続端末なし（この端末自身の通信）。",
                                "Busiest hour: $peak/h. Green = devices connected or unknown, orange = no devices (this phone's own traffic).",
                                "Heure la plus chargée : $peak/h. Vert = appareils connectés ou inconnu, orange = sans appareil (trafic propre du téléphone).",
                            ),
                        ) + "</p>",
                ),
            )
            val q = d.quiet
            if (q != null) {
                val today = mb(day.kbIn(q))
                body.append(
                    card(
                        tr("使わない時間帯（${q.label}）", "Quiet hours (${q.label})", "Heures d'inactivité (${q.label})"),
                        "<p>" + esc(tr("今日 $today", "Today $today", "Aujourd'hui $today")) + "</p>",
                    ),
                )
            }
            body.append(
                card(
                    tr("時間帯ごとの値", "Values by hour", "Valeurs par heure"),
                    "<table class=\"log\"><tr><th>" + esc(tr("時", "Hour", "Heure")) + "</th><th>" + esc(tr("通信量", "Data", "Données")) + "</th><th>" +
                        esc(tr("うち接続端末なし", "No devices", "Sans appareil")) + "</th></tr>" +
                        day.hours.filter { it.kb > 0 }.joinToString("") {
                            "<tr><td>${esc(tr("${it.hour}時台", "${it.hour}:00", "${it.hour} h"))}</td><td>${esc(mb(it.kb))}</td><td>${esc(mb(it.idleKb))}</td></tr>"
                        } +
                        "</table>",
                ),
            )
        }
        body.append(
            card(
                null,
                "<p class=\"sub\">" + withLink(
                    tr(
                        "通信量は、チェックなどで区切った区間ごとの値を時間で按分した目安です。使っていない時間帯の通信が多いときは、{link}を確認してください。",
                        "Amounts are estimates: each segment between checks is spread over its hours. If there's a lot of traffic at unused times, see {link}.",
                        "Les quantités sont des estimations : chaque segment entre deux vérifications est réparti sur ses heures. S'il y a beaucoup de trafic aux heures inutilisées, consultez {link}.",
                    ),
                    "/guide", guideTitle(),
                ) + "</p>",
            ),
        )
        return page(navTraffic(), body.toString(), footer(d))
    }

    private fun guideTitle() = tr("接続端末の通信を減らす設定", "how to reduce connected devices' data use", "comment réduire les données des appareils connectés")

    fun guidePage(): String {
        val body = StringBuilder(
            card(
                tr("接続端末の通信を減らす設定", "Reduce connected devices' data use", "Réduire les données des appareils connectés"),
                "<p>${esc(ClientGuide.INTRO)}</p>",
            ),
        )
        for (s in ClientGuide.sections) {
            body.append(
                card(
                    s.title,
                    "<ol>" + s.steps.joinToString("") { "<li>${esc(it)}</li>" } + "</ol>" + (s.note?.let { "<p class=\"sub\">${esc(it)}</p>" } ?: ""),
                ),
            )
        }
        body.append(card(null, "<p class=\"sub\">${esc(ClientGuide.CHECK)}</p>"))
        return page(navGuide(), body.toString(), null)
    }

    // ---- JSON ----

    /** 状態の JSON。文言は表示の言語、[overallKey]・[lang] は言語によらない値（スクリプトから使う場合はこちら） */
    fun statusJson(d: Data): String {
        val s = d.state
        val l = s.line
        val p = s.power
        val c = s.check
        val m = s.meter
        val o = s.overall(d.now)
        return Json.obj(
            "app" to "local.tethering.dedicated",
            "version" to d.version,
            "lang" to I18n.lang.code,
            "time" to Fmt.dateTime(d.now),
            "overall" to o.label,
            "overallKey" to o.name,
            "headline" to headline(s, d.now),
            "alerts" to s.alerts.map { it.label },
            "nextCheck" to s.nextCheckAt.takeIf { it > 0 }?.let { Fmt.dateTime(it) },
            "tether" to Json.Raw(Json.obj("state" to s.tether?.let { Fmt.ap(it.apState) }, "clients" to s.tether?.clients)),
            "schedule" to s.sched?.let { Json.Raw(Json.obj("enabled" to it.enabled, "quietHours" to it.quiet, "stoppedUntil" to it.stoppedUntil)) },
            "smartPlug" to s.plug?.let { Json.Raw(Json.obj("want" to it.want, "reason" to it.why, "range" to it.range, "stuck" to it.stuck)) },
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
