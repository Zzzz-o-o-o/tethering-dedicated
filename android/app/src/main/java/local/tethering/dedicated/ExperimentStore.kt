package local.tethering.dedicated

import org.json.JSONArray
import org.json.JSONObject

/**
 * 比較テストの保存（運用状態。バックアップの対象外）。
 * 途中でアプリが止まった場合、次の起動時に保存が残っていれば「中断」として扱い、元の設定に戻すよう知らせる。
 */
object ExperimentStore {
    private const val MAX_HISTORY = 10

    data class HistoryItem(
        /** 候補のキー（古い履歴では空） */
        val key: String,
        val title: String,
        val labelA: String,
        val labelB: String,
        val endedAt: Long,
        val decision: Decision,
        val avgA: Double?,
        val avgB: Double?,
        val unit: String,
        val savingPct: Double?,
        val notes: List<String>,
    )

    fun save(prefs: Prefs, run: ExperimentRun?) {
        prefs.experimentJson = run?.let { toJson(it).toString() }
    }

    fun load(prefs: Prefs): ExperimentRun? = prefs.experimentJson?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun addHistory(prefs: Prefs, run: ExperimentRun, o: Outcome, endedAt: Long) {
        val arr = JSONArray()
        arr.put(
            JSONObject().put("key", run.candidate.key)
                .put("title", run.candidate.title).put("labelA", run.candidate.labelA).put("labelB", run.candidate.labelB)
                .put("endedAt", endedAt).put("decision", o.decision.name).put("unit", o.unit)
                .put("avgA", o.avgA ?: JSONObject.NULL).put("avgB", o.avgB ?: JSONObject.NULL)
                .put("saving", o.savingPct ?: JSONObject.NULL).put("notes", JSONArray(o.notes)),
        )
        history(prefs).take(MAX_HISTORY - 1).forEach { arr.put(historyJson(it)) }
        prefs.experimentHistoryJson = arr.toString()
    }

    fun history(prefs: Prefs): List<HistoryItem> {
        val arr = prefs.experimentHistoryJson?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val j = arr.getJSONObject(i)
                val notes = j.optJSONArray("notes")
                // 決まった候補の名前は、今の表示の言語で出す（自由に指定したものは保存した名前のまま）
                val known = j.optString("key").takeIf { it.isNotEmpty() && it != Candidates.KEY_CUSTOM }?.let { Candidates.of(it) }
                HistoryItem(
                    j.optString("key"), known?.title ?: j.getString("title"), known?.labelA ?: j.getString("labelA"),
                    known?.labelB ?: j.getString("labelB"), j.getLong("endedAt"),
                    Decision.valueOf(j.getString("decision")), j.optDoubleOrNull("avgA"), j.optDoubleOrNull("avgB"),
                    j.optString("unit", "mW"), j.optDoubleOrNull("saving"),
                    if (notes == null) emptyList() else (0 until notes.length()).map { notes.getString(it) },
                )
            }.getOrNull()
        }
    }

    private fun historyJson(h: HistoryItem) = JSONObject().put("key", h.key)
        .put("title", h.title).put("labelA", h.labelA).put("labelB", h.labelB).put("endedAt", h.endedAt)
        .put("decision", h.decision.name).put("unit", h.unit)
        .put("avgA", h.avgA ?: JSONObject.NULL).put("avgB", h.avgB ?: JSONObject.NULL)
        .put("saving", h.savingPct ?: JSONObject.NULL).put("notes", JSONArray(h.notes))

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key)

    private fun toJson(r: ExperimentRun): JSONObject {
        val c = r.candidate
        val phases = JSONArray()
        r.phases.forEach { p ->
            phases.put(
                JSONObject().put("c", p.condition.toString()).put("sec", p.sec).put("mah", p.mah).put("mwSec", p.mwSec)
                    .put("mwSecSpan", p.mwSecSpan).put("checks", p.checks).put("failures", p.failures).put("bytes", p.bytes)
                    .put("clientsMax", p.clientsMax ?: JSONObject.NULL).put("rsrpSum", p.rsrpSum).put("rsrpN", p.rsrpN),
            )
        }
        return JSONObject()
            .put("key", c.key).put("title", c.title).put("labelA", c.labelA).put("labelB", c.labelB)
            .put("toA", c.toA).put("toB", c.toB).put("auto", c.auto)
            .put("phaseSec", r.phaseSec).put("repeats", r.repeats).put("minGain", r.minGainPct).put("startedAt", r.startedAt)
            .put("index", r.index).put("waiting", r.waitingSwitch).put("phases", phases)
    }

    private fun fromJson(j: JSONObject): ExperimentRun {
        val stored = Candidate(
            j.getString("key"), j.getString("title"), j.getString("labelA"), j.getString("labelB"),
            j.getString("toB"), j.getString("toA"), j.getBoolean("auto"),
        )
        // 決まった候補は今の表示の言語で出す（自由に指定したものは保存した文言のまま）
        val c = stored.key.takeIf { it != Candidates.KEY_CUSTOM }?.let { Candidates.of(it) } ?: stored
        val r = ExperimentRun(c, j.getLong("phaseSec"), j.getInt("repeats"), j.getInt("minGain"), j.getLong("startedAt"))
        r.index = j.getInt("index")
        r.waitingSwitch = j.getBoolean("waiting")
        val phases = j.getJSONArray("phases")
        for (i in 0 until minOf(phases.length(), r.phases.size)) {
            val pj = phases.getJSONObject(i)
            val p = r.phases[i]
            p.sec = pj.getLong("sec")
            p.mah = pj.getDouble("mah")
            p.mwSec = pj.getDouble("mwSec")
            p.mwSecSpan = pj.getLong("mwSecSpan")
            p.checks = pj.getInt("checks")
            p.failures = pj.getInt("failures")
            p.bytes = pj.getLong("bytes")
            p.clientsMax = if (pj.isNull("clientsMax")) null else pj.getInt("clientsMax")
            p.rsrpSum = pj.getLong("rsrpSum")
            p.rsrpN = pj.getInt("rsrpN")
        }
        return r
    }
}
