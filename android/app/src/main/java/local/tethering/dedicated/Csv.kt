package local.tethering.dedicated

/** CSV の 1 行の組み立てと分解（RFC 4180 相当。カンマ・ダブルクォート・改行を含む値はクォートする） */
object Csv {
    fun line(fields: List<String?>): String = fields.joinToString(",") { escape(it ?: "") }

    fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    /** 1 行を分解する。このアプリが書いた行（改行は含めない）を読むためのもの */
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
