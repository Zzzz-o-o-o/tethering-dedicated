package local.tethering.dedicated

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1 日 1 ファイルの CSV（`<prefix>yyyyMMdd.csv`）。保存期間と合計サイズを超えた古いファイルから消す。
 * 操作ログ（LogStore）と電力の記録（PowerStore）で共通に使う。
 * スレッドの安全性は呼び出し側で保証する（どちらも専用の 1 本のスレッドから呼ぶ）。
 */
class DailyCsv(private val dir: File, private val prefix: String, val header: List<String>) {
    private var prunedDay: String? = null
    private var appendsSincePrune = 0

    fun dayName(ms: Long): String = prefix + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms)) + ".csv"

    fun files(): List<File> =
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".csv") }?.sortedBy { it.name }.orEmpty()

    fun append(line: String, now: Long, days: Int, maxBytes: Long) {
        dir.mkdirs()
        val day = dayName(now)
        val f = File(dir, day)
        val isNew = !f.exists()
        FileOutputStream(f, true).bufferedWriter(Charsets.UTF_8).use { w ->
            if (isNew) {
                w.write(Csv.line(header))
                w.write("\n")
            }
            w.write(line)
            w.write("\n")
        }
        appendsSincePrune++
        if (prunedDay != day || appendsSincePrune >= 200) {
            prune(now, days, maxBytes)
            prunedDay = day
            appendsSincePrune = 0
        }
    }

    fun prune(now: Long, days: Int, maxBytes: Long) {
        val keepFrom = dayName(now - (days - 1) * DAY_MS)
        files().filter { it.name < keepFrom }.forEach { it.delete() }
        val remain = files().toMutableList()
        var total = remain.sumOf { it.length() }
        while (total > maxBytes && remain.size > 1) {
            val oldest = remain.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    /** 1 ファイルの行（見出しを除く）。日付は `yyyyMMdd` */
    fun rowsOf(file: File): List<List<String>> =
        runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .filter { it.isNotBlank() && !isHeader(it) }
            .map { Csv.parseLine(it) }
            .filter { it.size >= header.size }

    fun rowsOfDay(yyyyMMdd: String): List<List<String>> =
        File(dir, "$prefix$yyyyMMdd.csv").takeIf { it.exists() }?.let { rowsOf(it) }.orEmpty()

    /** すべての行を 1 つの CSV に書き出す（Excel で文字化けしないよう BOM 付き UTF-8）。戻り値は行数 */
    fun export(out: OutputStream): Int {
        var rows = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFF")
            w.write(Csv.line(header))
            w.write("\n")
            for (file in files()) {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { s ->
                        if (s.isBlank() || isHeader(s)) return@forEach
                        w.write(s)
                        w.write("\n")
                        rows++
                    }
                }
            }
        }
        return rows
    }

    fun tail(n: Int): List<String> {
        val f = files().lastOrNull() ?: return emptyList()
        return runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).filter { it.isNotBlank() }.takeLast(n)
    }

    fun clear() = files().forEach { it.delete() }

    fun totalBytes(): Long = files().sumOf { it.length() }

    private fun isHeader(line: String) = line.startsWith(header[0] + ",")

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
